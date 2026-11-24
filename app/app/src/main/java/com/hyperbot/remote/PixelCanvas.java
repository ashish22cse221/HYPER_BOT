package com.hyperbot.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Finger-drawing on a 128x64 grid of OLED pixels, with mirror symmetry and onion skin. */
final class PixelCanvas extends View {
    static final int W = 128, H = 64, MAX_FRAMES = 60;

    enum Tool { PEN, ERASE, FILL }

    interface Listener { void onCanvasChanged(); }

    private final List<boolean[]> frames = new ArrayList<>();
    private final ArrayDeque<boolean[]> undo = new ArrayDeque<>();
    private int current;
    private Tool tool = Tool.PEN;
    private int brush = 2;
    private boolean mirror = true;
    private boolean onion = true;
    private int lastX = -1, lastY = -1;
    private Listener listener;

    private final Paint lit = new Paint();
    private final Paint ghost = new Paint();
    private final Paint grid = new Paint();
    private final Paint axis = new Paint();

    PixelCanvas(Context context) {
        super(context);
        lit.setColor(Brutal.OLED_LIT);
        ghost.setColor(Brutal.OLED_GHOST);
        grid.setColor(0xFF1A1A1A);
        axis.setColor(0xFF2A2F3A);
        frames.add(new boolean[W * H]);
    }

    void setListener(Listener l) { listener = l; }
    void setTool(Tool t) { tool = t; }
    Tool tool() { return tool; }
    void setBrush(int b) { brush = Math.max(1, Math.min(4, b)); }
    int brush() { return brush; }
    void setMirror(boolean m) { mirror = m; invalidate(); }
    boolean mirror() { return mirror; }
    void setOnion(boolean o) { onion = o; invalidate(); }
    boolean onion() { return onion; }

    int frameCount() { return frames.size(); }
    int currentFrame() { return current; }
    boolean[] frame(int i) { return frames.get(i); }
    List<boolean[]> frames() { return frames; }

    void select(int i) {
        current = Math.max(0, Math.min(frames.size() - 1, i));
        undo.clear();
        invalidate();
        changed();
    }

    boolean addFrame(boolean copy) {
        if (frames.size() >= MAX_FRAMES) return false;
        frames.add(current + 1, copy ? frames.get(current).clone() : new boolean[W * H]);
        select(current + 1);
        return true;
    }

    void deleteFrame() {
        if (frames.size() == 1) { pushUndo(); java.util.Arrays.fill(frames.get(0), false); invalidate(); changed(); return; }
        frames.remove(current);
        select(Math.min(current, frames.size() - 1));
    }

    void load(List<boolean[]> source) {
        frames.clear();
        for (int i = 0; i < source.size() && i < MAX_FRAMES; i++) frames.add(source.get(i).clone());
        if (frames.isEmpty()) frames.add(new boolean[W * H]);
        select(0);
    }

    void undo() {
        if (undo.isEmpty()) return;
        frames.set(current, undo.pop());
        invalidate();
        changed();
    }

    void clearFrame() { pushUndo(); java.util.Arrays.fill(frames.get(current), false); invalidate(); changed(); }

    void invertFrame() {
        pushUndo();
        boolean[] f = frames.get(current);
        for (int i = 0; i < f.length; i++) f[i] = !f[i];
        invalidate();
        changed();
    }

    /** Shift the current frame by dx/dy pixels (wraps), handy for "move the eyes" frames. */
    void nudge(int dx, int dy) {
        pushUndo();
        boolean[] f = frames.get(current), out = new boolean[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            out[((y + dy + H) % H) * W + (x + dx + W) % W] = f[y * W + x];
        }
        frames.set(current, out);
        invalidate();
        changed();
    }

    private void pushUndo() {
        undo.push(frames.get(current).clone());
        while (undo.size() > 30) undo.removeLast();
    }

    private void changed() { if (listener != null) listener.onCanvasChanged(); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, width / 2);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Brutal.OLED_DARK);
        float cell = getWidth() / (float) W;
        if (onion && current > 0) drawFrame(c, frames.get(current - 1), cell, ghost);
        if (cell >= 5f) {
            for (int x = 8; x < W; x += 8) c.drawLine(x * cell, 0, x * cell, getHeight(), grid);
            for (int y = 8; y < H; y += 8) c.drawLine(0, y * cell, getWidth(), y * cell, grid);
        }
        if (mirror) c.drawLine(getWidth() / 2f, 0, getWidth() / 2f, getHeight(), axis);
        drawFrame(c, frames.get(current), cell, lit);
    }

    private void drawFrame(Canvas c, boolean[] f, float cell, Paint paint) {
        for (int y = 0; y < H; y++) {
            int x = 0;
            while (x < W) {                       // draw horizontal runs: far fewer rects
                if (!f[y * W + x]) { x++; continue; }
                int start = x;
                while (x < W && f[y * W + x]) x++;
                c.drawRect(start * cell, y * cell, x * cell, (y + 1) * cell, paint);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float cell = getWidth() / (float) W;
        int x = (int) (e.getX() / cell), y = (int) (e.getY() / cell);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);   // don't let the page scroll while drawing
                pushUndo();
                if (tool == Tool.FILL) {
                    boolean before = x >= 0 && x < W && y >= 0 && y < H && frames.get(current)[y * W + x];
                    fill(x, y);
                    int mx = W - 1 - x;
                    // Only mirror-fill if the mirrored spot is a separate region still at the old value.
                    if (mirror && mx >= 0 && mx < W && y >= 0 && y < H && frames.get(current)[y * W + mx] == before) fill(mx, y);
                } else {
                    stamp(x, y);
                }
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tool != Tool.FILL && lastX >= 0) line(lastX, lastY, x, y);
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastX = lastY = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                changed();
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void line(int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        while (true) {
            stamp(x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private void stamp(int cx, int cy) {
        boolean value = tool == Tool.PEN;
        boolean[] f = frames.get(current);
        int r0 = -(brush - 1) / 2, r1 = brush / 2;
        for (int dy = r0; dy <= r1; dy++) {
            for (int dx = r0; dx <= r1; dx++) {
                set(f, cx + dx, cy + dy, value);
                if (mirror) set(f, W - 1 - (cx + dx), cy + dy, value);
            }
        }
    }

    private static void set(boolean[] f, int x, int y, boolean v) {
        if (x >= 0 && x < W && y >= 0 && y < H) f[y * W + x] = v;
    }

    private void fill(int x, int y) {
        if (x < 0 || x >= W || y < 0 || y >= H) return;
        boolean[] f = frames.get(current);
        boolean target = f[y * W + x];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.push(y * W + x);
        while (!stack.isEmpty()) {
            int p = stack.pop();
            if (f[p] != target) continue;
            f[p] = !target;
            int px = p % W, py = p / W;
            if (px > 0) stack.push(p - 1);
            if (px < W - 1) stack.push(p + 1);
            if (py > 0) stack.push(p - W);
            if (py < H - 1) stack.push(p + W);
        }
    }
}
