package app.ffalcontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 冷装 payload：恢复出厂后不依赖 shell、不依赖 Mac，一次写入即可回到目标状态。
 *
 * 分级：
 *   CORE — 广告 / 遥测 / 推广 / 静默推送。默认档，写入后不影响观看与系统功能。
 *   FULL — CORE + 内容与小组件入口（VOD、有线频道、K 歌、用户中心、天气、游戏助手、
 *          蓝牙单独听、智能相册、酒店模式、音效入口、卡拉OK麦克风助手）。
 *
 * 红线包不在此文件出现：com.tcl.cyberui、com.tcl.tv、com.tcl.settings、
 * com.tcl.systemserver、com.tcl.ttvs、com.tcl.TFDS.service、com.tcl.guard、
 * com.tcl.providers.config、com.tcl.initsetup、com.sohu.inputmethod.sogou.tv、
 * com.tcl.ui_mediaCenter、com.tcl.autopair、com.tcl.tcl_bt_rcu_service、com.tvos。
 */
final class ProvisionPayload {

    /** payload 版本：改动清单一律递增，恢复包与报告都引用这个号。 */
    static final String VERSION = "2026-10-23.3";

    static final String[] CORE = new String[]{
            // 广告 / 遥测 / 推送（历史已隐藏，恢复出厂后会复活，必须重写）
            "com.tcl.bootadservice",
            "com.tcl.profilemanager",
            "com.tcl.logkit",
            "com.tcl.c2dm.client",
            "com.tcl.atomic.servicecenter",
            "com.tcl.xian.StartandroidService",
            "com.tcl.repairguide",
            "com.tcl.useragreement",
            "com.tcl.appreciate.art",
            "com.tcl.playskill",
            // 渠道 / 商店 / 搜索（诱导下单入口）
            "com.tcl.ffeducation",
            "com.tcl.appmarket2",
            "com.tcl.wholenetsearch",
            // 内容 / 购买入口
            "com.tcl.vod",
            "com.tcl.ffkaraoke",
            "com.tcl.gamebar",
            "com.tcl.tvsmartalbum",
            "com.tcl.hotelmenu",
            "com.tcl.t_solo",
            "com.audiocn.karaoke.micmanager",
            // 用户中心
            "com.tcl.usercenter",
            // 语音助手（uid 1000，已放开例外）：禁掉后遥控器语音键不再把内容推给桌面
            "com.tcl.walleve"
    };

    static final String[] FULL_EXTRA = new String[]{
            // 仅列 CORE 之外的可选项，避免与 CORE 重复
            "com.tcl.tliveplay",
            "com.tcl.usercenter",
            "com.tcl.common.weather",
            "com.tcl.hearaid"
    };

    /** 需要放行的第三方 HOME 包：真桌面 + 验证用探针。 */
    static final String[] LAUNCHER_ALLOW = new String[]{
            "app.orchard.tv"
    };

    /** 灵控桌面上已禁的组件（保留限域，不进包级）。 */
    static final String[] CYBERUI_COMPONENTS = new String[]{
            "com.tcl.cyberui.modechange.ChildChangeInterceptActivity",
            "com.tcl.childrentabchoosepop.ChildrenTabTopActivity",
            "com.tcl.cyberui.checkupdate.main.CheckUpdateActivity",
            "com.tcl.mlauncher.subject.recommend.DailyRecommendActivity",
            "com.tcl.mlauncher.subject.detail.SubjectDetailActivity",
            "com.tcl.mlauncher.subject.collection.SubjectCollectionActivity",
            "com.tcl.mlauncher.subject.toplist.TopListActivity",
            "com.tcl.mllauncher.mlscene.addwidget.apprank.AppRankActivity",
            "com.tcl.cyberui.venus.subject.view.RecommendSubjectDetailActivity",
            "com.tcl.cyberui.venus.rankings.VenusRankingsActivity",
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
            "com.tcl.subject.HotRankSubjectActivity",
            "com.tcl.subject.WaterFallSubjectActivity",
            "com.tcl.subject.CyberSubjectActivity",
            "com.tcl.subject.MoreCategorySubjectActivity",
            "com.tcl.subject.HPNewActivity",
            "com.tcl.subject.SubjectHighlightsActivity",
            "com.tcl.subject.GuessYouLikeActivity",
            "com.tcl.subject.LabelActivity",
            "com.tcl.mlauncher.mlchannel.MlChannelActivity",
            "com.tcl.cyberui.displayblockview.block.playwithchoose.ui.full.PwcFullScreenActivity",
            "com.tcl.mllauncher.mlscene.addwidget.widgetlist.WidgetListActivity",
            "com.tcl.mllauncher.mlscene.addwidget.cardcenter.CardCenterActivity",
            "com.tcl.mllauncher.mlscene.addwidget.widgetlist.WidgetListNoAnimActivity",
            "com.tcl.mllauncher.mlscene.addwidget.sizelist.WidgetSizeListActivity",
            "com.tcl.mllauncher.mlscene.addwidget.toTargetScene.ToTargetSceneActivity",
            "com.tcl.mllauncher.mlscene.addwidget.sizelist.WidgetSizeListAppsActivity"
    };

    static List<String> packages(String level) {
        List<String> out = new ArrayList<String>();
        for (String pkg : CORE) out.add(pkg);
        if ("full".equals(level)) for (String pkg : FULL_EXTRA) out.add(pkg);
        return out;
    }

    /**
     * 生成完整 Guard_disable_App 内容：先按现有行原顺序保留已有条目（GuardPolicy 不允许重排），
     * 再把缺失的目标条目追加在后面。
     */
    static String guard(String existingRaw, String level) throws Exception {
        return guard(existingRaw, level, null);
    }

    static String guard(String existingRaw, String level, java.util.Collection<String> skip) throws Exception {
        JSONObject top = new JSONObject();
        JSONArray list = new JSONArray();
        List<String> present = new ArrayList<String>();

        if (existingRaw != null && existingRaw.length() > 0) {
            JSONArray rows = new JSONArray(existingRaw);
            if (rows.length() != 1) throw new IllegalStateException("Guard row must hold exactly one object");
            JSONArray existing = rows.getJSONObject(0).getJSONArray("disableAppInfoList");
            for (int i = 0; i < existing.length(); i++) {
                JSONObject entry = existing.getJSONObject(i);
                list.put(entry);
                if (entry.optString("componentName", "").length() == 0) {
                    present.add(entry.getString("packageName"));
                    entry.put("disable", true);
                }
            }
        }

        for (String pkg : packages(level)) {
            if (skip != null && skip.contains(pkg)) continue;
            if (present.contains(pkg)) continue;
            JSONObject entry = new JSONObject();
            entry.put("packageName", pkg);
            entry.put("componentName", "");
            entry.put("disable", true);
            list.put(entry);
            present.add(pkg);
        }
        for (String component : CYBERUI_COMPONENTS) {
            if (present.contains("com.tcl.cyberui/" + component)) continue;
            boolean exists = false;
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.getJSONObject(i);
                if ("com.tcl.cyberui".equals(entry.optString("packageName", ""))
                        && component.equals(entry.optString("componentName", ""))) { exists = true; break; }
            }
            if (exists) continue;
            JSONObject entry = new JSONObject();
            entry.put("packageName", "com.tcl.cyberui");
            entry.put("componentName", component);
            entry.put("disable", true);
            list.put(entry);
        }
        top.put("disableAppInfoList", list);
        return new JSONArray().put(top).toString();
    }
}
