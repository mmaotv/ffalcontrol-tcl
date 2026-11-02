package app.ffalcontrol;

/**
 * 「全部功能」页里的一行：标题 + 一行说明 + 右侧状态。
 *
 * <p>分成两种：
 * <ul>
 *   <li>{@link #action}：点了之后把 id 交回 MainActivity 执行，业务逻辑一行都不搬；</li>
 *   <li>{@link #local}：本页自己处理（使用指南、关于、退出确认这类纯展示）。</li>
 * </ul>
 */
final class Feature {

    /** 交回主界面的动作标识；本页自处理时为 null。 */
    final String id;
    final String title;
    final String desc;
    /** 本页自处理的动作为 null 时表示交回主界面。 */
    final Runnable local;

    /** 右侧状态文案，可被后台读取的结果覆盖。 */
    String badge;
    /** 状态文案颜色。 */
    int badgeColor;

    private Feature(String id, String title, String desc, String badge, int badgeColor,
                    Runnable local) {
        this.id = id;
        this.title = title;
        this.desc = desc;
        this.badge = badge;
        this.badgeColor = badgeColor;
        this.local = local;
    }

    static Feature action(String id, String title, String desc, String badge, int badgeColor) {
        return new Feature(id, title, desc, badge, badgeColor, null);
    }

    static Feature local(String id, String title, String desc, String badge, int badgeColor,
                         Runnable local) {
        return new Feature(id, title, desc, badge, badgeColor, local);
    }
}
