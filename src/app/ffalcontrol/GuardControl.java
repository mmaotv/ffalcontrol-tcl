package app.ffalcontrol;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exactly one configuration row can be changed; no package mutation or trigger API exists here. */
final class GuardControl {
    private static final String FEATURE = "Guard_disable_App";
    private static final Uri URI = Uri.parse("content://com.tcl.providers.config/" + FEATURE);
    private static final int MATCH = PackageManager.MATCH_UNINSTALLED_PACKAGES
            | PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
    private final Context context;

    static final class ApiFailure extends Exception {
        final int status;
        final JSONObject result;
        ApiFailure(int status, String message) throws Exception {
            this.status = status;
            result = new JSONObject(); result.put("error", message);
        }
        ApiFailure(int status, JSONObject result) { this.status = status; this.result = result; }
    }

    private static final class State {
        final boolean exists;
        final String raw;
        final String sha;
        State(boolean exists, String raw) throws Exception {
            this.exists = exists; this.raw = raw; sha = exists ? sha256(raw) : "ABSENT";
        }
    }

    GuardControl(Context context) { this.context = context.getApplicationContext(); }

    synchronized JSONObject apply(String body) throws Exception {
        Map<String, Object> request;
        try { request = GuardPolicy.object(StrictJson.parse(body), "Request"); }
        catch (IllegalArgumentException invalid) { throw new ApiFailure(400, invalid.getMessage()); }
        for (String field : request.keySet())
            if (!field.equals("expected_sha256") && !field.equals("config_content")
                    && !field.equals("dry_run") && !field.equals("restore_backup_id"))
                throw new ApiFailure(400, "Unknown request field: " + field);
        if (!(request.get("expected_sha256") instanceof String)
                || !(request.get("config_content") instanceof String))
            throw new ApiFailure(400, "expected_sha256 and config_content must be strings");
        if (request.containsKey("dry_run") && !(request.get("dry_run") instanceof Boolean))
            throw new ApiFailure(400, "dry_run must be a JSON boolean when present");
        if (request.containsKey("restore_backup_id") && !(request.get("restore_backup_id") instanceof String))
            throw new ApiFailure(400, "restore_backup_id must be a string when present");
        boolean write = Boolean.FALSE.equals(request.get("dry_run"));
        String expected = (String) request.get("expected_sha256");
        if (!expected.equals("ABSENT") && !expected.matches("[0-9a-f]{64}"))
            throw new ApiFailure(400, "expected_sha256 must be lowercase SHA256 or ABSENT");
        String proposed = (String) request.get("config_content");
        State before = state();
        if (!before.sha.equals(expected)) throw stale(before);
        List<GuardPolicy.Change> changes;
        try { changes = GuardPolicy.changes(before.raw, proposed); }
        catch (IllegalArgumentException invalid) { throw new ApiFailure(422, invalid.getMessage()); }
        String restoreId = (String) request.get("restore_backup_id");
        Map<String, Map<String, Object>> restoreStates = null;
        if (restoreId != null) {
            Map<String, Object> saved = restoreBackup(restoreId);
            try { restoreStates = GuardPolicy.restoreEvidence(saved, restoreId, before.sha, changes); }
            catch (IllegalArgumentException invalid) { throw new ApiFailure(422, invalid.getMessage()); }
        }
        JSONArray states = inspectChanges(changes, restoreId, restoreStates);
        JSONObject result = new JSONObject();
        result.put("feature", FEATURE); result.put("dry_run", !write);
        result.put("current_exists", before.exists); result.put("current_sha256", before.sha);
        result.put("proposed_sha256", sha256(proposed)); result.put("changes", states);
        result.put("change_count", changes.size());
        result.put("policy_removed_does_not_restore_state", true);
        result.put("guard_execution_verified", false); result.put("triggered", false);
        if (restoreId != null) result.put("restore_backup_id", restoreId);
        if (!write) { result.put("status", "plan_only"); return result; }
        if (before.exists && proposed.equals(before.raw)) {
            result.put("status", "identical_content_no_write"); result.put("written", false); return result;
        }
        String backupId = backup(before, proposed, states);
        result.put("backup_id", backupId);
        // The OEM provider ignores update selection: check again immediately before its write.
        // This is not an atomic CAS against another process; readback is mandatory below.
        State rechecked = state();
        if (!rechecked.sha.equals(before.sha)) {
            result.put("error", "configuration_changed_after_backup_no_write");
            result.put("observed_sha256", rechecked.sha); result.put("written", false);
            throw new ApiFailure(409, result);
        }
        Integer updateCount = null;
        try {
            ContentValues values = new ContentValues();
            values.put("config_content", proposed);
            if (before.exists) {
                updateCount = context.getContentResolver().update(URI, values, null, null);
                result.put("operation", "update"); result.put("update_count", updateCount);
            } else {
                values.put("project_id", FEATURE);
                context.getContentResolver().insert(URI, values);
                result.put("operation", "insert"); result.put("update_count", JSONObject.NULL);
            }
            State after = state();
            result.put("observed_sha256", after.sha);
            boolean exact = after.exists && proposed.equals(after.raw) && sha256(proposed).equals(after.sha);
            boolean counted = !before.exists || (updateCount != null && updateCount.intValue() == 1);
            if (!exact || !counted) {
                result.put("error", "write_or_readback_not_verified");
                result.put("written", "unknown"); result.put("persistence_verified", false);
                throw new ApiFailure(500, result);
            }
            result.put("status", "configuration_written_and_readback_verified");
            result.put("written", true); result.put("persistence_verified", true);
            result.put("resulting_row_count", 1);
            return result;
        } catch (ApiFailure known) { throw known; }
        catch (Throwable failure) {
            result.put("error", "write_outcome_unknown: " + failure.getClass().getName() + ": " + failure.getMessage());
            result.put("written", "unknown"); result.put("persistence_verified", false);
            throw new ApiFailure(500, result);
        }
    }

