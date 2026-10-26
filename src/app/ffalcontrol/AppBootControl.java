package app.ffalcontrol;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One existing provider row, two fixed activities, no package setters or policy triggers. */
final class AppBootControl {
    private static final Uri URI = Uri.parse("content://com.tcl.providers.config/AppBootPolicy");
    private static final int MATCH = PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
    private final Context context;

    AppBootControl(Context context) { this.context = context.getApplicationContext(); }

    synchronized JSONObject current() throws Exception {
        JSONObject out = common();
        out.put("read_only", true);
        try {
            State state = state();
            out.put("current_exists", true); out.put("current_sha256", state.sha);
            out.put("config_content", state.raw);
            out.put("components", new JSONArray(AppBootPolicy.components(state.raw)));
            out.put("row_policy_valid", true);
        } catch (Exception failure) { out.put("row_error", describe(failure)); out.put("row_policy_valid", false); }
        JSONObject inspection = inspect(); out.put("preflight", inspection);
        out.put("write_preconditions_met", out.optBoolean("row_policy_valid") && inspection.getBoolean("valid"));
        return out;
    }

    synchronized JSONObject apply(String body) throws Exception {
        AppBootPolicy.Request request;
        try { request = new AppBootPolicy.Request(body); }
        catch (IllegalArgumentException invalid) { throw failure(400, invalid.getMessage()); }
        State before = state();
        if (!before.sha.equals(request.expected)) throw stale(before);
        JSONObject preflight = inspect(); requirePreflight(preflight);
        Map<String, Object> saved = null;
        String proposed;
        List<String> added;
        try {
            if (request.restoreId == null) {
                proposed = request.proposed;
                added = AppBootPolicy.addition(before.raw, proposed);
            } else {
                saved = loadBackup(request.restoreId);
                proposed = AppBootPolicy.restore(saved, request.restoreId, before.raw);
                AppBootPolicy.requireSameIdentity(AppBootPolicy.object(saved.get("target_identity"), "Saved target identity"),
                        asMap(preflight.getJSONObject("target_identity")));
                added = java.util.Collections.emptyList();
            }
        } catch (IllegalArgumentException invalid) { throw failure(422, invalid.getMessage()); }
        JSONObject result = common();
        result.put("dry_run", !request.write); result.put("written", false);
        result.put("operation", request.restoreId == null ? "add_fixed_components" : "restore_original_raw");
        result.put("current_sha256", before.sha); result.put("proposed_sha256", AppBootPolicy.sha(proposed));
        result.put("config_content", proposed); result.put("preflight", preflight);
        result.put("added_components", new JSONArray(added));
        result.put("requires_full_restart", request.restoreId != null);
        if (request.restoreId != null) result.put("restore_backup_id", request.restoreId);
        if (!request.write) { result.put("status", "plan_only"); return result; }
        if (before.raw.equals(proposed)) { result.put("status", "identical_content_no_write"); return result; }

        String backupId = backup(before, proposed, preflight.getJSONObject("target_identity"),
                result.getString("operation"), request.restoreId);
        result.put("backup_id", backupId);
        // Check the live gate and identities again after durable backup, then re-read the row last.
        JSONObject fresh = inspect();
        if (!fresh.getBoolean("valid") || !asMap(preflight.getJSONObject("target_identity"))
                .equals(fresh.has("target_identity") ? asMap(fresh.getJSONObject("target_identity")) : null)) {
            result.put("error", "preflight_changed_after_backup_no_write");
            result.put("latest_preflight", fresh); throw new GuardControl.ApiFailure(409, result);
        }
        State rechecked;
        try { rechecked = state(); }
        catch (Exception unavailable) {
            result.put("error", "configuration_recheck_failed_no_write: " + describe(unavailable));
            throw new GuardControl.ApiFailure(503, result);
        }
        if (!before.sha.equals(rechecked.sha) || !before.raw.equals(rechecked.raw)) {
            result.put("error", "configuration_changed_after_backup_no_write");
            result.put("observed_sha256", rechecked.sha); throw new GuardControl.ApiFailure(409, result);
        }
        // OEM update selection is ignored: this is a second read, not an atomic cross-process CAS.
        int count;
        State after;
        try {
            ContentValues values = new ContentValues(); values.put("config_content", proposed);
            count = context.getContentResolver().update(URI, values, null, null);
            result.put("update_count", count);
            after = state(); result.put("observed_sha256", after.sha);
        } catch (Throwable problem) {
            result.put("error", "write_outcome_unknown: " + describe(problem)); result.put("written", "unknown");
            result.put("persistence_verified", false); throw new GuardControl.ApiFailure(500, result);
        }
        if (count != 1 || !proposed.equals(after.raw) || !AppBootPolicy.sha(proposed).equals(after.sha)) {
            result.put("error", "write_or_full_readback_not_verified"); result.put("written", "unknown");
            result.put("persistence_verified", false); throw new GuardControl.ApiFailure(500, result);
        }
        result.put("status", "configuration_written_and_readback_verified");
        result.put("written", true); result.put("persistence_verified", true); result.put("resulting_row_count", 1);
        return result;
    }

