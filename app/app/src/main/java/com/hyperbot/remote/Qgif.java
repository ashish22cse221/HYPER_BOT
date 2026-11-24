package com.hyperbot.remote;

import java.util.Locale;

/** A parsed animation: frames(1) width(2) height(2) delays(frames*2) bitmaps(frames*1024), bit 1 = pixel off. */
final class Qgif {
    static final int W = 128, H = 64, FRAME_BYTES = W * H / 8, MAX_FRAMES = 255;

    final String name;
    final byte[] raw;
    final int frames;
    final int width;
    final int height;
    final int[] delays;
    private final int dataOffset;

    private Qgif(String name, byte[] raw, int frames, int width, int height, int[] delays) {
        this.name = name;
        this.raw = raw;
        this.frames = frames;
        this.width = width;
        this.height = height;
        this.delays = delays;
        this.dataOffset = 5 + frames * 2;
    }

    static Qgif parse(String name, byte[] bytes) throws IllegalArgumentException {
        if (bytes.length < 5) throw new IllegalArgumentException(name + ": too small");
        int frames = bytes[0] & 0xFF;
        int width = (bytes[1] & 0xFF) | ((bytes[2] & 0xFF) << 8);
        int height = (bytes[3] & 0xFF) | ((bytes[4] & 0xFF) << 8);
        long expected = 5L + frames * 2L + (long) frames * (width * height / 8);
        if (frames < 1 || width != W || height != H || bytes.length != expected) {
            throw new IllegalArgumentException(name + ": not a 128x64 animation");
        }
        int[] delays = new int[frames];
        for (int i = 0; i < frames; i++) {
            int at = 5 + i * 2;
            delays[i] = Math.max(20, (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8));
        }
        return new Qgif(name, bytes, frames, width, height, delays);
    }

    /** Build the file bytes from lit-pixel frames (true = on). */
    static byte[] encode(boolean[][] lit, int[] delays) {
        int n = Math.min(lit.length, MAX_FRAMES);
        byte[] out = new byte[5 + n * 2 + n * FRAME_BYTES];
        out[0] = (byte) n;
        out[1] = (byte) (W & 0xFF); out[2] = (byte) (W >> 8);
        out[3] = (byte) (H & 0xFF); out[4] = (byte) (H >> 8);
        for (int i = 0; i < n; i++) {
            int d = Math.max(20, Math.min(65535, delays[i]));
            out[5 + i * 2] = (byte) (d & 0xFF);
            out[6 + i * 2] = (byte) (d >> 8);
        }
        int base = 5 + n * 2;
        for (int f = 0; f < n; f++) {
            boolean[] frame = lit[f];
            int at = base + f * FRAME_BYTES;
            for (int p = 0; p < W * H; p++) {
                if (!frame[p]) out[at + (p >> 3)] |= (byte) (0x80 >> (p & 7));   // dark = 1
            }
        }
        return out;
    }

    boolean[] litFrame(int index) {
        boolean[] lit = new boolean[W * H];
        int base = dataOffset + index * FRAME_BYTES;
        for (int p = 0; p < lit.length; p++) lit[p] = ((raw[base + (p >> 3)] >> (7 - (p & 7))) & 1) == 0;
        return lit;
    }

    /** Write frame {@code index} into {@code pixels} (width*height ARGB). */
    void render(int index, int[] pixels, int litColor, int darkColor) {
        int base = dataOffset + index * FRAME_BYTES;
        for (int p = 0; p < pixels.length; p++) {
            boolean on = ((raw[base + (p >> 3)] >> (7 - (p & 7))) & 1) == 0;
            pixels[p] = on ? litColor : darkColor;
        }
    }

    int sizeKb() { return Math.max(1, Math.round(raw.length / 1024f)); }

    String label() {
        String base = name.replace(".qgif", "");
        if (base.startsWith("my_")) base = base.substring(3);
        return base.replace('_', ' ').replace('-', ' ').toUpperCase(Locale.ROOT);
    }

    String category() {
        if (name.startsWith("my_")) return "MINE";
        String base = name.replace(".qgif", "");
        int split = base.indexOf('_');
        if (split <= 0) return "OTHER";
        String head = base.substring(0, split).toUpperCase(Locale.ROOT);
        switch (head) {
            case "ACTION": case "EMOTION": case "EYE": case "EFFECT": case "THEME": return head;
            default: return "OTHER";
        }
    }

    /** Label without the category prefix: "emotion_love_02" -> "LOVE 02". */
    String shortLabel() {
        String label = label();
        String category = category();
        if (!category.equals("OTHER") && !category.equals("MINE") && label.startsWith(category + " ")) {
            return label.substring(category.length() + 1);
        }
        return label;
    }
}
