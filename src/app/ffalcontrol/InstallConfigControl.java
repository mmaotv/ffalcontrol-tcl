package app.ffalcontrol;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * InstallConfig 行：只允许改 launcherStrategy 的 packages / enable，以及两个拦截名单的 packages。
 * 其余 strategy 一字不动（render 里做深度语义比对）。
 *
 * 依据（本机 /system/priv-app/PackageInstaller_tv/PackageInstaller_tv.apk 反编译）：
 *   com.android.packageinstaller.config.InstallConfigManager 通过
 *   TclFrameworkFactory.getConfigGrabber(ctx, "InstallConfig") 读本行，
 *   com.android.packageinstaller.verifier.strategy.LauncherStrategy.allowInstall 判定：
 *     - strategy enable 为 false            -> 放行
 *     - APK 不声明 MAIN + HOME              -> 放行
 *     - 包名在 packages 白名单               -> 放行
 *     - 签名等于当前默认 HOME              -> 还要 metaData com.tcl.app.type == "com.tcl.ui"
 *     - 其余                                 -> verifyPendingInstall(-1)
 *   BlackStrategy / OverDueStrategy：包名 ∈ packages -> 拒（所以"移出名单"就是放行）
 * 因此 launcherStrategy.packages 是 HOME 放行入口，两个拦截名单的 packages 是解封入口。
 */
final class InstallConfigControl {
    static final String FEATURE = "InstallConfig";
    static final String LAUNCHER = "launcherStrategy";
    static final String BLACKLIST = "blackListStrategy";
    static final String OVERDUE = "overDueStrategy";
    static final Uri URI = Uri.parse("content://com.tcl.providers.config/" + FEATURE);
    private static final String PACKAGE_PATTERN = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+";

    private final Context context;

    InstallConfigControl(Context context) { this.context = context.getApplicationContext(); }

    synchronized JSONObject current() throws Exception {
        JSONObject out = common();
        out.put("read_only", true);
        State state = state();
        out.put("current_exists", state.exists);
        out.put("current_sha256", state.sha);
        out.put("config_content", state.raw == null ? JSONObject.NULL : state.raw);
        out.put("content_length", state.raw == null ? 0 : state.raw.length());
        out.put("strategies", strategies(state.raw));
        out.put("launcher_allowlist", launcherList(state.raw));
        out.put("write_api", "allow_launcher_package | remove_launcher_package | set_launcher_enable"
                + " | remove_from_strategy | add_to_strategy");
        return out;
    }

    synchronized JSONObject apply(String body) throws Exception {
        if (android.os.Process.myUid() / 100000 != 0)
            throw new GuardControl.ApiFailure(403, "InstallConfig changes are limited to Android user 0");
        JSONObject request;
        try { request = new JSONObject(body); }
        catch (Throwable invalid) { throw new GuardControl.ApiFailure(400, "body must be a JSON object"); }
        String action = request.optString("action", "");
        boolean write = request.optBoolean("write", false);
        String expected = request.optString("expected_sha256", "");
        if (expected.length() != 64) throw new GuardControl.ApiFailure(400, "expected_sha256 is required");
        State before = state();
        if (!before.exists) throw new GuardControl.ApiFailure(409, "InstallConfig row is absent; refusing insert");
        if (!before.sha.equals(expected)) {
            JSONObject stale = common();
            stale.put("error", "expected_sha256_mismatch_no_write");
            stale.put("current_sha256", before.sha);
            stale.put("written", false);
            throw new GuardControl.ApiFailure(409, stale);
        }

        String proposed;
        String applied;
        if ("allow_launcher_package".equals(action)) {
            String pkg = request.optString("package", "");
            if (!pkg.matches(PACKAGE_PATTERN))
                throw new GuardControl.ApiFailure(422, "package must be a full package name");
            proposed = withPackage(before.raw, LAUNCHER, pkg, true);
            applied = "allow:" + pkg;
        } else if ("remove_launcher_package".equals(action)) {
            String pkg = request.optString("package", "");
            if (!pkg.matches(PACKAGE_PATTERN))
                throw new GuardControl.ApiFailure(422, "package must be a full package name");
            proposed = withPackage(before.raw, LAUNCHER, pkg, false);
            applied = "remove:" + pkg;
        } else if ("remove_from_strategy".equals(action) || "add_to_strategy".equals(action)) {
            // 只对「拦截名单」类策略开放，且 add 分支实际上只用于撤销回滚。
            String pkg = request.optString("package", "");
            String strategy = request.optString("strategy", "");
            if (!pkg.matches(PACKAGE_PATTERN))
                throw new GuardControl.ApiFailure(422, "package must be a full package name");
            if (!BLACKLIST.equals(strategy) && !OVERDUE.equals(strategy))
                throw new GuardControl.ApiFailure(422,
                        "strategy must be " + BLACKLIST + " or " + OVERDUE);
            boolean add = "add_to_strategy".equals(action);
            proposed = withPackage(before.raw, strategy, pkg, add);
            applied = (add ? "add:" : "remove:") + strategy + "/" + pkg;
        } else if ("set_launcher_enable".equals(action)) {
            if (!request.has("enable"))
                throw new GuardControl.ApiFailure(422, "enable is required");
            boolean enable = request.optBoolean("enable", true);
            proposed = withLauncherEnable(before.raw, enable);
            applied = "enable:" + enable;
        } else {
            throw new GuardControl.ApiFailure(400, "unknown action: " + action);
        }

        JSONObject result = common();
        result.put("action", action);
        result.put("applied", applied);
        result.put("dry_run", !write);
        result.put("current_sha256", before.sha);
        result.put("proposed_sha256", sha256(proposed));
        result.put("proposed_length", proposed.length());
        result.put("launcher_allowlist_after", launcherList(proposed));
        if (!write) { result.put("status", "plan_only"); return result; }
        if (proposed.equals(before.raw)) {
            result.put("status", "identical_content_no_write");
            result.put("written", false);
            return result;
        }

        String backupId = backup(before, proposed, applied);
        result.put("backup_id", backupId);

        Integer updateCount = null;
        try {
            ContentValues values = new ContentValues();
            values.put("config_content", proposed);
            updateCount = context.getContentResolver().update(URI, values, null, null);
            result.put("operation", "update");
            result.put("update_count", updateCount);
            State after = state();
            result.put("observed_sha256", after.sha);
            result.put("observed_length", after.raw == null ? 0 : after.raw.length());
            boolean exact = after.exists && proposed.equals(after.raw)
                    && sha256(proposed).equals(after.sha)
                    && proposed.length() == (after.raw == null ? -1 : after.raw.length());
            boolean counted = updateCount != null && updateCount.intValue() == 1;
            if (!exact || !counted) {
                result.put("error", "write_or_readback_not_verified");
                result.put("written", "unknown");
                result.put("persistence_verified", false);
                throw new GuardControl.ApiFailure(500, result);
            }
            result.put("status", "install_config_written_and_readback_verified");
            result.put("written", true);
            result.put("persistence_verified", true);
            result.put("resulting_row_count", 1);
            return result;
        } catch (GuardControl.ApiFailure known) { throw known; }
        catch (Throwable failure) {
            result.put("error", "write_outcome_unknown: " + failure.getClass().getName() + ": " + failure.getMessage());
            result.put("written", "unknown");
            result.put("persistence_verified", false);
            throw new GuardControl.ApiFailure(500, result);
        }
    }

