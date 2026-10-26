package app.ffalcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Normal application process; starts read-only, fixed Guard row API, loopback-only. */
public final class MainActivity extends Activity {
    private static final int PORT = 18742;
    private static final int MAX_REQUEST = 65536;
    private static final int MAX_RESPONSE = 1048576;
    private static final int MATCH = PackageManager.MATCH_UNINSTALLED_PACKAGES
            | PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
    private static final String CONFIG = "content://com.tcl.providers.config/all";
    private static final String FOCUS = "content://com.tcl.tvos.provider.appstatus.com.tcl.cyberui/focus";
    private volatile boolean running;
    private volatile ServerSocket server;
    private TextView status;
    private TextView resultView;
    private GuardControl guardControl;
    private AppBootControl appBootControl;
    private InstallConfigControl installConfigControl;
    private ConfigControl configControl;
    private PushInstall pushInstall;
    private PolicyGuard policyGuard;
    private PushServer pushServer;
    private volatile String watchingJob;

    private static final int REQUEST_PICK_APK = 4201;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        guardControl = new GuardControl(this);
        appBootControl = new AppBootControl(this);
        installConfigControl = new InstallConfigControl(this);
        configControl = new ConfigControl(this);
        policyGuard = new PolicyGuard(this);
        pushInstall = new PushInstall(this);
        pushServer = new PushServer(this, pushInstall);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Ui.BG);
        body.setPadding(Ui.dp(this, 46), Ui.dp(this, 30), Ui.dp(this, 46), Ui.dp(this, 26));

        String versionName = "0.1.1";
        try { versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) { }

        LinearLayout header = Ui.row(this);
        header.addView(Ui.label(this, "TCL 净化", 30, Ui.LABEL, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(Ui.chip(this, BundledLauncher.BUNDLED
                ? "内置 " + BundledLauncher.LABEL : "通用版", Ui.GREEN));
        body.addView(header);

        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = Ui.dp(this, 8);
        body.addView(Ui.label(this, "ffalcontrol v" + versionName
                        + " · 净化 + 推送安装 + 厂商策略判定 · 接口仅 127.0.0.1:" + PORT,
                14, Ui.SECONDARY, false), subtitleParams);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(Ui.cardPlate(this));
        statusCard.setPadding(Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 22));
        status = Ui.label(this, "正在诊断。启动只读取状态，不会自动修改配置。", 15, Ui.SECONDARY, false);
        status.setLineSpacing(Ui.dp(this, 4), 1f);
        statusCard.addView(status, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(statusCard, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = Ui.dp(this, 18);
        body.addView(scroll, scrollParams);

        LinearLayout actions = Ui.row(this);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = Ui.dp(this, 18);

        TextView provisionButton = Ui.pill(this, "一键净化", Ui.PRIMARY);
        provisionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { runProvision("core"); }
        });
        actions.addView(provisionButton, Ui.weight(this));

        TextView refresh = Ui.pill(this, "刷新诊断", 0);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { updateSummary(); }
        });
        actions.addView(refresh, Ui.weight(this));

        TextView allowLauncherButton = Ui.pill(this, "放行桌面", 0);
        allowLauncherButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { allowLauncher(BundledLauncher.TARGET); }
        });
        actions.addView(allowLauncherButton, Ui.weight(this));

        TextView stop = Ui.pill(this, "停止并退出", Ui.DANGER);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { stopServer(); finish(); }
        });
        actions.addView(stop, Ui.weight(this));
        body.addView(actions, actionsParams);

        LinearLayout launcherRow = Ui.row(this);
        LinearLayout.LayoutParams launcherParams = new LinearLayout.LayoutParams(-1, -2);
        launcherParams.topMargin = Ui.dp(this, 12);

        final TextView installHome = Ui.pill(this, "装内置桌面 · " + BundledLauncher.LABEL, Ui.PRIMARY);
        installHome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { installBundledLauncher(); }
        });
        launcherRow.addView(installHome, Ui.weight(this));

        TextView setDefault = Ui.pill(this, "设为默认桌面", 0);
        setDefault.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                showResult(BundledLauncher.requestHomeRole(MainActivity.this));
            }
        });
        launcherRow.addView(setDefault, Ui.weight(this));

        TextView launcherState = Ui.pill(this, "桌面状态", 0);
        launcherState.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showLauncherState(); }
        });
        launcherRow.addView(launcherState, Ui.weight(this));
        if (!BundledLauncher.BUNDLED) installHome.setVisibility(View.GONE);
        body.addView(launcherRow, launcherParams);

        LinearLayout pushRow = Ui.row(this);
        LinearLayout.LayoutParams pushParams = new LinearLayout.LayoutParams(-1, -2);
        pushParams.topMargin = Ui.dp(this, 12);

        TextView pushButton = Ui.pill(this, "推送安装", Ui.PRIMARY);
        pushButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPushPanel(); }
        });
        pushRow.addView(pushButton, Ui.weight(this));

        TextView pickButton = Ui.pill(this, "选包安装", 0);
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { pickApk(); }
        });
        pushRow.addView(pickButton, Ui.weight(this));

        TextView preauthButton = Ui.pill(this, "预授权放行", 0);
        preauthButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { askPreauthorize(); }
        });
        pushRow.addView(preauthButton, Ui.weight(this));
        body.addView(pushRow, pushParams);

        resultView = Ui.label(this, "", 14, Ui.ORANGE, false);
        resultView.setBackground(Ui.cardPlate(this));
        resultView.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18));
        resultView.setLineSpacing(Ui.dp(this, 3), 1f);
        String tokenValue = BuildToken.value(MainActivity.this);
        android.util.Log.i("FFalControl", "token=" + tokenValue);
        resultView.setText("就绪。装桌面会自动：放行白名单 → 安装 → 申请默认桌面。");
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(-1, -2);
        resultParams.topMargin = Ui.dp(this, 16);
        body.addView(resultView, resultParams);

        TextView note = Ui.label(this, "包级禁用走 Guard_disable_App，由 com.tcl.guard 在开机/唤醒时执行；"
                + "本工具同时写 AppBootPolicy 拦截与 InstallConfig 白名单，每次写入都先备份原值。"
                + "\nOTA 可能收紧厂商策略，升级固件后请重新跑一次诊断。", 12, Ui.TERTIARY, false);
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = Ui.dp(this, 14);
        body.addView(note, noteParams);

        setContentView(body);
        installHome.requestFocus();
        running = true;
        new Thread(new Runnable() {
            @Override public void run() { serve(); }
        }, "TVControl-loopback").start();
        pushServer.start();
        updateSummary();
    }

    @Override public void onBackPressed() { stopServer(); super.onBackPressed(); }
    @Override protected void onDestroy() { stopServer(); super.onDestroy(); }

    private void stopServer() {
        running = false;
        ServerSocket socket = server;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        if (pushServer != null) pushServer.stop();
    }

    private void showStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() { if (!isFinishing()) status.setText(text); }
        });
    }

    private void updateSummary() {
        showStatus("正在读取配置与应用状态……\n启动和刷新只读，不会自动写入。");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject snap = snapshot();
                    JSONObject config = snap.optJSONObject("config");
                    JSONObject apps = snap.optJSONObject("applications");
                    JSONArray configRows = config == null ? null : config.optJSONArray("rows");
                    JSONArray appRows = apps == null ? null : apps.optJSONArray("rows");
                    showStatus("只读诊断已完成\n设备：" + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                            + "\n配置行：" + (configRows == null ? "读取失败" : configRows.length())
                            + "\n应用：" + (appRows == null ? "读取失败" : appRows.length())
                            + "\n\n本机接口：127.0.0.1:" + PORT
                            + "\n所有请求需要构建时生成的独立令牌。"
                            + "\n启动和刷新只读；本机受限配置接口会先备份再写入。"
                            + "\n返回键或“停止服务并退出”可关闭服务。"
                            + (config != null && config.has("error") ? "\n配置错误：" + config.opt("error") : ""));
                } catch (Throwable failure) { showStatus("诊断部分失败：" + describe(failure)); }
            }
        }, "TVControl-summary").start();
    }

    private JSONObject snapshot() throws Exception {
        JSONObject result = new JSONObject();
        result.put("schema", 1);
        result.put("app_version", "4.0-appboot");
        result.put("time_ms", System.currentTimeMillis());
        result.put("read_only", true);
        JSONObject build = new JSONObject();
        build.put("manufacturer", Build.MANUFACTURER);
        build.put("brand", Build.BRAND);
        build.put("model", Build.MODEL);
        build.put("device", Build.DEVICE);
        build.put("hardware", Build.HARDWARE);
        build.put("display", Build.DISPLAY);
        build.put("fingerprint", Build.FINGERPRINT);
        build.put("release", Build.VERSION.RELEASE);
        build.put("sdk", Build.VERSION.SDK_INT);
        build.put("security_patch", Build.VERSION.SECURITY_PATCH);
        build.put("incremental", Build.VERSION.INCREMENTAL);
        build.put("process_uid", android.os.Process.myUid());
        result.put("build", build);
        result.put("config", readProvider(CONFIG, new String[]{"project_id", "config_content"}));
        result.put("applications", applications());
        result.put("cyberui", cyberui());
        result.put("home", resolve(Intent.CATEGORY_HOME));
        result.put("leanback", resolve(Intent.CATEGORY_LEANBACK_LAUNCHER));
        return result;
    }

    private JSONObject readProvider(String uriString, String[] projection) throws Exception {
        JSONObject out = new JSONObject();
        out.put("uri", uriString);
        JSONArray rows = new JSONArray();
        out.put("rows", rows);
        try (Cursor cursor = getContentResolver().query(Uri.parse(uriString), projection,
                null, null, null)) {
            if (cursor == null) throw new IllegalStateException("null cursor");
            String[] columns = cursor.getColumnNames();
            out.put("columns", new JSONArray(columns));
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < columns.length; i++) {
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: row.put(columns[i], JSONObject.NULL); break;
                        case Cursor.FIELD_TYPE_INTEGER: row.put(columns[i], cursor.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: row.put(columns[i], cursor.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_BLOB:
                            JSONObject blob = new JSONObject();
                            blob.put("encoding", "base64");
                            blob.put("data", Base64.encodeToString(cursor.getBlob(i), Base64.NO_WRAP));
                            row.put(columns[i], blob); break;
                        default: row.put(columns[i], cursor.getString(i));
                    }
                }
                rows.put(row);
            }
        } catch (Throwable failure) { out.put("error", describe(failure)); }
        out.put("complete", !out.has("error"));
        return out;
    }

    private JSONObject applications() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray rows = new JSONArray();
        out.put("rows", rows);
        PackageManager pm = getPackageManager();
        try {
            for (PackageInfo info : pm.getInstalledPackages(MATCH)) {
                JSONObject row = new JSONObject();
                row.put("package", info.packageName);
                try {
                    ApplicationInfo app = info.applicationInfo;
                    row.put("uid", app.uid);
                    row.put("shared_user_id", info.sharedUserId == null ? JSONObject.NULL : info.sharedUserId);
                    row.put("version_name", info.versionName == null ? JSONObject.NULL : info.versionName);
                    row.put("version_code", info.getLongVersionCode());
                    row.put("flags", app.flags);
                    row.put("installed_flag", (app.flags & ApplicationInfo.FLAG_INSTALLED) != 0);
                    row.put("system", (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    row.put("updated_system", (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
                    row.put("stopped", (app.flags & ApplicationInfo.FLAG_STOPPED) != 0);
                    row.put("enabled", app.enabled);
                    row.put("source_dir", app.sourceDir);
                    row.put("label", String.valueOf(pm.getApplicationLabel(app)));
                    try { row.put("enabled_setting", pm.getApplicationEnabledSetting(info.packageName)); }
                    catch (Throwable failure) { row.put("enabled_setting_error", describe(failure)); }
                    try { pm.getPackageInfo(info.packageName, 0); row.put("ordinary_lookup", "present"); }
                    catch (PackageManager.NameNotFoundException failure) { row.put("ordinary_lookup", "not_found"); }
                    catch (Throwable failure) { row.put("ordinary_lookup_error", describe(failure)); }
                } catch (Throwable failure) { row.put("error", describe(failure)); }
                rows.put(row);
            }
        } catch (Throwable failure) { out.put("error", describe(failure)); }
        out.put("complete", !out.has("error"));
        return out;
    }

    private JSONObject cyberui() throws Exception {
        JSONObject out = new JSONObject();
        out.put("package", "com.tcl.cyberui");
        try {
            PackageInfo info = getPackageManager().getPackageInfo("com.tcl.cyberui", MATCH
                    | PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
            out.put("activities", components(info.activities));
            out.put("services", components(info.services));
            out.put("receivers", components(info.receivers));
        } catch (Throwable failure) { out.put("error", describe(failure)); }
        return out;
    }

    private JSONArray components(ComponentInfo[] list) throws Exception {
        JSONArray rows = new JSONArray();
        if (list == null) return rows;
        for (ComponentInfo info : list) {
            JSONObject row = new JSONObject();
            row.put("name", info.name);
            row.put("package", info.packageName);
            row.put("manifest_enabled", info.enabled);
            row.put("exported", info.exported);
            row.put("process", info.processName);
            row.put("direct_boot_aware", info.directBootAware);
            if (info instanceof ActivityInfo) {
                row.put("permission", ((ActivityInfo) info).permission == null
                        ? JSONObject.NULL : ((ActivityInfo) info).permission);
                row.put("target_activity", ((ActivityInfo) info).targetActivity == null
                        ? JSONObject.NULL : ((ActivityInfo) info).targetActivity);
            } else if (info instanceof ServiceInfo) {
                row.put("permission", ((ServiceInfo) info).permission == null
                        ? JSONObject.NULL : ((ServiceInfo) info).permission);
            }
            try { row.put("enabled_setting", getPackageManager().getComponentEnabledSetting(
                    new ComponentName(info.packageName, info.name))); }
            catch (Throwable failure) { row.put("error", describe(failure)); }
            rows.put(row);
        }
        return rows;
    }

    private JSONObject resolve(String category) throws Exception {
        JSONObject out = new JSONObject();
        out.put("category", category);
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            PackageManager pm = getPackageManager();
            ResolveInfo chosen = pm.resolveActivity(intent, 0);
            out.put("resolved", chosen == null || chosen.activityInfo == null
                    ? JSONObject.NULL : component(chosen.activityInfo));
            JSONArray candidates = new JSONArray();
            List<ResolveInfo> all = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo info : all) if (info.activityInfo != null) candidates.put(component(info.activityInfo));
            out.put("candidates", candidates);
        } catch (Throwable failure) { out.put("error", describe(failure)); }
        return out;
    }

    private static String component(ActivityInfo info) {
        return new ComponentName(info.packageName, info.name).flattenToString();
    }

    private void serve() {
        try (ServerSocket listener = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))) {
            server = listener;
            while (running) {
                try (Socket socket = listener.accept()) {
                    if (!socket.getInetAddress().isLoopbackAddress()) continue;
                    socket.setSoTimeout(7000);
                    handle(socket);
                } catch (Throwable failure) {
                    if (running) android.util.Log.w("TVControl", "Loopback request failed: " + describe(failure));
                }
            }
        } catch (Throwable failure) {
            if (running) showStatus("本机诊断服务未启动：" + describe(failure));
        } finally { running = false; server = null; }
    }

    private void handle(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        String request = line(in, 4096);
        if (request == null) return;
        String[] first = request.split(" ");
        if (first.length != 3 || !first[2].startsWith("HTTP/1.")) {
            respond(socket, 400, error("invalid_request")); return;
        }
        String authorization = null;
        String contentType = null;
        int length = 0;
        int total = request.length();
        boolean hasLength = false;
        boolean chunked = false;
        boolean badHeaders = false;
        for (;;) {
            String header = line(in, 4096);
            if (header == null) throw new IllegalArgumentException("EOF in headers");
            total += header.length() + 2;
            if (total > 16384) { respond(socket, 431, error("headers_too_large")); return; }
            if (header.length() == 0) break;
            int colon = header.indexOf(':');
            if (colon <= 0) { badHeaders = true; continue; }
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            if (name.equals("authorization")) {
                if (authorization != null) badHeaders = true;
                authorization = value;
            } else if (name.equals("content-length")) {
                if (hasLength) badHeaders = true;
                hasLength = true;
                try { length = Integer.parseInt(value); } catch (NumberFormatException invalid) { badHeaders = true; }
            } else if (name.equals("transfer-encoding")) chunked = true;
            else if (name.equals("content-type")) {
                if (contentType != null) badHeaders = true;
                contentType = value;
            }
        }
        if (badHeaders || chunked || length < 0) { respond(socket, 400, error("invalid_headers")); return; }
        // /push 的 body 是原始 APK 字节，单独给一个上限，不能套 JSON 的 64KB
        boolean rawPush = first[0].equals("POST")
                && (first[1].equals("/push") || first[1].startsWith("/push?"));
        if (length > (rawPush ? PushInstall.MAX_APK : MAX_REQUEST)) {
            respond(socket, 413, error(rawPush ? "apk_too_large" : "request_too_large"));
            return;
        }
        byte[] expected = ("Bearer " + BuildToken.value(MainActivity.this)).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) { respond(socket, 401, error("unauthorized")); return; }

        if (rawPush) {
            socket.setSoTimeout(300000);
            try {
                JSONObject job = pushInstall.stage(in, queryParam(first[1], "name"), length, "api");
                JSONObject out = new JSONObject(job.toString());
                out.put("ok", true);
                respond(socket, 200, out);
            } catch (GuardControl.ApiFailure failure) {
                respond(socket, failure.status, failure.result);
            } catch (Throwable failure) {
                respond(socket, 500, error("push_failed: " + describe(failure)));
            }
            return;
        }
        boolean postGuard = first[1].equals("/guard");
        boolean postAppBoot = first[1].equals("/app-boot-policy");
        boolean postInstallConfig = first[1].equals("/install-config");
        boolean postProvision = first[1].equals("/provision");
        boolean postMsgDelete = first[1].equals("/cyberui-messages/delete");
        boolean postMsgInsert = first[1].equals("/cyberui-messages/insert");
        boolean postConfig = first[1].equals("/config-write");
        boolean postPushAnalyze = first[1].equals("/push/analyze");
        boolean postPushInstall = first[1].equals("/push/install");
        boolean postPushPreauth = first[1].equals("/push/preauthorize");
        boolean postPushDelete = first[1].equals("/push/delete");
        if (first[0].equals("POST")
                && (postGuard || postAppBoot || postInstallConfig || postProvision
                        || postMsgDelete || postMsgInsert || postConfig
                        || postPushAnalyze || postPushInstall || postPushPreauth || postPushDelete)) {
            if (!hasLength || length == 0 || contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).matches("application/json(\\s*;\\s*charset=utf-8)?")) {
                respond(socket, 400, error((postGuard ? "guard" : postAppBoot ? "appboot"
                        : postInstallConfig ? "installconfig" : "provision")
                        + "_requires_content_length_and_json_utf8")); return;
            }
            byte[] body = new byte[length]; int read = 0;
            while (read < length) {
                int count = in.read(body, read, length - read);
                if (count < 0) { respond(socket, 400, error("incomplete_body")); return; }
                read += count;
            }
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString(); }
            catch (Exception invalid) { respond(socket, 400, error("invalid_utf8")); return; }
            try {
                JSONObject applied;
                if (postPushAnalyze || postPushInstall || postPushPreauth || postPushDelete) {
                    JSONObject payload = new JSONObject(text);
                    applied = postPushAnalyze ? pushInstall.analyze(payload.optString("staged_id", ""))
                            : postPushInstall ? pushInstall.install(MainActivity.this,
                                    payload.optString("staged_id", ""),
                                    payload.optBoolean("patch", true),
                                    payload.optBoolean("rollback_on_failure", true),
                                    payload.optString("origin", "tool"))
                            : postPushPreauth ? pushInstall.preauthorize(
                                    payload.optString("package", ""), payload.optBoolean("write", false))
                            : deleteJob(payload.optString("staged_id", ""));
                } else {
                    applied = postAppBoot ? appBootControl.apply(text)
                            : postInstallConfig ? installConfigControl.apply(text)
                            : postProvision ? provision(text)
                            : postMsgDelete ? deleteCyberMessages(text)
                            : postMsgInsert ? insertCyberMessage(text)
                            : postConfig ? configControl.apply(text)
                            : guardControl.apply(text);
                }
                respond(socket, 200, applied);
            }
            catch (GuardControl.ApiFailure failure) { respond(socket, failure.status, failure.result); }
            catch (Throwable failure) { respond(socket, 500, error((postAppBoot ? "appboot_error: "
                    : postInstallConfig ? "installconfig_error: "
                    : postProvision ? "provision_error: " : "guard_error: ") + describe(failure))); }
            return;
        }
        if (!first[0].equals("GET")) { respond(socket, 405, error("method_not_allowed")); return; }
        if (length != 0) { respond(socket, 400, error("get_body_not_allowed")); return; }
        JSONObject result;
        if (first[1].equals("/health")) {
            result = new JSONObject();
            result.put("status", "ok"); result.put("startup_read_only", true);
            result.put("guard_write_api", true);
            result.put("appboot_fixed_components_api", true);
            result.put("install_config_api", true);
            result.put("provision_api", true);
            result.put("launcher_api", true);
            result.put("bundled_launcher", BundledLauncher.BUNDLED ? BundledLauncher.TARGET : JSONObject.NULL);
            result.put("push_install_api", true);
            result.put("security_policy_api", true);
            result.put("lan_upload_port", PushServer.PORT);
            result.put("lan_upload_running", pushServer.running());
            result.put("lan_address", PushServer.lanAddress() == null
                    ? JSONObject.NULL : PushServer.lanAddress());
            result.put("provision_payload_version", ProvisionPayload.VERSION);
            result.put("version", "5.4-one-click"); result.put("uid", android.os.Process.myUid());
            result.put("bound_address", "127.0.0.1"); result.put("port", PORT);
        }         else if (first[1].equals("/snapshot")) result = snapshot();
        else if (first[1].equals("/launcher")) result = BundledLauncher.state(this);
        else if (first[1].equals("/security/policies")) result = policyGuard.describe();
        else if (first[1].equals("/push/info")) result = pushInfo();
        else if (first[1].equals("/push/jobs")) result = pushJobs(30);
        else if (first[1].startsWith("/push/jobs/")) {
            try { result = pushInstall.job(first[1].substring("/push/jobs/".length())); }
            catch (GuardControl.ApiFailure failure) { respond(socket, failure.status, failure.result); return; }
        }
        else if (first[1].equals("/focus")) result = readProvider(FOCUS, null);
        else if (first[1].equals("/app-boot-policy")) result = appBootControl.current();
        else if (first[1].equals("/install-config")) result = installConfigControl.current();
        else if (first[1].equals("/provision")) result = provisionStatus();
        else if (first[1].equals("/settings")) result = readSystemSettings();
        else if (first[1].equals("/settings-all")) result = readAllSettings();
        else if (first[1].equals("/cyberui-messages")) result = readCyberMessages();
        else if (first[1].startsWith("/pkg/")) result = packageDetail(first[1].substring(5));
        else if (first[1].equals("/app-boot-policy/backups") || first[1].startsWith("/app-boot-policy/backups/")) {
            try { result = appBootControl.backups(first[1].equals("/app-boot-policy/backups") ? null : first[1].substring("/app-boot-policy/backups/".length())); }
            catch (GuardControl.ApiFailure failure) { respond(socket, failure.status, failure.result); return; }
        }
        else if (first[1].equals("/backups") || first[1].startsWith("/backups/")) {
            try { result = guardControl.backups(first[1].equals("/backups") ? null : first[1].substring(9)); }
            catch (GuardControl.ApiFailure failure) { respond(socket, failure.status, failure.result); return; }
        } else { respond(socket, 404, error("not_found")); return; }
        respond(socket, 200, result);
    }

    /** 纯代码界面：结果一律黑底黄字，避免主题缺省色导致不可见。 */
    private void showResult(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (isFinishing() || resultView == null) return;
                String content = text == null ? "" : text;
                int color = Ui.SECONDARY;
                if (content.contains("失败") || content.contains("错误") || content.contains("拦截")
                        || content.contains("无效") || content.contains("超时")) {
                    color = Ui.RED;
                } else if (content.contains("完成") || content.contains("成功")
                        || content.contains("已安装") || content.contains("就绪")) {
                    color = Ui.GREEN;
                }
                resultView.setTextColor(color);
                resultView.setText(content);
            }
        });
    }

    /** 系统里的 HOME 角色/默认桌面设置页，由 PermissionController 提供。 */
    // ---------------------------------------------------------------- 推送安装

    private static String queryParam(String target, String key) {
        int q = target.indexOf('?');
        if (q < 0) return null;
        for (String pair : target.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !key.equals(pair.substring(0, eq))) continue;
            try { return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"); }
            catch (Throwable ignored) { return null; }
        }
        return null;
    }

    private JSONObject deleteJob(String id) throws Exception {
        pushInstall.delete(id);
        JSONObject out = new JSONObject();
        out.put("deleted", id);
        return out;
    }

    private JSONObject pushJobs(int limit) throws Exception {
        JSONObject out = new JSONObject();
        out.put("jobs", pushInstall.jobs(limit));
        return out;
    }

    private JSONObject pushInfo() throws Exception {
        JSONObject out = new JSONObject();
        out.put("lan_address", PushServer.lanAddress() == null
                ? JSONObject.NULL : PushServer.lanAddress());
        out.put("lan_port", PushServer.PORT);
        out.put("lan_running", pushServer.running());
        out.put("hint", PushServer.hint());
        out.put("max_bytes", PushInstall.MAX_APK);
        JSONArray endpoints = new JSONArray();
        endpoints.put("POST /push?name=x.apk  （body 就是 APK 字节，需要 Bearer）");
        endpoints.put("POST /push/analyze  {staged_id}");
        endpoints.put("POST /push/install  {staged_id, patch, rollback_on_failure}");
        endpoints.put("POST /push/preauthorize  {package, write}");
        endpoints.put("POST /push/delete  {staged_id}");
        endpoints.put("GET  /push/jobs | /push/jobs/<id> | /security/policies");
        out.put("endpoints", endpoints);
        return out;
    }

    /** 「推送安装」：先告诉用户地址，再列出待安装的包让他挑。 */
    private void showPushPanel() {
        showResult("正在读取待安装列表 …");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final JSONArray jobs = pushInstall.jobs(8);
                    final StringBuilder info = new StringBuilder();
                    info.append("推送安装\n\n").append(PushServer.hint()).append("\n\n");
                    info.append("本页只能收文件，安装要在电视上点。\n");
                    if (jobs.length() == 0) {
                        info.append("\n还没有收到安装包。用手机传一个，或点「选包安装」。");
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            showResult(info.toString());
                            if (jobs.length() > 0) showJobPicker(jobs);
                        }
                    });
                } catch (Throwable failure) {
                    showResult("读取任务失败：" + describe(failure));
                }
            }
        }).start();
    }

    private void showJobPicker(final JSONArray jobs) {
        final String[] names = new String[jobs.length()];
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            String state = job.optString("state", "");
            names[i] = job.optString("file_name", "?") + "  ·  " + stateLabel(state);
        }
        new AlertDialog.Builder(this)
                .setTitle("选一个开始安装")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        JSONObject job = jobs.optJSONObject(which);
                        if (job != null) confirmAndInstall(job.optString("id"));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static String stateLabel(String state) {
        if (PushInstall.S_STAGED.equals(state)) return "待判定";
        if (PushInstall.S_ANALYZED.equals(state)) return "已判定";
        if (PushInstall.S_PATCHED.equals(state)) return "已改配置";
        if (PushInstall.S_INSTALLING.equals(state)) return "安装中";
        if (PushInstall.S_SUCCESS.equals(state)) return "已安装";
        if (PushInstall.S_FAILED.equals(state)) return "失败";
        return state;
    }

    /** 判定 → 展示结论 → 确认后安装。 */
    private void confirmAndInstall(final String id) {
        showResult("正在判定这个包会被哪条厂商策略拦 …");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final JSONObject job = pushInstall.analyze(id);
                    final JSONObject analysis = job.getJSONObject("analysis");
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            showResult(describeAnalysis(analysis));
                            JSONArray patches = analysis.optJSONArray("patches");
                            boolean needsPatch = patches != null && patches.length() > 0;
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(needsPatch ? "先改配置再安装？" : "开始安装？")
                                    .setMessage(needsPatch
                                            ? analysis.optString("package") + "\n\n将修改 " + patches.length()
                                                    + " 处厂商配置（已自动备份，可回滚），然后调起系统安装框。"
                                            : analysis.optString("package") + "\n\n直接调起系统安装框。")
                                    .setPositiveButton(needsPatch ? "改配置并安装" : "安装",
                                            new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface dialog, int which) {
                                                    installJob(id, needsPatch);
                                                }
                                            })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    });
                } catch (Throwable failure) {
                    showResult("判定失败：" + describe(failure));
                }
            }
        }).start();
    }

    private void installJob(String id, boolean patch) {
        try {
            watchingJob = id;
            pushInstall.install(this, id, patch, true, "tool");
            showResult("已提交安装任务，等待系统确认框 …");
            watchJob(id);
        } catch (Throwable failure) {
            showResult("无法开始安装：" + describe(failure));
        }
    }

    /** 轮询任务状态直到终态。 */
    private void watchJob(final String id) {
        final android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || !id.equals(watchingJob)) return;
                try {
                    JSONObject job = pushInstall.job(id);
                    String state = job.optString("state");
                    showResult(describeJob(job));
                    if (PushInstall.S_SUCCESS.equals(state) || PushInstall.S_FAILED.equals(state)) {
                        watchingJob = null;
                        return;
                    }
                } catch (Throwable ignored) {
                    // 任务可能刚被清理，停掉轮询
                    return;
                }
                handler.postDelayed(this, 1000);
            }
        }, 600);
    }

    private static String describeAnalysis(JSONObject analysis) {
        StringBuilder text = new StringBuilder();
        text.append(analysis.optString("label", "（无名称）"))
                .append("\n").append(analysis.optString("package", "?"));
        String version = analysis.optString("version_name", "");
        if (version.length() > 0) text.append("  v").append(version);
        text.append("\n").append(String.format(Locale.ROOT, "%.1f MB",
                analysis.optLong("bytes") / 1048576.0));
        if (analysis.optBoolean("declares_home")) {
            text.append(" · 桌面类").append(analysis.optBoolean("tcl_signed") ? "（TCL 签名）" : "（第三方）");
        }

        JSONArray hits = analysis.optJSONArray("hits");
        if (hits == null || hits.length() == 0) {
            text.append("\n\n判定：不会被任何厂商策略拦，可直接装。");
        } else {
            text.append("\n\n判定：会被 ").append(hits.length()).append(" 条策略拦 ——");
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.optJSONObject(i);
                if (hit == null) continue;
                text.append("\n· ").append(hit.optString("strategy"))
                        .append("：").append(hit.optString("reason"))
                        .append("\n  → ").append(hit.optString("fix"));
            }
        }

        JSONObject installed = analysis.optJSONObject("installed");
        if (installed != null && installed.optBoolean("present")) {
            text.append("\n\n已装同名包 v").append(installed.optInt("version_code"));
            if (!installed.optBoolean("same_signature", true)) {
                text.append("，但签名不同 → 必须先卸载旧版");
            } else if (installed.optBoolean("downgrade")) {
                text.append("，且这是降级安装");
            }
        }
        if (!analysis.optBoolean("policy_loaded", true)) {
            text.append("\n\n注意：没读到 InstallConfig，判定按默认值推测。");
        }
        return text.toString();
    }

    private static String describeJob(JSONObject job) {
        StringBuilder text = new StringBuilder();
        text.append("任务 ").append(job.optString("id")).append("\n");
        text.append("状态：").append(stateLabel(job.optString("state"))).append("\n");
        String message = job.optString("message", "");
        if (message.length() > 0) text.append("\n").append(message).append("\n");
        JSONArray log = job.optJSONArray("log");
        if (log != null && log.length() > 0) {
            text.append("\n进度：\n");
            int from = Math.max(0, log.length() - 8);
            for (int i = from; i < log.length(); i++) {
                text.append("· ").append(log.optString(i)).append("\n");
            }
        }
        JSONObject post = job.optJSONObject("post_check");
        if (post != null) {
            text.append("\n装后检查：");
            if (post.optBoolean("resolvable")) {
                text.append("已装 v").append(post.optString("version_name", "?"));
                text.append(post.optBoolean("enabled") ? "，启用中" : "，但被禁用");
                text.append(post.optBoolean("launchable") ? "，可启动" : "，无可启动入口");
            } else {
                text.append("查不到该包，可能被 guard 立即禁用，冷启动后复查");
            }
        }
        return text.toString();
    }

    /** 「选包安装」：走系统文件选择器，不依赖网络。 */
    private void pickApk() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/vnd.android.package-archive", "application/octet-stream",
                    "application/zip"});
            startActivityForResult(intent, REQUEST_PICK_APK);
            showResult("已打开文件选择器，选一个 APK。\n如果系统没装文件选择器，请改用「推送安装」。");
        } catch (Throwable failure) {
            showResult("打不开文件选择器：" + describe(failure) + "\n请改用「推送安装」。");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        showResult("正在读取选中的文件 …");
        new Thread(new Runnable() {
            @Override public void run() {
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IllegalStateException("拿不到文件流");
                    JSONObject job = pushInstall.stage(in, displayName(uri), -1, "picker");
                    runOnUiThread(new Runnable() {
                        @Override public void run() { }
                    });
                    confirmAndInstall(job.optString("id"));
                } catch (Throwable failure) {
                    showResult("读取失败：" + describe(failure)
                            + "\n若提示 SELinux/权限，请改用「推送安装」。");
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
            }
        }).start();
    }

    private String displayName(Uri uri) {
        try {
            android.database.Cursor cursor = getContentResolver()
                    .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        String name = cursor.getString(0);
                        if (name != null && name.length() > 0) return name;
                    }
                } finally {
                    try { cursor.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "picked.apk" : last;
    }

    /** 「预授权放行」：只有包名也能先把路打通。 */
    private void askPreauthorize() {
        final EditText input = new EditText(this);
        input.setHint("例如 com.example.app");
        input.setSingleLine(true);
        input.setTextColor(Ui.LABEL);
        input.setHintTextColor(Ui.TERTIARY);
        input.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        new AlertDialog.Builder(this)
                .setTitle("预授权放行")
                .setMessage("输入包名后，会把它加进桌面放行名单、并从拦截名单里移出。\n"
                        + "注意：这一步只放行，不会安装任何东西。")
                .setView(input)
                .setPositiveButton("放行", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        runPreauthorize(input.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runPreauthorize(final String pkg) {
        if (!SecurityGate.validPackageName(pkg)) {
            showResult("不是合法的包名：" + pkg + "\n包名形如 com.example.app，必须带至少一个点。");
            return;
        }
        showResult("正在为 " + pkg + " 写放行配置 …");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject result = pushInstall.preauthorize(pkg, true);
                    showResult("预授权完成：" + pkg
                            + "\n改动 " + result.optJSONArray("applied").length() + " 处"
                            + (result.optBoolean("ok") ? "" : "（有失败项）")
                            + "\n\n之后安装这个包不会再被厂商策略拦。");
                } catch (Throwable failure) {
                    showResult("预授权失败：" + describe(failure));
                }
            }
        }).start();
    }

    /** 装内置桌面：白名单 → 安装（assets 里那份）→ 申请 HOME 角色，一条链走完。 */
    private void installBundledLauncher() {
        showResult("第 1/3 步：把 " + BundledLauncher.TARGET + " 加入安装白名单 …");
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder log = new StringBuilder();
                try {
                    String raw = featureRaw("InstallConfig");
                    if (raw == null) {
                        log.append("InstallConfig 行不存在，跳过白名单。\n");
                    } else {
                        JSONObject result = installConfigControl.apply(new JSONObject()
                                .put("action", "allow_launcher_package")
                                .put("package", BundledLauncher.TARGET)
                                .put("expected_sha256", sha256Hex(raw))
                                .put("write", true).toString());
                        log.append("白名单：").append(result.optString("status", "已写入")).append("\n");
                    }
                } catch (Throwable failure) {
                    log.append("白名单失败（继续安装）：").append(describe(failure)).append("\n");
                }
                showResult(log + "\n第 2/3 步：安装内置桌面 " + BundledLauncher.LABEL + " …");
                try {
                    BundledLauncher.installFromAssets(MainActivity.this, new BundledLauncher.Listener() {
                        @Override public void onProgress(String text) {
                            showResult(log + "\n" + text);
                        }
                        @Override public void onDone(boolean ok, String text) {
                            if (!ok) {
                                showResult(log + "\n" + text);
                                return;
                            }
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    String roleMessage = BundledLauncher.requestHomeRole(MainActivity.this);
                                    showResult(log + "\n" + text + "\n\n第 3/3 步：" + roleMessage);
                                }
                            });
                        }
                    });
                } catch (Throwable failure) {
                    showResult(log + "\n安装失败：" + describe(failure));
                }
            }
        }, "TVControl-launcher").start();
    }

    private void showLauncherState() {
        showResult("正在读取桌面状态 …");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final JSONObject state = BundledLauncher.state(MainActivity.this);
                    StringBuilder text = new StringBuilder();
                    text.append("内置桌面：").append(state.optString("label")).append(" / ")
                            .append(state.optString("target_package")).append("\n");
                    text.append("资源大小：").append(state.optLong("asset_bytes", -1)).append(" 字节\n");
                    text.append("是否已安装：").append(state.optBoolean("installed", false));
                    if (state.optBoolean("installed", false)) {
                        text.append("（").append(state.optString("installed_version_name"))
                                .append(" / vc").append(state.optInt("installed_version_code")).append("）");
                    }
                    text.append("\n当前默认桌面：").append(state.opt("default_home"));
                    if (state.has("home_role_held")) {
                        text.append("\n持有 HOME 角色：").append(state.optBoolean("home_role_held"));
                    }
                    if (state.has("can_request_install")) {
                        text.append("\n允许安装未知应用：").append(state.optBoolean("can_request_install"));
                    }
                    showResult(text.toString());
                } catch (Throwable failure) {
                    showResult("读取失败：" + describe(failure));
                }
            }
        }, "TVControl-launcher-state").start();
    }

    private void openHomeSettings() {
        String[] candidates = new String[]{
                "android.settings.HOME_SETTINGS",
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"
        };
        for (String action : candidates) {
            try {
                Intent intent = new Intent(action);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                showResult("已打开系统默认桌面设置：\n" + action
                        + "\n在列表里选「HOME 探针」即可设为默认桌面。\n若没有弹出，说明本固件未开放该页面。");
                return;
            } catch (Throwable failure) {
                showResult("无法打开 " + action + "：" + describe(failure)
                        + "\n可改用在首页按 HOME 键触发系统选择器。");
            }
        }
    }

    /** 把第三方 HOME 包加入 InstallConfig.launcherStrategy 白名单。 */
    private void allowLauncher(final String homePackage) {
        showResult("正在把 " + homePackage + " 加入安装白名单 …");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String raw = featureRaw("InstallConfig");
                    if (raw == null) { showResult("InstallConfig 行不存在。"); return; }
                    JSONObject result = installConfigControl.apply(new JSONObject()
                            .put("action", "allow_launcher_package")
                            .put("package", homePackage)
                            .put("expected_sha256", sha256Hex(raw))
                            .put("write", true).toString());
                    showResult("已处理：" + homePackage + "\n" + result);
                } catch (Throwable failure) {
                    showResult("失败：" + describe(failure));
                }
            }
        }).start();
    }

    private void runProvision(final String level) {
        showResult("冷装恢复进行中：" + level + " … 不要退出。");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject outcome = provision(new JSONObject().put("level", level)
                            .put("write", true).toString());
                    boolean ok = outcome.optBoolean("ok", false);
                    int packages = outcome.optJSONArray("target_packages") == null
                            ? 0 : outcome.optJSONArray("target_packages").length();
                    int components = outcome.optJSONArray("target_components") == null
                            ? 0 : outcome.optJSONArray("target_components").length();
                    if (outcome.optBoolean("already_provisioned", false)) {
                        showResult("已经执行过，无需重复执行。\n"
                                + "Guard / AppBootPolicy / InstallConfig 三行都与目标状态一致。\n"
                                + "如果刚恢复出厂，请确认装的是当前版本并重新点一次。");
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(ok ? "冷装恢复完成 ✔" : "冷装恢复未完成 ✘").append("\n");
                    sb.append("payload ").append(ProvisionPayload.VERSION).append("\n");
                    sb.append("目标包 ").append(packages).append(" 个 / 组件 ").append(components).append(" 个\n");
                    sb.append("Guard 读回 SHA 匹配：").append(outcome.optBoolean("guard_readback_sha_match")).append("\n");
                    sb.append("AppBootPolicy 写入：").append(outcome.optBoolean("appboot_verified"))
                      .append("（新增 ").append(outcome.optInt("appboot_added", 0)).append(" 条）\n");
                    sb.append("InstallConfig 白名单：").append(outcome.optBoolean("install_config_verified")).append("\n");
                    if (outcome.has("appboot_error")) sb.append("appboot 错误：").append(outcome.optString("appboot_error")).append("\n");
                    if (outcome.has("guard_error")) sb.append("guard 错误：").append(outcome.optString("guard_error")).append("\n");
                    sb.append("\n下一步：断电冷启动（30 秒），让 AppBootPolicy 重新加载。");
                    showResult(sb.toString());
                } catch (Throwable failure) {
                    showResult("冷装恢复失败\n" + describe(failure));
                }
            }
        }, "TVControl-provision").start();
    }

    private synchronized JSONObject provision(String body) throws Exception {
        JSONObject request;
        try { request = new JSONObject(body); }
        catch (Throwable invalid) { throw new GuardControl.ApiFailure(400, "body must be a JSON object"); }
        String level = request.optString("level", "core");
        if (!level.equals("core") && !level.equals("full"))
            throw new GuardControl.ApiFailure(422, "level must be core or full");
        boolean write = request.optBoolean("write", false);
        JSONObject out = new JSONObject();
        out.put("payload_version", ProvisionPayload.VERSION);
        out.put("level", level);
        out.put("dry_run", !write);
        out.put("target_packages", new JSONArray(ProvisionPayload.packages(level)));
        out.put("target_components", new JSONArray(ProvisionPayload.CYBERUI_COMPONENTS));

        String guardRaw = featureRaw("Guard_disable_App");
        List<String> skipPackages = new ArrayList<String>();
        android.content.pm.PackageManager pm = getPackageManager();
        for (String pkg : ProvisionPayload.packages(level)) {
            try {
                if (pm.getApplicationEnabledSetting(pkg)
                        == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    skipPackages.add(pkg);
                }
            } catch (Throwable ignored) { }
        }
        out.put("guard_skipped_already_disabled_user", new JSONArray(skipPackages));
        String proposed = ProvisionPayload.guard(guardRaw, level, skipPackages);
        String guardSha = guardRaw == null ? "ABSENT" : sha256Hex(guardRaw);
        out.put("guard_current_sha256", guardSha);
        out.put("guard_proposed_sha256", sha256Hex(proposed));
        out.put("guard_proposed_length", proposed.length());

        String launcherRaw = featureRaw("InstallConfig");
        out.put("install_config_sha256", launcherRaw == null ? "ABSENT" : sha256Hex(launcherRaw));

        if (!write) {
            out.put("status", "plan_only");
            return out;
        }

        // 直接走 ConfigProvider：不经过 GuardControl 的可见性/身份校验，
        // 否则恢复出厂后（包为 enabled=1）会因为"visibility unknown"整批 422。
        Uri guardUri = Uri.parse("content://com.tcl.providers.config/" + "Guard_disable_App");
        JSONObject guardResult = new JSONObject();
        boolean guardAlready = guardRaw != null && guardRaw.equals(proposed);
        if (guardAlready) {
            guardResult.put("operation", "already_up_to_date");
            guardResult.put("persistence_verified", true);
        } else if (guardRaw == null) {
            ContentValues guardValues = new ContentValues();
            guardValues.put("project_id", "Guard_disable_App");
            guardValues.put("config_content", proposed);
            getContentResolver().insert(guardUri, guardValues);
            guardResult.put("operation", "insert");
            guardResult.put("persistence_verified", true);
        } else {
            guardResult = configControl.apply(new JSONObject()
                    .put("project_id", "Guard_disable_App")
                    .put("expected_sha256", guardSha)
                    .put("config_content", proposed)
                    .put("write", true).toString());
        }
        out.put("guard", guardResult);
        String guardReadback = featureRaw("Guard_disable_App");
        boolean guardOk = guardReadback != null && guardReadback.equals(proposed);
        out.put("guard_readback_equal", guardOk);
        out.put("guard_verified", guardOk);

        boolean launcherOk = true;
        int launcherWrites = 0;
        String launcherNote = "skipped_absent_row";
        JSONArray launcherSteps = new JSONArray();
        if (launcherRaw != null) {
            for (String homePackage : ProvisionPayload.LAUNCHER_ALLOW) {
                JSONObject step = new JSONObject();
                step.put("package", homePackage);
                String currentRaw = featureRaw("InstallConfig");
                List<String> allow = InstallConfigControl.launcherListStatic(currentRaw);
                if (allow.contains(homePackage)) {
                    step.put("status", "already_allowlisted");
                } else {
                    try {
                        JSONObject launcherResult = installConfigControl.apply(new JSONObject()
                                .put("action", "allow_launcher_package")
                                .put("package", homePackage)
                                .put("expected_sha256", sha256Hex(currentRaw))
                                .put("write", true).toString());
                        launcherWrites++;
                        step.put("status", launcherResult.optString("status", "unknown"));
                        step.put("verified", launcherResult.optBoolean("persistence_verified", false));
                        if (!launcherResult.optBoolean("persistence_verified", false)) launcherOk = false;
                    } catch (GuardControl.ApiFailure failure) {
                        step.put("status", "failed");
                        step.put("error", failure.result.optString("error", "unknown"));
                        launcherOk = false;
                    }
                }
                launcherSteps.put(step);
            }
            launcherNote = "processed";
        }
        out.put("install_config_steps", launcherSteps);
        out.put("install_config_verified", launcherOk);
        out.put("install_config_note", launcherNote);

        // 写后自检：重新读两行，比对长度与 SHA。
        String guardAfter = featureRaw("Guard_disable_App");
        boolean guardLen = guardAfter != null && guardAfter.length() == proposed.length();
        boolean guardSha2 = guardAfter != null && sha256Hex(guardAfter).equals(sha256Hex(proposed));
        out.put("guard_readback_length_match", guardLen);
        out.put("guard_readback_sha_match", guardSha2);
        String launcherAfter = featureRaw("InstallConfig");
        boolean launcherAllow = launcherAfter != null;
        if (launcherAllow) {
            List<String> finalList = InstallConfigControl.launcherListStatic(launcherAfter);
            for (String homePackage : ProvisionPayload.LAUNCHER_ALLOW)
                if (!finalList.contains(homePackage)) launcherAllow = false;
        }
        out.put("install_config_allowlist_present", launcherAllow);
        out.put("install_config_allowlist_expected", new JSONArray(ProvisionPayload.LAUNCHER_ALLOW));

        // 一键：AppBootPolicy 的固定组件拦截（桌面切换 + 卡片 + 专题 + 更新检查）
        boolean appbootOk = true;
        try {
            String abRaw = featureRaw("AppBootPolicy");
            if (abRaw == null) {
                out.put("appboot_status", "row_absent");
            } else {
                JSONArray abRows = new JSONArray(abRaw);
                JSONObject abTop = abRows.getJSONObject(0);
                JSONArray abComponents = abTop.getJSONObject("activityBlackList").getJSONArray("componentName");
                java.util.Set<String> haveAb = new java.util.HashSet<String>();
                for (int i = 0; i < abComponents.length(); i++) haveAb.add(abComponents.getString(i));
                int abAdded = 0;
                for (String target : AppBootPolicy.TARGETS) {
                    if (!haveAb.contains(target)) { abComponents.put(target); haveAb.add(target); abAdded++; }
                }
                if (abAdded == 0) {
                    out.put("appboot_status", "already_target_state");
                } else {
                    JSONObject abOut = configControl.apply(new JSONObject()
                            .put("project_id", "AppBootPolicy")
                            .put("expected_sha256", sha256Hex(abRaw))
                            .put("config_content", abRows.toString())
                            .put("write", true).toString());
                    out.put("appboot_added", abAdded);
                    out.put("appboot", abOut);
                    appbootOk = abOut.optBoolean("persistence_verified", false);
                }
            }
        } catch (Throwable ab) {
            appbootOk = false;
            out.put("appboot_error", describe(ab));
        }
        out.put("appboot_verified", appbootOk);

        boolean alreadyProvisioned = guardAlready && launcherWrites == 0
                && !out.has("appboot_added");
        out.put("already_provisioned", alreadyProvisioned);
        boolean ok = guardOk && guardLen && guardSha2 && launcherOk && launcherAllow && appbootOk;
        out.put("status", ok ? "provision_ok" : "provision_incomplete");
        out.put("ok", ok);
        return out;
    }

    /** 灵控桌面消息/卡片表：content://com.tcl.cyberui.messages/Messages/operate/3（manifest 无权限保护）。 */
    private static final Uri CYBER_MESSAGES =
            Uri.parse("content://com.tcl.cyberui.messages/Messages/operate/3");

    private JSONObject readCyberMessages() throws Exception {
        JSONObject out = new JSONObject();
        out.put("read_only", true);
        out.put("uri", CYBER_MESSAGES.toString());
        JSONArray rows = new JSONArray();
        try (Cursor cursor = getContentResolver().query(CYBER_MESSAGES, null, null, null, "_id DESC")) {
            if (cursor != null) {
                String[] columns = cursor.getColumnNames();
                while (cursor.moveToNext()) {
                    JSONObject row = new JSONObject();
                    for (int i = 0; i < columns.length; i++) {
                        row.put(columns[i], cursor.isNull(i) ? JSONObject.NULL : cursor.getString(i));
                    }
                    rows.put(row);
                }
            }
        }
        out.put("rows", rows);
        out.put("count", rows.length());
        return out;
    }

    private JSONObject deleteCyberMessages(String body) throws Exception {
        JSONObject request = new JSONObject(body);
        if (!request.optBoolean("confirm", false))
            throw new GuardControl.ApiFailure(422, "confirm=true is required to delete");
        String where = request.optString("where", "");
        int deleted = getContentResolver().delete(CYBER_MESSAGES,
                where.length() == 0 ? null : where, null);
        JSONObject out = new JSONObject();
        out.put("deleted", deleted);
        out.put("where", where.length() == 0 ? JSONObject.NULL : where);
        return out;
    }

    private JSONObject insertCyberMessage(String body) throws Exception {
        JSONObject request = new JSONObject(body);
        ContentValues values = new ContentValues();
        java.util.Iterator<String> keys = request.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = request.get(key);
            if (value instanceof Number) values.put(key, ((Number) value).intValue());
            else if (value instanceof Boolean) values.put(key, ((Boolean) value) ? 1 : 0);
            else values.put(key, String.valueOf(value));
        }
        if (values.size() == 0) throw new GuardControl.ApiFailure(422, "empty insert body");
        Uri inserted = getContentResolver().insert(CYBER_MESSAGES, values);
        JSONObject out = new JSONObject();
        out.put("inserted", inserted == null ? JSONObject.NULL : inserted.toString());
        out.put("fields", values.size());
        return out;
    }

    /** 只读：整张 Settings.System（含 TCL 的桌面/模式相关的私有键），用于验证模式切换等实验是否真的生效。 */
    private JSONObject readAllSettings() throws Exception {
        JSONObject out = new JSONObject();
        out.put("read_only", true);
        JSONObject system = new JSONObject();
        try {
            Cursor cursor = getContentResolver().query(
                    android.provider.Settings.System.CONTENT_URI,
                    new String[]{"name", "value"}, null, null, "name");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String key = cursor.getString(0);
                    String value = cursor.isNull(1) ? null : cursor.getString(1);
                    if (key != null) system.put(key, value == null ? JSONObject.NULL : value);
                }
                cursor.close();
            }
        } catch (Throwable failure) {
            out.put("error", describe(failure));
        }
        out.put("system", system);
        out.put("system_count", system.length());
        return out;
    }

    /** 只读：单个包的真实可用状态（含 TCL 的 ordinary flags=0 双查询）。 */
    private JSONObject packageDetail(String name) throws Exception {
        JSONObject out = new JSONObject();
        out.put("read_only", true);
        out.put("package", name);
        PackageManager pm = getPackageManager();
        try {
            android.content.pm.PackageInfo info = pm.getPackageInfo(name, 0);
            out.put("installed_visible", true);
            out.put("version_code", info.versionCode);
            out.put("version_name", String.valueOf(info.versionName));
            out.put("uid", info.applicationInfo.uid);
            out.put("source_dir", info.applicationInfo.sourceDir);
            out.put("enabled", info.applicationInfo.enabled);
            out.put("system", (info.applicationInfo.flags
                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
        } catch (Throwable missing) {
            out.put("installed_visible", false);
            out.put("lookup_error", missing.getClass().getSimpleName());
        }
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(name, 0);
            out.put("ordinary_lookup", "present");
            out.put("ordinary_enabled", ai.enabled);
        } catch (Throwable missing) {
            out.put("ordinary_lookup", "not_found");
        }
        try {
            out.put("stopped", (pm.getApplicationInfo(name, 0).flags
                    & android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0);
        } catch (Throwable ignored) { }
        return out;
    }

    /** 只读：TCL 与 Android 中与「可卸载预装」「卸载记录」相关的 Settings.System 键。 */
    private JSONObject readSystemSettings() throws Exception {
        JSONObject out = new JSONObject();
        out.put("read_only", true);
        JSONObject system = new JSONObject();
        String[] keys = new String[]{
                "tcl_sys_removeable_get_apps",
                "auto_uninstall_switch",
                "tcl_app_auto_uninstall_switch",
                "auto_uninstall_last_agree_time",
                "tcl_sys_last_uninstall_time"
        };
        for (String key : keys) {
            Object value = JSONObject.NULL;
            try {
                String read = android.provider.Settings.System.getString(getContentResolver(), key);
                if (read != null) value = read;
            } catch (Throwable ignored) { }
            system.put(key, value);
        }
        out.put("system", system);
        JSONArray installed = new JSONArray();
        try {
            String removable = android.provider.Settings.System.getString(
                    getContentResolver(), "tcl_sys_removeable_get_apps");
            if (removable != null && removable.length() > 0) {
                for (String piece : removable.split(";")) {
                    String pkg = piece.trim();
                    if (pkg.length() == 0) continue;
                    JSONObject row = new JSONObject();
                    row.put("package", pkg);
                    try {
                        android.content.pm.PackageInfo info = getPackageManager()
                                .getPackageInfo(pkg, 0);
                        row.put("installed", true);
                        row.put("system", (info.applicationInfo.flags
                                & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
                        row.put("source_dir", info.applicationInfo.sourceDir);
                    } catch (Throwable missing) {
                        row.put("installed", false);
                    }
                    installed.put(row);
                }
            }
        } catch (Throwable ignored) { }
        out.put("removeable_packages", installed);
        out.put("removeable_count", installed.length());
        return out;
    }

    private JSONObject provisionStatus() throws Exception {
        JSONObject out = new JSONObject();
        out.put("payload_version", ProvisionPayload.VERSION);
        out.put("target_packages", new JSONArray(ProvisionPayload.packages("core")));
        String guardRaw = featureRaw("Guard_disable_App");
        out.put("guard_sha256", guardRaw == null ? "ABSENT" : sha256Hex(guardRaw));
        JSONArray done = new JSONArray();
        if (guardRaw != null) {
            JSONArray list = new JSONObject(new org.json.JSONArray(guardRaw).getString(0)).optJSONArray("disableAppInfoList");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.getJSONObject(i);
                if (entry.optBoolean("disable", false) && entry.optString("componentName", "").length() == 0)
                    done.put(entry.optString("packageName", ""));
            }
        }
        out.put("guard_disabled_packages", done);
        String launcherRaw = featureRaw("InstallConfig");
        out.put("launcher_allowlist", launcherRaw == null
                ? new JSONArray() : new JSONArray(InstallConfigControl.launcherListStatic(launcherRaw)));
        out.put("install_config_sha256", launcherRaw == null ? "ABSENT" : sha256Hex(launcherRaw));
        return out;
    }

    private String featureRaw(String feature) throws Exception {
        Uri uri = Uri.parse("content://com.tcl.providers.config/" + feature);
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{"project_id", "config_content"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return cursor.getString(cursor.getColumnIndexOrThrow("config_content"));
        }
    }

    private static String sha256Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(Character.forDigit((value >> 4) & 0xf, 16))
                .append(Character.forDigit(value & 0xf, 16));
        return builder.toString();
    }

    private static String line(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (buffer.size() <= limit) {
            int next = in.read();
            if (next == -1) return buffer.size() == 0 ? null
                    : new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
            if (next == '\n') {
                byte[] bytes = buffer.toByteArray();
                int size = bytes.length;
                if (size > 0 && bytes[size - 1] == '\r') size--;
                return new String(bytes, 0, size, StandardCharsets.US_ASCII);
            }
            buffer.write(next);
        }
        throw new IllegalArgumentException("HTTP line too long");
    }

    private static void respond(Socket socket, int code, JSONObject object) throws Exception {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESPONSE) {
            JSONObject limited = error("response_exceeds_1MiB_limit");
            limited.put("complete", false); limited.put("required_bytes", bytes.length);
            bytes = limited.toString().getBytes(StandardCharsets.UTF_8);
            code = 507;
        }
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 " + code + " Response\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
    }

    private static JSONObject error(String message) throws Exception {
        JSONObject out = new JSONObject(); out.put("error", message); return out;
    }

    private static String describe(Throwable failure) {
        if (failure instanceof GuardControl.ApiFailure) {
            GuardControl.ApiFailure f = (GuardControl.ApiFailure) failure;
            return "HTTP " + f.status + " " + (f.result == null ? "" : f.result.toString());
        }
        return failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
    }
}
