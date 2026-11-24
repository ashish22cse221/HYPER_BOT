package com.hyperbot.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tab 03: draw a face frame by frame, save it, or send it to play right away. */
final class DrawPage extends Page implements PixelCanvas.Listener {
    private static final int[] FRAME_TIMES = {60, 80, 100, 150, 200, 300, 500, 800};

    private final PixelCanvas canvas;
    private final QgifView preview;
    private final LinearLayout frameStrip;
    private final TextView frameLabel, timeLabel, status, sendButton;
    private final EditText nameField;
    private final List<TextView> toolChips = new ArrayList<>();
    private TextView sizeChip, mirrorChip, onionChip;
    private int timeIndex = 2;

    DrawPage(MainActivity host) {
        super(host);
        Context c = host;
        frameLabel = Brutal.mono(c, "", 13, Brutal.INK, true);
        setHero(new Hero(c, "03 / DRAW", "DRAW", "A FACE.", "128 × 64 · ONE PIXEL = ONE OLED DOT", "img/winged.webp", Brutal.BLUE, Brutal.WHITE));
        add(Brutal.sectionHead(c, "01", "CANVAS", frameLabel), 22);
        add(Brutal.mono(c, "128 x 64, ONE COLOUR, LIKE THE BOT'S SCREEN.", 10, Brutal.MUTE, false), 6);

        canvas = new PixelCanvas(c);
        canvas.setListener(this);
        add(Brutal.oledFrame(c, canvas), 10);

        // tools
        LinearLayout tools = Brutal.row(c);
        for (PixelCanvas.Tool t : PixelCanvas.Tool.values()) {
            TextView chip = Brutal.chip(c, t.name(), t == canvas.tool());
            chip.setOnClickListener(v -> { canvas.setTool(t); refreshTools(); });
            toolChips.add(chip);
            tools.addView(chip, Brutal.weighted(c, 1, 6));
        }
        sizeChip = Brutal.chip(c, "", false);
        sizeChip.setOnClickListener(v -> { canvas.setBrush(canvas.brush() % 4 + 1); refreshTools(); });
        tools.addView(sizeChip, Brutal.weighted(c, 1, 0));
        add(tools, 12);

        LinearLayout toggles = Brutal.row(c);
        mirrorChip = Brutal.chip(c, "MIRROR", true);
        mirrorChip.setOnClickListener(v -> { canvas.setMirror(!canvas.mirror()); refreshTools(); });
        onionChip = Brutal.chip(c, "ONION", true);
        onionChip.setOnClickListener(v -> { canvas.setOnion(!canvas.onion()); refreshTools(); });
        TextView undo = Brutal.chip(c, "UNDO", false);
        undo.setOnClickListener(v -> canvas.undo());
        TextView clear = Brutal.chip(c, "CLEAR", false);
        clear.setOnClickListener(v -> canvas.clearFrame());
        TextView invert = Brutal.chip(c, "INVERT", false);
        invert.setOnClickListener(v -> canvas.invertFrame());
        toggles.addView(mirrorChip, Brutal.weighted(c, 1, 6));
        toggles.addView(onionChip, Brutal.weighted(c, 1, 6));
        toggles.addView(undo, Brutal.weighted(c, 1, 6));
        toggles.addView(clear, Brutal.weighted(c, 1, 6));
        toggles.addView(invert, Brutal.weighted(c, 1, 0));
        add(toggles, 8);

        LinearLayout nudge = Brutal.row(c);
        TextView nudgeLabel = Brutal.mono(c, "MOVE", 11, Brutal.MUTE, true);
        nudge.addView(nudgeLabel, new LinearLayout.LayoutParams(host.dp(52), -2));
        String[][] arrows = {{"←", "-1", "0"}, {"→", "1", "0"}, {"↑", "0", "-1"}, {"↓", "0", "1"}};
        for (String[] a : arrows) {
            TextView chip = Brutal.chip(c, a[0], false);
            chip.setTextSize(16);
            int dx = Integer.parseInt(a[1]), dy = Integer.parseInt(a[2]);
            chip.setOnClickListener(v -> canvas.nudge(dx, dy));
            nudge.addView(chip, Brutal.weighted(c, 1, 6));
        }
        add(nudge, 8);

        // frames
        add(Brutal.sectionHead(c, "02", "FRAMES", null), 28);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        frameStrip = new LinearLayout(c);
        scroll.addView(frameStrip);
        add(scroll, 10);
        LinearLayout frameActions = Brutal.row(c);
        TextView copy = Brutal.chip(c, "+ COPY", false);
        copy.setOnClickListener(v -> { if (!canvas.addFrame(true)) host.toast("60 frames is the limit here"); });
        TextView blank = Brutal.chip(c, "+ BLANK", false);
        blank.setOnClickListener(v -> { if (!canvas.addFrame(false)) host.toast("60 frames is the limit here"); });
        TextView delete = Brutal.chip(c, "DELETE", false);
        delete.setOnClickListener(v -> canvas.deleteFrame());
        frameActions.addView(copy, Brutal.weighted(c, 1, 6));
        frameActions.addView(blank, Brutal.weighted(c, 1, 6));
        frameActions.addView(delete, Brutal.weighted(c, 1, 0));
        add(frameActions, 10);

        LinearLayout timing = Brutal.row(c);
        timing.addView(Brutal.mono(c, "EACH FRAME", 11, Brutal.MUTE, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView slower = Brutal.chip(c, "−", false);
        slower.setTextSize(16);
        slower.setOnClickListener(v -> { timeIndex = Math.min(FRAME_TIMES.length - 1, timeIndex + 1); onCanvasChanged(); });
        timeLabel = Brutal.mono(c, "", 14, Brutal.INK, true);
        timeLabel.setGravity(android.view.Gravity.CENTER);
        TextView faster = Brutal.chip(c, "+", false);
        faster.setTextSize(16);
        faster.setOnClickListener(v -> { timeIndex = Math.max(0, timeIndex - 1); onCanvasChanged(); });
        timing.addView(slower, new LinearLayout.LayoutParams(host.dp(48), -2));
        timing.addView(timeLabel, new LinearLayout.LayoutParams(host.dp(96), -2));
        timing.addView(faster, new LinearLayout.LayoutParams(host.dp(48), -2));
        add(timing, 14);

        add(Brutal.mono(c, "PLAYBACK", 11, Brutal.MUTE, true), 14);
        preview = new QgifView(c, Brutal.OLED_LIT, Brutal.OLED_DARK);
        LinearLayout previewRow = Brutal.row(c);
        previewRow.addView(Brutal.oledFrame(c, preview), new LinearLayout.LayoutParams(0, -2, 1));
        previewRow.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        add(previewRow, 6);

        // save / send
        add(Brutal.sectionHead(c, "03", "KEEP IT", null), 28);
        TextView open = Brutal.button(c, "START FROM A LIBRARY ANIMATION", false);
        open.setOnClickListener(v -> openFromLibrary());
        add(open, 10);
        nameField = Brutal.field(c, "NAME, E.G. HAPPY BLINK", 30);
        add(nameField, 14);
        LinearLayout buttons = Brutal.row(c);
        TextView save = Brutal.button(c, "SAVE TO MINE", false);
        save.setOnClickListener(v -> save(false));
        sendButton = Brutal.button(c, "SEND + PLAY", true);
        sendButton.setOnClickListener(v -> save(true));
        buttons.addView(save, Brutal.weighted(c, 1, 10));
        buttons.addView(sendButton, Brutal.weighted(c, 1, 0));
        add(buttons, 12);
        status = Brutal.mono(c, "", 11, Brutal.INK, false);
        add(status, 10);
        add(new Deco.Footer(c, "DRAW", "SAVE", "SEND"), 26);

        refreshTools();
        onCanvasChanged();
    }

    private void refreshTools() {
        PixelCanvas.Tool[] all = PixelCanvas.Tool.values();
        for (int i = 0; i < all.length; i++) Brutal.setChip(toolChips.get(i), all[i] == canvas.tool());
        sizeChip.setText("SIZE " + canvas.brush());
        Brutal.setChip(mirrorChip, canvas.mirror());
        Brutal.setChip(onionChip, canvas.onion());
    }

    @Override public void onCanvasChanged() {
        frameLabel.setText("FRAME " + (canvas.currentFrame() + 1) + "/" + canvas.frameCount());
        timeLabel.setText(FRAME_TIMES[timeIndex] + " MS");
        renderStrip();
        preview.setGif(Qgif.parse("preview.qgif", encode()));
    }

    private byte[] encode() {
        int n = canvas.frameCount();
        boolean[][] frames = new boolean[n][];
        int[] delays = new int[n];
        for (int i = 0; i < n; i++) { frames[i] = canvas.frame(i); delays[i] = FRAME_TIMES[timeIndex]; }
        return Qgif.encode(frames, delays);
    }

    private void renderStrip() {
        frameStrip.removeAllViews();
        for (int i = 0; i < canvas.frameCount(); i++) {
            FrameThumb thumb = new FrameThumb(host, canvas.frame(i), i == canvas.currentFrame(), i + 1);
            final int index = i;
            thumb.setOnClickListener(v -> canvas.select(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(76), host.dp(44));
            lp.rightMargin = host.dp(8);
            frameStrip.addView(thumb, lp);
        }
    }

    private void openFromLibrary() {
        List<Qgif> all = host.library.all();
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) names[i] = all.get(i).label() + "  (" + all.get(i).frames + " frames)";
        new AlertDialog.Builder(host)
                .setTitle("Start from")
                .setItems(names, (d, which) -> {
                    Qgif q = all.get(which);
                    List<boolean[]> frames = new ArrayList<>();
                    int step = Math.max(1, (int) Math.ceil(q.frames / (double) PixelCanvas.MAX_FRAMES));
                    for (int i = 0; i < q.frames; i += step) frames.add(q.litFrame(i));
                    canvas.load(frames);
                    timeIndex = closestTime(q.delays[0] * step);
                    nameField.setText(q.label().toLowerCase(Locale.ROOT) + " remix");
                    status.setText(q.frames > PixelCanvas.MAX_FRAMES ? "LOADED EVERY " + step + "TH FRAME (60 MAX)." : "LOADED " + q.frames + " FRAMES.");
                    status.setTextColor(Brutal.INK);
                    onCanvasChanged();
                })
                .show();
    }

    private static int closestTime(int ms) {
        int best = 0;
        for (int i = 1; i < FRAME_TIMES.length; i++) if (Math.abs(FRAME_TIMES[i] - ms) < Math.abs(FRAME_TIMES[best] - ms)) best = i;
        return best;
    }

    private void save(boolean andSend) {
        String title = nameField.getText().toString().trim();
        if (title.isEmpty()) title = "face " + new java.text.SimpleDateFormat("HHmm", Locale.ROOT).format(new java.util.Date());
        try {
            Qgif saved = host.library.saveMine(title, encode());
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

    /** Tiny frame preview in the strip. */
    static final class FrameThumb extends View {
        private final boolean[] frame;
        private final boolean selected;
        private final String number;
        private final Paint lit = new Paint(), edge = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        FrameThumb(Context c, boolean[] frame, boolean selected, int number) {
            super(c);
            this.frame = frame;
            this.selected = selected;
            this.number = String.valueOf(number);
            lit.setColor(Brutal.OLED_LIT);
            edge.setColor(selected ? Brutal.BLUE : Brutal.INK);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Brutal.dp(c, selected ? 4 : 2));
            text.setColor(selected ? Brutal.YELLOW : Brutal.MUTE);
            text.setTypeface(Brutal.MONO_BOLD);
            text.setTextSize(Brutal.dp(c, 10));
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(Brutal.OLED_DARK);
            float sx = getWidth() / 128f, sy = getHeight() / 64f;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) {
                if (frame[y * 128 + x]) c.drawRect(x * sx, y * sy, (x + 1) * sx, (y + 1) * sy, lit);
            }
            float half = edge.getStrokeWidth() / 2f;
            c.drawRect(half, half, getWidth() - half, getHeight() - half, edge);
            c.drawText(number, Brutal.dp(getContext(), 5), getHeight() - Brutal.dp(getContext(), 5), text);
        }
    }
}