    private JSONObject common() throws Exception {
        JSONObject out = new JSONObject();
        out.put("feature", FEATURE);
        out.put("uri", URI.toString());
        return out;
    }

    private static final class State {
        final boolean exists;
        final String raw;
        final String sha;
        State(boolean exists, String raw) throws Exception {
            this.exists = exists; this.raw = raw;
            this.sha = exists && raw != null ? sha256(raw) : "";
        }
    }

    private State state() throws Exception {
        try (Cursor cursor = context.getContentResolver().query(URI,
                new String[]{"project_id", "config_content"}, null, null, null)) {
            if (cursor == null) throw new GuardControl.ApiFailure(503, "InstallConfig query returned null cursor");
            if (!cursor.moveToFirst()) return new State(false, null);
            String id = cursor.getString(cursor.getColumnIndexOrThrow("project_id"));
            String raw = cursor.getString(cursor.getColumnIndexOrThrow("config_content"));
            if (!FEATURE.equals(id) || cursor.moveToNext())
                throw new GuardControl.ApiFailure(409, "Unexpected InstallConfig query cardinality or key");
            if (raw == null) throw new GuardControl.ApiFailure(422, "Existing InstallConfig content is NULL");
            return new State(true, raw);
        }
    }

    private static JSONObject top(String raw) throws Exception {
        JSONArray rows = new JSONArray(raw);
        if (rows.length() != 1) throw new IllegalStateException("InstallConfig must hold exactly one object");
        return rows.getJSONObject(0);
    }

