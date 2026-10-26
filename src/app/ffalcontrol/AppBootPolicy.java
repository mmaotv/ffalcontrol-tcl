package app.ffalcontrol;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Fixed activity rule. Everything outside the one array must remain byte-for-byte intact. */
final class AppBootPolicy {
    static final String FEATURE = "AppBootPolicy";
    static final String PACKAGE = "com.tcl.cyberui";
    static final String HOME = "com.tcl.cyberui.MainActivity";
    static final String ACTIVITY = "com.tcl.mllauncher.mlchange.MlChangeSysActivity";
    static final String ALIAS = "com.tcl.cyberui.DeskSwitchActivityAlias";
    /**
     * AppBootPolicy 在 Activity 真正启动/复用前拦截，不受 guard 的 top-skip 影响
     * （guard 执行时前台若是 cyberui 会整条跳过，所以组件级禁用只能靠这条路）。
     * 前两个是桌面切换，其余是桌面内推广页面的真实落地 Activity。
     */
    static final List<String> TARGETS = Arrays.asList(
            ACTIVITY, ALIAS,
            // 卡片中心 / 卡片列表 / 应用排行 / 尺寸选择 / 场景跳转
            "com.tcl.mllauncher.mlscene.addwidget.cardcenter.CardCenterActivity",
            "com.tcl.mllauncher.mlscene.addwidget.widgetlist.WidgetListActivity",
            "com.tcl.mllauncher.mlscene.addwidget.widgetlist.WidgetListNoAnimActivity",
            "com.tcl.mllauncher.mlscene.addwidget.apprank.AppRankActivity",
            "com.tcl.mllauncher.mlscene.addwidget.sizelist.WidgetSizeListActivity",
            "com.tcl.mllauncher.mlscene.addwidget.sizelist.WidgetSizeListAppsActivity",
            "com.tcl.mllauncher.mlscene.addwidget.toTargetScene.ToTargetSceneActivity",
            // 专题 / 榜单 / 推荐全屏
            "com.tcl.cyberui.venus.subject.view.RecommendSubjectDetailActivity",
            "com.tcl.cyberui.venus.rankings.VenusRankingsActivity",
            "com.tcl.cyberui.displayblockview.block.playwithchoose.ui.full.PwcFullScreenActivity",
            // 专题页
            "com.tcl.subject.HotRankSubjectActivity",
            "com.tcl.subject.GuessYouLikeActivity",
            "com.tcl.subject.MoreCategorySubjectActivity",
            "com.tcl.subject.SubjectHighlightsActivity",
            "com.tcl.subject.WaterFallSubjectActivity",
            "com.tcl.subject.CyberSubjectActivity",
            "com.tcl.subject.HPNewActivity",
            "com.tcl.subject.LabelActivity",
            // 安装列表
            "com.tcl.cyberui.application.InstallListActivity",
            // 少儿 tab / 儿童模式切换 / 更新检查
            "com.tcl.childrentabchoosepop.ChildrenTabTopActivity",
            "com.tcl.cyberui.modechange.ChildChangeInterceptActivity",
            "com.tcl.cyberui.checkupdate.main.CheckUpdateActivity",
            "com.tcl.mlauncher.subject.recommend.DailyRecommendActivity",
            "com.tcl.mlauncher.subject.detail.SubjectDetailActivity",
            "com.tcl.mlauncher.subject.collection.SubjectCollectionActivity",
            "com.tcl.mlauncher.subject.toplist.TopListActivity",
            "com.tcl.cyberui.subject.BridgeActivity",
            "com.tcl.cyberui.subject.VerticalSubjectActivity",
            "com.tcl.cyberui.subject.HorizontalSubjectActivity",
            "com.tcl.cyberui.subject.WebSubjectActivity",
            "com.tcl.cyberui.subject.HotRankSubjectActivity",
            "com.tcl.cyberui.subject.MoreCategorySubjectActivity",
            "com.tcl.cyberui.subject.HPNewActivity",
            "com.tcl.cyberui.subject.SubjectHighlightsActivity",
            "com.tcl.cyberui.subject.GuessYouLikeActivity",
            "com.tcl.cyberui.subject.LabelActivity",
            "com.tcl.cyberui.subject.WaterFallSubjectActivity",
            "com.tcl.cyberui.subject.CyberSubjectActivity",
            "com.tcl.subject.HorizontalSubjectActivity",
            "com.tcl.subject.VerticalSubjectActivity",
            "com.tcl.subject.WebSubjectActivity",
            "com.tcl.mlauncher.mlchannel.MlChannelActivity");

