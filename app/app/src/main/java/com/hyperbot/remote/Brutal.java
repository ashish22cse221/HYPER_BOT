package com.hyperbot.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * HyperBot design system: NOVA-style brutalism (paper, ink, electric blue, acid lime,
 * giant condensed headlines, mono microcopy) with a cyberpunk layer (violet gradient
 * ribbons, 8-point stars). Fonts: Anton, Syne, Space Grotesk, Space Mono (all SIL OFL,
 * licences in assets/fonts).
 */
final class Brutal {
    private Brutal() { }

    static final int PAPER = Color.rgb(243, 242, 238);
    static final int WHITE = Color.rgb(255, 255, 255);
    static final int INK = Color.rgb(11, 11, 12);
    static final int BLUE = Color.rgb(30, 64, 255);
    static final int LIME = Color.rgb(200, 255, 46);
    static final int YELLOW = LIME;                      // legacy name: the accent colour
    static final int VIOLET = Color.rgb(139, 92, 246);
    static final int VIOLET_LIGHT = Color.rgb(196, 132, 252);
    static final int RED = Color.rgb(255, 59, 48);
    static final int MUTE = Color.rgb(110, 110, 106);
    static final int OLED_LIT = Color.rgb(234, 242, 255);
    static final int OLED_DARK = Color.rgb(5, 5, 5);
    static final int OLED_GHOST = Color.rgb(44, 58, 92);

    /** Giant condensed headlines. */
    static Typeface HEAD = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    /** Wide wordmark / section titles. */
    static Typeface DISPLAY = Typeface.create("sans-serif-black", Typeface.NORMAL);
    /** Names, buttons. */
    static Typeface COND = Typeface.create("sans-serif-medium", Typeface.BOLD);
    static Typeface BODY = Typeface.DEFAULT;
    /** Microcopy labels. */
    static Typeface MONO = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
    static Typeface MONO_BOLD = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    /** Load the bundled fonts once; falls back to system fonts if anything is missing. */
    static void init(Context c) {
        HEAD = load(c, "fonts/anton.ttf", null, HEAD);
        DISPLAY = load(c, "fonts/syne.ttf", "'wght' 800", DISPLAY);
        COND = load(c, "fonts/spacegrotesk.ttf", "'wght' 700", COND);
        BODY = load(c, "fonts/spacegrotesk.ttf", "'wght' 500", BODY);
        MONO = load(c, "fonts/spacemono.ttf", null, MONO);
        MONO_BOLD = load(c, "fonts/spacemono_bold.ttf", null, MONO_BOLD);
    }

