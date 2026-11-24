package com.hyperbot.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.animation.ValueAnimator;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** The cyberpunk layer: 8-point stars, gradient name ribbons, crosshairs, marquee footers. */
final class Deco {
    private Deco() { }

    /** Eight-point sparkle star, violet-to-blue gradient (or solid ink). */
    static final class Star extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean ink;

        Star(Context c, boolean ink) {
            super(c);
            this.ink = ink;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            path.reset();
            float cx = w / 2f, cy = h / 2f, outer = Math.min(w, h) / 2f, inner = outer * 0.22f;
            for (int i = 0; i < 16; i++) {
                double a = Math.PI / 8 * i - Math.PI / 2;
                float r = (i % 2 == 0) ? (i % 4 == 0 ? outer : outer * 0.62f) : inner;
                float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            if (ink) paint.setColor(Brutal.INK);
            else paint.setShader(new LinearGradient(0, 0, w, h, Brutal.VIOLET_LIGHT, Brutal.BLUE, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) { c.drawPath(path, paint); }
    }

    /** Thin "+" registration mark. */
    static final class Crosshair extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Crosshair(Context c) {
            super(c);
            paint.setColor(Brutal.INK);
            paint.setStrokeWidth(Brutal.dp(c, 1.2f));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawLine(w / 2f, 0, w / 2f, h, paint);
            c.drawLine(0, h / 2f, w, h / 2f, paint);
        }
    }

    /** Gradient band repeating "HYPERBOT ✶" in black, like the pack's side ribbons. */
    static final class Ribbon extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint band = new Paint();
        private final Path star = new Path();
        private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String word;
        private ValueAnimator marquee;
        private float phase;

        Ribbon(Context c, String word) {
            super(c);
            this.word = word;
            text.setTypeface(Brutal.DISPLAY);
            text.setTextSize(Brutal.dp(c, 15));
            text.setColor(Brutal.INK);
            text.setLetterSpacing(0.08f);
            starPaint.setColor(Brutal.INK);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Brutal.dp(getContext(), 34));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            band.setShader(new LinearGradient(0, 0, w, 0, Brutal.BLUE, Brutal.VIOLET_LIGHT, Shader.TileMode.CLAMP));
        }

        private float unit() {
            float gap = Brutal.dp(getContext(), 12), s = Brutal.dp(getContext(), 6);
            return text.measureText(word) + gap + s * 2 + gap;
        }

