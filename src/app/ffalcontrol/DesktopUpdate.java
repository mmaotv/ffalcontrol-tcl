package app.ffalcontrol;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 桌面更新：查线上最新版 → 下载 → 交给 {@link ApkInstaller} 用本应用自己的会话安装。
 *
 * 两个桌面的发行渠道完全不同，不能用一个接口硬套：
 *  - Aerial 上游直接发 APK，查 `pengchujin/aerial-tv` 的 latest，取 aerial-standard.apk；
 *    GitHub 打不开时退回官网镜像（镜像没有版本号，只能「装最新」不能「比版本」）。
 *  - CleanYourTV 上游只发源码、不发 APK，安装包是我们自己在 mmaotv/AerialToolbox 的
 *    `cyt-v*` 发行版里代构建的，所以查那份清单并按 tag 前缀筛 —— 那个仓库同时挂着工具箱
 *    自己的发行版，用 `releases/latest` 会拿到工具箱本身。
 *
 * 这里只做「取到字节」，不负责安装：安装统一走 ApkInstaller，
 * 才能保住「自己会话 → 天然绕过厂商安装策略」这个前提。
 */
final class DesktopUpdate {

    // ===================== 桌面标识 =====================

    /** Aerial 桌面包名，与 build.sh 的 aerial 变体一致 */
    static final String AERIAL_PKG = "app.orchard.tv";
    /** CleanYourTV 桌面包名，与 build.sh 的 cyt 变体一致 */
    static final String CYT_PKG = "com.gathin.tvhome";

    /** Aerial 上游 */
    private static final String AERIAL_API =
            "https://api.github.com/repos/pengchujin/aerial-tv/releases/latest";
    private static final String AERIAL_PAGE =
            "https://github.com/pengchujin/aerial-tv/releases/latest";
    private static final String AERIAL_ASSET = "aerial-standard.apk";
    private static final String AERIAL_MIRROR = "https://aerial.qust.me/download/aerial-standard.apk";

    /** CleanYourTV 代构建发行版（我们自己的仓库） */
    private static final String CYT_API =
            "https://api.github.com/repos/mmaotv/AerialToolbox/releases?per_page=30";
    private static final String CYT_PAGE = "https://github.com/mmaotv/AerialToolbox/releases";
    private static final String CYT_ASSET_PREFIX = "CleanYourTV-";
    private static final String CYT_TAG_PREFIX = "cyt-v";

    /** 小于这个体积一定不是完整 APK（CleanYourTV 的包只有 600 多 KB） */
    private static final long MIN_APK_BYTES = 64L * 1024L;

    private static final String USER_AGENT = "ffalcontrol/0.3 (Android TV)";

    interface Progress {
        void onProgress(long read, long total);
    }

    /** 一个受支持的桌面 */
    static final class Desktop {
        final String pkg;
        final String label;
        final boolean aerial;

        private Desktop(String pkg, String label, boolean aerial) {
            this.pkg = pkg;
            this.label = label;
            this.aerial = aerial;
        }

        @Override public String toString() { return label; }
    }

    static final Desktop AERIAL = new Desktop(AERIAL_PKG, "Aerial", true);
    static final Desktop CLEAN_TV = new Desktop(CYT_PKG, "CleanYourTV", false);

    /** 两个桌面，内置变体的目标排在前面 */
    static Desktop[] known() {
        if (BundledLauncher.TARGET != null && BundledLauncher.TARGET.equals(AERIAL_PKG)) {
            return new Desktop[]{AERIAL, CLEAN_TV};
        }
        if (BundledLauncher.TARGET != null && BundledLauncher.TARGET.equals(CYT_PKG)) {
            return new Desktop[]{CLEAN_TV, AERIAL};
        }
        return new Desktop[]{AERIAL, CLEAN_TV};
    }

    /** 这个变体内置的是哪个桌面；通用版（没有内置包）返回 null */
    static Desktop bundled() {
        if (!BundledLauncher.BUNDLED) return null;
        for (Desktop desktop : known()) {
            if (desktop.pkg.equals(BundledLauncher.TARGET)) return desktop;
        }
        return null;
    }

    static Desktop byPackage(String pkg) {
        for (Desktop desktop : known()) if (desktop.pkg.equals(pkg)) return desktop;
        return null;
    }

    // ===================== 线上发行版 =====================

