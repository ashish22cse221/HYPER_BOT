package com.hyperbot.remote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small, dependency-free animated GIF decoder (GIF87a / GIF89a).
 * Handles global/local colour tables, transparency, interlacing and the
 * three disposal methods, and returns fully composited ARGB frames.
 */
final class GifDecoder {
    static final class Frame {
        final int[] argb;
        final int delayMs;
        Frame(int[] argb, int delayMs) { this.argb = argb; this.delayMs = delayMs; }
    }

    static final class Result {
        final int width;
        final int height;
        final List<Frame> frames;
        Result(int width, int height, List<Frame> frames) { this.width = width; this.height = height; this.frames = frames; }
    }

    /** Receives each composited frame as it is decoded; the array is reused, copy what you keep. */
    interface Sink {
        /** @return false to stop decoding. */
        boolean frame(int[] argb, int width, int height, int delayMs);
    }

    private static final int MAX_STACK = 4096;

    private final byte[] data;
    private int pos;

    private GifDecoder(byte[] data) { this.data = data; }

    static boolean looksLikeGif(byte[] bytes) {
        return bytes.length > 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
    }

    /** Decode up to {@code maxFrames} frames, keeping copies of all of them (small GIFs and tests). */
    static Result decode(byte[] bytes, int maxFrames) throws IOException {
        List<Frame> frames = new ArrayList<>();
        int[] size = new int[2];
        new GifDecoder(bytes).run(maxFrames, (argb, w, h, delay) -> {
            frames.add(new Frame(argb.clone(), delay));
            size[0] = w;
            size[1] = h;
            return true;
        });
        return new Result(size[0], size[1], frames);
    }

    /** Stream frames to {@code sink} without holding them all in memory. */
    static int stream(byte[] bytes, int maxFrames, Sink sink) throws IOException {
        return new GifDecoder(bytes).run(maxFrames, sink);
    }

    private int run(int maxFrames, Sink sink) throws IOException {
        if (!looksLikeGif(data)) throw new IOException("not a GIF");
        pos = 6;
        int width = u16();
        int height = u16();
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) throw new IOException("bad GIF size");
        int packed = u8();
        u8();                         // background colour index (we treat background as transparent)
        u8();                         // pixel aspect ratio
        int[] globalTable = (packed & 0x80) != 0 ? colorTable(1 << ((packed & 7) + 1)) : null;

        int[] canvas = new int[width * height];
        int[] previous = null;
        int emitted = 0;

        int delay = 0, disposal = 0, transparent = -1;
        int lastDisposal = 0, lastX = 0, lastY = 0, lastW = 0, lastH = 0;