    private static Typeface load(Context c, String path, String variation, Typeface fallback) {
        try {
            Typeface.Builder b = new Typeface.Builder(c.getAssets(), path);
            if (variation != null) b.setFontVariationSettings(variation);
            Typeface t = b.build();
            return t != null ? t : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ drawables

    /** Flat face, solid border, optional hard shadow to the bottom-right. */
    static Drawable block(Context c, int fill, int border, int borderDp, int shadowDp, int shadowColor) {
        GradientDrawable face = new GradientDrawable();
        face.setColor(fill);
        if (borderDp > 0) face.setStroke(dp(c, borderDp), border);
        if (shadowDp <= 0) return face;
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(shadowColor);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{shadow, face});
        int s = dp(c, shadowDp);
        layers.setLayerInset(0, s, s, 0, 0);
        layers.setLayerInset(1, 0, 0, s, s);
        return layers;
    }


    /**
     * Block with a hard shadow that "presses into" its shadow: while pressed the shadow is
     * dropped and {@link #pressLift} slides the face onto where the shadow was.
     */
    static Drawable pressable(Context c, int fill, int borderDp, int shadowDp) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        GradientDrawable face = new GradientDrawable();
        face.setColor(fill);
        face.setStroke(dp(c, borderDp), INK);
        LayerDrawable pressed = new LayerDrawable(new Drawable[]{face});
        int s = dp(c, shadowDp);
        pressed.setLayerInset(0, 0, 0, s, s);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[0], block(c, fill, INK, borderDp, shadowDp, INK));
        return states;
    }

    static android.animation.StateListAnimator pressLift(Context c, int shadowDp) {
        float s = dp(c, shadowDp);
        android.animation.StateListAnimator sla = new android.animation.StateListAnimator();
        android.animation.ObjectAnimator down = android.animation.ObjectAnimator.ofPropertyValuesHolder((Object) null,
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, s),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, s));
        down.setDuration(70);
        android.animation.ObjectAnimator up = android.animation.ObjectAnimator.ofPropertyValuesHolder((Object) null,
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f));
        up.setDuration(120);
        sla.addState(new int[]{android.R.attr.state_pressed}, down);
        sla.addState(new int[0], up);
        return sla;
    }

    static Drawable block(Context c, int fill, int border, int borderDp) {
        return block(c, fill, border, borderDp, 0, INK);
    }

    /** Padding helper that leaves room for a hard shadow. */
    static void pad(View view, int allDp, int shadowDp) {
        Context c = view.getContext();
        int p = dp(c, allDp), s = dp(c, shadowDp);
        view.setPadding(p, p, p + s, p + s);
    }

    /** Diagonal hatching: things that exist but can't be used yet. */
    static final class Hatch extends Drawable {
        private final Paint fill = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint();
        private final float gap, border;

        Hatch(Context c, int background, int stripe, int borderColor) {
            fill.setColor(background);
            line.setColor(stripe);
            line.setStrokeWidth(dp(c, 1.5f));
            edge.setColor(borderColor);
            edge.setStyle(Paint.Style.STROKE);
            border = dp(c, 3);
            edge.setStrokeWidth(border);
            gap = dp(c, 9);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawRect(b, fill);
            canvas.save();
            canvas.clipRect(b);
            float h = b.height();
            for (float x = b.left - h; x < b.right; x += gap) canvas.drawLine(x, b.bottom, x + h, b.top, line);
            canvas.restore();
            float half = border / 2f;
            canvas.drawRect(b.left + half, b.top + half, b.right - half, b.bottom - half, edge);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @SuppressWarnings("deprecation")
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    // -------------------------------------------------------------- widgets

    static TextView mono(Context c, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(bold ? MONO_BOLD : MONO);
        view.setIncludeFontPadding(false);
        return view;
    }

    static TextView cond(Context c, String value, float sp, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(COND);
        view.setIncludeFontPadding(false);
        return view;
    }

    static TextView display(Context c, String value, float sp) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTypeface(DISPLAY);
        view.setTextSize(sp);
        view.setTextColor(INK);
        view.setIncludeFontPadding(false);
        return view;
    }

    /** Primary = acid lime with ink text (NOVA's "Buy"); secondary = white. Both ink-bordered. */
    static TextView button(Context c, String label, boolean primary) {
        TextView b = new TextView(c);
        b.setTypeface(COND);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setLetterSpacing(0.02f);
        b.setAllCaps(true);
        styleButton(b, label, primary ? LIME : WHITE, INK, true);
        return b;
    }

    static void styleButton(TextView b, String label, int fill, int ink, boolean enabled) {
        Context c = b.getContext();
        b.setText(label);
        b.setEnabled(enabled);
        if (enabled) {
            b.setTextColor(ink);
            b.setBackground(pressable(c, fill, 2, 4));
            b.setStateListAnimator(pressLift(c, 4));
        } else {
            b.setTextColor(MUTE);
            b.setBackground(new Hatch(c, PAPER, 0x22111111, MUTE));
            b.setStateListAnimator(null);
        }
        int s = enabled ? dp(c, 4) : 0;
        b.setPadding(dp(c, 14), dp(c, 12), dp(c, 14) + s, dp(c, 12) + s);
    }

    /** Small selectable rectangle used for filters, tools and segmented choices. */
    static TextView chip(Context c, String label, boolean on) {
        TextView chip = mono(c, label, 12, on ? PAPER : INK, true);
        chip.setGravity(Gravity.CENTER);
        setChip(chip, on);
        chip.setPadding(dp(c, 11), dp(c, 9), dp(c, 11), dp(c, 9));
        return chip;
    }

    static void setChip(TextView chip, boolean on) {
        chip.setTextColor(on ? WHITE : INK);
        chip.setBackground(block(chip.getContext(), on ? BLUE : WHITE, on ? BLUE : INK, 2));
        chip.setSelected(on);
    }

    static EditText field(Context c, String hint, int maxLength) {
        EditText field = new EditText(c);
        field.setHint(hint);
        field.setHintTextColor(MUTE);
        field.setTextColor(INK);
        field.setTypeface(MONO);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setBackground(block(c, WHITE, INK, 2));
        field.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        if (maxLength > 0) field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
        return field;
    }

    /** "01  TITLE ............ right" section header. */
    static LinearLayout sectionHead(Context c, String number, String title, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = mono(c, number, 12, WHITE, true);
        num.setBackgroundColor(BLUE);
        num.setGravity(Gravity.CENTER);
        row.addView(num, new LinearLayout.LayoutParams(dp(c, 32), dp(c, 28)));
        TextView name = display(c, title, 21);
        name.setPadding(dp(c, 10), 0, dp(c, 6), 0);
        row.addView(name);
        Deco.Star star = new Deco.Star(c, false);
        row.addView(star, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
        row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        if (right != null) row.addView(right);
        return row;
    }

    /** Bordered panel with a hard shadow; content goes inside the returned layout. */
    static LinearLayout panel(Context c, int shadowDp) {
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(block(c, WHITE, INK, 2, shadowDp, INK));
        pad(panel, 14, shadowDp);
        return panel;
    }

    static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    static LinearLayout.LayoutParams spaced(Context c, int width, int height, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    static LinearLayout.LayoutParams weighted(Context c, float weight, int rightMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        lp.rightMargin = dp(c, rightMarginDp);
        return lp;
    }

    /** Black OLED frame that holds a preview at 2:1. */
    static LinearLayout oledFrame(Context c, View screen) {
        LinearLayout frame = new LinearLayout(c);
        frame.setBackgroundColor(INK);
        int p = dp(c, 3);
        frame.setPadding(p, p, p, p);
        frame.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        return frame;
    }

    /**
     * NOVA-style page opener: giant two-line condensed headline, a crosshair, and a
     * three-line mono note on the right.
     */
    static View hero(Context c, String line1, String line2, String note) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.BOTTOM);
        TextView head = new TextView(c);
        head.setText(line1 + "\n" + line2);
        head.setTypeface(HEAD);
        head.setTextSize(54);
        head.setTextColor(INK);
        head.setIncludeFontPadding(false);
        head.setLineSpacing(0, 0.86f);
        row.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        TextView side = mono(c, note, 10, INK, false);
        side.setLineSpacing(0, 1.25f);
        side.setPadding(dp(c, 8), 0, 0, dp(c, 6));
        row.addView(side);
        frame.addView(row, new android.widget.FrameLayout.LayoutParams(-1, -2));
        Deco.Crosshair cross = new Deco.Crosshair(c);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dp(c, 40), dp(c, 40));
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.rightMargin = dp(c, 18);
        frame.addView(cross, lp);
        return frame;
    }

    /** Solid block with a giant value inside, like NOVA's blue balance bar. */
    static TextView valueBlock(Context c, String value, int fill, int ink) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTypeface(HEAD);
        v.setTextSize(40);
        v.setTextColor(ink);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setBackgroundColor(fill);
        v.setPadding(dp(c, 10), dp(c, 6), dp(c, 14), dp(c, 8));
        return v;
    }
}