        /** Endless sideways scroll, one word-and-star unit every ~2.6 s. */
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!Motion.on()) return;
            marquee = ValueAnimator.ofFloat(0f, 1f);
            marquee.setDuration(2600);
            marquee.setRepeatCount(ValueAnimator.INFINITE);
            marquee.setInterpolator(new LinearInterpolator());
            marquee.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); if (isShown()) invalidate(); });
            marquee.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (marquee != null) marquee.cancel();
            marquee = null;
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), band);
            Paint.FontMetrics m = text.getFontMetrics();
            float baseline = getHeight() / 2f - (m.ascent + m.descent) / 2f;
            float gap = Brutal.dp(getContext(), 12), s = Brutal.dp(getContext(), 6);
            float x = Brutal.dp(getContext(), 10) - unit() * (1f + phase);
            while (x < getWidth()) {
                c.drawText(word, x, baseline, text);
                x += text.measureText(word) + gap;
                drawStar(c, x, getHeight() / 2f, s);
                x += s * 2 + gap;
            }
        }

        private void drawStar(Canvas c, float cx, float cy, float r) {
            star.reset();
            for (int i = 0; i < 16; i++) {
                double a = Math.PI / 8 * i - Math.PI / 2;
                float rr = (i % 2 == 0) ? (i % 4 == 0 ? r : r * 0.6f) : r * 0.2f;
                float px = cx + r + (float) Math.cos(a) * rr, py = cy + (float) Math.sin(a) * rr;
                if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
            }
            star.close();
            c.drawPath(star, starPaint);
        }
    }

    /** NOVA footer strip: "TINY ——— SCREEN ——— BIG MOOD  →" on electric blue. */
    static final class Footer extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint();
        private final String[] words;
        private ValueAnimator nudge;
        private float shift;

        Footer(Context c, String... words) {
            super(c);
            this.words = words;
            text.setTypeface(Brutal.MONO);
            text.setTextSize(Brutal.dp(c, 10));
            text.setColor(Brutal.WHITE);
            text.setLetterSpacing(0.2f);
            line.setColor(Brutal.WHITE);
            line.setStrokeWidth(Brutal.dp(c, 1));
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Brutal.dp(getContext(), 38));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!Motion.on()) return;
            nudge = ValueAnimator.ofFloat(0f, Brutal.dp(getContext(), 6));
            nudge.setDuration(600);
            nudge.setRepeatMode(ValueAnimator.REVERSE);
            nudge.setRepeatCount(ValueAnimator.INFINITE);
            nudge.addUpdateListener(a -> { shift = (float) a.getAnimatedValue(); if (isShown()) invalidate(); });
            nudge.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (nudge != null) nudge.cancel();
            nudge = null;
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(Brutal.BLUE);
            float pad = Brutal.dp(getContext(), 12), arrow = Brutal.dp(getContext(), 22);
            float y = getHeight() / 2f;
            Paint.FontMetrics m = text.getFontMetrics();
            float baseline = y - (m.ascent + m.descent) / 2f;
            float textW = 0;
            for (String w : words) textW += text.measureText(w);
            float free = getWidth() - pad * 2 - arrow - textW;
            float seg = Math.max(0, free / Math.max(1, words.length)) ;
            float x = pad;
            for (String w : words) {
                c.drawText(w, x, baseline, text);
                x += text.measureText(w) + Brutal.dp(getContext(), 6);
                c.drawLine(x, y, x + seg - Brutal.dp(getContext(), 12), y, line);
                x += seg - Brutal.dp(getContext(), 6);
            }
            float ax = getWidth() - pad - arrow + shift;
            c.drawLine(ax, y, ax + arrow, y, line);
            c.drawLine(ax + arrow, y, ax + arrow - Brutal.dp(getContext(), 7), y - Brutal.dp(getContext(), 6), line);
            c.drawLine(ax + arrow, y, ax + arrow - Brutal.dp(getContext(), 7), y + Brutal.dp(getContext(), 6), line);
        }
    }

    /** Greek-key border between ink rules; scrolls sideways while its hero is on screen. */
    static final class Meander extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rule = new Paint();
        private final Path key = new Path();
        private ValueAnimator scroll;
        private float phase;

        Meander(Context c) {
            super(c);
            line.setColor(Brutal.INK);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Brutal.dp(c, 2.5f));
            line.setStrokeCap(Paint.Cap.SQUARE);
            line.setStrokeJoin(Paint.Join.MITER);
            rule.setColor(Brutal.INK);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Brutal.dp(getContext(), 32));
        }

        void start() {
            stop();
            if (!Motion.on()) return;
            scroll = ValueAnimator.ofFloat(0f, 1f);
            scroll.setDuration(2400);
            scroll.setRepeatCount(ValueAnimator.INFINITE);
            scroll.setInterpolator(new LinearInterpolator());
            scroll.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
            scroll.start();
        }

        void stop() {
            if (scroll != null) scroll.cancel();
            scroll = null;
        }

        @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

        @Override protected void onDraw(Canvas c) {
            float u = Brutal.dp(getContext(), 1), b = Brutal.dp(getContext(), 3);
            int w = getWidth(), h = getHeight();
            c.drawColor(Brutal.PAPER);
            c.drawRect(0, 0, w, b, rule);
            c.drawRect(0, h - b, w, h, rule);
            float unit = 40 * u, top = b;
            key.reset();
            for (float x = -unit * (1f + phase); x < w + unit; x += unit) {
                key.moveTo(x, top + 24 * u);
                key.lineTo(x + 34 * u, top + 24 * u);
                key.lineTo(x + 34 * u, top + 2 * u);
                key.lineTo(x + 6 * u, top + 2 * u);
                key.lineTo(x + 6 * u, top + 18 * u);
                key.lineTo(x + 28 * u, top + 18 * u);
                key.lineTo(x + 28 * u, top + 8 * u);
                key.lineTo(x + 12 * u, top + 8 * u);
                key.lineTo(x + 12 * u, top + 13 * u);
                key.lineTo(x + 22 * u, top + 13 * u);
            }
            c.drawPath(key, line);
        }
    }
}