    static final class Request {
        final String expected, proposed, restoreId;
        final boolean write;
        Request(String body) {
            Map<String, Object> fields = object(StrictJson.parse(body), "Request");
            for (String key : fields.keySet())
                if (!Arrays.asList("expected_sha256", "config_content", "dry_run", "restore_backup_id").contains(key))
                    fail("Unknown request field: " + key);
            expected = string(fields.get("expected_sha256"), "expected_sha256");
            if (!expected.matches("[0-9a-f]{64}")) fail("expected_sha256 must be a lowercase raw SHA256; ABSENT is not allowed");
            if (fields.containsKey("dry_run") && !(fields.get("dry_run") instanceof Boolean))
                fail("dry_run must be a JSON boolean");
            write = Boolean.FALSE.equals(fields.get("dry_run"));
            if (fields.containsKey("restore_backup_id")) {
                restoreId = string(fields.get("restore_backup_id"), "restore_backup_id");
                requireBackupId(restoreId);
                if (fields.containsKey("config_content")) fail("Restore uses only internal backup raw content; config_content is not accepted");
                proposed = null;
            } else {
                restoreId = null;
                proposed = string(fields.get("config_content"), "config_content");
            }
        }
    }

    /** 在原始行基础上追加缺失的 TARGETS，其余字节保持原样（字符串级拼接，不做 JSON 重序列化）。 */
    static String propose(String raw) {
        List<String> have = new ArrayList<String>(components(raw));
        List<String> need = new ArrayList<String>();
        for (String target : TARGETS) if (!have.contains(target)) need.add(target);
        if (need.isEmpty()) return raw;
        int[] span = componentSpan(raw);
        StringBuilder out = new StringBuilder(raw.substring(0, span[0] + 1));
        out.append(raw, span[0] + 1, span[1] - 1);
        for (String target : need) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '[') out.append(',');
            out.append('"').append(target).append('"');
        }
        out.append(raw.substring(span[1] - 1));
        return out.toString();
    }

    static List<String> addition(String original, String proposed) {
        List<String> before = components(original), after = components(proposed);
        List<String> expected = new ArrayList<String>(before);
        List<String> added = new ArrayList<String>();
        for (String target : TARGETS) if (!expected.contains(target)) { expected.add(target); added.add(target); }
        if (!expected.equals(after)) fail("Only append the fixed missing class names, in fixed order");
        int[] oldSpan = componentSpan(original), newSpan = componentSpan(proposed);
        if (!original.substring(0, oldSpan[0]).equals(proposed.substring(0, newSpan[0]))
                || !original.substring(oldSpan[1]).equals(proposed.substring(newSpan[1])))
            fail("All raw bytes outside [0].activityBlackList.componentName must remain unchanged");
        // A request already at the desired state must be an exact no-op, not a formatting write.
        if (added.isEmpty() && !original.equals(proposed)) fail("Already configured: only exact unchanged raw content is accepted");
        return added;
    }

    static List<String> components(String raw) {
        Object parsed = StrictJson.parse(raw);
        if (!(parsed instanceof List) || ((List<?>) parsed).isEmpty()) fail("Existing AppBootPolicy must be a nonempty array");
        Map<String, Object> top = object(((List<?>) parsed).get(0), "First AppBootPolicy object");
        if (!Boolean.TRUE.equals(top.get("enable")) || !Boolean.TRUE.equals(top.get("activityFunEnable")))
            fail("Current enable and activityFunEnable must both be JSON true");
        Map<String, Object> block = object(top.get("activityBlackList"), "activityBlackList");
        strings(block.get("packageName"), "activityBlackList.packageName");
        return strings(block.get("componentName"), "activityBlackList.componentName");
    }

    static String restore(Map<String, Object> saved, String id, String currentRaw) throws Exception {
        requireBackupId(id);
        if (!number(saved.get("schema"), 1) || !id.equals(saved.get("id"))
                || !FEATURE.equals(saved.get("feature")) || !number(saved.get("user_id"), 0)
                || !"add_fixed_components".equals(saved.get("operation"))
                || !Boolean.TRUE.equals(saved.get("original_exists")))
            fail("Restore requires this endpoint's own original add-operation backup");
        String original = string(saved.get("config_content"), "Saved original raw content");
        String proposed = string(saved.get("proposed_config_content"), "Saved proposed raw content");
        if (!sha(original).equals(saved.get("original_sha256"))
                || !sha(proposed).equals(saved.get("proposed_sha256"))
                || !proposed.equals(currentRaw)) fail("Backup content SHA or exact current row does not match");
        if (addition(original, proposed).isEmpty()) fail("Backup does not contain a fixed-component addition");
        return original;
    }

    static void requireSameIdentity(Map<String, Object> saved, Map<String, Object> current) {
        if (!saved.equals(current)) fail("HOME or desktop activity package identity changed since the backup");
    }

    static int effectiveTestValue(String raw) {
        int value = raw == null ? 0 : Integer.parseInt(raw);
        if (value != 0) fail("tcl_test is nonzero");
        return value;
    }

    static void requireBackupId(String id) {
        if (id == null || !id.matches("appboot-[0-9]+-[0-9a-f]{8}\\.json")) fail("Invalid AppBootPolicy backup id");
    }
    static boolean number(Object value, long expected) {
        return value instanceof Number && new BigDecimal(value.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
    }
    static Map<String, Object> object(Object value, String purpose) { return GuardPolicy.object(value, purpose); }
    private static String string(Object value, String purpose) {
        if (!(value instanceof String)) fail(purpose + " must be a string");
        return (String) value;
    }
    private static List<String> strings(Object value, String purpose) {
        if (!(value instanceof List) || ((List<?>) value).size() > 512) fail(purpose + " must be an array of at most 512 strings");
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) result.add(string(item, purpose + " entry"));
        if (new HashSet<String>(result).size() != result.size()) fail(purpose + " contains duplicate entries");
        return result;
    }
    static String sha(String raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    /** Locate only the specified value, after StrictJson has validated the entire input. */
    private static int[] componentSpan(String raw) {
        int firstObject = space(raw, space(raw, 0) + 1);
        int[] blacklist = member(raw, firstObject, "activityBlackList");
        return member(raw, blacklist[0], "componentName");
    }
    private static int[] member(String raw, int objectStart, String wanted) {
        int p = space(raw, objectStart + 1);
        while (raw.charAt(p) != '}') {
            int keyEnd = end(raw, p);
            String key = (String) StrictJson.parse(raw.substring(p, keyEnd));
            int valueStart = space(raw, space(raw, keyEnd) + 1);
            int valueEnd = end(raw, valueStart);
            if (wanted.equals(key)) return new int[]{valueStart, valueEnd};
            p = space(raw, valueEnd);
            if (raw.charAt(p) == ',') p = space(raw, p + 1);
        }
        throw new IllegalArgumentException("Missing raw member: " + wanted);
    }
    private static int end(String raw, int start) {
        char first = raw.charAt(start);
        if (first == '"') {
            for (int p = start + 1; p < raw.length(); p++) {
                if (raw.charAt(p) == '\\') p++;
                else if (raw.charAt(p) == '"') return p + 1;
            }
        } else if (first == '[' || first == '{') {
            int depth = 0;
            for (int p = start; p < raw.length(); p++) {
                char c = raw.charAt(p);
                if (c == '"') p = end(raw, p) - 1;
                else if (c == '[' || c == '{') depth++;
                else if ((c == ']' || c == '}') && --depth == 0) return p + 1;
            }
        } else {
            int p = start;
            while (p < raw.length() && ",]} \t\r\n".indexOf(raw.charAt(p)) < 0) p++;
            return p;
        }
        throw new IllegalArgumentException("Invalid raw value boundary");
    }
    private static int space(String raw, int p) {
        while (p < raw.length() && " \t\r\n".indexOf(raw.charAt(p)) >= 0) p++;
        return p;
    }
    private static void fail(String reason) { throw new IllegalArgumentException(reason); }
}
