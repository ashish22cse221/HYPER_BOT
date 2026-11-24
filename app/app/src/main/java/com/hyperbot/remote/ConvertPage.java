package com.hyperbot.remote;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tab 04: turn any GIF or picture from the phone into a bot animation, with a live preview. */
final class ConvertPage extends Page {
    private static final int[] FRAME_CAPS = {20, 40, 60, 120};
    private static final float[] SPEEDS = {0.5f, 1f, 2f};
    private static final int MAX_DECODE_FRAMES = 300;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OledConverter.Options options = new OledConverter.Options();
    private OledConverter.Source source;
    private OledConverter.Prepared prepared;
    private byte[] result;
    private int generation;

    private final TextView sourceInfo, resultInfo, thresholdLabel, status, sendButton;
    private final QgifView preview;
    private final EditText nameField;
    private final SeekBar threshold;
    private final List<TextView> fitChips = new ArrayList<>(), ditherChips = new ArrayList<>(),
            speedChips = new ArrayList<>(), capChips = new ArrayList<>();
    private TextView invertChip;
    private int capIndex = 2, speedIndex = 1;
    private final View controls;

    ConvertPage(MainActivity host) {
        super(host);
        Context c = host;
        setHero(new Hero(c, "04 / CONVERT", "ANY GIF", "→ 1-BIT.", "GIF · PHOTO → 128 × 64", "img/arch.webp", Brutal.VIOLET_LIGHT, Brutal.INK));
        add(Brutal.sectionHead(c, "01", "SOURCE", null), 22);
        add(Brutal.mono(c, "ANY GIF OR PICTURE FROM YOUR PHONE, SQUEEZED INTO 128 x 64 AND ONE COLOUR.", 10, Brutal.MUTE, false), 6);
        TextView pick = Brutal.button(c, "PICK A GIF OR PICTURE  ↗", true);
        pick.setOnClickListener(v -> host.pickImage());
        add(pick, 12);
        sourceInfo = Brutal.mono(c, "NOTHING LOADED YET.", 12, Brutal.INK, false);
        sourceInfo.setLineSpacing(0, 1.25f);
        add(sourceInfo, 10);

        preview = new QgifView(c, Brutal.OLED_LIT, Brutal.OLED_DARK);
        add(Brutal.oledFrame(c, preview), 12);
        resultInfo = Brutal.mono(c, "", 12, Brutal.INK, true);
        add(resultInfo, 8);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        controls = box;
        box.addView(Brutal.sectionHead(c, "02", "TUNE", null), Brutal.spaced(c, -1, -2, 26));
        box.addView(optionRow("FIT", new String[]{"FIT", "FILL", "STRETCH"}, fitChips, i -> { options.fit = OledConverter.Fit.values()[i]; reprepare(); }, 0), Brutal.spaced(c, -1, -2, 12));
        box.addView(optionRow("DITHER", new String[]{"NONE", "PATTERN", "SMOOTH"}, ditherChips, i -> { options.dither = OledConverter.Dither.values()[i]; rerender(); }, 0), Brutal.spaced(c, -1, -2, 10));

        LinearLayout thresholdRow = Brutal.row(c);
        thresholdRow.addView(Brutal.mono(c, "CUT", 11, Brutal.MUTE, true), new LinearLayout.LayoutParams(host.dp(64), -2));
        threshold = new SeekBar(c);
        threshold.setMax(255);
        threshold.setProgress(options.threshold);
        android.content.res.ColorStateList ink = android.content.res.ColorStateList.valueOf(Brutal.INK);
        threshold.setProgressTintList(android.content.res.ColorStateList.valueOf(Brutal.BLUE));
        threshold.setThumbTintList(ink);
        threshold.setProgressBackgroundTintList(ink);
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { options.threshold = Math.max(1, v); thresholdLabel.setText(String.valueOf(options.threshold)); if (user) rerender(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { rerender(); }
        });
        thresholdRow.addView(threshold, new LinearLayout.LayoutParams(0, -2, 1));
        thresholdLabel = Brutal.mono(c, String.valueOf(options.threshold), 12, Brutal.INK, true);
        thresholdRow.addView(thresholdLabel, new LinearLayout.LayoutParams(host.dp(40), -2));
        box.addView(thresholdRow, Brutal.spaced(c, -1, -2, 14));
        box.addView(Brutal.mono(c, "LOWER CUT = MORE PIXELS LIGHT UP.", 10, Brutal.MUTE, false), Brutal.spaced(c, -1, -2, 2));

        LinearLayout invertRow = Brutal.row(c);
        invertRow.addView(Brutal.mono(c, "INVERT", 11, Brutal.MUTE, true), new LinearLayout.LayoutParams(host.dp(64), -2));
        invertChip = Brutal.chip(c, "OFF", false);
        invertChip.setOnClickListener(v -> { options.invert = !options.invert; refreshChips(); rerender(); });
        invertRow.addView(invertChip, new LinearLayout.LayoutParams(host.dp(80), -2));
        box.addView(invertRow, Brutal.spaced(c, -1, -2, 12));

        box.addView(optionRow("SPEED", new String[]{"0.5x", "1x", "2x"}, speedChips, i -> { speedIndex = i; options.speed = SPEEDS[i]; rerender(); }, 1), Brutal.spaced(c, -1, -2, 12));
        box.addView(optionRow("FRAMES", new String[]{"20", "40", "60", "120"}, capChips, i -> { capIndex = i; options.maxFrames = FRAME_CAPS[i]; reprepare(); }, 2), Brutal.spaced(c, -1, -2, 10));
        box.addView(Brutal.mono(c, "FEWER FRAMES = SMALLER FILE (ABOUT 1 KB EACH). TIMING IS KEPT.", 10, Brutal.MUTE, false), Brutal.spaced(c, -1, -2, 2));

        box.addView(Brutal.sectionHead(c, "03", "KEEP IT", null), Brutal.spaced(c, -1, -2, 26));
        nameField = Brutal.field(c, "NAME", 30);
        box.addView(nameField, Brutal.spaced(c, -1, -2, 12));
        LinearLayout buttons = Brutal.row(c);
        TextView save = Brutal.button(c, "SAVE TO MINE", false);
        save.setOnClickListener(v -> save(false));
        sendButton = Brutal.button(c, "SEND + PLAY", true);
        sendButton.setOnClickListener(v -> save(true));
        buttons.addView(save, Brutal.weighted(c, 1, 10));
        buttons.addView(sendButton, Brutal.weighted(c, 1, 0));
        box.addView(buttons, Brutal.spaced(c, -1, -2, 12));
        status = Brutal.mono(c, "", 12, Brutal.INK, false);
        box.addView(status, Brutal.spaced(c, -1, -2, 10));
        add(box, 0);
        add(new Deco.Footer(c, "PICK", "TUNE", "SEND"), 26);
        options.maxFrames = FRAME_CAPS[capIndex];
        refreshChips();
        controls.setVisibility(View.GONE);
    }

