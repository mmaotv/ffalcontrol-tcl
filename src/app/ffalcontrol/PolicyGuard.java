package app.ffalcontrol;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 读 InstallConfig 的六策略快照，并执行「最小改动」的配置手术。
 *
 * 写操作全部委托给 InstallConfigControl：它已经有 sha256 compare-and-set、写前备份、
 * 写后读回比对、以及「其它 strategy 一字不动」的语义校验。这里只负责：
 *   - 把原始 JSON 解析成 SecurityGate.Policy
 *   - 把 SecurityGate.Patch 翻译成 InstallConfigControl 的 action
 *   - 失败时反向撤销（回滚）
 */
final class PolicyGuard {

    static final String FEATURE = "InstallConfig";
    private static final Uri URI = Uri.parse("content://com.tcl.providers.config/" + FEATURE);

    private final Context context;
    private final InstallConfigControl control;

    PolicyGuard(Context context) {
        this.context = context.getApplicationContext();
        this.control = new InstallConfigControl(this.context);
    }

    // ---------------------------------------------------------------- 读

    /** 原始 config_content，行不存在返回 null。 */
    String raw() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = resolver.query(URI, new String[]{"project_id", "config_content"}, null, null, null);
        if (cursor == null) throw new GuardControl.ApiFailure(503, "InstallConfig 查询返回空游标");
        try {
            if (!cursor.moveToFirst()) return null;
            String id = cursor.getString(cursor.getColumnIndexOrThrow("project_id"));
            if (!FEATURE.equals(id)) throw new GuardControl.ApiFailure(409, "InstallConfig 行键不匹配：" + id);
            return cursor.getString(cursor.getColumnIndexOrThrow("config_content"));
        } finally {
            try { cursor.close(); } catch (Throwable ignored) { }
        }
    }

    /** 当前六策略快照。 */
    SecurityGate.Policy snapshot() {
        SecurityGate.Policy policy = new SecurityGate.Policy();
        String raw;
        try {
            raw = raw();
        } catch (Throwable unreadable) {
            // 读不到就按「默认启用」保守处理，让界面上能看到"未读取"而不是假装放行
            policy.loaded = false;
            return policy;
        }
        if (raw == null) {
            // 行不存在时 PackageInstaller_tv 拿不到配置，verifyPendingInstall(1) 直接放行
            policy.loaded = true;
            policy.launcherEnabled = false;
            policy.blacklistEnabled = false;
            policy.overdueEnabled = false;
            policy.thridEnabled = false;
            policy.pmEnabled = false;
            policy.sourceSha = "";
            return policy;
        }
        try {
            policy.sourceSha = InstallConfigControl.sha256(raw);
            JSONArray rows = new JSONArray(raw);
            if (rows.length() == 0) return policy;
            JSONObject top = rows.getJSONObject(0);
            boolean rootEnabled = parseBool(top.opt("enable"), true);
            if (!rootEnabled) {
                // 顶层 enable=false 时 setUpStrategies 直接放行，六策略跑都不跑
                policy.loaded = true;
                policy.launcherEnabled = false;
                policy.blacklistEnabled = false;
                policy.overdueEnabled = false;
                policy.thridEnabled = false;
                policy.pmEnabled = false;
                return policy;
            }
            JSONArray strategies = top.optJSONArray("strategies");
            if (strategies == null) return policy;
            for (int i = 0; i < strategies.length(); i++) {
                JSONObject strategy = strategies.getJSONObject(i);
                String name = strategy.optString("name", "");
                boolean enabled = parseBool(strategy.opt("enable"), true);
                JSONArray packages = strategy.optJSONArray("packages");
                List<String> values = new ArrayList<String>();
                if (packages != null) {
                    for (int j = 0; j < packages.length(); j++) {
                        String value = packages.optString(j, null);
                        if (value != null) values.add(value);
                    }
                }
                if (SecurityGate.LAUNCHER.equals(name)) {
                    policy.launcherEnabled = enabled;
                    policy.launcherWhitelist.addAll(values);
                } else if (SecurityGate.BLACKLIST.equals(name)) {
                    policy.blacklistEnabled = enabled;
                    policy.blacklist.addAll(values);
                } else if (SecurityGate.OVERDUE.equals(name)) {
                    policy.overdueEnabled = enabled;
                    policy.overdue.addAll(values);
                } else if (SecurityGate.THRID.equals(name)) {
                    policy.thridEnabled = enabled;
                } else if (SecurityGate.PM.equals(name)) {
                    policy.pmEnabled = enabled;
                }
            }
            policy.loaded = true;
        } catch (Throwable broken) {
            policy.loaded = false;
        }
        return policy;
    }

    /** 给 /security/policies 用的可读快照。 */
    JSONObject describe() throws Exception {
        SecurityGate.Policy policy = snapshot();
        JSONObject out = new JSONObject();
        out.put("feature", FEATURE);
        out.put("uri", URI.toString());
        out.put("loaded", policy.loaded);
        out.put("row_sha256", policy.sourceSha == null ? JSONObject.NULL : policy.sourceSha);

        JSONObject strategies = new JSONObject();
        strategies.put(SecurityGate.LAUNCHER, row(policy.launcherEnabled, policy.launcherWhitelist));
        strategies.put(SecurityGate.BLACKLIST, row(policy.blacklistEnabled, policy.blacklist));
        strategies.put(SecurityGate.OVERDUE, row(policy.overdueEnabled, policy.overdue));
        strategies.put(SecurityGate.THRID, row(policy.thridEnabled, new ArrayList<String>()));
        strategies.put(SecurityGate.PM, row(policy.pmEnabled, new ArrayList<String>()));
        strategies.put(SecurityGate.SAFE, row(false, new ArrayList<String>()));
        out.put("strategies", strategies);

        out.put("verifier_enabled",
                context.getPackageManager().hasSystemFeature("com.tcl.software.packageinstaller_install_control"));
        out.put("default_home", ApkAnalyzer.defaultHome(context));
        out.put("module_allow_install", context.getPackageManager().canRequestPackageInstalls());
        return out;
    }

    private static JSONObject row(boolean enabled, java.util.Collection<String> packages) throws Exception {
        JSONObject row = new JSONObject();
        row.put("enable", enabled);
        row.put("packages", new JSONArray(packages));
        row.put("count", packages.size());
        return row;
    }

    // ---------------------------------------------------------------- 写

    /** 执行一条改动。write=false 时只出计划。 */
    JSONObject apply(SecurityGate.Patch patch, boolean write) throws Exception {
        String raw = raw();
        if (raw == null) throw new GuardControl.ApiFailure(409, "InstallConfig 行不存在，拒绝写入");
        JSONObject body = new JSONObject();
        body.put("action", patch.action());
        body.put("package", patch.packageName);
        if (SecurityGate.LAUNCHER.equals(patch.strategy)) {
            body.put("strategy", SecurityGate.LAUNCHER);
        } else {
            body.put("strategy", patch.strategy);
        }
        body.put("expected_sha256", InstallConfigControl.sha256(raw));
        body.put("write", write);
        return control.apply(body.toString());
    }

    /** 撤销一条已应用的改动。 */
    JSONObject revert(SecurityGate.Patch patch) throws Exception {
        SecurityGate.Patch inverse = new SecurityGate.Patch(patch.strategy, patch.packageName, !patch.add);
        if (SecurityGate.LAUNCHER.equals(inverse.strategy)) {
            // launcherStrategy 的「撤掉白名单」语义就是 remove_launcher_package
            inverse = new SecurityGate.Patch(SecurityGate.LAUNCHER, patch.packageName, false);
        }
        return apply(inverse, true);
    }

    /**
     * 预授权：不需要 APK，直接按包名放行。
     * 只做两件事：加进桌面放行名单、从拦截/过期名单里移出。
     */
    JSONObject preauthorize(String pkg, boolean write) throws Exception {
        if (!SecurityGate.validPackageName(pkg))
            throw new GuardControl.ApiFailure(422, "不是合法的包名：" + pkg);
        SecurityGate.Policy policy = snapshot();
        List<SecurityGate.Hit> hits = new ArrayList<SecurityGate.Hit>();

        if (policy.launcherEnabled && !policy.launcherWhitelist.contains(pkg)) {
            hits.add(new SecurityGate.Hit(SecurityGate.LAUNCHER,
                    "预授权：允许作为桌面安装", "加入 " + SecurityGate.LAUNCHER + ".packages"));
        }
        if (policy.blacklistEnabled && policy.blacklist.contains(pkg)) {
            hits.add(new SecurityGate.Hit(SecurityGate.BLACKLIST, "命中拦截名单", "从名单移出"));
        }
        if (policy.overdueEnabled && policy.overdue.contains(pkg)) {
            hits.add(new SecurityGate.Hit(SecurityGate.OVERDUE, "命中过期名单", "从名单移出"));
        }

        // 预授权的语义是"无条件放行"：即使当前不在名单里，也先加白名单
        List<SecurityGate.Patch> patches = new ArrayList<SecurityGate.Patch>();
        if (policy.launcherEnabled) patches.add(new SecurityGate.Patch(SecurityGate.LAUNCHER, pkg, true));
        for (SecurityGate.Hit hit : hits) {
            if (SecurityGate.BLACKLIST.equals(hit.strategy))
                patches.add(new SecurityGate.Patch(SecurityGate.BLACKLIST, pkg, false));
            if (SecurityGate.OVERDUE.equals(hit.strategy))
                patches.add(new SecurityGate.Patch(SecurityGate.OVERDUE, pkg, false));
        }
        // 已经在名单里就不再重复加，避免 InstallConfigControl 抛 422
        patches = dedupe(patches, policy);

        JSONObject out = new JSONObject();
        out.put("package", pkg);
        out.put("dry_run", !write);
        out.put("policy_loaded", policy.loaded);
        JSONArray applied = new JSONArray();
        JSONArray errors = new JSONArray();
        for (SecurityGate.Patch patch : patches) {
            JSONObject row = new JSONObject();
            row.put("describe", patch.describe());
            try {
                row.put("result", apply(patch, write));
            } catch (Throwable failure) {
                row.put("error", describe(failure));
                errors.put(patch.describe());
            }
            applied.put(row);
        }
        out.put("applied", applied);
        out.put("errors", errors);
        out.put("ok", errors.length() == 0);
        return out;
    }

    private static List<SecurityGate.Patch> dedupe(List<SecurityGate.Patch> patches, SecurityGate.Policy policy) {
        List<SecurityGate.Patch> out = new ArrayList<SecurityGate.Patch>();
        for (SecurityGate.Patch patch : patches) {
            boolean redundant = false;
            if (SecurityGate.LAUNCHER.equals(patch.strategy) && patch.add
                    && policy.launcherWhitelist.contains(patch.packageName)) redundant = true;
            if (SecurityGate.BLACKLIST.equals(patch.strategy) && !patch.add
                    && !policy.blacklist.contains(patch.packageName)) redundant = true;
            if (SecurityGate.OVERDUE.equals(patch.strategy) && !patch.add
                    && !policy.overdue.contains(patch.packageName)) redundant = true;
            if (!redundant) out.add(patch);
        }
        return out;
    }

    private static boolean parseBool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean) return (Boolean) value;
        String text = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(text) || "1".equals(text)) return true;
        if ("false".equals(text) || "0".equals(text)) return false;
        return fallback;
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "null";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
