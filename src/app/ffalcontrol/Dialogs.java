package app.ffalcontrol;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 统一弹窗。
 *
 * 系统 AlertDialog 在电视上是白底、按钮挤成一团，和本工具的暗色界面割裂得厉害，
 * 所以这里用和主界面同一套材质（{@link Ui#panel}、{@link Ui#pill}、{@link Ui#menuRow}）
 * 自己搭：深色毛玻璃卡片 + 标题 + 可滚动正文/列表 + 药丸按钮，遥控器方向键直接可操作。
 *
 * 三种形态：
 *   message —— 只有正文和一个「知道了」；
 *   confirm —— 正文 + 取消/确定；
 *   list    —— 一组可点的行（次级菜单、选择题）。
 */
final class Dialogs {

    interface Action {
        void run();
    }

    static final class Builder {

        private final Activity activity;
        private String eyebrow;
        private String title;
        private CharSequence body;
        private View extraView;
        private String[] itemLabels;
        private Action[] itemActions;
        private String positiveLabel;
        private Action positiveAction;
        private String negativeLabel;
        private Action negativeAction;

        private Builder(Activity activity) {
            this.activity = activity;
        }

        Builder eyebrow(String value) { this.eyebrow = value; return this; }

        Builder title(String value) { this.title = value; return this; }

        Builder body(CharSequence value) { this.body = value; return this; }

        /** 追加一个自定义控件（输入框等），放在正文下面 */
        Builder view(View value) { this.extraView = value; return this; }

        Builder items(String[] labels, Action[] actions) {
            this.itemLabels = labels;
            this.itemActions = actions;
            return this;
        }

        Builder positive(String label, Action action) {
            this.positiveLabel = label;
            this.positiveAction = action;
            return this;
        }

        Builder negative(String label, Action action) {
            this.negativeLabel = label;
            this.negativeAction = action;
            return this;
        }

        Dialog show() {
            final View[] firstFocus = new View[]{null};
            final Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(true);

            Context context = activity;
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.panel(context));
            card.setPadding(Ui.dp(context, 30), Ui.dp(context, 26),
                    Ui.dp(context, 30), Ui.dp(context, 24));

            if (notBlank(eyebrow)) {
                TextView label = Ui.label(context, eyebrow, 12, Ui.TERTIARY, false);
                label.setLetterSpacing(0.10f);
                card.addView(label);
            }

            if (notBlank(title)) {
                TextView heading = Ui.label(context, title, 22, Ui.LABEL, true);
                heading.setLineSpacing(Ui.dp(context, 3), 1f);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = Ui.dp(context, 8);
                card.addView(heading, params);
            }

            if (body != null && body.length() > 0) {
                TextView text = Ui.label(context, body, 15, Ui.SECONDARY, false);
                text.setLineSpacing(Ui.dp(context, 5), 1f);
                addScrolled(card, text, 330, "滚动查看全部");
            }

            if (extraView != null) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = Ui.dp(context, 16);
                card.addView(extraView, params);
            }

            if (itemLabels != null && itemLabels.length > 0) {
                LinearLayout list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < itemLabels.length; i++) {
                    final Action action = itemActions != null && i < itemActions.length ? itemActions[i] : null;
                    TextView row = Ui.menuRow(context, itemLabels[i]);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            dialog.dismiss();
                            if (action != null) action.run();
                        }
                    });
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                    if (i > 0) rowParams.topMargin = Ui.dp(context, 6);
                    list.addView(row, rowParams);
                    if (firstFocus[0] == null) firstFocus[0] = row;
                }
                addScrolled(card, list, 470, "还有更多，按上下键");
            }

            // 按钮区：默认给一个「知道了」，避免出现关不掉的空弹窗
            String okLabel = positiveLabel;
            final Action okAction = positiveAction;
            if (!notBlank(okLabel) && !notBlank(negativeLabel)) okLabel = "知道了";

            TextView okButton = null;
            if (notBlank(okLabel)) {
                okButton = Ui.pill(context, okLabel, Ui.PRIMARY);
                okButton.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        dialog.dismiss();
                        if (okAction != null) okAction.run();
                    }
                });
                firstFocus[0] = okButton;
            }
            TextView cancelButton = null;
            if (notBlank(negativeLabel)) {
                cancelButton = Ui.pill(context, negativeLabel, 0);
                cancelButton.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        dialog.dismiss();
                        if (negativeAction != null) negativeAction.run();
                    }
                });
                if (firstFocus[0] == null) firstFocus[0] = cancelButton;
            }
            if (okButton != null || cancelButton != null) {
                LinearLayout buttons = new LinearLayout(context);
                buttons.setOrientation(LinearLayout.HORIZONTAL);
                buttons.setGravity(Gravity.END);
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, -2);
                buttonParams.leftMargin = Ui.dp(context, 8);
                if (cancelButton != null) buttons.addView(cancelButton, buttonParams);
                if (okButton != null) buttons.addView(okButton, buttonParams);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = Ui.dp(context, 22);
                card.addView(buttons, params);
            }

            dialog.setContentView(card);
            dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
                @Override public void onShow(android.content.DialogInterface unused) {
                    Window window = dialog.getWindow();
                    if (window == null) return;
                    int screenDp = activity.getResources().getConfiguration().screenWidthDp;
                    int width = Math.min(640, Math.max(320, screenDp - 80));
                    window.setLayout(Ui.dp(activity, width), ViewGroup.LayoutParams.WRAP_CONTENT);
                    // 焦点交给主按钮（没有主按钮就是第一个条目），遥控器按一下就能继续
                    if (firstFocus[0] != null) firstFocus[0].requestFocus();
                }
            });

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.dimAmount = 0.62f;
                window.setAttributes(attributes);
            }
            dialog.show();
            return dialog;
        }

        /**
         * 把一段内容放进限高滚动区；内容超出时补一行提示 —— 电视上没有滚动条，
         * 用户看不出下面还有东西。
         */
        private void addScrolled(LinearLayout card, View content, int capDp, String hintText) {
            Context context = activity;
            final int cap = Ui.dp(context, capDp);
            CappedScroll scroll = new CappedScroll(context, cap);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(content, new LinearLayout.LayoutParams(-1, -2));

            final TextView hint = Ui.label(context, "↑ ↓ " + hintText, 11, Ui.TERTIARY, false);
            hint.setVisibility(View.GONE);

            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
            hintParams.topMargin = Ui.dp(context, 6);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = Ui.dp(context, 12);
            card.addView(scroll, params);
            card.addView(hint, hintParams);

            content.post(new Runnable() {
                @Override public void run() {
                    if (content.getHeight() > cap) hint.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    /** 内容长了就滚动，但别把整屏撑满（电视上要留出上下文）。 */
    private static final class CappedScroll extends ScrollView {
        private final int maxHeight;

        CappedScroll(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    private static boolean notBlank(String value) {
        return value != null && value.trim().length() > 0;
    }

    static Builder on(Activity activity) {
        return new Builder(activity);
    }

    private Dialogs() {}
}
