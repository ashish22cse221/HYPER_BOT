package com.hyperbot.remote;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cold-start screen: the angel, the wordmark and a lime loading bar, then it lifts away. */
final class Splash extends FrameLayout {
    private final Runnable onDone;
    private boolean finished;

    @SuppressWarnings("deprecation")
    Splash(Context c, Runnable onDone) {
        super(c);
        this.onDone = onDone;
        setBackgroundColor(Brutal.PAPER);
        setClickable(true);   // swallow touches while it's up
        // Edge-to-edge: keep the footer strip above the navigation bar.
        setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView angel = new ImageView(c);
        angel.setImageBitmap(Motion.art(c, "img/angel.webp"));
        angel.setAdjustViewBounds(true);
        angel.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        col.addView(angel, new LinearLayout.LayoutParams(-2, dp(300)));

        TextView word = Brutal.display(c, "HyperBot", 44);
        word.setLetterSpacing(-0.02f);
        col.addView(word, Brutal.spaced(c, -2, -2, 14));
        TextView tag = Brutal.mono(c, "TINY SCREEN // BIG MOOD", 10, Brutal.MUTE, true);
        tag.setLetterSpacing(0.08f);
        col.addView(tag, Brutal.spaced(c, -2, -2, 8));

        FrameLayout track = new FrameLayout(c);
        track.setBackground(Brutal.block(c, Brutal.WHITE, Brutal.INK, 2));
        View fill = new View(c);
        fill.setBackgroundColor(Brutal.LIME);
        fill.setPivotX(0);
        int pad = dp(2);
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(-1, -1);
        fillLp.setMargins(pad, pad, pad, pad);
        track.addView(fill, fillLp);
        col.addView(track, Brutal.spaced(c, dp(180), dp(14), 22));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        addView(col, lp);

        View strip = new Deco.Footer(c, "TINY", "SCREEN", "BIG MOOD");
        addView(strip, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        if (!Motion.on()) {
            postDelayed(this::finish, 500);
            return;
        }
        angel.setAlpha(0f);
        angel.setTranslationY(dp(24));
        angel.animate().alpha(1f).translationY(0).setDuration(800).setInterpolator(Motion.OUT).start();
        word.setAlpha(0f);
        word.setTranslationX(-dp(30));
        word.animate().alpha(1f).translationX(0).setStartDelay(250).setDuration(600).setInterpolator(Motion.OUT).start();
        tag.setAlpha(0f);
        tag.animate().alpha(1f).setStartDelay(500).setDuration(500).start();
        fill.setScaleX(0f);
        fill.animate().scaleX(1f).setStartDelay(350).setDuration(1100).setInterpolator(Motion.OUT)
                .withEndAction(this::lift).start();
    }

    private int dp(float v) { return Brutal.dp(getContext(), v); }

    private void lift() {
        animate().alpha(0f).translationY(-dp(40)).setDuration(380).setInterpolator(Motion.SNAP)
                .withEndAction(this::finish).start();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        onDone.run();
    }
}
