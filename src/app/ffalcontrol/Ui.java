package app.ffalcontrol;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * aerial-tv (tvOS dark) 风格的最小实现：只用 framework API，不引第三方库。
 * 取色与排版取自 aerial-tv 的 Palette / Type（MIT）。
 */
final class Ui {

    static final int BG          = Color.parseColor("#000000");
    static final int LABEL       = Color.parseColor("#FFFFFF");
    static final int SECONDARY   = Color.parseColor("#99EBEBF5");
    static final int TERTIARY    = Color.parseColor("#4DEBEBF5");
    static final int GLASS       = Color.parseColor("#E6202022");
    static final int FILL        = Color.parseColor("#2E787880");
    static final int FILL_STRONG = Color.parseColor("#52787880");
    static final int HAIRLINE    = Color.parseColor("#1AFFFFFF");
    static final int GREEN       = Color.parseColor("#30D158");
    static final int ORANGE      = Color.parseColor("#FF9F0A");
    static final int RED         = Color.parseColor("#FF453A");
    static final int FOCUS       = Color.parseColor("#F5F5F7");
    static final int ON_FOCUS    = Color.parseColor("#1D1D1F");

    static final int PRIMARY = 1;
    static final int DANGER  = 2;

    private static final String DISPLAY = "sans-serif";
    private static final String TEXT = "sans-serif";

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable plate(int fill, int strokeColor, float radiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(fill);
        shape.setCornerRadius(radiusPx);
        if (strokeColor != 0) shape.setStroke(1, strokeColor);
        return shape;
    }

    /** 药丸按钮背景：常态半透明灰，聚焦态近白板。 */
    private static StateListDrawable pillBackground(Context context, int kind) {
        float radius = dp(context, 28);
        int fill = kind == PRIMARY ? FILL_STRONG : FILL;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, plate(FOCUS, 0, radius));
        states.addState(new int[]{android.R.attr.state_pressed}, plate(FOCUS, 0, radius));
        states.addState(new int[]{}, plate(fill, HAIRLINE, radius));
        return states;
    }

    private static ColorStateList pillTextColors(int kind) {
        int idle = kind == DANGER ? RED : kind == PRIMARY ? LABEL : SECONDARY;
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{ON_FOCUS, ON_FOCUS, idle});
    }

    /** 一个 tvOS 药丸按钮。可用 LinearLayout 的 weight 均分宽度。 */
    static TextView pill(Context context, CharSequence text, int kind) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        button.setTypeface(android.graphics.Typeface.create(TEXT, kind == PRIMARY
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        button.setTextColor(pillTextColors(kind));
        button.setBackground(pillBackground(context, kind));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 22), dp(context, 15), dp(context, 22), dp(context, 15));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        // 聚焦时轻微放大，模拟 tvOS 的抬起感
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                view.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(140).start();
            }
        });
        return button;
    }

    /** 卡片底色（毛玻璃 + hairline）。 */
    static GradientDrawable cardPlate(Context context) {
        return plate(GLASS, HAIRLINE, dp(context, 22));
    }

    /** 右上角小标签：胶囊底 + 彩色字。 */
    static TextView chip(Context context, CharSequence text, int color) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTextColor(color);
        chip.setTypeface(android.graphics.Typeface.create(TEXT, android.graphics.Typeface.BOLD));
        chip.setBackground(plate(FILL, HAIRLINE, dp(context, 20)));
        chip.setPadding(dp(context, 16), dp(context, 7), dp(context, 16), dp(context, 7));
        chip.setGravity(Gravity.CENTER);
        return chip;
    }

    /** 毛玻璃卡片：深灰底 + 1px 白描边 + 大圆角。 */
    static TextView glassCard(Context context) {
        TextView card = new TextView(context);
        card.setBackground(plate(GLASS, HAIRLINE, dp(context, 22)));
        card.setPadding(dp(context, 26), dp(context, 22), dp(context, 26), dp(context, 22));
        card.setTextColor(SECONDARY);
        card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        card.setLineSpacing(dp(context, 4), 1f);
        return card;
    }

    static TextView label(Context context, CharSequence text, float sizeSp, int color, boolean display) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTypeface(android.graphics.Typeface.create(display ? DISPLAY : TEXT,
                display ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        view.setLetterSpacing(display ? -0.02f : -0.005f);
        return view;
    }

    static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    static LinearLayout.LayoutParams weight(Context context) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int gap = dp(context, 4);
        params.setMargins(gap, 0, gap, 0);
        return params;
    }
}
