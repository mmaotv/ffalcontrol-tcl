package app.ffalcontrol;

import java.io.File;

/**
 * 不依赖 Android 的自测：验证 AXML 解析与策略判定。
 * 编译：javac -d out src/app/ffalcontrol/AxmlReader.java src/app/ffalcontrol/SecurityGate.java test/app/ffalcontrol/SelfTest.java
 * 运行：java -cp out app.ffalcontrol.SelfTest <apk> [<apk> ...]
 */
public final class SelfTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: SelfTest <apk> [<apk> ...]");
            return;
        }
        for (String path : args) {
            File apk = new File(path);
            System.out.println("=== " + apk.getName() + " (" + apk.length() + " 字节)");
            try {
                AxmlReader.Manifest manifest = AxmlReader.read(apk);
                System.out.println("  package        = " + manifest.packageName);
                System.out.println("  versionName    = " + manifest.versionName);
                System.out.println("  versionCode    = " + manifest.versionCode);
                System.out.println("  declaresHome   = " + manifest.declaresHome);
                System.out.println("  launcherActs   = " + manifest.launcherActivities);
                System.out.println("  tclAppType     = " + manifest.tclAppType);
                check("有包名", manifest.packageName != null && manifest.packageName.length() > 0);
            } catch (Throwable failure) {
                System.out.println("  解析失败: " + failure);
                failures++;
            }
        }
        policyScenarios();
        System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
        if (failures != 0) System.exit(1);
    }

    private static void policyScenarios() {
        System.out.println("\n=== 判定场景");

        SecurityGate.Policy policy = new SecurityGate.Policy();
        policy.launcherWhitelist.add("com.gathin.tvhome");
        policy.blacklist.add("com.tcl.vod");

        // 1. 第三方桌面、不在白名单 → 应命中 launcherStrategy
        SecurityGate.Facts home = facts("com.example.home", true, false, false);
        expect("第三方桌面被 launcherStrategy 拦", SecurityGate.names(SecurityGate.hits(home, policy)),
                SecurityGate.LAUNCHER);

        // 2. 同一桌面加入白名单后 → 应放行
        SecurityGate.Facts allowlisted = facts("com.gathin.tvhome", true, false, false);
        expect("白名单内的桌面放行", SecurityGate.names(SecurityGate.hits(allowlisted, policy)), null);

        // 3. TCL 签名 + com.tcl.ui → 放行
        SecurityGate.Facts tclHome = facts("com.tcl.cyberui", true, true, true);
        expect("TCL 官方桌面放行", SecurityGate.names(SecurityGate.hits(tclHome, policy)), null);

        // 4. 有 TCL 签名但没有 com.tcl.ui metaData → 仍被拦
        SecurityGate.Facts halfTcl = facts("com.fake.home", true, true, false);
        expect("签名对但缺 metaData 仍被拦",
                SecurityGate.names(SecurityGate.hits(halfTcl, policy)), SecurityGate.LAUNCHER);

        // 5. 普通应用（非桌面）→ 不拦
        SecurityGate.Facts plain = facts("com.example.app", false, false, false);
        expect("普通应用放行", SecurityGate.names(SecurityGate.hits(plain, policy)), null);

        // 6. 黑名单命中
        SecurityGate.Facts black = facts("com.tcl.vod", false, false, false);
        expect("黑名单命中被拦", SecurityGate.names(SecurityGate.hits(black, policy)),
                SecurityGate.BLACKLIST);

        // 7. 我们的 installer 不触发 thridStrategy；换成系统安装器就触发
        SecurityGate.Facts manual = facts("com.example.tool", false, false, false);
        manual.installer = SecurityGate.SYSTEM_INSTALLER;
        expect("系统安装器路径触发 thridStrategy",
                SecurityGate.names(SecurityGate.hits(manual, policy)), SecurityGate.THRID);
        expect("本工具路径不触发 thridStrategy",
                SecurityGate.names(SecurityGate.hits(facts("com.example.tool", false, false, false), policy)),
                null);

        // 8. adb 触发 pmStrategy
        SecurityGate.Facts adb = facts("com.example.tool", false, false, false);
        adb.fromAdb = true;
        expect("adb 安装触发 pmStrategy", SecurityGate.names(SecurityGate.hits(adb, policy)),
                SecurityGate.PM);

        // 9. patch 计划：只加白名单，不往黑名单加
        java.util.List<SecurityGate.Patch> plan = SecurityGate.plan(
                SecurityGate.hits(home, policy), "com.example.home");
        check("桌面命中时给出加白名单的 patch",
                plan.size() == 1 && plan.get(0).add && SecurityGate.LAUNCHER.equals(plan.get(0).strategy));
        System.out.println("  patch: " + (plan.isEmpty() ? "(无)" : plan.get(0).describe()));

        java.util.List<SecurityGate.Patch> blackPlan = SecurityGate.plan(
                SecurityGate.hits(black, policy), "com.tcl.vod");
        check("黑名单命中时给出移出名单的 patch",
                blackPlan.size() == 1 && !blackPlan.get(0).add);

        // 10. 包名校验
        check("合法包名通过", SecurityGate.validPackageName("com.example.app"));
        check("非法包名被拒", !SecurityGate.validPackageName("com"));
        check("非法包名被拒2", !SecurityGate.validPackageName("com..app"));
        check("非法包名被拒3", !SecurityGate.validPackageName("1com.example"));
    }

    private static SecurityGate.Facts facts(String pkg, boolean home, boolean tclSigned, boolean tclUi) {
        SecurityGate.Facts facts = new SecurityGate.Facts();
        facts.packageName = pkg;
        facts.declaresHome = home;
        facts.tclSigned = tclSigned;
        facts.tclUiMeta = tclUi;
        return facts;
    }

    private static void expect(String label, java.util.List<String> actual, String wanted) {
        boolean ok = wanted == null ? actual.isEmpty() : actual.contains(wanted);
        check(label, ok);
        if (!ok) System.out.println("    实际命中: " + actual + "，期望: " + wanted);
    }

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label);
        if (!ok) failures++;
    }
}
