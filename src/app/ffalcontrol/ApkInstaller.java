package app.ffalcontrol;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用本进程的 PackageInstaller 会话把 APK 字节写进去并提交。
 *
 * 为什么这条路能通：字节始终在本应用进程内，经 openWrite() 的 FD 进入 PackageInstaller，
 * 不经过 /sdcard（FUSE 视图，system_server 读不了）、不经过 system_server 读文件，
 * 所以不需要存储权限、不需要 adb、不需要 root。
 *
 * 顺带绕过两条厂商策略：installer 是 app.ffalcontrol 而不是 com.android.packageinstaller
 * （ThridStrategy 不触发），也不带 INSTALL_FROM_ADB 标志（PmStrategy 不触发）。
 */
final class ApkInstaller {

    static final String ACTION_RESULT = "app.ffalcontrol.INSTALL_RESULT";
    private static final AtomicInteger REQUESTS = new AtomicInteger(100);

    interface Listener {
        void onProgress(String text);
        /** status 取 PackageInstaller.STATUS_*，无回执时是 Integer.MIN_VALUE。 */
        void onDone(boolean ok, String text, int status);
    }

    private ApkInstaller() {}

    static void install(Activity activity, File apk, String pkgName, Listener listener) {
        long bytes = apk.length();
        try {
            FileInputStream in = new FileInputStream(apk);
            install(activity, in, bytes, pkgName, listener);
        } catch (Throwable failure) {
            listener.onDone(false, "打开文件失败：" + describe(failure), Integer.MIN_VALUE);
        }
    }

    /** 阻塞式安装（调用方负责放到后台线程）。in 由本方法负责关闭。 */
    static void install(Activity activity, InputStream in, long expectedBytes,
                        String pkgName, Listener listener) {
        Context app = activity.getApplicationContext();
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (pkgName != null && SecurityGate.validPackageName(pkgName)) params.setAppPackageName(pkgName);
        if (expectedBytes > 0) params.setSize(expectedBytes);
        if (Build.VERSION.SDK_INT >= 31) {
            // 普通应用拿不到免确认，系统会降级为用户确认；写着只是为了在特权场景少一次点击。
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        final AtomicReference<Intent> result = new AtomicReference<Intent>();
        final CountDownLatch done = new CountDownLatch(1);
        final int request = REQUESTS.incrementAndGet();

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
                try (OutputStream out = session.openWrite("base.apk", 0,
                        expectedBytes > 0 ? expectedBytes : -1)) {
                    byte[] chunk = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(chunk)) > 0) {
                        out.write(chunk, 0, count);
                        copied += count;
                    }
                    session.fsync(out);
                }
                if (expectedBytes > 0 && copied != expectedBytes) {
                    throw new IllegalStateException("字节数不一致：期望 " + expectedBytes + "，实际 " + copied);
                }
                Intent callback = new Intent(ACTION_RESULT).setPackage(app.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pending = PendingIntent.getBroadcast(app, request, callback, flags);
                session.commit(pending.getIntentSender());
            } finally {
                session.close();
            }
            listener.onProgress("已提交安装会话 " + sessionId + "，等待系统确认（最长 3 分钟）…");
            if (!done.await(180, TimeUnit.SECONDS)) {
                listener.onDone(false, "等待系统确认超时。请在电视上确认安装框，或重试。", Integer.MIN_VALUE);
                return;
            }
        } catch (Throwable failure) {
            if (sessionId != -1) {
                try { installer.abandonSession(sessionId); } catch (Throwable ignored) { }
            }
            listener.onDone(false, "创建安装会话失败：" + describe(failure), Integer.MIN_VALUE);
            return;
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { app.unregisterReceiver(receiver); } catch (Throwable ignored) { }
        }

        Intent outcome = result.get();
        int status = outcome == null ? Integer.MIN_VALUE
                : outcome.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String message = outcome == null ? "(无回调)"
                : outcome.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_SUCCESS) {
            listener.onDone(true, "安装成功。", status);
            return;
        }
        listener.onDone(false, explain(status) + "\nstatus=" + status
                + (message == null ? "" : "\n" + message), status);
    }

    /** 失败码 → 人话 + 下一步。 */
    static String explain(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "安装被取消。";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "被系统安装策略拦截。多半是厂商安装校验拒绝了，点「查看判定」能看出是哪条规则。";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "包名或签名冲突，需要先卸载旧版本。";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "与当前系统不兼容（可能是 abi 或 minSdk 不匹配）。";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "安装包无效或校验未通过。";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "存储空间不足。";
            default:
                return "未知失败。";
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "null";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
