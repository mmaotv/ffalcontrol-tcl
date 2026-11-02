package app.ffalcontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全部功能：主界面「更多」进来的完整页面。
 *
 * <p>只负责展示与转发：每行把 id 通过 {@code setResult} 带回 MainActivity 执行，
 * 这里不碰净化、安装、推送任何业务逻辑，改版时两边的风险是隔开的。
 */
public final class MoreActivity extends Activity {

    /** 交回主界面执行的动作标识。 */
    static final String EXTRA_ACTION = "action";

    static final String ACT_INSTALL_DESKTOP = "install_desktop";
    static final String ACT_DESKTOP_UPDATE  = "desktop_update";
    static final String ACT_SET_HOME        = "set_home";
    static final String ACT_ALLOW_LAUNCHER  = "allow_launcher";
    static final String ACT_LAUNCHER_STATE  = "launcher_state";
    static final String ACT_PUSH            = "push";
    static final String ACT_PICK_APK        = "pick_apk";
    static final String ACT_PROVISION       = "provision";
    static final String ACT_PREAUTHORIZE    = "preauthorize";
    static final String ACT_REFRESH         = "refresh";
    static final String ACT_EXIT            = "exit";

    /** 状态胶囊：id → 视图，后台读完回填。 */
    private final Map<String, TextView> badges = new LinkedHashMap<String, TextView>();
    /** 每行状态胶囊原本的颜色，聚焦时切深色、失焦时还原。 */
    private final Map<TextView, Integer> badgeColors = new LinkedHashMap<TextView, Integer>();

