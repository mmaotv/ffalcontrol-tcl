package app.ffalcontrol;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.InputStream;

/**
 * 内置桌面：把 assets/launcher.apk 交给 ApkInstaller 装进去，再申请 HOME 角色。
 *
 * 实际的会话写入在 ApkInstaller 里（和「推送安装」共用同一套代码）。
 */
final class BundledLauncher {

    /** assets 里的内置桌面文件名。universal 变体没有这个 asset。 */
    static final String ASSET = "launcher.apk";
    /** 内置桌面包名，由 build.sh 按变体生成 LauncherSpec。 */
    static final String TARGET = LauncherSpec.TARGET;
    /** 界面上的显示名。 */
    static final String LABEL = LauncherSpec.LABEL;
    /** 这个变体是否内置了桌面。 */
    static final boolean BUNDLED = LauncherSpec.BUNDLED;
    private static final int ROLE_REQUEST = 4101;
    private static final int HOME_SETTINGS_REQUEST = 4102;

    interface Listener {
        void onProgress(String text);
        void onDone(boolean ok, String text);
    }

    private BundledLauncher() {}

    /** 内置桌面的当前状态，只读。 */
    static JSONObject state(Context context) throws Exception {
        Context app = context.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        JSONObject out = new JSONObject();
        out.put("bundled", BUNDLED);
        out.put("asset", ASSET);
        out.put("asset_bytes", BUNDLED ? assetSize(app) : 0);
        out.put("target_package", TARGET);
        out.put("label", LABEL);

        if (BUNDLED) {
            try {
                PackageInfo info = pm.getPackageInfo(TARGET, 0);
                out.put("installed", true);
                out.put("installed_version_name", info.versionName);
                out.put("installed_version_code", Build.VERSION.SDK_INT >= 28
                        ? (int) info.getLongVersionCode() : info.versionCode);
            } catch (Throwable missing) {
                out.put("installed", false);
            }
        } else {
            out.put("installed", false);
        }

        out.put("default_home", ApkAnalyzer.defaultHome(app));

        if (Build.VERSION.SDK_INT >= 26) out.put("can_request_install", pm.canRequestPackageInstalls());
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager roles = (RoleManager) app.getSystemService(Context.ROLE_SERVICE);
                out.put("home_role_available", roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME));
                out.put("home_role_held", roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME));
            } catch (Throwable unsupported) {
                out.put("home_role_available", false);
            }
        }
        return out;
    }

    /** 阻塞式安装（调用方负责放到后台线程）。 */
    static void installFromAssets(Activity activity, final Listener listener) {
        Context app = activity.getApplicationContext();
        if (!BUNDLED) {
            listener.onDone(false, "这个变体没有内置桌面。用「推送安装」或「选包安装」装任意 APK。");
            return;
        }
        long expectedBytes = assetSize(app);
        if (expectedBytes <= 0) {
            listener.onDone(false, "内置桌面资源缺失（assets/" + ASSET + "）。");
            return;
        }
        listener.onProgress("内置桌面：" + expectedBytes + " 字节，准备安装会话 …");
        InputStream in;
        try {
            in = app.getAssets().open(ASSET);
        } catch (Throwable failure) {
            listener.onDone(false, "读取内置资源失败：" + describe(failure));
            return;
        }
        ApkInstaller.install(activity, in, expectedBytes, TARGET, new ApkInstaller.Listener() {
            @Override public void onProgress(String text) { listener.onProgress(text); }
            @Override public void onDone(boolean ok, String text, int status) {
                listener.onDone(ok, ok ? LABEL + " 安装成功（" + TARGET + "）。"
                        : "安装失败：" + text);
            }
        });
    }

    /**
     * 申请 HOME 角色（Android 10+ 的正规路径），失败则回落到系统默认应用页。
     * 返回给人看的一句话。
     */
    static String requestHomeRole(Activity activity) {
        String target = TARGET == null || TARGET.length() == 0 ? null : TARGET;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager roles = (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                        String current = ApkAnalyzer.defaultHome(activity);
                        if (target != null && target.equals(current)) {
                            return "默认桌面已经是 " + LABEL + "，无需再设置。";
                        }
                    }
                    activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME),
                            ROLE_REQUEST);
                    return "已在电视上弹出「默认桌面」确认框，请选目标桌面并确认。";
                }
            } catch (Throwable unsupported) {
                // 继续走回落路径
            }
        }
        for (String action : new String[]{"android.settings.HOME_SETTINGS",
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"}) {
            try {
                activity.startActivityForResult(new Intent(action), HOME_SETTINGS_REQUEST);
                return "本固件不支持直接申请 HOME 角色，已打开系统默认应用页，请手动选目标桌面。";
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return "无法打开默认桌面设置，请在首页按 HOME 键触发系统选择器。";
    }

    /** 从 PackageManager 拿内置桌面的安装状态（供 /launcher 接口用）。 */
    static JSONObject installedInfo(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        JSONObject out = new JSONObject();
        try {
            ApplicationInfo info = pm.getApplicationInfo(TARGET, 0);
            out.put("installed", true);
            out.put("label", pm.getApplicationLabel(info).toString());
        } catch (Throwable missing) {
            out.put("installed", false);
        }
        return out;
    }

    private static long assetSize(Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            long total = 0;
            byte[] chunk = new byte[64 * 1024];
            int count;
            while ((count = in.read(chunk)) > 0) total += count;
            return total;
        } catch (Throwable missing) {
            return -1;
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "null";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
