package com.hyperbot.remote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Renders a poke the way the bot's poke screen shows it. Android draws the
 * text (so emoji work), each pixel is thresholded to on/off, and the result is
 * packed into the SSD1306 page format the firmware's poke screen expects:
 * for each 8-row page, one byte per column, LSB = top row.
 */
final class PokeRenderer {
    static final int SENDER_H = 16;   // 2 pages, drawn at y = 15 on the bot
    static final int TEXT_H = 24;     // 3 pages, drawn just below the sender
    static final int MAX_W = 512;     // firmware limit; wider bitmaps scroll on the bot

    static final class Rendered {
        final byte[] senderPages;
        final int senderW;
        final byte[] textPages;
        final int textW;
        final Bitmap preview;         // 128x64, OLED colours, first screen only
        final boolean scrolls;
        Rendered(byte[] senderPages, int senderW, byte[] textPages, int textW, Bitmap preview, boolean scrolls) {
            this.senderPages = senderPages; this.senderW = senderW; this.textPages = textPages; this.textW = textW;
            this.preview = preview; this.scrolls = scrolls;
        }
    }

    private PokeRenderer() { }

    static Rendered render(String sender, String text) {
        boolean[][] s = rasterize(sender.isEmpty() ? "Phone" : sender, SENDER_H, Typeface.create("sans-serif-condensed", Typeface.BOLD), 15f);
        boolean[][] t = rasterize(text.isEmpty() ? "Poke!" : text, TEXT_H, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 21f);
        int sw = s[0].length, tw = t[0].length;
        return new Rendered(pack(s), sw, pack(t), tw, preview(s, t), sw > 128 || tw > 128);
    }

    /** Draw white text on black at the given height, then threshold. Index [row][col]. */
    private static boolean[][] rasterize(String text, int height, Typeface face, float size) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(face);
        paint.setTextSize(size);
        paint.setColor(Color.WHITE);
        Paint.FontMetrics m = paint.getFontMetrics();
        int width = Math.max(1, Math.min(MAX_W, (int) Math.ceil(paint.measureText(text)) + 2));
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        float baseline = (height - (m.descent - m.ascent)) / 2f - m.ascent;
        canvas.drawText(text, 1, baseline, paint);
        int[] px = new int[width * height];
        bmp.getPixels(px, 0, width, 0, 0, width, height);
        bmp.recycle();
        boolean[][] out = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = px[y * width + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                // Colour emoji: bright faces light up, their dark features stay off.
                out[y][x] = (0.299f * r + 0.587f * g + 0.114f * b) > 110;
            }
        }
        return out;
    }

    private static byte[] pack(boolean[][] img) {
        int h = img.length, w = img[0].length, pages = (h + 7) / 8;
        byte[] out = new byte[pages * w];
        for (int p = 0; p < pages; p++) {
            for (int x = 0; x < w; x++) {
                int v = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int y = p * 8 + bit;
                    if (y < h && img[y][x]) v |= 1 << bit;
                }
                out[p * w + x] = (byte) v;
            }
        }
        return out;
    }

    /** Mock of the bot's poke screen: title row, sender at y=15, message below. */
    private static Bitmap preview(boolean[][] sender, boolean[][] text) {
        Bitmap bmp = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Brutal.OLED_DARK);
        Paint title = new Paint();
        title.setColor(Brutal.OLED_LIT);
        title.setTypeface(Typeface.MONOSPACE);
        title.setTextSize(11f);
        canvas.drawText(">> Poke! <<", 4, 12, title);
        blit(bmp, sender, 15);
        blit(bmp, text, 15 + SENDER_H + 1);
        return bmp;
    }

    private static void blit(Bitmap bmp, boolean[][] img, int top) {
        for (int y = 0; y < img.length; y++) {
            int py = top + y;
            if (py >= 64) break;
            for (int x = 0; x < Math.min(128, img[0].length); x++) if (img[y][x]) bmp.setPixel(x, py, Brutal.OLED_LIT);
        }
    }
}