        while (pos < data.length && emitted < maxFrames) {
            int block = u8();
            if (block == 0x3B) break;                       // trailer
            if (block == 0x21) {                            // extension
                int label = u8();
                if (label == 0xF9) {                        // graphic control extension
                    int size = u8();
                    int flags = u8();
                    delay = u16() * 10;
                    int index = u8();
                    pos += Math.max(0, size - 4);
                    skipSubBlocks();
                    disposal = (flags >> 2) & 7;
                    transparent = (flags & 1) != 0 ? index : -1;
                } else {
                    skipSubBlocks();
                }
                continue;
            }
            if (block != 0x2C) {
                if (emitted == 0) throw new IOException("corrupt GIF block 0x" + Integer.toHexString(block));
                break;                                       // tolerate junk after real frames
            }

            // Apply the previous frame's disposal before drawing this one.
            if (lastDisposal == 2) {
                clearRect(canvas, width, height, lastX, lastY, lastW, lastH);
            } else if (lastDisposal == 3 && previous != null) {
                System.arraycopy(previous, 0, canvas, 0, canvas.length);
            }

            int ix = u16(), iy = u16(), iw = u16(), ih = u16();
            int imagePacked = u8();
            boolean interlaced = (imagePacked & 0x40) != 0;
            int[] table = (imagePacked & 0x80) != 0 ? colorTable(1 << ((imagePacked & 7) + 1)) : globalTable;
            if (table == null) throw new IOException("GIF has no colour table");

            byte[] indices = lzw(iw * ih);
            if (disposal == 3) previous = canvas.clone();
            draw(canvas, width, height, indices, table, ix, iy, iw, ih, interlaced, transparent);

            emitted++;
            if (!sink.frame(canvas, width, height, delay <= 10 ? 100 : delay)) break;
            lastDisposal = disposal;
            lastX = ix; lastY = iy; lastW = iw; lastH = ih;
            delay = 0; disposal = 0; transparent = -1;
        }
        if (emitted == 0) throw new IOException("GIF has no frames");
        return emitted;
    }

    private static void clearRect(int[] canvas, int width, int height, int x, int y, int w, int h) {
        for (int row = Math.max(0, y); row < Math.min(height, y + h); row++) {
            Arrays.fill(canvas, row * width + Math.max(0, x), row * width + Math.min(width, x + w), 0);
        }
    }

    private static void draw(int[] canvas, int width, int height, byte[] indices, int[] table,
                             int ix, int iy, int iw, int ih, boolean interlaced, int transparent) {
        int pass = 0, step = interlaced ? 8 : 1, line = 0;
        for (int i = 0; i < ih; i++) {
            int row = i;
            if (interlaced) {
                if (line >= ih) {
                    pass++;
                    switch (pass) {
                        case 1: line = 4; step = 8; break;
                        case 2: line = 2; step = 4; break;
                        default: line = 1; step = 2; break;
                    }
                }
                row = line;
                line += step;
            }
            int y = iy + row;
            if (y < 0 || y >= height) continue;
            int src = i * iw;
            for (int x = 0; x < iw; x++) {
                int dx = ix + x;
                if (dx < 0 || dx >= width) continue;
                int index = indices[src + x] & 0xFF;
                if (index == transparent || index >= table.length) continue;
                canvas[y * width + dx] = table[index];
            }
        }
    }

    // ---------------------------------------------------------------- LZW ---

    private byte[] lzw(int pixelCount) throws IOException {
        int minCodeSize = u8();
        if (minCodeSize < 2 || minCodeSize > 11) throw new IOException("bad LZW code size");
        byte[] block = readSubBlocks();

        byte[] out = new byte[pixelCount];
        short[] prefix = new short[MAX_STACK];
        byte[] suffix = new byte[MAX_STACK];
        byte[] stack = new byte[MAX_STACK + 1];

        int clear = 1 << minCodeSize;
        int end = clear + 1;
        int available = clear + 2;
        int codeSize = minCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        int oldCode = -1;
        int first = 0;
        for (int code = 0; code < clear; code++) { prefix[code] = 0; suffix[code] = (byte) code; }

        int bits = 0, datum = 0, bi = 0, top = 0, outPos = 0;
        while (outPos < pixelCount) {
            if (top == 0) {
                while (bits < codeSize) {
                    if (bi >= block.length) { Arrays.fill(out, outPos, pixelCount, (byte) 0); return out; }
                    datum |= (block[bi++] & 0xFF) << bits;
                    bits += 8;
                }
                int code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;

                if (code == clear) {
                    codeSize = minCodeSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clear + 2;
                    oldCode = -1;
                    continue;
                }
                if (code == end || code > available) break;
                if (oldCode == -1) {
                    stack[top++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }
                int inCode = code;
                if (code == available) {          // KwKwK case
                    stack[top++] = (byte) first;
                    code = oldCode;
                }
                while (code > clear) {
                    if (top >= MAX_STACK) throw new IOException("LZW stack overflow");
                    stack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = suffix[code] & 0xFF;
                stack[top++] = (byte) first;
                if (available < MAX_STACK) {
                    prefix[available] = (short) oldCode;
                    suffix[available] = (byte) first;
                    available++;
                    if ((available & codeMask) == 0 && available < MAX_STACK) {
                        codeSize++;
                        codeMask += available;
                    }
                }
                oldCode = inCode;
            }
            out[outPos++] = stack[--top];
        }
        return out;
    }

    // ------------------------------------------------------------- helpers --

    private int[] colorTable(int entries) throws IOException {
        if (pos + entries * 3 > data.length) throw new IOException("truncated colour table");
        int[] table = new int[entries];
        for (int i = 0; i < entries; i++) {
            int r = data[pos++] & 0xFF, g = data[pos++] & 0xFF, b = data[pos++] & 0xFF;
            table[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return table;
    }

    private byte[] readSubBlocks() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (pos < data.length) {
            int size = u8();
            if (size == 0) break;
            if (pos + size > data.length) size = data.length - pos;
            out.write(data, pos, size);
            pos += size;
        }
        return out.toByteArray();
    }

    private void skipSubBlocks() throws IOException {
        while (pos < data.length) {
            int size = u8();
            if (size == 0) return;
            pos += size;
        }
    }

    private int u8() throws IOException {
        if (pos >= data.length) throw new IOException("truncated GIF");
        return data[pos++] & 0xFF;
    }

    private int u16() throws IOException { return u8() | (u8() << 8); }
}