    interface Choice { void pick(int index); }

    private View optionRow(String label, String[] names, List<TextView> into, Choice choice, int initial) {
        LinearLayout row = Brutal.row(host);
        row.addView(Brutal.mono(host, label, 11, Brutal.MUTE, true), new LinearLayout.LayoutParams(host.dp(64), -2));
        for (int i = 0; i < names.length; i++) {
            TextView chip = Brutal.chip(host, names[i], i == initial);
            final int index = i;
            chip.setOnClickListener(v -> { choice.pick(index); refreshChips(); });
            into.add(chip);
            row.addView(chip, Brutal.weighted(host, 1, i == names.length - 1 ? 0 : 6));
        }
        return row;
    }

    private void refreshChips() {
        for (int i = 0; i < fitChips.size(); i++) Brutal.setChip(fitChips.get(i), options.fit.ordinal() == i);
        for (int i = 0; i < ditherChips.size(); i++) Brutal.setChip(ditherChips.get(i), options.dither.ordinal() == i);
        for (int i = 0; i < speedChips.size(); i++) Brutal.setChip(speedChips.get(i), speedIndex == i);
        for (int i = 0; i < capChips.size(); i++) Brutal.setChip(capChips.get(i), capIndex == i);
        invertChip.setText(options.invert ? "ON" : "OFF");
        Brutal.setChip(invertChip, options.invert);
    }

    // ------------------------------------------------------------- pipeline

    void load(Uri uri) {
        String fileName = displayName(uri);
        sourceInfo.setText("READING " + fileName.toUpperCase(Locale.ROOT) + "…");
        final int gen = ++generation;
        worker.execute(() -> {
            try (InputStream in = host.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("could not open file");
                byte[] bytes = Library.readAll(in);
                OledConverter.Source s;
                if (GifDecoder.looksLikeGif(bytes)) {
                    s = OledConverter.Source.fromGif(bytes, MAX_DECODE_FRAMES);
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 1;
                    BitmapFactory.Options probe = new BitmapFactory.Options();
                    probe.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, probe);
                    while (probe.outWidth / o.inSampleSize > 1024 || probe.outHeight / o.inSampleSize > 1024) o.inSampleSize *= 2;
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    if (bmp == null) throw new java.io.IOException("not a picture this phone can read");
                    int[] px = new int[bmp.getWidth() * bmp.getHeight()];
                    bmp.getPixels(px, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
                    s = OledConverter.Source.fromPixels(px, bmp.getWidth(), bmp.getHeight());
                    bmp.recycle();
                }
                OledConverter.Prepared p = OledConverter.prepare(s, options.fit, options.maxFrames);
                boolean invert = OledConverter.suggestInvert(p);
                OledConverter.Source finalSource = s;
                main.post(() -> {
                    if (gen != generation) return;
                    source = finalSource;
                    prepared = p;
                    options.invert = invert;
                    refreshChips();
                    String base = fileName.replaceAll("\\.[^.]+$", "");
                    nameField.setText(base.length() > 30 ? base.substring(0, 30) : base);
                    sourceInfo.setText(String.format(Locale.ROOT, "%s\n%d FRAME%s / %d x %d / %.1f S%s",
                            fileName.toUpperCase(Locale.ROOT), s.frames(), s.frames() == 1 ? "" : "S",
                            s.sourceWidth(), s.sourceHeight(), s.durationMs() / 1000f,
                            s.frames() >= MAX_DECODE_FRAMES ? " (FIRST " + MAX_DECODE_FRAMES + " ONLY)" : ""));
                    controls.setVisibility(View.VISIBLE);
                    status.setText(invert ? "IT WAS MOSTLY BRIGHT, SO INVERT IS ON. FLIP IT IF IT LOOKS WRONG." : "");
                    status.setTextColor(Brutal.INK);
                    rerender();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (gen != generation) return;
                    sourceInfo.setText("COULD NOT READ THAT FILE: " + e.getMessage());
                });
            }
        });
    }