    private State state() throws Exception {
        try (Cursor cursor = context.getContentResolver().query(URI,
                new String[]{"project_id", "config_content"}, null, null, null)) {
            if (cursor == null) throw new ApiFailure(503, "Guard query returned null cursor");
            if (!cursor.moveToFirst()) return new State(false, null);
            String id = cursor.getString(cursor.getColumnIndexOrThrow("project_id"));
            String raw = cursor.getString(cursor.getColumnIndexOrThrow("config_content"));
            if (!FEATURE.equals(id) || cursor.moveToNext()) throw new ApiFailure(409, "Unexpected Guard query cardinality or key");
            if (raw == null) throw new ApiFailure(422, "Existing Guard config_content is NULL; refusing write");
            return new State(true, raw);
        }
    }

    private ApiFailure stale(State state) throws Exception {
        JSONObject result = new JSONObject(); result.put("error", "expected_sha256_mismatch_no_write");
        result.put("current_exists", state.exists); result.put("current_sha256", state.sha);
        result.put("written", false); return new ApiFailure(409, result);
    }

    private JSONArray inspectChanges(List<GuardPolicy.Change> changes, String restoreId,
            Map<String, Map<String, Object>> restoreStates) throws Exception {
        if (android.os.Process.myUid() / 100000 != 0)
            throw new ApiFailure(403, "Guard changes are limited to Android user 0");
        PackageManager pm = context.getPackageManager();
        Set<String> home = new HashSet<String>();
        for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), MATCH)) {
            if (info.activityInfo != null && "com.tcl.cyberui".equals(info.activityInfo.packageName)) {
                home.add(info.activityInfo.name);
                if (info.activityInfo.targetActivity != null) home.add(info.activityInfo.targetActivity);
            }
        }
        JSONArray states = new JSONArray();
        for (GuardPolicy.Change change : changes) {
            PackageInfo info;
            try { info = pm.getPackageInfo(change.packageName, MATCH | PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS); }
            catch (Throwable failure) { throw new ApiFailure(422, "Changed target is unavailable: " + change.packageName); }
            if (info.applicationInfo == null || (info.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0)
                throw new ApiFailure(422, "Changed target is not installed for user 0: " + change.packageName);
            ApplicationInfo app = info.applicationInfo;
            JSONObject state = new JSONObject();
            state.put("packageName", change.packageName); state.put("componentName", change.componentName);
            state.put("policy_action", change.removed ? "remove" : change.oldDisable == null ? "add" : "change");
            state.put("desired_disable", change.removed ? JSONObject.NULL : change.disable);
            if (change.removed) state.put("policy_removed_does_not_restore_state", true);
            state.put("previous_config_disable", change.oldDisable == null ? JSONObject.NULL : change.oldDisable);
            state.put("uid", app.uid); state.put("user_id", app.uid / 100000);
            state.put("shared_user_id", info.sharedUserId == null ? JSONObject.NULL : info.sharedUserId);
            state.put("version_code", info.getLongVersionCode());
            state.put("flags", app.flags); state.put("source_dir", app.sourceDir);
            if (app.uid / 100000 != 0) throw new ApiFailure(403, "Target is not in user 0");
            if (change.componentName.length() == 0) {
                try { GuardPolicy.requireOrdinaryUid(change.packageName, app.uid); }
                catch (IllegalArgumentException invalid) { throw new ApiFailure(403, "Whole-package changes require ordinary app UID; refused " + change.packageName + " uid=" + app.uid); }
                boolean visible = inspectOrdinaryVisibility(pm, info, state);
                if (restoreStates != null) {
                    try { GuardPolicy.requireSameRestoreTarget(restoreStates.get(change.packageName),
                            app.uid, info.getLongVersionCode(), app.sourceDir); }
                    catch (IllegalArgumentException invalid) { throw new ApiFailure(422, invalid.getMessage()); }
                    state.put("restore_backup_id", restoreId);
                    state.put("restore_hidden_target", false);
                    state.put("restoration_basis", "same_target_saved_visible_baseline_and_current_config_sha");
                } else if (!visible) {
                    throw new ApiFailure(422, "Ordinary PM visibility is unknown; cannot infer hidden state for "
                            + change.packageName + "; a matching restore_backup_id is required for reversal");
                }
                try { state.put("application_enabled_setting", pm.getApplicationEnabledSetting(change.packageName)); }
                catch (IllegalArgumentException unavailable) {
                    if (visible) throw new ApiFailure(422, "Visible target enabled state became unavailable");
                    state.put("application_enabled_setting", JSONObject.NULL);
                }
            } else {
                String component = change.componentName;
                if (GuardPolicy.protectedName(component) || home.contains(component))
                    throw new ApiFailure(403, "Protected Home/Main/settings component: " + component);
                ComponentInfo declared = find(info.activities, component);
                if (declared == null) declared = find(info.services, component);
                if (declared == null) declared = find(info.receivers, component);
                if (declared == null) throw new ApiFailure(422, "Changed cyberui component is not declared: " + component);
                if (declared instanceof ActivityInfo) {
                    String aliasTarget = ((ActivityInfo) declared).targetActivity;
                    if (aliasTarget != null && (home.contains(aliasTarget) || GuardPolicy.protectedName(aliasTarget)))
                        throw new ApiFailure(403, "Alias points at a protected component: " + component);
                }
                int enabled = pm.getComponentEnabledSetting(new ComponentName(change.packageName, component));
                try { GuardPolicy.requireRestorableComponentState(enabled); }
                catch (IllegalArgumentException invalid) { throw new ApiFailure(422, "Cannot exactly restore component state " + enabled + " for " + component); }
                state.put("enabled_setting_before", enabled);
                state.put("manifest_enabled", declared.enabled);
                state.put("desired_enabled_setting", change.removed ? JSONObject.NULL : change.disable ? 2 : 0);
            }
            states.put(state);
        }
        return states;
    }

    private boolean inspectOrdinaryVisibility(PackageManager pm, PackageInfo expected,
            JSONObject state) throws Exception {
        PackageInfo ordinaryPackage = null;
        ApplicationInfo ordinaryApp = null;
        // Exactly flags=0: no MATCH_UNINSTALLED_PACKAGES, MATCH_ANY_USER,
        // MATCH_KNOWN_PACKAGES or MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS relaxation.
        try { ordinaryPackage = pm.getPackageInfo(expected.packageName, 0); }
        catch (PackageManager.NameNotFoundException notVisible) { /* Not a proof of hidden=true. */ }
        try { ordinaryApp = pm.getApplicationInfo(expected.packageName, 0); }
        catch (PackageManager.NameNotFoundException notVisible) { /* Not a proof of hidden=true. */ }
        state.put("ordinary_package_lookup", ordinaryPackage == null ? "not_found" : "present");
        state.put("ordinary_application_lookup", ordinaryApp == null ? "not_found" : "present");
        if ((ordinaryPackage == null) != (ordinaryApp == null))
            throw new ApiFailure(422, "Ordinary PM lookups disagree; retry a fresh snapshot");
        boolean visible = ordinaryPackage != null;
        if (visible) {
            if (!expected.packageName.equals(ordinaryPackage.packageName)
                    || expected.getLongVersionCode() != ordinaryPackage.getLongVersionCode()
                    || !sameInstalledApp(expected.applicationInfo, ordinaryPackage.applicationInfo)
                    || !sameInstalledApp(expected.applicationInfo, ordinaryApp))
                throw new ApiFailure(422, "Ordinary PM target identity or installed flag does not match");
        }
        state.put("hidden_before", visible ? Boolean.FALSE : JSONObject.NULL);
        state.put("hidden_before_known", visible);
        state.put("hidden_state_source", visible ? GuardPolicy.VISIBLE_STATE_SOURCE
                : "ordinary_pm_not_found_does_not_prove_hidden");
        return visible;
    }

    private static boolean sameInstalledApp(ApplicationInfo expected, ApplicationInfo actual) {
        return actual != null && expected.uid == actual.uid
                && expected.packageName.equals(actual.packageName)
                && expected.sourceDir != null && expected.sourceDir.equals(actual.sourceDir)
                && (actual.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
    }

    private static ComponentInfo find(ComponentInfo[] list, String name) {
        if (list != null) for (ComponentInfo info : list) if (name.equals(info.name)) return info;
        return null;
    }

    private String backup(State before, String proposed, JSONArray states) throws Exception {
        File directory = new File(context.getFilesDir(), "guard-backups");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new ApiFailure(500, "Cannot create internal backup directory");
        String id = "guard-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
        File target = new File(directory, id);
        File temporary = new File(directory, id + ".tmp");
        JSONObject backup = new JSONObject();
        backup.put("schema", 2); backup.put("id", id); backup.put("time_ms", System.currentTimeMillis());
        backup.put("feature", FEATURE); backup.put("user_id", 0);
        backup.put("original_exists", before.exists); backup.put("original_sha256", before.sha);
        backup.put("config_content", before.exists ? before.raw : JSONObject.NULL);
        backup.put("proposed_sha256", sha256(proposed)); backup.put("target_states_before", states);
        backup.put("phase", "before_provider_write"); backup.put("guard_execution_verified", false);
        byte[] bytes = backup.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new ApiFailure(500, "Cannot finalize backup; no provider write performed");
        if (!sha256(readFile(target)).equals(sha256(new String(bytes, StandardCharsets.UTF_8))))
            throw new ApiFailure(500, "Backup readback mismatch; no provider write performed");
        return id;
    }

    private Map<String, Object> restoreBackup(String id) throws Exception {
        if (!id.matches("guard-[0-9]+-[0-9a-f]{8}\\.json")) throw new ApiFailure(400, "Invalid restore backup id");
        File file = new File(new File(context.getFilesDir(), "guard-backups"), id);
        if (!file.isFile()) throw new ApiFailure(404, "Restore backup not found");
        try {
            Map<String, Object> saved = GuardPolicy.object(StrictJson.parse(readFile(file), 1048576), "Restore backup");
            boolean exists = Boolean.TRUE.equals(saved.get("original_exists"));
            Object raw = saved.get("config_content");
            if (exists ? !(raw instanceof String) || !sha256((String) raw).equals(saved.get("original_sha256"))
                    : !Boolean.FALSE.equals(saved.get("original_exists")) || raw != null || !"ABSENT".equals(saved.get("original_sha256")))
                throw new IllegalArgumentException("Restore backup original content SHA is invalid");
            return saved;
        } catch (IllegalArgumentException invalid) { throw new ApiFailure(422, invalid.getMessage()); }
    }

    JSONObject backups(String id) throws Exception {
        File directory = new File(context.getFilesDir(), "guard-backups");
        if (id != null) {
            if (!id.matches("guard-[0-9]+-[0-9a-f]{8}\\.json")) throw new ApiFailure(400, "Invalid backup id");
            File file = new File(directory, id);
            if (!file.isFile()) throw new ApiFailure(404, "Backup not found");
            return new JSONObject(readFile(file));
        }
        JSONObject result = new JSONObject(); JSONArray backups = new JSONArray();
        File[] files = directory.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) if (file.getName().matches("guard-[0-9]+-[0-9a-f]{8}\\.json")) {
                JSONObject info = new JSONObject(); info.put("id", file.getName());
                info.put("bytes", file.length()); info.put("time_ms", file.lastModified());
                info.put("content_path", "/backups/" + file.getName()); backups.put(info);
            }
        }
        result.put("backups", backups); return result;
    }

    private static String readFile(File file) throws Exception {
        if (file.length() > 1048576) throw new IllegalArgumentException("Backup exceeds response limit");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] block = new byte[8192]; int count;
            while ((count = in.read(block)) != -1) {
                if (bytes.size() + count > 1048576) throw new IllegalArgumentException("Backup exceeds response limit");
                bytes.write(block, 0, count);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    static String sha256(String raw) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