    synchronized String currentRaw() throws Exception { return state().raw; }

    private State state() throws Exception {
        try (Cursor cursor = context.getContentResolver().query(URI, new String[]{"project_id", "config_content"}, null, null, null)) {
            if (cursor == null) throw failure(503, "AppBootPolicy query returned null cursor");
            if (!cursor.moveToFirst()) throw failure(422, "AppBootPolicy row is absent; insert/delete are not supported");
            String id = cursor.getString(cursor.getColumnIndexOrThrow("project_id"));
            String raw = cursor.getString(cursor.getColumnIndexOrThrow("config_content"));
            if (!AppBootPolicy.FEATURE.equals(id) || cursor.moveToNext()) throw failure(409, "Unexpected AppBootPolicy row key or cardinality");
            if (raw == null) throw failure(422, "Existing AppBootPolicy raw content is NULL");
            return new State(raw);
        }
    }

    private JSONObject inspect() throws Exception {
        JSONObject out = new JSONObject(); JSONArray errors = new JSONArray();
        out.put("caller_uid", android.os.Process.myUid());
        if (android.os.Process.myUid() / 100000 != 0) errors.put("Caller must be Android user 0");
        JSONObject test = new JSONObject(); out.put("tcl_test", test);
        test.put("source", "Settings.Global.getString_normal_application_context");
        try {
            String raw = Settings.Global.getString(context.getContentResolver(), "tcl_test");
            test.put("raw", raw == null ? JSONObject.NULL : raw);
            test.put("readable", true);
            test.put("absent_default_effective_zero", raw == null);
            test.put("effective_value", AppBootPolicy.effectiveTestValue(raw));
            test.put("is_zero", true);
        } catch (Exception unavailable) {
            if (!test.has("readable")) test.put("readable", false);
            test.put("is_zero", false); test.put("error", describe(unavailable));
            errors.put("tcl_test unavailable, nonzero or not an integer");
        }
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pkg = pm.getPackageInfo(AppBootPolicy.PACKAGE, PackageManager.GET_ACTIVITIES | MATCH);
            ApplicationInfo app = pkg.applicationInfo;
            if (app == null || !AppBootPolicy.PACKAGE.equals(pkg.packageName)
                    || !AppBootPolicy.PACKAGE.equals(app.packageName) || app.uid / 100000 != 0
                    || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0 || app.sourceDir == null || !app.enabled)
                throw new IllegalArgumentException("Cyberui package is not a visible installed enabled user-0 package");
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo home = resolved == null ? null : resolved.activityInfo;
            boolean cyberuiIsDefaultHome = home != null && AppBootPolicy.PACKAGE.equals(home.packageName)
                    && AppBootPolicy.HOME.equals(home.name) && home.targetActivity == null
                    && home.applicationInfo != null && home.applicationInfo.uid == app.uid;
            // A replaced default desktop is an expected end state: the fixed-component list is written
            // precisely so the vendor desktop cannot take Home back. What must still hold is that cyberui
            // is an installed, enabled HOME candidate, otherwise the write protects nothing.
            JSONArray homes = new JSONArray();
            boolean cyberuiIsHomeCandidate = false;
            for (ResolveInfo ri : pm.queryIntentActivities(intent, MATCH)) if (ri.activityInfo != null) {
                ActivityInfo ai = ri.activityInfo;
                homes.put(new ComponentName(ai.packageName, ai.name).flattenToString());
                if (AppBootPolicy.PACKAGE.equals(ai.packageName) && AppBootPolicy.HOME.equals(ai.name)) {
                    if (ai.targetActivity != null || ai.applicationInfo == null || ai.applicationInfo.uid != app.uid)
                        throw new IllegalArgumentException("Cyberui HOME candidate has an unexpected identity");
                    cyberuiIsHomeCandidate = true;
                }
                if (AppBootPolicy.PACKAGE.equals(ai.packageName)
                        && (AppBootPolicy.TARGETS.contains(ai.name) || AppBootPolicy.TARGETS.contains(ai.targetActivity)))
                    throw new IllegalArgumentException("A fixed target is a declared HOME or HOME alias target");
            }
            if (!cyberuiIsDefaultHome && !cyberuiIsHomeCandidate)
                throw new IllegalArgumentException("Cyberui is neither the resolved HOME nor a HOME candidate");
            JSONObject identity = new JSONObject();
            identity.put("package", pkg.packageName); identity.put("uid", app.uid); identity.put("user_id", 0);
            identity.put("version_code", pkg.getLongVersionCode()); identity.put("source_dir", app.sourceDir);
            // Record the HOME candidate set (stable across default-desktop changes), not the single
            // resolved default: a restore must not be refused just because the owner switched Home.
            identity.put("home_candidates", homes);
            JSONArray identities = new JSONArray(), states = new JSONArray();
            for (String name : AppBootPolicy.TARGETS) {
                ActivityInfo ai = find(pkg.activities, name);
                if (ai == null || !AppBootPolicy.PACKAGE.equals(ai.packageName) || ai.applicationInfo == null
                        || ai.applicationInfo.uid != app.uid || !app.sourceDir.equals(ai.applicationInfo.sourceDir))
                    throw new IllegalArgumentException("Fixed activity declaration or identity does not match: " + name);
                // 桌面切换那两个必须导出（跨进程可拉起）；其余 22 个是 cyberui 内部跳转，
                // exported=false 是正常的，拦的是本进程 startActivity。
                if ((AppBootPolicy.ACTIVITY.equals(name) || AppBootPolicy.ALIAS.equals(name)) && !ai.exported)
                    throw new IllegalArgumentException("Desktop-switch target must be exported: " + name);
                String target = AppBootPolicy.ALIAS.equals(name) ? AppBootPolicy.ACTIVITY : null;
                if (target == null ? ai.targetActivity != null : !target.equals(ai.targetActivity))
                    throw new IllegalArgumentException("Unexpected alias identity: " + name);
                JSONObject component = new JSONObject(); component.put("name", name);
                component.put("target_activity", target == null ? JSONObject.NULL : target);
                identities.put(component);
                JSONObject state = new JSONObject(component.toString());
                state.put("enabled_setting", pm.getComponentEnabledSetting(new ComponentName(AppBootPolicy.PACKAGE, name)));
                state.put("manifest_enabled", ai.enabled); state.put("exported", ai.exported); states.put(state);
            }
            identity.put("activities", identities); out.put("target_identity", identity);
            out.put("activity_states", states); out.put("home_candidates", homes);
        } catch (Exception unavailable) { errors.put(describe(unavailable)); }
        out.put("errors", errors); out.put("valid", errors.length() == 0);
        return out;
    }

    private void requirePreflight(JSONObject preflight) throws Exception {
        if (preflight.getBoolean("valid")) return;
        JSONObject result = common(); result.put("written", false); result.put("error", "preflight_refused_no_write");
        result.put("preflight", preflight); throw new GuardControl.ApiFailure(422, result);
    }
    private String backup(State before, String proposed, JSONObject identity, String operation, String restoreId) throws Exception {
        File directory = directory();
        if (!directory.isDirectory() && !directory.mkdirs()) throw failure(500, "Cannot create internal AppBootPolicy backup directory");
        String id = "appboot-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
        JSONObject data = common(); data.put("schema", 1); data.put("id", id); data.put("time_ms", System.currentTimeMillis());
        data.put("user_id", 0); data.put("original_exists", true); data.put("original_sha256", before.sha);
        data.put("config_content", before.raw); data.put("proposed_config_content", proposed);
        data.put("proposed_sha256", AppBootPolicy.sha(proposed)); data.put("target_identity", identity);
        data.put("operation", operation); data.put("phase", "before_provider_write");
        data.put("requires_full_restart", restoreId != null);
        if (restoreId != null) data.put("restore_backup_id", restoreId);
        String raw = data.toString(); File target = new File(directory, id), temporary = new File(directory, id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(raw.getBytes(StandardCharsets.UTF_8)); out.flush(); out.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw failure(500, "Cannot finalize backup; no provider write performed");
        syncDirectory(directory);
        syncDirectory(context.getFilesDir());
        if (!raw.equals(readFile(target))) throw failure(500, "Internal backup full readback mismatch; no write performed");
        return id;
    }

    private Map<String, Object> loadBackup(String id) throws Exception {
        AppBootPolicy.requireBackupId(id);
        File file = new File(directory(), id);
        if (!file.isFile()) throw failure(404, "AppBootPolicy backup not found");
        return AppBootPolicy.object(StrictJson.parse(readFile(file), 1048576), "Internal AppBootPolicy backup");
    }
    synchronized JSONObject backups(String id) throws Exception {
        if (id != null) {
            try { loadBackup(id); } catch (IllegalArgumentException invalid) { throw failure(400, invalid.getMessage()); }
            return new JSONObject(readFile(new File(directory(), id)));
        }
        JSONObject result = common(); JSONArray list = new JSONArray(); File[] files = directory().listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) if (file.isFile() && file.getName().matches("appboot-[0-9]+-[0-9a-f]{8}\\.json")) {
                JSONObject entry = new JSONObject(); entry.put("id", file.getName()); entry.put("bytes", file.length());
                entry.put("time_ms", file.lastModified()); entry.put("content_path", "/app-boot-policy/backups/" + file.getName()); list.put(entry);
            }
        }
        result.put("backups", list); return result;
    }
    private File directory() { return new File(context.getFilesDir(), "appboot-backups"); }
    private static void syncDirectory(File directory) throws Exception {
        FileDescriptor fd = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
        try { Os.fsync(fd); } finally { Os.close(fd); }
    }
    private static String readFile(File file) throws Exception {
        if (file.length() > 1048576) throw new IllegalArgumentException("AppBootPolicy backup is too large");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] block = new byte[8192]; int count;
            while ((count = in.read(block)) != -1) {
                if (bytes.size() + count > 1048576) throw new IllegalArgumentException("AppBootPolicy backup is too large");
                bytes.write(block, 0, count);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
    private static Map<String, Object> asMap(JSONObject json) { return AppBootPolicy.object(StrictJson.parse(json.toString()), "Identity"); }
    private static ActivityInfo find(ActivityInfo[] activities, String name) {
        ActivityInfo found = null;
        if (activities != null) for (ActivityInfo ai : activities) if (name.equals(ai.name)) {
            if (found != null) throw new IllegalArgumentException("Duplicate activity declaration: " + name);
            found = ai;
        }
        return found;
    }
    private static JSONObject common() throws Exception {
        JSONObject out = new JSONObject(); out.put("feature", AppBootPolicy.FEATURE);
        out.put("runtime_effect_verified", false); out.put("triggered", false);
        out.put("package_manager_states_modified", false); out.put("database_restore_clears_runtime_list", false);
        out.put("restore_requires_full_restart", true); return out;
    }
    private static GuardControl.ApiFailure failure(int code, String message) throws Exception {
        JSONObject out = common(); out.put("error", message); out.put("written", false);
        return new GuardControl.ApiFailure(code, out);
    }
    private static GuardControl.ApiFailure stale(State state) throws Exception {
        JSONObject out = common(); out.put("error", "expected_sha256_mismatch_no_write");
        out.put("current_sha256", state.sha); out.put("written", false); return new GuardControl.ApiFailure(409, out);
    }
    private static String describe(Throwable failure) { return failure.getClass().getName() + ": " + failure.getMessage(); }
    private static final class State {
        final String raw, sha;
        State(String raw) throws Exception { this.raw = raw; sha = AppBootPolicy.sha(raw); }
    }
}
