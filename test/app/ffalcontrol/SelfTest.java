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
        versionScenarios();
        if (args.length == 0) {
            System.out.println("\n（没给 APK 路径，跳过清单解析）");
            System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
            if (failures != 0) System.exit(1);
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

    /** 桌面更新的版本比较：纯文本逻辑，不需要真机 */
    private static void versionScenarios() {
        System.out.println("=== 版本比较");
        check("1.56 比 1.52 新", VersionText.compare("1.56", "1.52") > 0);
        check("v3.3.0 比 3.0.0 新", VersionText.compare("v3.3.0", "3.0.0") > 0);
        check("3.0.0 与 v3.0.0 相同", VersionText.compare("3.0.0", "v3.0.0") == 0);
        check("1.2 与 1.2.0 相同（缺段按 0）", VersionText.compare("1.2", "1.2.0") == 0);
        check("段内是数字比较：3.10 > 3.9", VersionText.compare("3.10", "3.9") > 0);
        check("反向比较为负", VersionText.compare("1.52", "1.56") < 0);
        check("空版本不误判成有更新",
                VersionText.compare("", "3.3.0") == 0 && VersionText.compare(null, "1.0") == 0);
        check("normalize 去掉 v 与空白", "3.3.0".equals(VersionText.normalize(" v3.3.0 ")));
        check("最多取四段数字", VersionText.digits("1.2.3.4.5").length == 4);
        check("带后缀的版本号只取数字", VersionText.compare("1.0-beta2", "1.0-beta1") > 0);
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