    static final class Release {
        final Desktop desktop;
        /** 归一化版本号；镜像兜底时为空（不知道版本） */
        final String version;
        final String tag;
        final String url;
        final String source;
        /** GitHub Release 正文，展示给用户看 */
        final String notes;
        final String pageUrl;
        /** 拿到的日期，形如 2026-11-01 */
        final String publishedAt;

        Release(Desktop desktop, String version, String tag, String url, String source,
                String notes, String pageUrl, String publishedAt) {
            this.desktop = desktop;
            this.version = version == null ? "" : version;
            this.tag = tag == null ? "" : tag;
            this.url = url;
            this.source = source;
            this.notes = notes == null ? "" : notes;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.publishedAt = publishedAt == null ? "" : publishedAt;
        }

        boolean versionKnown() { return version.length() > 0; }
    }

    /**
     * 查某个桌面的最新发行版。
     *
     * @throws IOException 两条来源都拿不到时抛出，由调用方翻成人话
     */
    static Release latest(Desktop desktop) throws IOException {
        return desktop.aerial ? latestAerial() : latestCleanTv();
    }

    private static Release latestAerial() throws IOException {
        IOException apiFailure;
        try {
            JSONObject json = new JSONObject(httpGet(AERIAL_API));
            String tag = json.optString("tag_name");
            String url = null;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name");
                    // 只要标准版；guard 版是另一套接管方式，这个工具不用
                    if (AERIAL_ASSET.equals(name)) {
                        url = asset.optString("browser_download_url");
                        break;
                    }
                }
            }
            if (url == null || url.length() == 0) {
                throw new IOException("Release 里没有 " + AERIAL_ASSET);
            }
            return new Release(AERIAL, normalizeVersion(tag), tag, url,
                    "GitHub · " + AERIAL_ASSET, json.optString("body"),
                    json.optString("html_url").isEmpty() ? AERIAL_PAGE : json.optString("html_url"),
                    json.optString("published_at"));
        } catch (IOException failure) {
            apiFailure = failure;
        } catch (Throwable failure) {
            apiFailure = new IOException(describe(failure));
        }

        // 官网镜像：只能确认「确实有一个包可下」，给不出版本号
        if (headOk(AERIAL_MIRROR)) {
            return new Release(AERIAL, "", "", AERIAL_MIRROR,
                    "官网镜像（版本号未知）", "官网只放当前最新包，没有版本号接口。",
                    AERIAL_PAGE, "");
        }
        throw new IOException("连不上 GitHub，也拿不到官网镜像：" + apiFailure.getMessage());
    }

    private static Release latestCleanTv() throws IOException {
        try {
            String body = httpGet(CYT_API).trim();
            JSONArray list = new JSONArray(body);
            for (int i = 0; i < list.length(); i++) {
                JSONObject release = list.optJSONObject(i);
                if (release == null) continue;
                String tag = release.optString("tag_name");
                // 本仓库同时挂着工具箱自己的 v1.3.x 和手机端 phone-v*，只认 cyt-v*
                if (!tag.startsWith(CYT_TAG_PREFIX)) continue;
                String url = null;
                String assetName = "";
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.optJSONObject(j);
                        if (asset == null) continue;
                        String name = asset.optString("name");
                        if (name.startsWith(CYT_ASSET_PREFIX) && name.endsWith(".apk")) {
                            url = asset.optString("browser_download_url");
                            assetName = name;
                            break;
                        }
                    }
                }
                if (url == null || url.length() == 0) continue;
                return new Release(CLEAN_TV, normalizeVersion(tag.substring(CYT_TAG_PREFIX.length())),
                        tag, url, "GitHub · " + assetName, release.optString("body"),
                        release.optString("html_url").isEmpty() ? CYT_PAGE : release.optString("html_url"),
                        release.optString("published_at"));
            }
            throw new IOException("没有找到 " + CYT_TAG_PREFIX + "* 发行版");
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException(describe(failure));
        }
    }

    // ===================== 本机已装版本 =====================

    /** 已装版本号；没装返回 null。targetSdk 28 不受包可见性限制，直接查即可。 */
    static String installedVersion(Context context, Desktop desktop) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(desktop.pkg, 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** 线上版本比已装新（或已装未知）时返回 true */
    static boolean isNewer(Release release, String installed) {
        if (!release.versionKnown()) return true; // 版本号未知，只能让用户自己决定
        if (installed == null) return true;
        if (installed.length() == 0) return true;
        return compare(release.version, installed) > 0;
    }

    /** 版本号比较见 {@link VersionText}（纯文本逻辑，可单独自测）。 */
    static int compare(String a, String b) { return VersionText.compare(a, b); }

    private static String normalizeVersion(String raw) { return VersionText.normalize(raw); }

    // ===================== 下载 =====================

    /** 下载到指定文件（先写 .part 再改名，失败不会留下半个包）。 */
    static void download(String url, File dest, Progress progress) throws IOException {
        File dir = dest.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录：" + dir);
        File temp = new File(dest.getParentFile(), dest.getName() + ".part");
        HttpURLConnection connection = open(url, 60_000);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("HTTP " + code);
            long total = connection.getContentLengthLong();
            InputStream input = connection.getInputStream();
            try {
                FileOutputStream output = new FileOutputStream(temp);
                try {
                    byte[] chunk = new byte[64 * 1024];
                    long copied = 0;
                    int read;
                    while ((read = input.read(chunk)) > 0) {
                        output.write(chunk, 0, read);
                        copied += read;
                        if (progress != null) progress.onProgress(copied, total);
                    }
                    output.flush();
                } finally {
                    output.close();
                }
            } finally {
                input.close();
            }
            if (temp.length() < MIN_APK_BYTES) {
                throw new IOException("下到的包只有 " + temp.length() + " 字节，不像是完整 APK");
            }
            if (!looksLikeApk(temp)) {
                throw new IOException("下到的不是 APK（可能是错误页），请检查网络或代理");
            }
            if (dest.exists()) dest.delete();
            if (!temp.renameTo(dest)) {
                throw new IOException("改名失败：" + dest.getAbsolutePath());
            }
        } finally {
            temp.delete();
            try { connection.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 下载落地位置：应用自己的 cache，安装时由本进程读，不需要存储权限 */
    static File cacheFile(Context context, Desktop desktop, String version) {
        String name = desktop.pkg + "-" + (version == null || version.length() == 0 ? "latest" : version) + ".apk";
        return new File(new File(context.getCacheDir(), "desktop-update"), name);
    }

    /**
     * 一次性检查：给本机接口 `GET /launcher/update` 用，也方便在电视上用 curl 看结果。
     * 单个桌面查失败不影响另一个，错误写在各自的 error 里。
     */
    static JSONObject check(Context context) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray rows = new JSONArray();
        out.put("desktops", rows);
        for (Desktop desktop : known()) {
            JSONObject row = new JSONObject();
            row.put("package", desktop.pkg);
            row.put("label", desktop.label);
            String installed = installedVersion(context, desktop);
            row.put("installed", installed == null ? JSONObject.NULL : installed);
            try {
                Release release = latest(desktop);
                row.put("latest", release.versionKnown() ? release.version : JSONObject.NULL);
                row.put("tag", release.tag);
                row.put("source", release.source);
                row.put("url", release.url);
                row.put("page", release.pageUrl);
                row.put("version_known", release.versionKnown());
                JSONObject notes = new JSONObject();
                notes.put("text", release.notes);
                notes.put("length", release.notes.length());
                row.put("release_notes", notes);
                row.put("update_available", isNewer(release, installed));
            } catch (Throwable failure) {
                row.put("error", describe(failure));
            }
            rows.put(row);
        }
        out.put("bundled_target", BundledLauncher.BUNDLED ? BundledLauncher.TARGET : JSONObject.NULL);
        return out;
    }

    // ===================== HTTP 小工具 =====================

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = open(url, 30_000);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("HTTP " + code);
            InputStream input = connection.getInputStream();
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int read;
                while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
                return new String(buffer.toByteArray(), "UTF-8");
            } finally {
                input.close();
            }
        } finally {
            try { connection.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static boolean headOk(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, 15_000);
            connection.setRequestMethod("HEAD");
            int code = connection.getResponseCode();
            return code >= 200 && code <= 399;
        } catch (Throwable failure) {
            return false;
        } finally {
            if (connection != null) try { connection.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static HttpURLConnection open(String url, int readTimeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        return connection;
    }

    /** 只看 ZIP 头，避免把 GitHub 的错误页当成安装包装进去 */
    private static boolean looksLikeApk(File file) {
        InputStream input = null;
        try {
            input = new java.io.FileInputStream(file);
            byte[] head = new byte[4];
            if (input.read(head) != 4) return false;
            return head[0] == 'P' && head[1] == 'K'
                    && head[2] == 0x03 && head[3] == 0x04;
        } catch (Throwable failure) {
            return false;
        } finally {
            if (input != null) try { input.close(); } catch (Throwable ignored) { }
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "null";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private DesktopUpdate() {}
}