    private ScrollView scroller;
    private View firstRow;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Ui.BG);
        body.setPadding(Ui.dp(this, 46), Ui.dp(this, 30), Ui.dp(this, 46), Ui.dp(this, 26));

        body.addView(buildHeader());

        final boolean bundled = BundledLauncher.BUNDLED;
        final List<Feature> all = buildFeatures();
        final int desktopCount = bundled ? 5 : 1;

        TextView subtitle = Ui.label(this, all.size() + " 个功能，按用途分 5 组。"
                        + "每行右边是它现在的状态，灰字写的是「什么时候该用它」。",
                14, Ui.SECONDARY, false);
        subtitle.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = Ui.dp(this, 8);
        body.addView(subtitle, subtitleParams);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        int cursor = 0;
        addSection(list, "桌面", "换桌面、修桌面，都在这一组", Ui.GREEN,
                slice(all, cursor, desktopCount));
        cursor += desktopCount;
        addSection(list, "安装", "不用数据线，两种把 APK 装进去的办法", Ui.BLUE,
                slice(all, cursor, 2));
        cursor += 2;
        addSection(list, "净化与放行", "改任何配置前都会先备份原值", Ui.ORANGE,
                slice(all, cursor, 3));
        cursor += 3;
        addSection(list, "帮助", "看不懂某个功能时先看这里", Ui.GREY,
                slice(all, cursor, 2));
        cursor += 2;
        addSection(list, "退出", "只停后台服务，不影响已经做过的改动", Ui.RED,
                slice(all, cursor, 1));

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(list, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        listParams.topMargin = Ui.dp(this, 18);
        body.addView(scroller, listParams);

        setContentView(body);

        list.post(new Runnable() {
            @Override public void run() {
                if (firstRow != null) firstRow.requestFocus();
            }
        });

        fillLiveBadges();
    }

    // ---------------------------------------------------------------- 头部

    private View buildHeader() {
        LinearLayout header = Ui.row(this);

        TextView back = Ui.pill(this, "← 返回", 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        header.addView(back, new LinearLayout.LayoutParams(-2, -2));

        TextView title = Ui.label(this, "全部功能", 30, Ui.LABEL, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = Ui.dp(this, 20);
        header.addView(title, titleParams);

        header.addView(Ui.chip(this, BundledLauncher.BUNDLED
                ? "内置 " + BundledLauncher.LABEL : "通用版", Ui.GREEN));
        return header;
    }

    // ---------------------------------------------------------------- 功能清单

    /** 顺序即页面顺序。通用版没有内置桌面，桌面组只剩「桌面更新」一项。 */
    private List<Feature> buildFeatures() {
        List<Feature> out = new ArrayList<Feature>();

        if (BundledLauncher.BUNDLED) {
            out.add(Feature.action(ACT_INSTALL_DESKTOP, "装桌面",
                    "从安装包内部直接装，不用 U 盘也不用电脑",
                    "内置 " + BundledLauncher.LABEL, Ui.GREEN));
        }
        out.add(Feature.action(ACT_DESKTOP_UPDATE, "桌面更新",
                "联网查线上新版，比现在装的更新才下载安装",
                "查询中", Ui.GREY));
        if (BundledLauncher.BUNDLED) {
            out.add(Feature.action(ACT_SET_HOME, "设为默认桌面",
                    "按 Home 键和开机时进这个桌面，会弹一次系统确认",
                    "读取中", Ui.GREY));
            out.add(Feature.action(ACT_ALLOW_LAUNCHER, "放行桌面",
                    "桌面被杀掉、或装完不显示时，把它加进系统白名单",
                    "装不上时用", Ui.ORANGE));
            out.add(Feature.action(ACT_LAUNCHER_STATE, "桌面状态",
                    "看当前默认桌面是谁、内置桌面有没有真的在跑",
                    "只读", Ui.GREY));
        }

        out.add(Feature.action(ACT_PUSH, "推送安装",
                "同一个 WiFi 下用手机或电脑推过来，不用数据线",
                "同 WiFi", Ui.BLUE));
        out.add(Feature.action(ACT_PICK_APK, "选包安装",
                "电视上已经有 APK 文件时，从文件管理器里挑一个装",
                "本机文件", Ui.GREY));

        out.add(Feature.action(ACT_PROVISION, "一键净化",
                "备份原值 → 关广告位 → 禁用厂商预装 → 申请默认桌面",
                "核心档", Ui.ORANGE));
        out.add(Feature.action(ACT_PREAUTHORIZE, "预授权放行",
                "给某个应用单独开「装 APK」的白名单，之后装它不再被拦",
                "需包名", Ui.GREY));
        out.add(Feature.action(ACT_REFRESH, "刷新诊断",
                "重新读一遍当前状态，只读不写，随时可以点",
                "只读", Ui.GREY));

        out.add(Feature.local("guide", "使用指南",
                "三分钟上手：装上去 → 净化 → 冷启动 → 桌面被抢怎么办",
                "3 分钟", Ui.GREEN, new Runnable() {
                    @Override public void run() { showGuide(); }
                }));
        out.add(Feature.local("about", "关于与接口",
                "版本、内置桌面、局域网地址，排障时给对方看",
                "只读", Ui.GREY, new Runnable() {
                    @Override public void run() { showAbout(); }
                }));

        out.add(Feature.local(ACT_EXIT, "停止并退出",
                "关掉后台服务并退出应用。已经净化过的结果不会丢",
                "危险", Ui.RED, new Runnable() {
                    @Override public void run() { confirmExit(); }
                }));

        return out;
    }

    private static List<Feature> slice(List<Feature> all, int from, int count) {
        int end = Math.min(from + count, all.size());
        if (from >= end) return new ArrayList<Feature>();
        return new ArrayList<Feature>(all.subList(from, end));
    }

    // ---------------------------------------------------------------- 分组与行

    private void addSection(LinearLayout parent, String title, String hint, int accent,
                            List<Feature> items) {
        if (items.isEmpty()) return;

        LinearLayout caption = Ui.row(this);
        caption.addView(Ui.label(this, title, 16, Ui.LABEL, true));
        caption.addView(Ui.label(this, "  " + hint, 13, Ui.SECONDARY, false));

        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
        captionParams.topMargin = Ui.dp(this, 22);
        captionParams.bottomMargin = Ui.dp(this, 8);
        parent.addView(caption, captionParams);

        for (Feature feature : items) {
            View row = buildRow(feature, accent);
            if (firstRow == null) firstRow = row;
            parent.addView(row);
        }
    }

    private View buildRow(final Feature feature, final int accent) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));
        row.setBackground(Ui.rounded(this, 0x14FFFFFF, Ui.HAIRLINE, 18));

        View bar = new View(this);
        bar.setBackground(Ui.rounded(this, accent, 0, 2));
        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 4), Ui.dp(this, 34));
        barParams.rightMargin = Ui.dp(this, 16);
        row.addView(bar, barParams);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        final TextView title = Ui.label(this, feature.title, 17, Ui.LABEL, false);
        final TextView desc = Ui.label(this, feature.desc, 13, Ui.SECONDARY, false);
        desc.setLineSpacing(Ui.dp(this, 3), 1f);
        column.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = Ui.dp(this, 3);
        column.addView(desc, descParams);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView badge = new TextView(this);
        badge.setText(feature.badge);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        badge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        badge.setTextColor(feature.badgeColor);
        badge.setBackground(Ui.rounded(this, Ui.FILL, Ui.HAIRLINE, 20));
        badge.setPadding(Ui.dp(this, 16), Ui.dp(this, 7), Ui.dp(this, 16), Ui.dp(this, 7));
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.leftMargin = Ui.dp(this, 18);
        row.addView(badge, badgeParams);

        if (feature.id != null) badges.put(feature.id, badge);
        badgeColors.put(badge, feature.badgeColor);

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                view.setBackground(focused
                        ? Ui.rounded(MoreActivity.this, Ui.FOCUS, 0, 18)
                        : Ui.rounded(MoreActivity.this, 0x14FFFFFF, Ui.HAIRLINE, 18));
                title.setTextColor(focused ? Ui.ON_FOCUS : Ui.LABEL);
                desc.setTextColor(focused ? 0xC71D1D1F : Ui.SECONDARY);
                int idle = badgeColors.containsKey(badge) ? badgeColors.get(badge) : Ui.GREY;
                badge.setTextColor(focused ? Ui.ON_FOCUS : idle);
                badge.setBackground(focused
                        ? Ui.rounded(MoreActivity.this, 0x1F000000, 0, 20)
                        : Ui.rounded(MoreActivity.this, Ui.FILL, Ui.HAIRLINE, 20));
                if (focused) revealRow(view);
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dispatch(feature); }
        });

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(rowParams);
        return row;
    }

    /** 焦点跟手滚动：遥控器一路按下去时，正在选的那行必须露出来。 */
    private void revealRow(View view) {
        if (scroller == null) return;
        Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight() + Ui.dp(this, 24));
        view.requestRectangleOnScreen(rect, false);
    }

    private void dispatch(Feature feature) {
        if (feature.local != null) { feature.local.run(); return; }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACTION, feature.id));
        finish();
    }

    // ---------------------------------------------------------------- 状态回填

    /** 已装版本、当前默认桌面这类要读包管理器的，后台读完再填，主线程不摸 IO。 */
    private void fillLiveBadges() {
        new Thread(new Runnable() {
            @Override public void run() {
                DesktopUpdate.Desktop bundled = DesktopUpdate.bundled();
                String updateText;
                if (bundled == null) {
                    updateText = "两个桌面可选";
                } else {
                    String installed = DesktopUpdate.installedVersion(MoreActivity.this, bundled);
                    updateText = installed == null ? "未装" : "已装 " + installed;
                }

                String home = ApkAnalyzer.defaultHome(MoreActivity.this);
                final String homeText;
                if (home == null) homeText = "未设置";
                else if (BundledLauncher.TARGET != null && BundledLauncher.TARGET.equals(home))
                    homeText = "已是默认";
                else homeText = home;

                String lan = PushServer.lanAddress();
                final String pushText = lan == null || lan.length() == 0
                        ? "同 WiFi" : lan + ":" + PushServer.PORT;

                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        setBadge(ACT_DESKTOP_UPDATE, updateText, Ui.GREEN);
                        setBadge(ACT_SET_HOME, homeText, Ui.GREY);
                        setBadge(ACT_PUSH, pushText, Ui.BLUE);
                    }
                });
            }
        }, "MoreActivity-badges").start();
    }

    private void setBadge(String id, String text, int color) {
        TextView badge = badges.get(id);
        if (badge == null) return;
        badge.setText(text);
        View parent = (View) badge.getParent();
        boolean focused = parent != null && parent.isFocused();
        badge.setTextColor(focused ? Ui.ON_FOCUS : color);
        badgeColors.put(badge, color);
    }

    // ---------------------------------------------------------------- 本页自处理

    private void showGuide() {
        Dialogs.on(this)
                .eyebrow("使用指南")
                .title("三分钟上手")
                .body(MainActivity.GUIDE)
                .positive("知道了", null)
                .show();
    }

    private void showAbout() {
        String version = "0.4.0";
        int code = 0;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) version = info.versionName;
            code = (int) info.getLongVersionCode();
        } catch (Throwable ignored) { }

        String host = PushServer.lanAddress();
        StringBuilder text = new StringBuilder();
        text.append("应用：净化 · ")
                .append(BundledLauncher.BUNDLED ? BundledLauncher.LABEL : "通用版")
                .append("\n版本：v").append(shownVersion(version)).append("（vc").append(code).append("）");
        text.append("\n包名：").append(getPackageName());
        text.append("\n\n内置桌面：").append(BundledLauncher.BUNDLED
                ? BundledLauncher.LABEL + " / " + BundledLauncher.TARGET : "无（通用版）");
        text.append("\n本机控制口：127.0.0.1:18742（需构建期令牌，不在此显示）");
        text.append("\n局域网收包口：")
                .append(host == null ? "未联网" : host + ":" + PushServer.PORT);
        text.append("\n\n两个口都只在本机 / 同一局域网可用，公网访问不到。");

        Dialogs.on(this)
                .eyebrow("关于")
                .title("版本与接口")
                .body(text.toString())
                .positive("关闭", null)
                .show();
    }

    /** 版本号形如 0.4.1-aerial，界面上只显示数字部分，变体已经在上面的「应用」行写了。 */
    private static String shownVersion(String raw) {
        int dash = raw == null ? -1 : raw.indexOf('-');
        return dash > 0 ? raw.substring(0, dash) : (raw == null ? "" : raw);
    }

    private void confirmExit() {
        Dialogs.on(this)
                .eyebrow("退出")
                .title("停止并退出？")
                .body("会关掉局域网收包口和本机控制口，后台服务一起停。\n"
                        + "已经净化过的配置、已经装好的桌面都不会被撤销。")
                .positive("退出", new Dialogs.Action() {
                    @Override public void run() {
                        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACTION, ACT_EXIT));
                        finish();
                    }
                })
                .negative("取消", null)
                .show();
    }
}
