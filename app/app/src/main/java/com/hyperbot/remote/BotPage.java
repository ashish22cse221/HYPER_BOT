package com.hyperbot.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Tab 01: who you're linked to, what's on its screen, poke it, log. */
final class BotPage extends Page {
    private static final String[] EMOJI = {"❤️", "😊", "😂", "🥺", "😴", "👋", "🎉", "☕", "🔥", "👍", "🤖", "⭐", "(^_^)", "<3", "zzz", "o/"};

    private TextView nameBlock, linkFacts, linkWarning, connectTile;
    private TextView freeValue, freeSub, gifsValue, gifsSub;
    private QgifView mirror;
    private TextView mirrorName, mirrorNote, liveTag;
    private EditText pokeFrom, pokeText;
    private PixelPreview pokePreview;
    private TextView pokeHint, pokeButton, pokeStatus;
    private TextView logView;
    private TextView boardInfo, forgetButton, resetButton;
    private LinearLayout pokeSection;
    private PokeRenderer.Rendered rendered;
    private int lastGifs = -1;

    BotPage(MainActivity host) {
        super(host);
        Context c = host;
        setHero(new Hero(c, "01 / BOT", "YOUR", "BOT", "SAY HI · DRAW · SHUFFLE", "img/bust.webp", 0, Brutal.BLUE));

        nameBlock = Brutal.valueBlock(c, "OFFLINE", Brutal.INK, Brutal.WHITE);
        LinearLayout nameRow = Brutal.row(c);
        nameRow.addView(nameBlock);
        add(nameRow, 10);

        add(statTiles(), 12);
        add(actionTiles(), 12);

        linkFacts = Brutal.mono(c, "", 11, Brutal.INK, false);
        linkFacts.setLineSpacing(0, 1.3f);
        add(linkFacts, 12);
        linkWarning = Brutal.mono(c, "", 12, Brutal.WHITE, true);
        linkWarning.setBackgroundColor(Brutal.RED);
        linkWarning.setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8));
        linkWarning.setLineSpacing(0, 1.25f);
        linkWarning.setVisibility(View.GONE);
        add(linkWarning, 10);

        add(Brutal.sectionHead(c, "01", "ON SCREEN NOW", null), 30);
        add(mirrorPanel(), 10);

        add(Brutal.sectionHead(c, "02", "POKE", null), 30);
        pokeSection = (LinearLayout) pokePanel();
        add(pokeSection, 10);

        add(Brutal.sectionHead(c, "03", "LOG", null), 30);
        logView = Brutal.mono(c, "", 10, Brutal.INK, false);
        logView.setLineSpacing(0, 1.35f);
        logView.setBackground(Brutal.block(c, Brutal.WHITE, Brutal.INK, 2));
        logView.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(12));
        add(logView, 10);

        add(Brutal.sectionHead(c, "04", "THIS BOT", null), 30);
        add(botSettings(), 10);

        add(Brutal.sectionHead(c, "05", "ABOUT", null), 30);
        add(aboutPanel(), 10);
        add(new Deco.Footer(c, "SAY HI", "TO YOUR", "BOT"), 26);
    }

    // ------------------------------------------------------------- hero area

    private View statTiles() {
        Context c = host;
        LinearLayout row = Brutal.row(c);
        row.setGravity(Gravity.TOP);
        LinearLayout free = tile(Brutal.INK, Brutal.WHITE);
        free.addView(tileLabel("FREE SPACE", Brutal.WHITE));
        freeValue = tileValue("—", Brutal.WHITE);
        free.addView(freeValue);
        freeSub = Brutal.mono(c, "CONNECT TO SEE", 9, 0xFFB5B5B0, false);
        free.addView(freeSub, Brutal.spaced(c, -2, -2, 4));
        row.addView(free, Brutal.weighted(c, 1, 10));

        LinearLayout gifs = tile(Brutal.LIME, Brutal.INK);
        gifs.addView(tileLabel("ON THE BOT", Brutal.INK));
        gifsValue = tileValue("—", Brutal.INK);
        gifs.addView(gifsValue);
        gifsSub = Brutal.mono(c, "GIFS · SHUFFLE", 9, Brutal.INK, false);
        gifs.addView(gifsSub, Brutal.spaced(c, -2, -2, 4));
        row.addView(gifs, Brutal.weighted(c, 1, 0));
        return row;
    }

    private LinearLayout tile(int fill, int ink) {
        LinearLayout t = new LinearLayout(host);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Brutal.block(host, fill, Brutal.INK, 2));
        t.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(12));
        t.setMinimumHeight(host.dp(96));
        return t;
    }

    private TextView tileLabel(String text, int color) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        TextView t = Brutal.mono(host, text + "  ↗", 10, color, true);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView tileValue(String text, int color) {
        TextView v = new TextView(host);
        v.setText(text);
        v.setTypeface(Brutal.HEAD);
        v.setTextSize(30);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setPadding(0, host.dp(6), 0, 0);
        v.setSingleLine(true);
        return v;
    }

    /** NOVA's Send / Receive / Swap row: square tiles, the first one filled blue. */
    private View actionTiles() {
        Context c = host;
        LinearLayout row = Brutal.row(c);
        connectTile = actionTile("◎", "CONNECT", true);
        connectTile.setOnClickListener(v -> host.connectOrDisconnect());
        row.addView(connectTile, Brutal.weighted(c, 1, 8));
        TextView poke = actionTile("✦", "POKE", false);
        poke.setOnClickListener(v -> root.post(() -> root.smoothScrollTo(0, pokeSection.getTop() - host.dp(60))));
        row.addView(poke, Brutal.weighted(c, 1, 8));
        TextView moods = actionTile("▦", "MOODS", false);
        moods.setOnClickListener(v -> host.showPage(1));
        row.addView(moods, Brutal.weighted(c, 1, 8));
        TextView draw = actionTile("✎", "DRAW", false);
        draw.setOnClickListener(v -> host.showPage(2));
        row.addView(draw, Brutal.weighted(c, 1, 0));
        return row;
    }

    private TextView actionTile(String glyph, String label, boolean filled) {
        TextView t = Brutal.mono(host, glyph + "\n" + label, 10, filled ? Brutal.WHITE : Brutal.INK, true);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(host.dp(4), 1f);
        t.setTextSize(10);
        t.setPadding(0, host.dp(14), 0, host.dp(12));
        t.setBackground(Brutal.block(host, filled ? Brutal.BLUE : Brutal.WHITE, Brutal.INK, 2));
        return t;
    }

    @Override void onLinkChanged(BotLink.Link state, String detail) {
        boolean ready = state == BotLink.Link.READY;
        nameBlock.setText(ready ? host.link.deviceName().toUpperCase(Locale.ROOT) : headline(state, detail));
        nameBlock.setBackgroundColor(ready ? Brutal.BLUE : (state == BotLink.Link.OLD_FIRMWARE ? Brutal.RED : Brutal.INK));
        boolean busy = state == BotLink.Link.SCANNING || state == BotLink.Link.CONNECTING || state == BotLink.Link.HANDSHAKE
                || state == BotLink.Link.PAIRING;
        connectTile.setText(ready ? "✕\nDROP LINK" : busy ? "■\nSTOP" : "◎\nCONNECT");

        if (state == BotLink.Link.OLD_FIRMWARE) {
            showWarning(detail + ". FLASH THE HYPERBOT FIRMWARE (firmware/ IN THE REPO, PLATFORMIO UPLOAD) AND CONNECT AGAIN.");
        } else if (state != BotLink.Link.OFF || detail.equals("NOT LINKED")) {
            linkWarning.setVisibility(View.GONE);
        }
        if (!ready) {
            switch (state) {
                case SCANNING: linkFacts.setText("SEARCHING FOR A HYPERBOT NEARBY…"); break;
                case CONNECTING: linkFacts.setText("OPENING A BLUETOOTH CONNECTION…"); break;
                case HANDSHAKE: linkFacts.setText("CHECKING FIRMWARE AND FREE SPACE…"); break;
                case PAIRING: linkFacts.setText("A 4-DIGIT CODE IS ON THE BOT'S SCREEN. TYPE IT IN THE BOX."); break;
                default: linkFacts.setText("TURN THE BOT ON, KEEP IT CLOSE, TAP CONNECT. FIRST TIME: TYPE THE CODE FROM ITS SCREEN."); break;
            }
            freeValue.setText("—");
            freeSub.setText("CONNECT TO SEE");
            gifsValue.setText("—");
            onNowPlaying("");
        } else {
            linkFacts.setText("LINKED OVER BLUETOOTH. EVERYTHING BELOW IS LIVE.");
        }
        onBusyChanged();
    }

    private static String headline(BotLink.Link state, String detail) {
        switch (state) {
            case SCANNING: return "SCANNING…";
            case CONNECTING: return "LINKING…";
            case HANDSHAKE: return "HANDSHAKE…";
            case PAIRING: return "PAIRING…";
            case OLD_FIRMWARE: return "WRONG FIRMWARE";
            default: return detail.equals("NOT LINKED") ? "OFFLINE" : detail;
        }
    }

    void onBoard(int freeKb, int totalKb, int gifs, int mtu) {
        lastGifs = gifs;
        freeValue.setText(freeKb + " KB");
        freeSub.setText("OF " + totalKb + " KB · MTU " + mtu);
        gifsValue.setText(gifs + (gifs == 1 ? " GIF" : " GIFS"));
    }

    void onPlayback(int dwell, int speed) {
        gifsSub.setText((dwell > 0 ? dwell + " S EACH" : "SHUFFLE") + (speed > 1 ? " · " + speed + "X" : ""));
    }

    void onBoardInfo(String firmware, boolean oledOk, int pairedPhones) {
        boardInfo.setText("FIRMWARE  v" + firmware + "\nPAIRED PHONES  " + pairedPhones
                + "\nSCREEN  " + (oledOk ? "OK" : "NOT FOUND"));
        if (!oledOk) {
            showWarning("THE BOT CAN'T FIND ITS SCREEN. CHECK THE OLED WIRING: VCC → 3V3, GND → GND, "
                    + "SDA → D8 (GPIO8), SCL → D9 (GPIO9). BLUETOOTH STILL WORKS.");
        }
    }

    private View botSettings() {
        Context c = host;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        boardInfo = Brutal.mono(c, "CONNECT TO SEE FIRMWARE AND PAIRING", 11, Brutal.INK, false);
        boardInfo.setLineSpacing(0, 1.4f);
        boardInfo.setBackground(Brutal.block(c, Brutal.WHITE, Brutal.INK, 2));
        boardInfo.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(12));
        box.addView(boardInfo);
        LinearLayout row = Brutal.row(c);
        forgetButton = Brutal.button(c, "FORGET THIS PHONE", false);
        forgetButton.setOnClickListener(v -> host.confirm("Forget this phone?",
                "The bot stops trusting this phone. You'll type a new code from its screen to connect again.",
                "FORGET", () -> host.link.forgetThisPhone()));
        row.addView(forgetButton, Brutal.weighted(c, 1, 8));
        resetButton = Brutal.button(c, "RESET BOT", false);
        resetButton.setOnClickListener(v -> host.confirm("Reset the bot?",
                "Every paired phone is forgotten and first-time setup runs again. Your GIFs stay on the bot.",
                "RESET", () -> host.link.resetBot()));
        row.addView(resetButton, Brutal.weighted(c, 1, 0));
        box.addView(row, Brutal.spaced(c, -1, -2, 10));
        TextView tip = Brutal.mono(c, "LOST YOUR PHONE? UNPLUG AND REPLUG THE BOT 5 TIMES IN A ROW (WITHIN 6 S OF EACH POWER-ON) TO RESET IT.", 9, Brutal.MUTE, false);
        tip.setLineSpacing(0, 1.3f);
        box.addView(tip, Brutal.spaced(c, -1, -2, 8));
        return box;
    }

    private View aboutPanel() {
        Context c = host;
        String version = "?";
        try { version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView about = Brutal.mono(c,
                "HYPERBOT APP v" + version + "\n\n"
                        + "HYPERBOT IS A REMIX OF QBIT BY SEANCHANGX\n"
                        + "github.com/SeanChangX/QBIT\n"
                        + "LICENSED CC BY-NC-SA 4.0 · NON-COMMERCIAL\n\n"
                        + "CHANGES: NEW BLUETOOTH UPLOAD PROTOCOL, PAIRING, POKES, LIVE MIRROR, "
                        + "DISPLAY TIME + SPEED, PIXEL EDITOR, GIF CONVERTER, MOOD PRESETS, NEW APP DESIGN.\n\n"
                        + "FONTS: ANTON, SYNE, SPACE GROTESK, SPACE MONO (SIL OFL).",
                9, Brutal.INK, false);
        about.setLineSpacing(0, 1.35f);
        about.setBackground(Brutal.block(c, Brutal.WHITE, Brutal.INK, 2));
        about.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(12));
        about.setTextIsSelectable(true);
        return about;
    }

    void showWarning(String text) {
        linkWarning.setText(text);
        linkWarning.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- mirror

    private View mirrorPanel() {
        Context c = host;
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Brutal.INK);

        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(14));
        LinearLayout top = Brutal.row(c);
        top.addView(Brutal.mono(c, "MIRROR", 10, 0xFFB5B5B0, true), new LinearLayout.LayoutParams(0, -2, 1));
        liveTag = Brutal.mono(c, "OFF", 10, Brutal.INK, true);
        liveTag.setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3));
        top.addView(liveTag);
        inner.addView(top);

        mirror = new QgifView(c, Brutal.OLED_LIT, Brutal.OLED_DARK);
        mirror.setScanline(true);
        LinearLayout screen = new LinearLayout(c);
        screen.setBackground(Brutal.block(c, Brutal.OLED_DARK, 0xFF2A2A2E, 2));
        screen.setPadding(host.dp(4), host.dp(4), host.dp(4), host.dp(4));
        screen.addView(mirror, new LinearLayout.LayoutParams(-1, -2));
        inner.addView(screen, Brutal.spaced(c, -1, -2, 10));

        mirrorName = new TextView(c);
        mirrorName.setTypeface(Brutal.HEAD);
        mirrorName.setTextSize(30);
        mirrorName.setTextColor(Brutal.WHITE);
        mirrorName.setIncludeFontPadding(false);
        mirrorName.setSingleLine(true);
        inner.addView(mirrorName, Brutal.spaced(c, -1, -2, 12));
        mirrorNote = Brutal.mono(c, "", 10, 0xFFB5B5B0, false);
        mirrorNote.setLineSpacing(0, 1.25f);
        inner.addView(mirrorNote, Brutal.spaced(c, -1, -2, 4));
        panel.addView(inner);
        panel.addView(new Deco.Footer(c, "SAME", "SCREEN", "LIVE"));
        onNowPlaying("");
        return panel;
    }

    void onNowPlaying(String file) {
        if (mirror == null) return;
        boolean linked = host.link.link() == BotLink.Link.READY || host.link.link() == BotLink.Link.HANDSHAKE;
        liveTag.setText(linked ? "● LIVE" : "OFF");
        liveTag.setBackgroundColor(linked ? Brutal.LIME : 0xFF55554F);
        if (!linked) {
            mirror.setGif(null);
            mirror.setEmptyText("OFFLINE");
            mirrorName.setText("NOT LINKED");
            mirrorNote.setText("CONNECT TO SEE WHAT THE BOT SHOWS, AS IT HAPPENS.");
            return;
        }
        if (file == null || file.isEmpty()) {
            mirror.setGif(null);
            mirror.setEmptyText("BUILT-IN FACE");
            mirrorName.setText("BUILT-IN FACE");
            mirrorNote.setText("THE BOT IS ON ITS IDLE ANIMATION.");
            return;
        }
        Qgif known = host.library.find(file);
        mirror.setGif(known);
        mirror.setEmptyText(known == null ? "NO PREVIEW ON THIS PHONE" : null);
        if (known == null) host.log("mirror: " + file + " is not in this phone's library");
        mirrorName.setText(BotLink.label(file));
        mirrorNote.setText(known != null ? known.category() + " · " + known.frames + " FRAMES · IN SYNC WITH THE BOT"
                : "NOT IN YOUR LIBRARY, SO THERE'S NO PREVIEW.");
    }

    void onLibraryChanged() { onNowPlaying(host.link.nowPlaying()); }

    // ------------------------------------------------------------------ poke

    private View pokePanel() {
        Context c = host;
        LinearLayout panel = Brutal.panel(c, 0);
        panel.addView(Brutal.mono(c, "FROM", 10, Brutal.MUTE, true));
        pokeFrom = Brutal.field(c, "YOUR NAME", 16);
        pokeFrom.setText(host.prefs.getString("poke_from", "Phone"));
        panel.addView(pokeFrom, Brutal.spaced(c, -1, -2, 6));
        panel.addView(Brutal.mono(c, "MESSAGE", 10, Brutal.MUTE, true), Brutal.spaced(c, -2, -2, 12));
        pokeText = Brutal.field(c, "SAY HI. EMOJI WORK.", 40);
        panel.addView(pokeText, Brutal.spaced(c, -1, -2, 6));

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        for (String e : EMOJI) {
            TextView chip = Brutal.chip(c, e, false);
            chip.setTextSize(e.length() <= 2 ? 18 : 12);
            chip.setOnClickListener(v -> pokeText.getText().insert(Math.max(0, pokeText.getSelectionStart()), e));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, host.dp(44));
            lp.rightMargin = host.dp(8);
            chips.addView(chip, lp);
        }
        scroll.addView(chips);
        panel.addView(scroll, Brutal.spaced(c, -1, -2, 10));

        panel.addView(Brutal.mono(c, "ON THE BOT IT LOOKS LIKE THIS", 10, Brutal.MUTE, true), Brutal.spaced(c, -2, -2, 14));
        pokePreview = new PixelPreview(c);
        panel.addView(Brutal.oledFrame(c, pokePreview), Brutal.spaced(c, -1, -2, 6));
        pokeHint = Brutal.mono(c, "", 10, Brutal.MUTE, false);
        panel.addView(pokeHint, Brutal.spaced(c, -1, -2, 6));

        pokeButton = Brutal.button(c, "SEND POKE  ↗", true);
        pokeButton.setOnClickListener(v -> sendPoke());
        panel.addView(pokeButton, Brutal.spaced(c, -1, -2, 14));
        pokeStatus = Brutal.mono(c, "", 11, Brutal.INK, false);
        panel.addView(pokeStatus, Brutal.spaced(c, -1, -2, 8));

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { renderPoke(); }
        };
        pokeFrom.addTextChangedListener(watcher);
        pokeText.addTextChangedListener(watcher);
        renderPoke();
        return panel;
    }

    private void renderPoke() {
        rendered = PokeRenderer.render(pokeFrom.getText().toString().trim(), pokeText.getText().toString().trim());
        pokePreview.setBitmap(rendered.preview);
        pokeHint.setText(rendered.scrolls ? "LONGER THAN THE SCREEN: IT SCROLLS ON THE BOT." : "");
    }

    private void sendPoke() {
        String from = pokeFrom.getText().toString().trim();
        host.prefs.edit().putString("poke_from", from).apply();
        if (rendered == null) renderPoke();
        if (host.link.sendPoke(from, pokeText.getText().toString().trim(), rendered)) {
            pokeStatus.setText("SENDING…");
            pokeStatus.setTextColor(Brutal.INK);
            host.startedJob();
        }
    }

    @Override void onBusyChanged() {
        if (forgetButton != null) {
            boolean on = host.link.ready() && !host.link.firmware().isEmpty();
            Brutal.styleButton(forgetButton, "FORGET THIS PHONE", Brutal.WHITE, Brutal.INK, on);
            Brutal.styleButton(resetButton, "RESET BOT", Brutal.RED, Brutal.WHITE, on);
        }
        if (pokeButton == null) return;
        Brutal.styleButton(pokeButton, canSend() ? "SEND POKE  ↗" : (host.link.isBusy() ? "BOT IS BUSY" : "CONNECT TO POKE"),
                Brutal.LIME, Brutal.INK, canSend());
    }

    @Override void onJobDone(BotLink.Kind kind, int count) {
        pokeStatus.setText("DELIVERED. LOOK AT THE BOT.");
        pokeStatus.setTextColor(Brutal.BLUE);
    }

    @Override void onJobFailed(BotLink.Kind kind, String reason) {
        pokeStatus.setText(reason);
        pokeStatus.setTextColor(Brutal.RED);
    }

    void showLog(String text) { if (logView != null) logView.setText(text); }

    /** Draws a small bitmap scaled up with hard pixels. */
    static final class PixelPreview extends View {
        private Bitmap bitmap;
        private final Paint paint = new Paint();
        private final Rect dst = new Rect();

        PixelPreview(Context c) {
            super(c);
            paint.setFilterBitmap(false);
            paint.setAntiAlias(false);
        }

        void setBitmap(Bitmap b) { bitmap = b; invalidate(); }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, width / 2);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Brutal.OLED_DARK);
            if (bitmap == null) return;
            dst.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(bitmap, null, dst, paint);
        }
    }
}
