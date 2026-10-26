package app.ffalcontrol;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯逻辑：算清楚「这个 APK 会被哪条厂商策略拦」。
 *
 * 严格复刻 /system/priv-app/PackageInstaller_tv 里的三个判定分支（反编译所得）：
 *   LauncherStrategy  strategy 关 → 放行；非 MAIN+HOME → 放行；包名在白名单 → 放行；
 *                     签名 == 当前默认桌面 且 metaData com.tcl.app.type == "com.tcl.ui" → 放行；否则拒
 *   BlackStrategy     包名 ∈ packages → 拒
 *   OverDueStrategy   包名 ∈ packages → 拒
 *   ThridStrategy     仅当 installer == com.android.packageinstaller 时才查来源；系统应用/自身/白名单外 → 拒
 *   PmStrategy        installFlag & 0x20 (INSTALL_FROM_ADB) → 拒
 *   SafeStrategy      空实现，永远放行
 *
 * 无 Android 依赖，所以能在 JVM 上直接跑真实判定，不必上电视试。
 */
final class SecurityGate {

    static final String LAUNCHER = "launcherStrategy";
    static final String BLACKLIST = "blackListStrategy";
    static final String OVERDUE = "overDueStrategy";
    static final String THRID = "thridStrategy";
    static final String PM = "pmStrategy";
    static final String SAFE = "safeStrategy";

    /** 系统安装器的包名：只有它发起的安装才会被 thridStrategy 检查。 */
    static final String SYSTEM_INSTALLER = "com.android.packageinstaller";
    /** INSTALL_FROM_ADB */
    static final int FLAG_FROM_ADB = 0x20;

    private SecurityGate() {}

    /** 六策略的当前配置快照。 */
    static final class Policy {
        boolean launcherEnabled = true;
        boolean blacklistEnabled = true;
        boolean overdueEnabled = true;
        boolean thridEnabled = true;
        boolean pmEnabled = true;
        final Set<String> launcherWhitelist = new LinkedHashSet<String>();
        final Set<String> blacklist = new LinkedHashSet<String>();
        final Set<String> overdue = new LinkedHashSet<String>();

        /** 是否拿到了真实的 InstallConfig（false 表示只能按默认值猜测）。 */
        boolean loaded;
        String sourceSha;
    }

    /** 从 APK 里读出来的、判定需要的事实。 */
    static final class Facts {
        String packageName = "";
        boolean declaresHome;
        boolean tclSigned;
        boolean tclUiMeta;
        /** 谁发起安装。我们自己的会话是 app.ffalcontrol，系统安装器是 com.android.packageinstaller。 */
        String installer = "app.ffalcontrol";
        boolean fromAdb;

        Facts copy() {
            Facts clone = new Facts();
            clone.packageName = packageName;
            clone.declaresHome = declaresHome;
            clone.tclSigned = tclSigned;
            clone.tclUiMeta = tclUiMeta;
            clone.installer = installer;
            clone.fromAdb = fromAdb;
            return clone;
        }
    }

    /** 一条命中：哪条策略、为什么、怎么解。 */
    static final class Hit {
        final String strategy;
        final String reason;
        final String fix;
        Hit(String strategy, String reason, String fix) {
            this.strategy = strategy;
            this.reason = reason;
            this.fix = fix;
        }
    }

