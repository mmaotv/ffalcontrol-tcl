package app.ffalcontrol;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure comparison policy. No Android APIs or I/O; unchanged legacy entries remain untouched. */
final class GuardPolicy {
    static final String VISIBLE_STATE_SOURCE = "ordinary_pm_flags_0_package_and_application_installed";
    /**
     * v5 扩展：在 v4 的 10 个基础上加入本轮 cleanyourtv 同代清单核实过的普通 UID 包。
     * 红线包（cyberui / tv / settings / systemserver / ttvs / TFDS.service / guard /
     * providers.config / initsetup / sogou / ui_mediaCenter / autopair /
     * tcl_bt_rcu_service / tvos）永远不在此表内。
     * 系统 UID（< 10000）的包即使在此表内，仍会被 requireOrdinaryUid 拒绝写入，
     * 例如 com.tcl.versionUpdateApp、com.tcl.UpdatePeripheral、com.tcl.walleve、
     * com.tcl.micmanager —— 保留条目只为记录意图，不代表可写。
     */
    static final Set<String> WHOLE_PACKAGES = new HashSet<String>(Arrays.asList(
            // v4 基线
            "com.tcl.ffeducation", "com.tcl.appmarket2", "com.tcl.wholenetsearch",
            "com.tcl.vod", "com.tcl.tliveplay", "com.tcl.ffkaraoke", "com.tcl.usercenter",
            "com.tcl.bootadservice", "com.tcl.playskill", "com.tcl.walleve",
            // v5 新增：广告与遥测
            "com.tcl.profilemanager", "com.tcl.logkit", "com.tcl.c2dm.client",
            "com.tcl.atomic.servicecenter", "com.tcl.xian.StartandroidService",
            "com.tcl.repairguide", "com.tcl.useragreement", "com.tcl.appreciate.art",
            // v5 新增：内容与小组件入口
            "com.tcl.common.weather", "com.tcl.gamebar", "com.tcl.t_solo",
            "com.tcl.tvsmartalbum", "com.tcl.hotelmenu", "com.tcl.hearaid",
            "com.audiocn.karaoke.micmanager",
            // v5 新增：OTA（系统 UID，实际写入会被 requireOrdinaryUid 拒绝，仅留档）
            "com.tcl.versionUpdateApp", "com.tcl.UpdatePeripheral", "com.snm.upgrade"));

    /**
     * uid 1000（android.uid.system）里允许整包隐藏的例外。
     * guard 服务端不做 UID 校验，只调 setApplicationHiddenSettingAsUser；
     * 这里的限制纯粹是本工具自己加的保守策略。语音助手 walleve 必须放开，
     * 否则遥控器语音键会一直把内容推给 cyberui。
     */
    static final Set<String> SYSTEM_UID_ALLOWED = new HashSet<String>(Arrays.asList(
            "com.tcl.walleve"));

    static final class Change {
        final String packageName;
        final String componentName;
        final boolean disable;
        final Boolean oldDisable;
        final boolean removed;
        Change(String p, String c, boolean d, Boolean old, boolean removed) {
            packageName = p; componentName = c; disable = d; oldDisable = old; this.removed = removed;
        }
    }

