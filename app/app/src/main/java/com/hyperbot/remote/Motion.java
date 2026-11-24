package com.hyperbot.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared motion for the classical-brutalism UI. Everything checks {@link #on()} first, so
 * the app stays still when the phone's "Remove animations" setting is on.
 */
final class Motion {
    private Motion() { }

    /** Fast out, soft landing: the same curve the design canvas uses. */
    static final Interpolator OUT = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    static final Interpolator SNAP = new PathInterpolator(0.7f, 0f, 0.2f, 1f);

    private static final Map<String, Bitmap> ART = new HashMap<>();

    static boolean on() { return ValueAnimator.areAnimatorsEnabled(); }

    /** Fade + rise into place. */
    static void rise(View v, long delay) {
        if (!on()) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(Brutal.dp(v.getContext(), 26));
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(620).setInterpolator(OUT).start();
    }

    /** Rise the children of a column one after another, starting at child {@code from}. */
    static void stagger(ViewGroup group, int from, long firstDelay, long step) {
        if (!on()) return;
        for (int i = from; i < group.getChildCount(); i++) {
            rise(group.getChildAt(i), firstDelay + Math.min(i - from, 8) * step);
        }
    }

    /** Engraved artwork from assets/img, decoded once. */
    static Bitmap art(Context c, String path) {
        Bitmap cached = ART.get(path);
        if (cached != null) return cached;
        try (InputStream in = c.getAssets().open(path)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b != null) ART.put(path, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
