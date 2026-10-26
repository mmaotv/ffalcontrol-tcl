package app.ffalcontrol;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推送安装流水线。
 *
 *   RECEIVED → STAGED → ANALYZED → (PATCHED) → INSTALLING → COMMITTED / FAILED
 *
 * 核心原则：先算清楚会被哪条策略拦，再改最少的那一项，然后走系统原生安装确认框。
 * 不做静默安装（普通应用没有 INSTALL_PACKAGES，也拿不到免确认）。
 */
final class PushInstall {

    static final long MAX_APK = 200L * 1024 * 1024;
    private static final int KEEP_JOBS = 30;

    static final String S_STAGED = "STAGED";
    static final String S_ANALYZED = "ANALYZED";
    static final String S_PATCHED = "PATCHED";
    static final String S_INSTALLING = "INSTALLING";
    static final String S_SUCCESS = "COMMITTED";
    static final String S_FAILED = "FAILED";
    static final String S_PREAUTH = "PREAUTHORIZED";

    interface Listener {
        void onProgress(String text);
        void onDone(boolean ok, JSONObject job);
    }

    private final Context context;
    private final PolicyGuard guard;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    PushInstall(Context context) {
        this.context = context.getApplicationContext();
        this.guard = new PolicyGuard(this.context);
    }

    // ---------------------------------------------------------------- 收包

    /** 把字节流落成一份待处理任务。调用方负责关闭 in。 */
    synchronized JSONObject stage(InputStream in, String fileName, long declaredBytes, String source)
            throws Exception {
        if (declaredBytes > MAX_APK) throw new GuardControl.ApiFailure(413, "APK 超过 200 MB 上限");
        File dir = apkDir();
        File jobs = jobDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new GuardControl.ApiFailure(500, "无法创建缓存目录");
        if (!jobs.isDirectory() && !jobs.mkdirs()) throw new GuardControl.ApiFailure(500, "无法创建任务目录");

        String id = "p" + System.currentTimeMillis() + "-" + Long.toHexString(Double.doubleToLongBits(Math.random()) & 0xffffL);
        File apk = new File(dir, id + ".apk");

        long copied = 0;
        try (FileOutputStream out = new FileOutputStream(apk)) {
            byte[] chunk = new byte[64 * 1024];
            int count;
            while ((count = in.read(chunk)) > 0) {
                copied += count;
                if (copied > MAX_APK) {
                    out.close();
                    apk.delete();
                    throw new GuardControl.ApiFailure(413, "APK 超过 200 MB 上限");
                }
                out.write(chunk, 0, count);
            }
            out.flush();
            out.getFD().sync();
        } catch (GuardControl.ApiFailure known) {
            throw known;
        } catch (Throwable failure) {
            apk.delete();
            throw new GuardControl.ApiFailure(500, "写入失败：" + describe(failure));
        }
        if (copied == 0) {
            apk.delete();
            throw new GuardControl.ApiFailure(422, "收到 0 字节，不是有效的 APK");
        }
        if (!looksLikeApk(apk)) {
            apk.delete();
            throw new GuardControl.ApiFailure(422, "不是 ZIP/APK（缺少 PK 头）");
        }

        JSONObject job = new JSONObject();
        job.put("id", id);
        job.put("file_name", fileName == null || fileName.length() == 0 ? id + ".apk" : fileName);
        job.put("bytes", copied);
        job.put("source", source == null ? "api" : source);
        job.put("state", S_STAGED);
        job.put("created_ms", System.currentTimeMillis());
        job.put("updated_ms", System.currentTimeMillis());
        job.put("sha256", ApkAnalyzer.sha256(apk));
        save(job);
        prune();
        return job;
    }

    private static boolean looksLikeApk(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[2];
            return in.read(head) == 2 && head[0] == 'P' && head[1] == 'K';
        } catch (Throwable failure) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 分析

    /** 分析并落盘结论。 */
    JSONObject analyze(String id) throws Exception {
        JSONObject job = load(id);
        File apk = apkFile(id);
        if (!apk.isFile()) throw new GuardControl.ApiFailure(404, "APK 已被清理，请重新推送");
        SecurityGate.Policy policy = guard.snapshot();
        JSONObject analysis = ApkAnalyzer.analyze(context, apk, policy, id, job.optString("file_name", null));
        job.put("analysis", analysis);
        job.put("package", analysis.optString("package", ""));
        job.put("state", S_ANALYZED);
        job.put("updated_ms", System.currentTimeMillis());
        save(job);
        return job;
    }

