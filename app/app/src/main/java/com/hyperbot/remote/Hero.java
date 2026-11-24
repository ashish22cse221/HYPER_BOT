package com.hyperbot.remote;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Page opener from the design canvas: engraved classical art bleeding off the right edge,
 * a two-line Anton headline (second line on a colour block), a coordinate tag, a caption,
 * two bobbing shards and the scrolling Greek-key border underneath.
 */
final class Hero extends LinearLayout {
    private final FrameLayout stage;
    private final ImageView art;
    private final Headline line1, line2;
    private final TextView tag, caption;
    private final View shardA, shardB;
    private final Deco.Meander meander;
    private final List<Animator> loops = new ArrayList<>();

    /**
     * @param fill colour block behind line 2, or 0 for no block (line 2 is drawn in {@code ink})
     */
    Hero(Context c, String coord, String first, String second, String note, String artPath, int fill, int ink) {
        super(c);
        setOrientation(VERTICAL);
        stage = new FrameLayout(c);
        stage.setClipChildren(true);

        art = new ImageView(c);
        art.setImageBitmap(Motion.art(c, artPath));
        art.setAdjustViewBounds(true);
        art.setScaleType(ImageView.ScaleType.FIT_START);
        art.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(dp(205), -2, Gravity.END | Gravity.TOP);
        artLp.rightMargin = -dp(26);
        artLp.topMargin = -dp(12);
        stage.addView(art, artLp);

        shardA = shard(c, Brutal.LIME, 18, 18);
        FrameLayout.LayoutParams a = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.END | Gravity.TOP);
        a.rightMargin = dp(168);
        a.topMargin = dp(26);
        stage.addView(shardA, a);
        shardB = shard(c, Brutal.BLUE, 14, -12);
        FrameLayout.LayoutParams b = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.END | Gravity.BOTTOM);
        b.rightMargin = dp(22);
        b.bottomMargin = dp(30);
        stage.addView(shardB, b);

        tag = Brutal.mono(c, "[ " + coord + " ]", 8, Brutal.MUTE, true);
        tag.setLetterSpacing(0.06f);
        FrameLayout.LayoutParams t = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP);
        t.leftMargin = dp(18);
        t.topMargin = dp(14);
        stage.addView(tag, t);

        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        line1 = new Headline(c, first, 54, Brutal.INK, 0, 0);
        words.addView(line1, new LinearLayout.LayoutParams(-2, -2));
        line2 = new Headline(c, second, 54, ink, fill, fill != 0 ? 7 : 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2);
        l2.topMargin = dp(fill != 0 ? 8 : 10);
        words.addView(line2, l2);
        FrameLayout.LayoutParams w = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP);
        w.leftMargin = dp(18);
        w.topMargin = dp(36);
        stage.addView(words, w);

        caption = Brutal.mono(c, note, 9, Brutal.MUTE, true);
        caption.setLetterSpacing(0.06f);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM);
        cp.leftMargin = dp(18);
        cp.bottomMargin = dp(10);
        stage.addView(caption, cp);

        addView(stage, new LayoutParams(-1, dp(196)));
        meander = new Deco.Meander(c);
        addView(meander, new LayoutParams(-1, -2));
        setContentDescription(first + " " + second);
    }

    private int dp(float v) { return Brutal.dp(getContext(), v); }

    private static View shard(Context c, int color, int sizeDp, float rotation) {
        View v = new View(c);
        v.setBackground(Brutal.block(c, color, Brutal.INK, 2, 0, Brutal.INK));
        v.setElevation(0);
        v.setRotation(rotation);
        v.setTag(rotation);
        return v;
    }

    /** Entrance + idle loops. Safe to call every time the page is shown. */
    void play() {
        stop();
        if (!Motion.on()) return;
        if (stage.getWidth() == 0) { post(this::play); return; }

        art.setAlpha(0f);
        art.setTranslationX(dp(48));
        art.setScaleX(1.04f);
        art.setScaleY(1.04f);
        art.setTranslationY(0);
        art.animate().alpha(1f).translationX(0).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(900)
                .setInterpolator(Motion.OUT).withEndAction(this::startFloat).start();

        line1.setAlpha(0f);
        line1.setTranslationX(-dp(36));
        line1.animate().alpha(1f).translationX(0).setStartDelay(150).setDuration(700).setInterpolator(Motion.OUT).start();

        // Colour block wipes on from the left.
        final int w = Math.max(1, line2.getWidth()), h = Math.max(1, line2.getHeight());
        line2.setClipBounds(new Rect(0, 0, 0, h));
        ValueAnimator wipe = ValueAnimator.ofFloat(0f, 1f);
        wipe.setStartDelay(550);
        wipe.setDuration(600);
        wipe.setInterpolator(Motion.SNAP);
        wipe.addUpdateListener(a -> line2.setClipBounds(new Rect(0, 0, Math.round(w * (float) a.getAnimatedValue()), h)));
        wipe.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { line2.setClipBounds(null); }
            @Override public void onAnimationCancel(Animator animation) { line2.setClipBounds(null); }
        });
        wipe.start();
        loops.add(wipe);

        for (View v : new View[]{tag, caption}) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setStartDelay(800).setDuration(600).start();
        }

        bob(shardA, 900, 4500);
        bob(shardB, 1050, 5200);
        meander.start();
    }

    private void startFloat() {
        if (!Motion.on()) return;
        ObjectAnimator f = ObjectAnimator.ofFloat(art, View.TRANSLATION_Y, 0f, -dp(9));
        f.setDuration(3000);
        f.setRepeatMode(ValueAnimator.REVERSE);
        f.setRepeatCount(ValueAnimator.INFINITE);
        f.setInterpolator(new AccelerateDecelerateInterpolator());
        f.start();
        loops.add(f);
    }

    private void bob(View v, long delay, long period) {
        float r = (Float) v.getTag();
        v.setScaleX(0f);
        v.setScaleY(0f);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(500)
                .setInterpolator(new OvershootInterpolator(2.2f)).withEndAction(() -> {
                    ObjectAnimator loop = ObjectAnimator.ofPropertyValuesHolder(v,
                            PropertyValuesHolder.ofFloat(View.ROTATION, r, r + 24f),
                            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -dp(7)));
                    loop.setDuration(period / 2);
                    loop.setRepeatMode(ValueAnimator.REVERSE);
                    loop.setRepeatCount(ValueAnimator.INFINITE);
                    loop.setInterpolator(new AccelerateDecelerateInterpolator());
                    loop.start();
                    loops.add(loop);
                }).start();
    }

    /** Stop idle loops (page hidden) and leave everything in its resting place. */
    void stop() {
        for (Animator a : loops) a.cancel();
        loops.clear();
        meander.stop();
        for (View v : new View[]{art, line1, tag, caption, shardA, shardB}) v.animate().cancel();
        art.setAlpha(1f); art.setTranslationX(0); art.setTranslationY(0); art.setScaleX(1f); art.setScaleY(1f);
        line1.setAlpha(1f); line1.setTranslationX(0);
        line2.setClipBounds(null);
        tag.setAlpha(1f); caption.setAlpha(1f);
        for (View s : new View[]{shardA, shardB}) {
            s.setScaleX(1f); s.setScaleY(1f); s.setTranslationY(0); s.setRotation((Float) s.getTag());
        }
    }

    /**
     * Anton headline sized to the letters themselves (cap height), not the font's tall line box,
     * so two lines sit tight and nothing gets clipped or truncated. Optional colour block behind.
     */
    static final class Headline extends View {
        private final String text;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint block = new android.graphics.Paint();
        private final Rect bounds = new Rect();
        private final int pad;

        Headline(Context c, String text, float sizeSp, int ink, int fill, int padDp) {
            super(c);
            this.text = text.toUpperCase(java.util.Locale.ROOT);
            paint.setTypeface(Brutal.HEAD);
            paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp,
                    c.getResources().getDisplayMetrics()));
            paint.setColor(ink);
            block.setColor(fill);
            pad = Brutal.dp(c, padDp);
            paint.getTextBounds(this.text, 0, this.text.length(), bounds);
            setContentDescription(text);
        }

        @Override protected void onMeasure(int w, int h) {
            int width = (int) Math.ceil(paint.measureText(text)) + pad * 2;
            int height = bounds.height() + pad * 2;
            setMeasuredDimension(width, height);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (block.getColor() != 0) c.drawRect(0, 0, getWidth(), getHeight(), block);
            c.drawText(text, pad, pad - bounds.top, paint);
        }
    }
}