    private static JSONObject strategy(JSONObject top, String name) throws Exception {
        JSONArray list = top.getJSONArray("strategies");
        JSONObject found = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject strategy = list.getJSONObject(i);
            if (name.equals(strategy.optString("name", ""))) {
                if (found != null) throw new IllegalStateException("duplicate " + name);
                found = strategy;
            }
        }
        if (found == null) throw new IllegalStateException(name + " is absent");
        return found;
    }

    private static JSONObject launcher(JSONObject top) throws Exception {
        return strategy(top, LAUNCHER);
    }

    private String withPackage(String raw, String strategyName, String pkg, boolean add) throws Exception {
        JSONObject top = top(raw);
        JSONObject target = strategy(top, strategyName);
        JSONArray packages = target.has("packages") ? target.getJSONArray("packages") : new JSONArray();
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < packages.length(); i++) values.add(packages.getString(i));
        if (add) {
            if (values.contains(pkg)) throw new GuardControl.ApiFailure(422, "package already allowlisted: " + pkg);
            values.add(pkg);
        } else {
            if (!values.remove(pkg)) throw new GuardControl.ApiFailure(422, "package is not in " + strategyName + ": " + pkg);
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < values.size(); i++) next.put(values.get(i));
        target.put("packages", next);
        return render(top, raw);
    }

    private String withLauncherEnable(String raw, boolean enable) throws Exception {
        JSONObject top = top(raw);
        JSONObject launcher = launcher(top);
        launcher.put("enable", String.valueOf(enable));
        return render(top, raw);
    }

    /**
     * 序列化后再做一次语义比对：只允许 launcherStrategy 的 packages / enable 与原文不同，
     * 其余 strategy、其余顶层键必须深度等价（含顺序与数值类型）。
     */
    private static String render(JSONObject modified, String raw) throws Exception {
        String proposed = new JSONArray().put(modified).toString();
        JSONObject before = top(raw);
        JSONObject after = top(proposed);
        if (!keysEqual(before, after))
            throw new IllegalStateException("InstallConfig top-level keys changed");
        JSONArray a = before.getJSONArray("strategies");
        JSONArray b = after.getJSONArray("strategies");
        if (a.length() != b.length())
            throw new IllegalStateException("strategy count changed");
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            JSONObject y = b.getJSONObject(i);
            String name = x.optString("name", "");
            Set<String> editable = editableFields(name);
            if (editable == null) {
                if (!x.toString().equals(y.toString()))
                    throw new IllegalStateException("strategy other than launcherStrategy changed: "
                            + x.optString("name", "?"));
                continue;
            }
            if (!x.names().toString().equals(y.names().toString()))
                throw new IllegalStateException(name + " gained or lost a field");
            Iterator<String> names = x.keys();
            while (names.hasNext()) {
                String field = names.next();
                if (editable.contains(field)) continue;
                if (!String.valueOf(x.opt(field)).equals(String.valueOf(y.opt(field))))
                    throw new IllegalStateException(name + " field changed: " + field);
            }
        }
        return proposed;
    }

    /**
     * 允许改动的字段表。不在表里的 strategy 必须一字不动。
     * launcherStrategy 沿用原设计（packages + enable）；
     * 两个拦截名单只允许动 packages —— 往黑名单里加包名对本工具没有任何用处。
     */
    private static Set<String> editableFields(String name) {
        if (LAUNCHER.equals(name)) return new HashSet<String>(Arrays.asList("packages", "enable"));
        if (BLACKLIST.equals(name) || OVERDUE.equals(name))
            return new HashSet<String>(Arrays.asList("packages"));
        return null;
    }

    private static boolean keysEqual(JSONObject x, JSONObject y) {
        if (x.length() != y.length()) return false;
        Iterator<String> keys = x.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!y.has(key)) return false;
        }
        return true;
    }

    static List<String> launcherListStatic(String raw) throws Exception {
        if (raw == null) return new ArrayList<String>();
        JSONArray packages = launcher(top(raw)).optJSONArray("packages");
        List<String> out = new ArrayList<String>();
        if (packages != null) for (int i = 0; i < packages.length(); i++) out.add(packages.getString(i));
        return out;
    }

    private static List<String> launcherList(String raw) throws Exception {
        return launcherListStatic(raw);
    }

    private static JSONArray strategies(String raw) throws Exception {
        JSONArray out = new JSONArray();
        if (raw == null) return out;
        JSONArray list = top(raw).getJSONArray("strategies");
        for (int i = 0; i < list.length(); i++) {
            JSONObject strategy = list.getJSONObject(i);
            JSONObject row = new JSONObject();
            row.put("name", strategy.optString("name", ""));
            row.put("enable", strategy.optString("enable", ""));
            row.put("priority", strategy.optString("priority", ""));
            row.put("packages", strategy.optJSONArray("packages") == null
                    ? new JSONArray() : strategy.optJSONArray("packages"));
            out.put(row);
        }
        return out;
    }

    private String backup(State before, String proposed, String applied) throws Exception {
        File dir = new File(context.getFilesDir(), "installconfig-backups");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new GuardControl.ApiFailure(500, "cannot create backup directory");
        String id = "ic-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        JSONObject record = new JSONObject();
        record.put("schema", 1);
        record.put("id", id);
        record.put("feature", FEATURE);
        record.put("user_id", 0);
        record.put("applied", applied);
        record.put("original_sha256", before.sha);
        record.put("proposed_sha256", sha256(proposed));
        record.put("original_length", before.raw.length());
        record.put("proposed_length", proposed.length());
        record.put("time_ms", System.currentTimeMillis());
        record.put("original_content", before.raw);
        File file = new File(dir, id + ".json");
        FileOutputStream out = new FileOutputStream(file);
        out.write(record.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.getFD().sync();
        out.close();
        return id;
    }

    static String readBackup(Context context, String id) throws Exception {
        if (id == null || !id.matches("ic-[0-9]+-[0-9a-f]{8}"))
            throw new GuardControl.ApiFailure(422, "invalid backup id");
        File file = new File(new File(context.getFilesDir(), "installconfig-backups"), id + ".json");
        if (!file.isFile()) throw new GuardControl.ApiFailure(404, "backup not found");
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
        in.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(Character.forDigit((value >> 4) & 0xf, 16))
                .append(Character.forDigit(value & 0xf, 16));
        return builder.toString();
    }
}
