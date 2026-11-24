package com.hyperbot.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The one big button. It is also the progress bar: while an upload runs the
 * fill grows left to right and the label inverts where the fill passes under it.
 */
final class SendBar extends View {
    enum Mode { LOCKED, ARMED, RUNNING, DONE, FAILED }

    private Mode mode = Mode.LOCKED;
    private String label = "";
    private float progress;

    private final Paint face = new Paint();
    private final Paint shadow = new Paint();
    private final Paint edge = new Paint();
    private final Paint fill = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Brutal.Hatch hatch;
    private final float shadowOffset;
    private final float border;
    private final RectF box = new RectF();

    SendBar(Context context) {
        super(context);
        shadowOffset = Brutal.dp(context, 6);
        border = Brutal.dp(context, 3);
        shadow.setColor(Brutal.INK);
        edge.setColor(Brutal.INK);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Brutal.dp(context, 2));
        fill.setColor(Brutal.BLUE);
        text.setTypeface(Brutal.COND);
        text.setTextSize(Brutal.dp(context, 20));
        text.setLetterSpacing(0.04f);
        text.setTextAlign(Paint.Align.CENTER);
        hatch = new Brutal.Hatch(context, Brutal.PAPER, 0x33111111, Brutal.INK);
        setClickable(true);
        setFocusable(true);
    }

    void show(Mode next, String nextLabel, float nextProgress) {
        mode = next;
        label = nextLabel;
        progress = Math.max(0f, Math.min(1f, nextProgress));
        setEnabled(next == Mode.ARMED);
        setContentDescription(nextLabel);
        invalidate();
    }

    Mode mode() { return mode; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Brutal.dp(getContext(), 64) + (int) shadowOffset);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth() - shadowOffset;
        float h = getHeight() - shadowOffset;
        boolean pushed = isPressed() && mode == Mode.ARMED;
        float dx = pushed ? shadowOffset : 0;

        if (mode == Mode.LOCKED) {
            hatch.setBounds(0, 0, (int) w, (int) h);
            hatch.draw(canvas);
            drawLabel(canvas, 0, w, h, Brutal.INK);
            return;
        }

        if (!pushed) canvas.drawRect(shadowOffset, shadowOffset, w + shadowOffset, h + shadowOffset, shadow);
        box.set(dx, dx, w + dx, h + dx);

        switch (mode) {
            case ARMED:
                face.setColor(Brutal.LIME);
                canvas.drawRect(box, face);
                drawLabel(canvas, dx, w, h, Brutal.INK);
                break;
            case RUNNING: {
                face.setColor(Brutal.WHITE);
                canvas.drawRect(box, face);
                float split = box.left + box.width() * progress;
                canvas.drawRect(box.left, box.top, split, box.bottom, fill);
                // Ink label everywhere, then paper label clipped to the filled part.
                drawLabel(canvas, dx, w, h, Brutal.INK);
                canvas.save();
                canvas.clipRect(box.left, box.top, split, box.bottom);
                drawLabel(canvas, dx, w, h, Brutal.WHITE);
                canvas.restore();
                break;
            }
            case DONE:
                face.setColor(Brutal.INK);
                canvas.drawRect(box, face);
                drawLabel(canvas, dx, w, h, Brutal.LIME);
                break;
            case FAILED:
                face.setColor(Brutal.RED);
                canvas.drawRect(box, face);
                drawLabel(canvas, dx, w, h, Brutal.WHITE);
                break;
            default:
                break;
        }
        float half = border / 2f;
        canvas.drawRect(box.left + half, box.top + half, box.right - half, box.bottom - half, edge);
    }

    private void drawLabel(Canvas canvas, float dx, float w, float h, int color) {
        text.setColor(color);
        String shown = label;
        float max = w - Brutal.dp(getContext(), 24);
        if (text.measureText(shown) > max) {
            String base = label;
            while (base.length() > 1 && text.measureText(base + "…") > max) base = base.substring(0, base.length() - 1);
            shown = base + "…";
        }
        Paint.FontMetrics m = text.getFontMetrics();
        float baseline = dx + h / 2f - (m.ascent + m.descent) / 2f;
        canvas.drawText(shown, dx + w / 2f, baseline, text);
    }
}