    /** 本工具发起安装时会被哪几条拦。 */
    static List<Hit> hits(Facts facts, Policy policy) {
        List<Hit> out = new ArrayList<Hit>();
        String pkg = facts.packageName;

        if (policy.launcherEnabled && facts.declaresHome) {
            boolean whitelisted = policy.launcherWhitelist.contains(pkg);
            boolean genuineTcl = facts.tclSigned && facts.tclUiMeta;
            if (!whitelisted && !genuineTcl) {
                out.add(new Hit(LAUNCHER,
                        whitelisted ? "桌面类，但签名不是 TCL 官方"
                                : "声明了 MAIN + HOME 的第三方桌面，未在放行名单内",
                        "把 " + pkg + " 加入 InstallConfig.launcherStrategy.packages"));
            }
        }
        if (policy.blacklistEnabled && policy.blacklist.contains(pkg)) {
            out.add(new Hit(BLACKLIST, "包名命中 blackListStrategy 拦截名单",
                    "把 " + pkg + " 从 blackListStrategy.packages 移除"));
        }
        if (policy.overdueEnabled && policy.overdue.contains(pkg)) {
            out.add(new Hit(OVERDUE, "包名命中 overDueStrategy 过期名单",
                    "把 " + pkg + " 从 overDueStrategy.packages 移除"));
        }
        if (policy.thridEnabled && SYSTEM_INSTALLER.equals(facts.installer)) {
            out.add(new Hit(THRID, "由系统安装器发起，来源不在白名单",
                    "改用本工具安装（installer 会变成 " + "app.ffalcontrol" + "，这条不触发）"));
        }
        if (policy.pmEnabled && facts.fromAdb) {
            out.add(new Hit(PM, "安装标志含 INSTALL_FROM_ADB (0x20)",
                    "改用本工具安装，不要用 adb install"));
        }
        return out;
    }

    /** 换成「用户自己在文件管理器点开安装」这条路径时会多命中哪些。 */
    static List<Hit> ifManualInstall(Facts facts, Policy policy) {
        Facts manual = facts.copy();
        manual.installer = SYSTEM_INSTALLER;
        return hits(manual, policy);
    }

    /** 换成 adb install 会多命中哪些。 */
    static List<Hit> ifAdbInstall(Facts facts, Policy policy) {
        Facts adb = facts.copy();
        adb.fromAdb = true;
        return hits(adb, policy);
    }

    /** 只要策略名。 */
    static List<String> names(List<Hit> hits) {
        List<String> out = new ArrayList<String>();
        for (Hit hit : hits) out.add(hit.strategy);
        return out;
    }

    /** 需要的配置改动：strategy → 对包名做什么。 */
    static final class Patch {
        final String strategy;
        final String packageName;
        final boolean add;
        Patch(String strategy, String packageName, boolean add) {
            this.strategy = strategy;
            this.packageName = packageName;
            this.add = add;
        }
        String action() {
            if (LAUNCHER.equals(strategy)) return add ? "allow_launcher_package" : "remove_launcher_package";
            return add ? "add_to_strategy" : "remove_from_strategy";
        }
        String describe() {
            String verb = add ? "加入" : "移出";
            String list = LAUNCHER.equals(strategy) ? "桌面放行名单"
                    : BLACKLIST.equals(strategy) ? "拦截名单"
                    : OVERDUE.equals(strategy) ? "过期名单" : strategy;
            return verb + list + "：" + packageName;
        }
    }

    /**
     * 由命中结果推出最小改动集。
     * 只做两件事：往桌面放行名单里加、从拦截/过期名单里移。绝不往黑名单里加。
     * thrid/pm 两条不需要改配置 —— 换安装路径即可，这里不出 patch。
     */
    static List<Patch> plan(List<Hit> hits, String pkg) {
        List<Patch> out = new ArrayList<Patch>();
        for (Hit hit : hits) {
            if (LAUNCHER.equals(hit.strategy)) out.add(new Patch(LAUNCHER, pkg, true));
            else if (BLACKLIST.equals(hit.strategy)) out.add(new Patch(BLACKLIST, pkg, false));
            else if (OVERDUE.equals(hit.strategy)) out.add(new Patch(OVERDUE, pkg, false));
        }
        return out;
    }

    /** 判断一个包名是否规格合法。 */
    static boolean validPackageName(String name) {
        if (name == null) return false;
        if (name.length() < 3 || name.length() > 200) return false;
        return name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
}
