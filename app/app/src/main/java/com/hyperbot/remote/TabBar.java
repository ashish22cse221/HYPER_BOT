package com.hyperbot.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

/** Compact bottom nav: 26dp line icons, 62dp tall, active tab blue with a lime strip. */
final class TabBar extends LinearLayout {
    interface OnPick { void pick(int index); }

    private static final String[] NAMES = {"BOT", "PLAYLIST", "DRAW", "CONVERT"};
    private final Tab[] tabs = new Tab[NAMES.length];
    private int current = -1;

    TabBar(Context c, OnPick onPick) {
        super(c);
        setBackgroundColor(Brutal.INK);
        for (int i = 0; i < NAMES.length; i++) {
            Tab t = new Tab(c, i, NAMES[i]);
            final int index = i;
            t.setOnClickListener(v -> onPick.pick(index));
            addView(t, new LayoutParams(0, Brutal.dp(c, 62), 1));
            tabs[i] = t;
        }
    }

    void select(int index) {
        for (int i = 0; i < tabs.length; i++) tabs[i].setActive(i == index, i == index && index != current);
        current = index;
    }

    /** Slide up from the bottom edge on launch. */
    void enter() {
        if (!Motion.on()) return;
        post(() -> {
            setTranslationY(getHeight());
            animate().translationY(0).setStartDelay(200).setDuration(600).setInterpolator(Motion.OUT).start();
        });
    }

    private static final class Tab extends View {
        private final String label;
        private final Path icon = new Path();
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();
        private boolean active;
        private float pop = 1f, bar = 1f;

        Tab(Context c, int kind, String label) {
            super(c);
            this.label = label;
            setClickable(true);
            setFocusable(true);
            setContentDescription(label.charAt(0) + label.substring(1).toLowerCase());
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            stroke.setStrokeCap(Paint.Cap.SQUARE);
            stroke.setStrokeJoin(Paint.Join.MITER);
            stroke.setColor(Brutal.WHITE);
            text.setTypeface(Brutal.MONO_BOLD);
            text.setTextSize(Brutal.dp(c, 11));
            text.setLetterSpacing(0.06f);
            text.setColor(Brutal.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            buildIcon(kind);
        }

        /** Same 24-unit line icons as the design canvas. */
        private void buildIcon(int kind) {
            Path p = icon;
            switch (kind) {
                case 0: // bot
                    p.addRect(4, 7, 20, 19, Path.Direction.CW);
                    p.moveTo(12, 3); p.lineTo(12, 7);
                    p.moveTo(7.6f, 12); p.lineTo(8.4f, 12);
                    p.moveTo(15.6f, 12); p.lineTo(16.4f, 12);
                    p.moveTo(9, 16); p.lineTo(15, 16);
                    p.moveTo(2, 11); p.lineTo(2, 15);
                    p.moveTo(22, 11); p.lineTo(22, 15);
                    break;
                case 1: // playlist
                    p.moveTo(4, 6); p.lineTo(15, 6);
                    p.moveTo(4, 12); p.lineTo(15, 12);
                    p.moveTo(4, 18); p.lineTo(11, 18);
                    p.moveTo(17, 15); p.lineTo(17, 21); p.lineTo(21, 18); p.close();
                    break;
                case 2: // draw
                    p.moveTo(15, 4); p.lineTo(20, 9); p.lineTo(9, 20); p.lineTo(4, 20); p.lineTo(4, 15); p.close();
                    p.moveTo(13, 6); p.lineTo(18, 11);
                    break;
                default: // convert
                    p.moveTo(4, 8); p.lineTo(17, 8); p.lineTo(14, 5);
                    p.moveTo(20, 16); p.lineTo(7, 16); p.lineTo(10, 19);
                    break;
            }
        }

        void setActive(boolean on, boolean animate) {
            active = on;
            setSelected(on);
            if (on && animate && Motion.on()) {
                ValueAnimator a = ValueAnimator.ofFloat(0.5f, 1f);
                a.setDuration(450);
                a.setInterpolator(new OvershootInterpolator(2.5f));
                a.addUpdateListener(v -> { pop = (float) v.getAnimatedValue(); bar = Math.min(1f, v.getAnimatedFraction() * 1.6f); invalidate(); });
                a.start();
            } else {
                pop = 1f;
                bar = 1f;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            fill.setColor(active ? Brutal.BLUE : Brutal.INK);
            c.drawRect(0, 0, w, h, fill);
            if (active) {
                fill.setColor(Brutal.LIME);
                c.drawRect(0, 0, w * bar, Brutal.dp(getContext(), 3), fill);
            }
            float size = Brutal.dp(getContext(), 26);
            float gap = Brutal.dp(getContext(), 4);
            Paint.FontMetrics m = text.getFontMetrics();
            float textH = m.descent - m.ascent;
            float top = (h - (size + gap + textH)) / 2f;
            c.save();
            c.translate(w / 2f, top + size / 2f);
            float s = size / 24f * pop;
            c.scale(s, s);
            c.translate(-12, -12);
            c.drawPath(icon, stroke);
            c.restore();
            c.drawText(label, w / 2f, top + size + gap - m.ascent, text);
        }
    }
}