    private void reprepare() {
        if (source == null) return;
        final int gen = ++generation;
        final OledConverter.Fit fit = options.fit;
        final int cap = options.maxFrames;
        final OledConverter.Source s = source;
        resultInfo.setText("WORKING…");
        worker.execute(() -> {
            OledConverter.Prepared p = OledConverter.prepare(s, fit, cap);
            main.post(() -> { if (gen == generation) { prepared = p; rerender(); } });
        });
    }

    private final Runnable renderNow = this::renderInBackground;

    private void rerender() {
        if (prepared == null) return;
        main.removeCallbacks(renderNow);
        main.postDelayed(renderNow, 60);                   // debounce slider drags
    }

    private void renderInBackground() {
        final OledConverter.Prepared p = prepared;
        final OledConverter.Options o = copy(options);
        final int gen = ++generation;
        worker.execute(() -> {
            byte[] bytes = Qgif.encode(OledConverter.render(p, o), OledConverter.delays(p, o));
            Qgif q = Qgif.parse("convert.qgif", bytes);
            long loop = 0;
            for (int d : q.delays) loop += d;
            final long loopMs = loop;
            main.post(() -> {
                if (gen != generation) return;
                result = bytes;
                preview.setGif(q);
                resultInfo.setText(String.format(Locale.ROOT, "%d FRAMES / %d KB / %.1f S LOOP", q.frames, q.sizeKb(), loopMs / 1000f));
            });
        });
    }

    private static OledConverter.Options copy(OledConverter.Options o) {
        OledConverter.Options c = new OledConverter.Options();
        c.fit = o.fit; c.dither = o.dither; c.threshold = o.threshold; c.invert = o.invert; c.speed = o.speed; c.maxFrames = o.maxFrames;
        return c;
    }

    private void save(boolean andSend) {
        if (result == null) return;
        String title = nameField.getText().toString().trim();
        try {
            Qgif saved = host.library.saveMine(title.isEmpty() ? "converted" : title, result);
            host.libraryChanged();
            if (!andSend) {
                status.setText("SAVED AS " + saved.label() + ". IT'S IN PLAYLIST > LIBRARY > MINE.");
                status.setTextColor(Brutal.BLUE);
                return;
            }
            if (!canSend()) {
                status.setText("SAVED. CONNECT TO THE BOT TO SEND IT.");
                status.setTextColor(Brutal.INK);
                return;
            }
            if (host.link.sendSingle(new BotLink.Item(saved.name, saved.raw))) {
                host.startedSingleFrom(this);
                status.setText("SENDING " + saved.label());
                status.setTextColor(Brutal.INK);
            }
        } catch (Exception e) {
            status.setText("COULD NOT SAVE: " + e.getMessage());
            status.setTextColor(Brutal.RED);
        }
    }

    @Override void onBusyChanged() {
        if (sendButton == null) return;
        Brutal.styleButton(sendButton, host.link.isBusy() ? "BOT IS BUSY" : "SEND + PLAY  ↗", Brutal.LIME, Brutal.INK, !host.link.isBusy());
    }

    @Override void onProgress(BotLink.Kind kind, int part, int parts, String label, long partDone, long partSize, long allDone, long allSize) {
        status.setText("SENDING " + label + "  " + (partSize == 0 ? 0 : partDone * 100 / partSize) + "%");
    }

    @Override void onJobDone(BotLink.Kind kind, int count) {
        status.setText("ON THE BOT NOW. IT STAYS UNTIL THE NEXT ONE.");
        status.setTextColor(Brutal.BLUE);
    }

    @Override void onJobFailed(BotLink.Kind kind, String reason) {
        status.setText(reason);
        status.setTextColor(Brutal.RED);
    }

    void shutdown() { worker.shutdownNow(); }

    private String displayName(Uri uri) {
        try (Cursor cursor = host.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "picture" : last;
    }
}