    static List<Change> changes(String before, String proposed) {
        Map<String, Object> newTop = top(proposed);
        Map<String, Object> oldTop = before == null ? emptyTop() : top(before);
        Map<String, Object> oldOther = new LinkedHashMap<String, Object>(oldTop);
        Map<String, Object> newOther = new LinkedHashMap<String, Object>(newTop);
        oldOther.remove("disableAppInfoList"); newOther.remove("disableAppInfoList");
        if (!oldOther.equals(newOther)) fail("Other top-level fields must remain semantically unchanged");
        LinkedHashMap<String, Map<String, Object>> oldEntries = entries(oldTop);
        LinkedHashMap<String, Map<String, Object>> newEntries = entries(newTop);
        List<String> retainedOrder = new ArrayList<String>();
        for (String key : newEntries.keySet()) if (oldEntries.containsKey(key)) retainedOrder.add(key);
        List<String> originalRetainedOrder = new ArrayList<String>();
        for (String key : oldEntries.keySet()) if (newEntries.containsKey(key)) originalRetainedOrder.add(key);
        if (!retainedOrder.equals(originalRetainedOrder)) fail("Retained existing entries cannot be reordered");
        List<Change> result = new ArrayList<Change>();
        for (Map.Entry<String, Map<String, Object>> pair : newEntries.entrySet()) {
            Map<String, Object> next = pair.getValue();
            Map<String, Object> old = oldEntries.get(pair.getKey());
            if (old != null && old.equals(next)) continue;
            String p = (String) next.get("packageName");
            String c = component(next);
            allowedKind(p, c);
            if (old == null) {
                for (String field : next.keySet())
                    if (!field.equals("packageName") && !field.equals("componentName") && !field.equals("disable"))
                        fail("Unknown field in new entry: " + field);
                if (next.containsKey("componentName") && !(next.get("componentName") instanceof String))
                    fail("New componentName must be a string");
            } else {
                Map<String, Object> oldRest = new LinkedHashMap<String, Object>(old);
                Map<String, Object> nextRest = new LinkedHashMap<String, Object>(next);
                oldRest.remove("disable"); nextRest.remove("disable");
                if (!oldRest.equals(nextRest)) fail("Only disable may change in an existing entry: " + pair.getKey());
            }
            result.add(new Change(p, c, ((Boolean) next.get("disable")).booleanValue(),
                    old == null ? null : (Boolean) old.get("disable"), false));
        }
        for (Map.Entry<String, Map<String, Object>> pair : oldEntries.entrySet()) {
            if (newEntries.containsKey(pair.getKey())) continue;
            Map<String, Object> old = pair.getValue();
            String p = (String) old.get("packageName");
            String c = component(old);
            allowedKind(p, c);
            result.add(new Change(p, c, false, (Boolean) old.get("disable"), true));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String purpose) {
        if (!(value instanceof Map)) fail(purpose + " must be an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> top(String raw) {
        Object parsed = StrictJson.parse(raw);
        if (!(parsed instanceof List) || ((List<?>) parsed).size() != 1)
            fail("Guard config must be an array containing exactly one object");
        Map<String, Object> top = object(((List<?>) parsed).get(0), "Guard config entry");
        if (!(top.get("disableAppInfoList") instanceof List)) fail("disableAppInfoList must be an array");
        return top;
    }

    private static Map<String, Object> emptyTop() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("disableAppInfoList", new ArrayList<Object>());
        return result;
    }

    private static LinkedHashMap<String, Map<String, Object>> entries(Map<String, Object> top) {
        List<?> list = (List<?>) top.get("disableAppInfoList");
        if (list.size() > 512) fail("Too many Guard entries");
        LinkedHashMap<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Object item : list) {
            Map<String, Object> entry = object(item, "disableAppInfoList entry");
            Object pkg = entry.get("packageName");
            if (!(pkg instanceof String) || !((String) pkg).matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
                fail("packageName must be a valid full package name");
            if (!(entry.get("disable") instanceof Boolean)) fail("disable must be a JSON boolean");
            String component = component(entry);
            if (component.length() > 0 && !component.matches("[A-Za-z_$][A-Za-z0-9_$.]*"))
                fail("Invalid componentName");
            String key = pkg + "/" + component;
            if (result.put(key, entry) != null) fail("Duplicate package/component key: " + key);
        }
        return result;
    }

    private static String component(Map<String, Object> entry) {
        Object component = entry.get("componentName");
        if (component == null) return "";
        if (!(component instanceof String)) fail("componentName must be string or legacy null");
        return (String) component;
    }
    private static void allowedKind(String p, String c) {
        if (c.length() == 0) {
            if (!WHOLE_PACKAGES.contains(p)) fail("Whole-package target is outside the fixed allowlist: " + p);
        } else if (!p.equals("com.tcl.cyberui")) fail("Component changes are only allowed in com.tcl.cyberui");
    }
    static void requireOrdinaryUid(String packageName, int uid) {
        if (uid >= 10000 && uid < 100000) return;
        if (uid == 1000 && SYSTEM_UID_ALLOWED.contains(packageName)) return;
        fail("Whole-package changes require ordinary app UID in user 0 (or a reviewed uid-1000 exception)");
    }
    static void requireRestorableComponentState(int state) {
        if (state != 0 && state != 2) fail("Component state cannot be exactly restored by Guard: " + state);
    }

    /** A saved visible baseline authorizes only its own immediate true-to-false reversals. */
    static Map<String, Map<String, Object>> restoreEvidence(Map<String, Object> backup,
            String id, String currentSha, List<Change> changes) {
        if (!numberEquals(backup.get("schema"), 2) || !id.equals(backup.get("id"))
                || !"Guard_disable_App".equals(backup.get("feature"))
                || !numberEquals(backup.get("user_id"), 0)
                || !currentSha.equals(backup.get("proposed_sha256")))
            fail("Restore backup identity or current configuration SHA does not match");
        if (!(backup.get("target_states_before") instanceof List))
            fail("Restore backup has no target states");
        if (changes.isEmpty()) fail("Restore request must contain a true-to-false change");
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Change change : changes) {
            if (change.removed || change.disable || !Boolean.TRUE.equals(change.oldDisable)
                    || change.componentName.length() != 0)
                fail("Restore backup permits only whole-package true-to-false reversals");
            Map<String, Object> matched = null;
            for (Object item : (List<?>) backup.get("target_states_before")) {
                Map<String, Object> state = object(item, "Saved target state");
                if (!change.packageName.equals(state.get("packageName"))
                        || !"".equals(state.get("componentName"))) continue;
                if (matched != null) fail("Duplicate saved target state");
                matched = state;
            }
            if (matched == null || !Boolean.FALSE.equals(matched.get("hidden_before"))
                    || !Boolean.TRUE.equals(matched.get("hidden_before_known"))
                    || !Boolean.TRUE.equals(matched.get("desired_disable"))
                    || !VISIBLE_STATE_SOURCE.equals(matched.get("hidden_state_source")))
                fail("Restore target lacks this tool's proven visible baseline: " + change.packageName);
            result.put(change.packageName, matched);
        }
        return result;
    }

    static void requireSameRestoreTarget(Map<String, Object> saved, int uid,
            long version, String sourceDir) {
        if (!numberEquals(saved.get("uid"), uid) || !numberEquals(saved.get("user_id"), 0)
                || !numberEquals(saved.get("version_code"), version)
                || sourceDir == null || !sourceDir.equals(saved.get("source_dir")))
            fail("Restore target UID, user, version, or source path has changed");
    }

    private static boolean numberEquals(Object value, long expected) {
        return value instanceof Number && new BigDecimal(value.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
    }
    static boolean protectedName(String component) {
        return component.equals("MainActivity") || component.endsWith(".MainActivity")
                || component.endsWith(".DeskTopSettingsActivity")
                || component.endsWith(".MlChangeSysActivity")
                || component.endsWith(".DeskSwitchActivityAlias");
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
