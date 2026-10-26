package app.ffalcontrol;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内置桌面：把 assets/launcher.apk 用本进程的 PackageInstaller 会话写进去并提交。
 *
 * 为什么这样做：APK 字节始终在本应用进程内，经 openWrite() 的 FD 进入 PackageInstaller，
 * 不经过 /sdcard、不经过 system_server 读文件，所以不需要存储权限、不需要 adb、不需要根。
 *
 * 安装完成后可在同一个界面里申请 HOME 角色（RoleManager），把内置桌面设为默认桌面。
 */
final class BundledLauncher {

    /** assets 里的内置桌面文件名。 */
    static final String ASSET = "launcher.apk";
    /** 内置桌面包名，由 build.sh 按变体生成 LauncherSpec。 */
    static final String TARGET = LauncherSpec.TARGET;
    /** 界面上的显示名。 */
    static final String LABEL = LauncherSpec.LABEL;
    private static final String ACTION_RESULT = "app.ffalcontrol.LAUNCHER_INSTALL_RESULT";
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
        out.put("asset", ASSET);
        out.put("asset_bytes", assetSize(app));
        out.put("target_package", TARGET);
        out.put("label", LABEL);

        try {
            PackageInfo info = pm.getPackageInfo(TARGET, 0);
            out.put("installed", true);
            out.put("installed_version_name", info.versionName);
            out.put("installed_version_code", Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode);
        } catch (Throwable missing) {
            out.put("installed", false);
        }

        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        out.put("default_home", resolved == null || resolved.activityInfo == null
                ? null : resolved.activityInfo.packageName);

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
    static void installFromAssets(Activity activity, Listener listener) throws Exception {
        Context app = activity.getApplicationContext();
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();

        long expectedBytes = assetSize(app);
        if (expectedBytes <= 0) {
            listener.onDone(false, "内置桌面资源缺失（assets/" + ASSET + "）。");
            return;
        }
        listener.onProgress("内置桌面：" + expectedBytes + " 字节，准备安装会话 …");

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(TARGET);
        params.setSize(expectedBytes);
        if (Build.VERSION.SDK_INT >= 31) {
            // 普通应用拿不到免确认，系统会降级为用户确认；写在这里只是为了在特权场景下少一次点击。
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        final AtomicReference<Intent> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = Build.VERSION.SDK_INT >= 33
                            ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                            : (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        final Intent launch = confirm;
                        activity.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                try { activity.startActivity(launch); } catch (Throwable ignored) { }
                            }
                        });
                    }
                    return; // 等用户点完，结果会再回调一次
                }
                result.set(new Intent(intent));
                done.countDown();
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, filter);

        int sessionId = -1;
        try {
            sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                long copied = 0;
                try (InputStream in = app.getAssets().open(ASSET);
                     OutputStream out = session.openWrite("base.apk", 0, expectedBytes)) {
                    byte[] chunk = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(chunk)) > 0) {
                        out.write(chunk, 0, count);
                        copied += count;
                    }
                    session.fsync(out);
                }
                if (copied != expectedBytes) {
                    throw new IllegalStateException("资源字节数不一致：期望 " + expectedBytes + "，实际 " + copied);
                }
                Intent callback = new Intent(ACTION_RESULT).setPackage(app.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pending = PendingIntent.getBroadcast(app, 0, callback, flags);
                session.commit(pending.getIntentSender());
            } finally {
                session.close();
            }
            listener.onProgress("已提交安装会话 " + sessionId + "，等待系统确认（最长 3 分钟）…");
            if (!done.await(180, TimeUnit.SECONDS)) {
                listener.onDone(false, "等待系统确认超时。请在电视上确认安装框，或重试。");
                return;
            }
        } catch (Throwable failure) {
            if (sessionId != -1) {
                try { installer.abandonSession(sessionId); } catch (Throwable ignored) { }
            }
            listener.onDone(false, "创建安装会话失败：" + describe(failure));
            return;
        } finally {
            try { app.unregisterReceiver(receiver); } catch (Throwable ignored) { }
        }

        Intent outcome = result.get();
        int status = outcome == null ? Integer.MIN_VALUE
                : outcome.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String message = outcome == null ? "(无回调)"
                : outcome.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_SUCCESS) {
            listener.onDone(true, LABEL + " 安装成功（" + TARGET + "）。");
            return;
        }
        String hint;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: hint = "安装被取消。"; break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED: hint = "被系统安装策略拦截（可能被厂商安装校验拒绝）。"; break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT: hint = "包名或签名冲突，需先卸载旧版本。"; break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: hint = "与当前系统不兼容。"; break;
            case PackageInstaller.STATUS_FAILURE_INVALID: hint = "安装包无效或校验未通过。"; break;
            case PackageInstaller.STATUS_FAILURE_STORAGE: hint = "存储空间不足。"; break;
            default: hint = "未知失败。"; break;
        }
        listener.onDone(false, "安装失败：" + hint + "\nstatus=" + status + "\n" + message);
    }

    /**
     * 申请 HOME 角色（Android 10+ 的正规路径），失败则回落到系统默认应用页。
     * 返回给人看的一句话。
     */
    static String requestHomeRole(Activity activity) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager roles = (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                        PackageManager pm = activity.getPackageManager();
                        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                        ResolveInfo resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
                        String current = resolved == null || resolved.activityInfo == null
                                ? null : resolved.activityInfo.packageName;
                        if (TARGET.equals(current)) return "默认桌面已经是 " + LABEL + "，无需再设置。";
                    }
                    activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME),
                            ROLE_REQUEST);
                    return "已在电视上弹出「默认桌面」确认框，请选 " + LABEL + " 并确认。";
                }
            } catch (Throwable unsupported) {
                // 继续走回落路径
            }
        }
        for (String action : new String[]{"android.settings.HOME_SETTINGS",
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"}) {
            try {
                activity.startActivityForResult(new Intent(action), HOME_SETTINGS_REQUEST);
                return "本固件不支持直接申请 HOME 角色，已打开系统默认应用页，请手动选 " + LABEL + "。";
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
