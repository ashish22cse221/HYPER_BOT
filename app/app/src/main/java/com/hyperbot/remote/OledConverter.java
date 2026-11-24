package com.hyperbot.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns decoded frames into what the 128x64 one-colour OLED can show.
 * Work happens in two stages so sliders stay fast: {@link #prepare} scales and
 * picks frames once (expensive), {@link #render} applies threshold, dithering
 * and inversion on every change (cheap).
 */
final class OledConverter {
    static final int W = 128, H = 64;

    enum Fit { FIT, FILL, STRETCH }
    enum Dither { NONE, ORDERED, DIFFUSE }

    static final class Prepared {
        final float[][] luma;     // per frame, W*H, 0..255
        final int[] delays;       // ms, after frame reduction
        final int sourceFrames;
        Prepared(float[][] luma, int[] delays, int sourceFrames) { this.luma = luma; this.delays = delays; this.sourceFrames = sourceFrames; }
        int frames() { return luma.length; }
    }

    static final class Options {
        Fit fit = Fit.FIT;
        Dither dither = Dither.NONE;
        int threshold = 128;
        boolean invert;
        float speed = 1f;
        int maxFrames = 60;
    }

    /** Decoded animation shrunk to at most 256 px per side, stored as luminance (alpha folded in). */
    static final class Source {
        static final int WORK = 256;
        final int width, height;
        final List<byte[]> luma = new ArrayList<>();
        final List<Integer> delays = new ArrayList<>();
        private final int srcW, srcH;

        Source(int srcW, int srcH) {
            this.srcW = srcW;
            this.srcH = srcH;
            float k = Math.min(1f, WORK / (float) Math.max(srcW, srcH));
            width = Math.max(1, Math.round(srcW * k));
            height = Math.max(1, Math.round(srcH * k));
        }

        int frames() { return luma.size(); }
        int sourceWidth() { return srcW; }
        int sourceHeight() { return srcH; }
        long durationMs() { long t = 0; for (int d : delays) t += d; return t; }

        void add(int[] argb, int delayMs) {
            byte[] out = new byte[width * height];
            if (width == srcW && height == srcH) {
                for (int i = 0; i < out.length; i++) out[i] = (byte) Math.round(lum(argb[i]));
            } else {
                for (int y = 0; y < height; y++) {
                    int y0 = y * srcH / height, y1 = Math.max(y0 + 1, (y + 1) * srcH / height);
                    for (int x = 0; x < width; x++) {
                        int x0 = x * srcW / width, x1 = Math.max(x0 + 1, (x + 1) * srcW / width);
                        float sum = 0;
                        for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) sum += lum(argb[yy * srcW + xx]);
                        out[y * width + x] = (byte) Math.round(sum / ((x1 - x0) * (y1 - y0)));
                    }
                }
            }
            luma.add(out);
            delays.add(delayMs);
        }

        static Source fromGif(byte[] gif, int maxFrames) throws java.io.IOException {
            Source[] holder = new Source[1];
            GifDecoder.stream(gif, maxFrames, (argb, w, h, delay) -> {
                if (holder[0] == null) holder[0] = new Source(w, h);
                holder[0].add(argb, delay);
                return true;
            });
            return holder[0];
        }

        static Source fromPixels(int[] argb, int w, int h) {
            Source source = new Source(w, h);
            source.add(argb, 1000);
            return source;
        }
    }

    private static final int[][] BAYER = {
            {0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

    /** Scale every kept frame to 128x64 luminance. Transparent pixels count as dark (OLED off). */
    static Prepared prepare(Source source, Fit fit, int maxFrames) {
        int n = source.frames();
        int keep = Math.max(1, Math.min(n, maxFrames));
        List<float[]> luma = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        // Evenly spaced frames; skipped frames donate their time so the loop keeps its speed.
        int taken = -1;
        for (int k = 0; k < keep; k++) {
            int index = (int) Math.floor((double) k * n / keep);
            if (index <= taken) continue;
            int next = k + 1 < keep ? (int) Math.floor((double) (k + 1) * n / keep) : n;
            int time = 0;
            for (int j = index; j < next; j++) time += source.delays.get(j);
            luma.add(scale(source.luma.get(index), source.width, source.height, fit));
            delays.add(time);
            taken = index;
        }
        int[] d = new int[delays.size()];
        for (int i = 0; i < d.length; i++) d[i] = delays.get(i);
        return new Prepared(luma.toArray(new float[0][]), d, n);
    }

    /** Average brightness of the first frame: a bright result usually means "draw dark lines lit", i.e. invert. */
    static boolean suggestInvert(Prepared prepared) {
        float[] f = prepared.luma[0];
        double sum = 0;
        for (float v : f) sum += v;
        return sum / f.length > 140;
    }

    /** Lit pixels per frame, true = pixel on. */
    static boolean[][] render(Prepared p, Options o) {
        boolean[][] out = new boolean[p.frames()][];
        for (int i = 0; i < out.length; i++) out[i] = threshold(p.luma[i], o);
        return out;
    }

    static int[] delays(Prepared p, Options o) {
        int[] d = new int[p.delays.length];
        for (int i = 0; i < d.length; i++) d[i] = Math.max(20, Math.min(65535, Math.round(p.delays[i] / o.speed)));
        return d;
    }

    private static boolean[] threshold(float[] luma, Options o) {
        boolean[] lit = new boolean[W * H];
        float[] work = o.dither == Dither.DIFFUSE ? new float[W * H] : null;
        for (int i = 0; i < luma.length; i++) {
            float v = o.invert ? 255f - luma[i] : luma[i];
            if (work != null) work[i] = v;
            else if (o.dither == Dither.ORDERED) {
                int x = i % W, y = i / W;
                // Shift the Bayer pattern around the chosen threshold.
                float bias = (BAYER[y & 3][x & 3] + 0.5f) / 16f - 0.5f;
                lit[i] = v + bias * 255f >= o.threshold;
            } else {
                lit[i] = v >= o.threshold;
            }
        }
        if (work != null) {                         // Floyd-Steinberg
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int i = y * W + x;
                    boolean on = work[i] >= o.threshold;
                    lit[i] = on;
                    float error = work[i] - (on ? 255f : 0f);
                    if (x + 1 < W) work[i + 1] += error * 7 / 16f;
                    if (y + 1 < H) {
                        if (x > 0) work[i + W - 1] += error * 3 / 16f;
                        work[i + W] += error * 5 / 16f;
                        if (x + 1 < W) work[i + W + 1] += error * 1 / 16f;
                    }
                }
            }
        }
        return lit;
    }

    /** Scale luminance into 128x64. Downscaling averages (smooth), upscaling uses nearest (keeps pixel art crisp). */
    static float[] scale(byte[] src, int sw, int sh, Fit fit) {
        float[] out = new float[W * H];
        float sx, sy, offX = 0, offY = 0;      // target pixels per source pixel
        if (fit == Fit.STRETCH) {
            sx = W / (float) sw;
            sy = H / (float) sh;
        } else {
            float s = fit == Fit.FIT ? Math.min(W / (float) sw, H / (float) sh) : Math.max(W / (float) sw, H / (float) sh);
            sx = sy = s;
            offX = (W - sw * s) / 2f;
            offY = (H - sh * s) / 2f;
        }
        for (int ty = 0; ty < H; ty++) {
            float y0 = (ty - offY) / sy, y1 = (ty + 1 - offY) / sy;
            for (int tx = 0; tx < W; tx++) {
                float x0 = (tx - offX) / sx, x1 = (tx + 1 - offX) / sx;
                if (x1 <= 0 || y1 <= 0 || x0 >= sw || y0 >= sh) continue;     // letterbox bars stay dark
                if (sx >= 1f && sy >= 1f) {
                    int px = clamp((int) ((x0 + x1) / 2f), sw), py = clamp((int) ((y0 + y1) / 2f), sh);
                    out[ty * W + tx] = src[py * sw + px] & 0xFF;
                    continue;
                }
                int ax = clamp((int) Math.floor(x0), sw), bx = clamp((int) Math.ceil(x1) - 1, sw);
                int ay = clamp((int) Math.floor(y0), sh), by = clamp((int) Math.ceil(y1) - 1, sh);
                float sum = 0;
                int count = 0;
                for (int y = ay; y <= by; y++) {
                    for (int x = ax; x <= bx; x++) { sum += src[y * sw + x] & 0xFF; count++; }
                }
                out[ty * W + tx] = count == 0 ? 0 : sum / count;
            }
        }
        return out;
    }

    private static int clamp(int v, int size) { return v < 0 ? 0 : (v >= size ? size - 1 : v); }

    private static float lum(int c) {
        int a = (c >>> 24) & 0xFF, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (0.299f * r + 0.587f * g + 0.114f * b) * a / 255f;
    }
}