    /** 只读分析，不落盘（给 /push/analyze 用，语义上等同 analyze）。 */
    JSONObject peek(String id) throws Exception {
        return load(id);
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 异步安装：立即返回任务快照，状态由调用方轮询。
     * 必须异步 —— 系统确认框要等用户点，最长 3 分钟，同步会把 HTTP 连接拖死。
     */
    JSONObject install(final Activity activity, final String id, final boolean patchEnabled,
                       final boolean rollbackOnFailure, final String origin) throws Exception {
        final JSONObject job = load(id);
        if (!apkFile(id).isFile()) throw new GuardControl.ApiFailure(404, "APK 已被清理，请重新推送");
        if (!busy.compareAndSet(false, true)) throw new GuardControl.ApiFailure(409, "另一个安装任务正在进行");
        job.put("state", S_INSTALLING);
        job.put("updated_ms", System.currentTimeMillis());
        job.put("message", "已排队");
        job.put("origin", origin == null ? "tool" : origin);
        save(job);

        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runInstall(activity, id, patchEnabled, rollbackOnFailure, origin);
                } catch (Throwable failure) {
                    try {
                        JSONObject broken = load(id);
                        broken.put("state", S_FAILED);
                        broken.put("message", "安装线程异常：" + describe(failure));
                        broken.put("updated_ms", System.currentTimeMillis());
                        save(broken);
                    } catch (Throwable ignored) { }
                } finally {
                    busy.set(false);
                }
            }
        }, "push-install-" + id);
        worker.start();
        return load(id);
    }

    private void runInstall(Activity activity, String id, boolean patchEnabled,
                            boolean rollbackOnFailure, String origin) throws Exception {
        JSONObject job = load(id);
        JSONArray log = job.optJSONArray("log");
        if (log == null) log = new JSONArray();

        // 1. 分析
        job = analyze(id);
        JSONObject analysis = job.getJSONObject("analysis");
        String pkg = analysis.optString("package", "");
        log.put("包名 " + pkg + "，版本 " + analysis.optString("version_name", "?")
                + "，字节 " + analysis.optLong("bytes"));
        if (analysis.optBoolean("declares_home")) log.put("检测到 MAIN + HOME：这是一个桌面类应用");
        job.put("log", log);
        save(job);

        // 2. 需要的话改配置
        final List<SecurityGate.Patch> applied = new ArrayList<SecurityGate.Patch>();
        JSONArray planned = analysis.optJSONArray("patches");
        if (patchEnabled && planned != null && planned.length() > 0) {
            for (int i = 0; i < planned.length(); i++) {
                JSONObject row = planned.getJSONObject(i);
                SecurityGate.Patch patch = new SecurityGate.Patch(
                        row.optString("strategy"), row.optString("package"), row.optBoolean("add"));
                try {
                    JSONObject result = guard.apply(patch, true);
                    log.put("已修改配置：" + patch.describe()
                            + "（" + result.optString("status", "ok") + "）");
                    applied.add(patch);
                } catch (Throwable failure) {
                    // 已经有白名单时会 422，不算失败
                    String text = describe(failure);
                    if (text.contains("already allowlisted") || text.contains("already")) {
                        log.put("配置已就绪：" + patch.describe());
                    } else {
                        log.put("改配置失败：" + patch.describe() + " → " + text);
                    }
                }
            }
            job.put("log", log);
            job.put("applied_patches", renderPatches(applied));
            job.put("state", S_PATCHED);
            save(job);
        } else if (planned != null && planned.length() == 0) {
            log.put("无需改配置，直接安装");
            job.put("log", log);
            save(job);
        }

        // 3. 装
        final int[] statusHolder = new int[]{Integer.MIN_VALUE};
        final boolean[] okHolder = new boolean[]{false};

        ApkInstaller.install(activity, apkFile(id), pkg, new ApkInstaller.Listener() {
            @Override public void onProgress(String text) {
                try {
                    JSONObject live = load(id);
                    JSONArray liveLog = live.optJSONArray("log");
                    if (liveLog == null) liveLog = new JSONArray();
                    liveLog.put(text);
                    live.put("log", liveLog);
                    live.put("message", text);
                    live.put("updated_ms", System.currentTimeMillis());
                    save(live);
                } catch (Throwable ignored) { }
            }
            @Override public void onDone(boolean ok, String text, int status) {
                okHolder[0] = ok;
                statusHolder[0] = status;
                try {
                    JSONObject live = load(id);
                    JSONArray liveLog = live.optJSONArray("log");
                    if (liveLog == null) liveLog = new JSONArray();
                    liveLog.put(text);
                    live.put("log", liveLog);
                    live.put("message", text);
                    live.put("status_code", status);
                    live.put("updated_ms", System.currentTimeMillis());
                    save(live);
                } catch (Throwable ignored) { }
            }
        });

        job = load(id);
        log = job.optJSONArray("log");
        if (log == null) log = new JSONArray();

        if (okHolder[0]) {
            job.put("state", S_SUCCESS);
            JSONObject post = postCheck(pkg);
            job.put("post_check", post);
            log.put("装后检查：" + describePostCheck(post));
        } else {
            job.put("state", S_FAILED);
            job.put("status_code", statusHolder[0]);
            // 只在"被策略拦"时回滚：其它失败（用户取消、空间不足）配置留着无害，方便重试
            if (rollbackOnFailure && statusHolder[0] == PackageInstaller.STATUS_FAILURE_BLOCKED
                    && !applied.isEmpty()) {
                JSONArray rolled = new JSONArray();
                for (SecurityGate.Patch patch : applied) {
                    try {
                        guard.revert(patch);
                        rolled.put(patch.describe());
                        log.put("已回滚：" + patch.describe());
                    } catch (Throwable failure) {
                        log.put("回滚失败：" + patch.describe() + " → " + describe(failure));
                    }
                }
                job.put("rolled_back", rolled);
            }
        }
        job.put("log", log);
        job.put("updated_ms", System.currentTimeMillis());
        save(job);
    }

    /** 装完不等于能用：查三项。 */
    private JSONObject postCheck(String pkg) throws Exception {
        JSONObject out = new JSONObject();
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            out.put("resolvable", true);
            out.put("version_name", info.versionName);
            out.put("version_code", android.os.Build.VERSION.SDK_INT >= 28
                    ? (int) info.getLongVersionCode() : info.versionCode);
            int setting = pm.getApplicationEnabledSetting(pkg);
            boolean disabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            out.put("enabled", !disabled);
            out.put("enabled_setting", setting);
            out.put("launchable", pm.getLaunchIntentForPackage(pkg) != null);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                ApplicationInfo ai = info.applicationInfo;
                out.put("hidden_system_app", ai != null && ai.enabled == false);
            }
        } catch (Throwable missing) {
            out.put("resolvable", false);
        }
        return out;
    }

    private static String describePostCheck(JSONObject post) {
        if (!post.optBoolean("resolvable", false)) {
            return "装完但系统查不到该包，可能被 guard 立即禁用了。建议冷启动后复查。";
        }
        StringBuilder text = new StringBuilder("已装 v").append(post.optString("version_name", "?"));
        if (!post.optBoolean("enabled", true)) {
            text.append("；但当前是禁用状态，需在守卫名单里放行后开机生效");
        } else if (!post.optBoolean("launchable", true)) {
            text.append("；但没有可启动入口（可能是组件型应用）");
        } else {
            text.append("；可正常启动");
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- 预授权

    JSONObject preauthorize(String pkg, boolean write) throws Exception {
        return guard.preauthorize(pkg, write);
    }

    // ---------------------------------------------------------------- 查询

    JSONArray jobs(int limit) throws Exception {
        File[] files = jobDir().listFiles();
        List<JSONObject> rows = new ArrayList<JSONObject>();
        if (files != null) {
            Arrays.sort(files, new java.util.Comparator<File>() {
                @Override public int compare(File x, File y) {
                    return Long.compare(y.lastModified(), x.lastModified());
                }
            });
            for (File file : files) {
                if (rows.size() >= limit) break;
                if (!file.getName().endsWith(".json")) continue;
                try {
                    rows.add(new JSONObject(readFile(file)));
                } catch (Throwable ignored) { }
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject row : rows) out.put(summary(row));
        return out;
    }

    JSONObject job(String id) throws Exception {
        return load(id);
    }

    void delete(String id) throws Exception {
        requireId(id);
        File apk = apkFile(id);
        if (apk.exists() && !apk.delete()) throw new GuardControl.ApiFailure(500, "删除 APK 失败");
        File job = jobFile(id);
        if (job.exists() && !job.delete()) throw new GuardControl.ApiFailure(500, "删除任务失败");
    }

    /** 列表里只给概要，避免把长日志全吐出去。 */
    private static JSONObject summary(JSONObject job) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", job.optString("id"));
        out.put("file_name", job.optString("file_name"));
        out.put("bytes", job.optLong("bytes"));
        out.put("package", job.optString("package"));
        out.put("state", job.optString("state"));
        out.put("source", job.optString("source"));
        out.put("created_ms", job.optLong("created_ms"));
        out.put("updated_ms", job.optLong("updated_ms"));
        out.put("message", job.optString("message"));
        JSONObject analysis = job.optJSONObject("analysis");
        if (analysis != null) {
            out.put("verdict", analysis.optString("verdict"));
            out.put("blocked_by", analysis.optJSONArray("blocked_by"));
            out.put("label", analysis.optString("label"));
            out.put("declares_home", analysis.optBoolean("declares_home"));
        }
        return out;
    }

    private static JSONArray renderPatches(List<SecurityGate.Patch> patches) throws Exception {
        JSONArray rows = new JSONArray();
        for (SecurityGate.Patch patch : patches) {
            JSONObject row = new JSONObject();
            row.put("strategy", patch.strategy);
            row.put("package", patch.packageName);
            row.put("add", patch.add);
            row.put("describe", patch.describe());
            rows.put(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- 存储

    private File apkDir() { return new File(context.getCacheDir(), "push"); }

    private File jobDir() { return new File(context.getFilesDir(), "push-jobs"); }

    private File apkFile(String id) {
        if (!validId(id)) throw new IllegalArgumentException("非法任务号：" + id);
        return new File(apkDir(), id + ".apk");
    }

    private File jobFile(String id) {
        if (!validId(id)) throw new IllegalArgumentException("非法任务号：" + id);
        return new File(jobDir(), id + ".json");
    }

    private static boolean validId(String id) {
        return id != null && id.matches("p[0-9]+-[0-9a-f]{1,16}");
    }

    private static void requireId(String id) throws Exception {
        if (!validId(id)) throw new GuardControl.ApiFailure(422, "非法任务号：" + id);
    }

    synchronized JSONObject load(String id) throws Exception {
        requireId(id);
        File file = jobFile(id);
        if (!file.isFile()) throw new GuardControl.ApiFailure(404, "任务不存在：" + id);
        return new JSONObject(readFile(file));
    }

    synchronized void save(JSONObject job) throws Exception {
        File file = jobFile(job.optString("id"));
        File dir = jobDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new GuardControl.ApiFailure(500, "无法创建任务目录");
        File temp = new File(dir, file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(job.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } finally {
            try { out.close(); } catch (Throwable ignored) { }
        }
        if (!temp.renameTo(file)) {
            file.delete();
            if (!temp.renameTo(file)) throw new GuardControl.ApiFailure(500, "任务写入失败");
        }
    }

    /** 只保留最近 KEEP_JOBS 个任务，顺带清掉没有任务记录的孤儿 APK。 */
    private void prune() {
        File[] files = jobDir().listFiles();
        if (files != null && files.length > KEEP_JOBS) {
            Arrays.sort(files, new java.util.Comparator<File>() {
                @Override public int compare(File x, File y) {
                    return Long.compare(x.lastModified(), y.lastModified());
                }
            });
            for (int i = 0; i < files.length - KEEP_JOBS; i++) {
                String name = files[i].getName();
                if (name.endsWith(".json")) {
                    String id = name.substring(0, name.length() - 5);
                    apkFile(id).delete();
                }
                files[i].delete();
            }
        }
        File[] apks = apkDir().listFiles();
        if (apks == null) return;
        for (File apk : apks) {
            String name = apk.getName();
            if (!name.endsWith(".apk")) continue;
            String id = name.substring(0, name.length() - 4);
            if (!jobFile(id).isFile()) apk.delete();
        }
    }

    private static String readFile(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream((int) Math.max(64, file.length()));
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) > 0) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "null";
        if (failure instanceof GuardControl.ApiFailure) {
            return String.valueOf(((GuardControl.ApiFailure) failure).result.opt("error"));
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
