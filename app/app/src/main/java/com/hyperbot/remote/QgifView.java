package com.hyperbot.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

/** Plays a qgif the way the 0.96" OLED shows it: hard pixels, no smoothing. */
final class QgifView extends View {
    private Qgif gif;
    private final Paint paint = new Paint();
    private Bitmap bitmap;
    private int[] pixels;
    private int frame;
    private long nextFrameAt;
    private final Rect target = new Rect();
    private final int lit;
    private final int dark;
    private boolean scanline;
    private String emptyText;
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scan = new Paint();

    QgifView(Context context, int lit, int dark) {
        super(context);
        this.lit = lit;
        this.dark = dark;
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
    }

    /** Message drawn on the dark screen when there is nothing to play. */
    void setEmptyText(String text) { emptyText = text; invalidate(); }

    /** CRT-style lime sweep over the screen (used on the live mirror). */
    void setScanline(boolean on) { scanline = on && Motion.on(); invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float band = h * 0.28f;
        scan.setShader(new LinearGradient(0, 0, 0, band, new int[]{0x00C8FF2E, 0x1AC8FF2E, 0x00C8FF2E}, null, Shader.TileMode.CLAMP));
    }

    void setGif(Qgif next) {
        if (next == gif) { invalidate(); return; }
        gif = next;
        frame = 0;
        nextFrameAt = 0;
        if (gif != null && (bitmap == null || bitmap.getWidth() != gif.width || bitmap.getHeight() != gif.height)) {
            bitmap = Bitmap.createBitmap(gif.width, gif.height, Bitmap.Config.ARGB_8888);
            pixels = new int[gif.width * gif.height];
        }
        postInvalidateOnAnimation();
    }

    // Restart playback whenever the view comes back on screen (tab switch, scroll, app resume):
    // a hidden view stops scheduling frames, so without this it stays frozen or blank.
    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) { nextFrameAt = 0; postInvalidateOnAnimation(); }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        nextFrameAt = 0;
        postInvalidateOnAnimation();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Always 2:1, like the panel.
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, width / 2);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(dark);
        if (gif == null) {
            if (emptyText != null && !emptyText.isEmpty()) {
                emptyPaint.setColor(Brutal.OLED_GHOST);
                emptyPaint.setTypeface(Brutal.MONO_BOLD);
                emptyPaint.setTextAlign(Paint.Align.CENTER);
                emptyPaint.setTextSize(Brutal.dp(getContext(), 11));
                Paint.FontMetrics m = emptyPaint.getFontMetrics();
                canvas.drawText(emptyText, getWidth() / 2f, getHeight() / 2f - (m.ascent + m.descent) / 2f, emptyPaint);
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now >= nextFrameAt) {
            gif.render(frame, pixels, lit, dark);
            bitmap.setPixels(pixels, 0, gif.width, 0, 0, gif.width, gif.height);
            nextFrameAt = now + gif.delays[frame];
            frame = (frame + 1) % gif.frames;
        }
        // Integer scale where possible so pixels stay square and crisp.
        int scale = Math.max(1, Math.min(getWidth() / gif.width, getHeight() / gif.height));
        int w = gif.width * scale;
        int h = gif.height * scale;
        if (w < getWidth() * 0.8f) { w = getWidth(); h = getWidth() / 2; }
        int left = (getWidth() - w) / 2;
        int top = (getHeight() - h) / 2;
        target.set(left, top, left + w, top + h);
        canvas.drawBitmap(bitmap, null, target, paint);
        long wait = Math.max(33, nextFrameAt - SystemClock.uptimeMillis());
        if (scanline) {
            float band = getHeight() * 0.28f;
            float t = (now % 3200) / 3200f;
            float y = -band * 1.2f + t * (getHeight() + band * 2.4f);
            canvas.save();
            canvas.translate(0, y);
            canvas.drawRect(0, 0, getWidth(), band, scan);
            canvas.restore();
            wait = Math.min(wait, 40);
        }
        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
            postInvalidateDelayed(wait);
        }
    }
}
