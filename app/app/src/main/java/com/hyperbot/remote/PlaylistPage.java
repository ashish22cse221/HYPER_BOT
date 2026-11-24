package com.hyperbot.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tab 02: mood presets, the playlist, the send bar and the animation library. */
final class PlaylistPage extends Page {
    static final int MAX = 8;
    private static final String[] FILTERS = {"ALL", "MINE", "ACTION", "EMOTION", "EYE", "EFFECT", "THEME", "OTHER"};

    private final List<Qgif> playlist = new ArrayList<>();
    private String filter = "ALL";
    private String query = "";
    private long startedAt;

    private static final int[] DWELLS = {5, 8, 10, 15};
    private static final int[] SPEEDS = {1, 2, 3};
    private TextView countText, totalText, sendDetail, cancelButton, libraryCount, playbackNote;
    private final java.util.List<TextView> dwellChips = new ArrayList<>(), speedChips = new ArrayList<>();
    private int dwell = 8, speed = 1;
    private LinearLayout slotTable, filterRow, grid;
    private SendBar sendBar;

    PlaylistPage(MainActivity host) {
        super(host);
        Context c = host;
        setHero(new Hero(c, "02 / PLAYLIST", "PLAY", "LIST.", "1 TO 8 FACES · SHUFFLED ON THE BOT", "img/david.webp", Brutal.LIME, Brutal.INK));
        add(Brutal.sectionHead(c, "01", "MOODS", null), 22);
        add(Brutal.mono(c, "ONE TAP FILLS THE PLAYLIST. TWEAK IT, THEN SEND.", 10, Brutal.MUTE, false), 6);
        add(moodRow(), 10);
        add(Brutal.sectionHead(c, "02", "ON SCREEN FOR", null), 30);
        add(playbackPanel(), 10);

        countText = Brutal.mono(c, "0 / " + MAX, 13, Brutal.INK, true);
        add(Brutal.sectionHead(c, "03", "PLAYLIST", countText), 30);
        slotTable = new LinearLayout(c);
        slotTable.setOrientation(LinearLayout.VERTICAL);
        slotTable.setBackground(Brutal.block(c, Brutal.PAPER, Brutal.INK, 3));
        slotTable.setPadding(host.dp(3), host.dp(3), host.dp(3), host.dp(3));
        add(slotTable, 10);
        totalText = Brutal.mono(c, "", 12, Brutal.MUTE, false);
        add(totalText, 6);

        sendBar = new SendBar(c);
        sendBar.setOnClickListener(v -> send());
        add(sendBar, 16);
        LinearLayout under = Brutal.row(c);
        sendDetail = Brutal.mono(c, "", 12, Brutal.INK, false);
        under.addView(sendDetail, new LinearLayout.LayoutParams(0, -2, 1));
        cancelButton = Brutal.mono(c, "CANCEL", 12, Brutal.RED, true);
        cancelButton.setPadding(host.dp(10), host.dp(8), host.dp(2), host.dp(8));
        cancelButton.setOnClickListener(v -> host.link.cancel());
        cancelButton.setVisibility(View.GONE);
        under.addView(cancelButton);
        add(under, 4);

        libraryCount = Brutal.mono(c, "", 13, Brutal.INK, true);
        add(Brutal.sectionHead(c, "04", "LIBRARY", libraryCount), 34);
        EditText search = Brutal.field(c, "FILTER BY NAME", 32);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { query = s.toString().trim().toUpperCase(Locale.ROOT); renderGrid(); }
        });
        add(search, 12);
        HorizontalScrollView fs = new HorizontalScrollView(c);
        fs.setHorizontalScrollBarEnabled(false);
        filterRow = new LinearLayout(c);
        fs.addView(filterRow);
        add(fs, 10);
        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        add(grid, 14);
        TextView foot = Brutal.mono(c, "TAP A TILE TO ADD IT, TAP A ROW TO DROP IT.\nLONG-PRESS ONE OF YOURS TO DELETE IT.\nSENDING REPLACES EVERY GIF ON THE BOT.", 10, Brutal.MUTE, false);
        foot.setLineSpacing(0, 1.3f);
        add(foot, 22);
        add(new Deco.Footer(c, "PICK", "SHUFFLE", "REPEAT"), 22);

        renderPlaylist();
        renderFilters();
        renderGrid();
    }

    // ----------------------------------------------------------------- moods

    private View moodRow() {
        Context c = host;
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        int[][] colours = {{Brutal.BLUE, Brutal.WHITE}, {Brutal.LIME, Brutal.INK}, {Brutal.INK, Brutal.WHITE}, {0, Brutal.WHITE}, {Brutal.WHITE, Brutal.INK}};
        int k = 0;
        for (Moods.Mood mood : Moods.ALL) {
            int[] col = colours[k++ % colours.length];
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            if (col[0] == 0) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{Brutal.VIOLET_LIGHT, Brutal.BLUE});
                g.setStroke(host.dp(2), Brutal.INK);
                card.setBackground(g);
            } else {
                card.setBackground(Brutal.block(c, col[0], Brutal.INK, 2));
            }
            card.setPadding(host.dp(12), host.dp(10), host.dp(14), host.dp(14));
            TextView arrow = Brutal.mono(c, "↗", 14, col[1], true);
            arrow.setGravity(android.view.Gravity.END);
            card.addView(arrow, new LinearLayout.LayoutParams(-1, -2));
            TextView name = new TextView(c);
            name.setText(mood.name);
            name.setTypeface(Brutal.HEAD);
            name.setTextSize(34);
            name.setTextColor(col[1]);
            name.setIncludeFontPadding(false);
            card.addView(name);
            TextView blurb = Brutal.mono(c, mood.blurb.toUpperCase(Locale.ROOT), 9, col[1], false);
            blurb.setMaxWidth(host.dp(150));
            blurb.setLineSpacing(0, 1.2f);
            card.addView(blurb, Brutal.spaced(c, -2, -2, 6));
            card.setOnClickListener(v -> applyMood(mood));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(170), -1);
            lp.rightMargin = host.dp(10);
            row.addView(card, lp);
        }
        scroll.addView(row);
        return scroll;
    }

    // -------------------------------------------------------------- playback

    private View playbackPanel() {
        Context c = host;
        LinearLayout panel = Brutal.panel(c, 0);
        panel.addView(Brutal.mono(c, "EACH ANIMATION STAYS ON THE BOT AT LEAST THIS LONG. SHORT ONES REPEAT UNTIL TIME IS UP.", 10, Brutal.MUTE, false));
        LinearLayout dRow = Brutal.row(c);
        for (int i = 0; i < DWELLS.length; i++) {
            final int value = DWELLS[i];
            TextView chip = Brutal.chip(c, value + " S", value == dwell);
            chip.setOnClickListener(v -> { if (host.link.setDwell(value)) playbackNote.setText("SENDING…"); else needLink(); });
            dwellChips.add(chip);
            dRow.addView(chip, Brutal.weighted(c, 1, i == DWELLS.length - 1 ? 0 : 6));
        }
        panel.addView(dRow, Brutal.spaced(c, -1, -2, 10));
        panel.addView(Brutal.mono(c, "SPEED", 10, Brutal.MUTE, true), Brutal.spaced(c, -2, -2, 14));
        LinearLayout sRow = Brutal.row(c);
        String[] names = {"AS DRAWN", "2X", "3X"};
        for (int i = 0; i < SPEEDS.length; i++) {
            final int value = SPEEDS[i];
            TextView chip = Brutal.chip(c, names[i], value == speed);
            chip.setOnClickListener(v -> { if (host.link.setSpeed(value)) playbackNote.setText("SENDING…"); else needLink(); });
            speedChips.add(chip);
            sRow.addView(chip, Brutal.weighted(c, i == 0 ? 1.6f : 1, i == SPEEDS.length - 1 ? 0 : 6));
        }
        panel.addView(sRow, Brutal.spaced(c, -1, -2, 8));
        playbackNote = Brutal.mono(c, "CONNECT TO CHANGE THESE. THE BOT REMEMBERS THEM.", 10, Brutal.INK, false);
        panel.addView(playbackNote, Brutal.spaced(c, -1, -2, 10));
        return panel;
    }

    private void needLink() {
        playbackNote.setText(host.link.isBusy() ? "WAIT FOR THE UPLOAD TO FINISH." : "CONNECT ON THE BOT TAB FIRST.");
    }

    void onPlayback(int dwellSeconds, int speedValue) {
        if (dwellSeconds > 0) dwell = dwellSeconds;
        if (speedValue > 0) speed = speedValue;
        for (int i = 0; i < DWELLS.length; i++) Brutal.setChip(dwellChips.get(i), DWELLS[i] == dwell);
        for (int i = 0; i < SPEEDS.length; i++) Brutal.setChip(speedChips.get(i), SPEEDS[i] == speed);
        playbackNote.setText("ON THE BOT: " + dwell + " S EACH, " + (speed == 1 ? "AS DRAWN" : speed + "X SPEED") + ".");
    }

    private void applyMood(Moods.Mood mood) {
        if (host.link.isBusy()) return;
        playlist.clear();
        playlist.addAll(Moods.resolve(mood, host.library));
        host.log(mood.name.toLowerCase(Locale.ROOT) + " mood loaded into the playlist");
        renderPlaylist();
        renderGrid();
    }

    // -------------------------------------------------------------- playlist

    private void renderPlaylist() {
        slotTable.removeAllViews();
        boolean locked = host.link.isBusy();
        int rows = Math.min(MAX, Math.max(5, playlist.size() + 1));
        long bytes = 0;
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                View rule = new View(host);
                rule.setBackgroundColor(Brutal.INK);
                slotTable.addView(rule, new LinearLayout.LayoutParams(-1, host.dp(2)));
            }
            Qgif gif = i < playlist.size() ? playlist.get(i) : null;
            if (gif != null) bytes += gif.raw.length;
            slotTable.addView(slotRow(i, gif, locked));
        }
        countText.setText(playlist.size() + " / " + MAX);
        countText.setTextColor(playlist.isEmpty() ? Brutal.INK : Brutal.BLUE);
        totalText.setText(playlist.isEmpty() ? "NOTHING PICKED YET. TRY A MOOD." : "TOTAL " + Math.round(bytes / 1024f) + " KB");
        refreshSendBar();
    }

    private View slotRow(int index, Qgif gif, boolean locked) {
        Context c = host;
        LinearLayout row = Brutal.row(c);
        row.setMinimumHeight(host.dp(56));
        row.setPadding(0, 0, host.dp(12), 0);
        TextView num = Brutal.mono(c, String.format(Locale.ROOT, "%02d", index + 1), 15, gif == null ? Brutal.MUTE : Brutal.PAPER, true);
        num.setGravity(Gravity.CENTER);
        num.setBackgroundColor(gif == null ? Brutal.PAPER : Brutal.INK);
        row.addView(num, new LinearLayout.LayoutParams(host.dp(46), host.dp(56)));
        if (gif == null) {
            TextView empty = Brutal.mono(c, "EMPTY", 12, Brutal.MUTE, false);
            empty.setPadding(host.dp(12), 0, 0, 0);
            row.addView(empty, new LinearLayout.LayoutParams(0, -2, 1));
            return row;
        }
        FrameLayout screen = new FrameLayout(c);
        screen.setBackgroundColor(Brutal.INK);
        screen.setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2));
        QgifView thumb = new QgifView(c, Brutal.OLED_LIT, Brutal.OLED_DARK);
        thumb.setGif(gif);
        screen.addView(thumb, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(host.dp(84), -2);
        slp.leftMargin = host.dp(10);
        row.addView(screen, slp);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(host.dp(12), 0, host.dp(8), 0);
        TextView name = Brutal.cond(c, gif.shortLabel(), 17, Brutal.INK);
        name.setSingleLine(true);
        texts.addView(name);
        texts.addView(Brutal.mono(c, gif.category() + " / " + gif.frames + " FR / " + gif.sizeKb() + " KB", 11, Brutal.MUTE, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(Brutal.mono(c, locked ? "" : "DROP", 11, Brutal.INK, true));
        if (!locked) row.setOnClickListener(v -> { playlist.remove(gif); renderPlaylist(); renderGrid(); });
        return row;
    }

    private void refreshSendBar() {
        if (host.link.busyKind() == BotLink.Kind.PLAYLIST) return;
        SendBar.Mode mode = sendBar.mode();
        if (mode == SendBar.Mode.DONE || mode == SendBar.Mode.FAILED) return;
        if (host.link.isBusy()) sendBar.show(SendBar.Mode.LOCKED, "BOT IS BUSY", 0);
        else if (host.link.link() != BotLink.Link.READY) sendBar.show(SendBar.Mode.LOCKED, playlist.isEmpty() ? "PICK, THEN CONNECT" : "CONNECT TO SEND", 0);
        else if (playlist.isEmpty()) sendBar.show(SendBar.Mode.LOCKED, "PICK AT LEAST ONE", 0);
        else sendBar.show(SendBar.Mode.ARMED, "SEND " + playlist.size() + " TO THE BOT", 0);
    }

    private void send() {
        if (playlist.isEmpty() || !canSend()) return;
        List<BotLink.Item> items = new ArrayList<>();
        for (Qgif g : playlist) items.add(new BotLink.Item(g.name, g.raw));
        if (host.link.sendPlaylist(items)) {
            startedAt = SystemClock.uptimeMillis();
            cancelButton.setVisibility(View.VISIBLE);
            host.startedJob();
            renderPlaylist();
        }
    }

    private void hold(SendBar.Mode mode, String label, long ms) {
        sendBar.show(mode, label, 1f);
        sendBar.removeCallbacks(release);
        sendBar.postDelayed(release, ms);
    }

    private final Runnable release = () -> { sendBar.show(SendBar.Mode.LOCKED, "", 0); refreshSendBar(); };

    @Override void onBusyChanged() { renderPlaylist(); }

    @Override void onProgress(BotLink.Kind kind, int part, int parts, String label, long partDone, long partSize, long allDone, long allSize) {
        int pct = partSize == 0 ? 0 : (int) (partDone * 100 / partSize);
        String text = label.equals("CLEARING BOARD") ? "CLEARING THE BOT" : (part + 1) + "/" + parts + "  " + label + "  " + pct + "%";
        sendBar.removeCallbacks(release);
        sendBar.show(SendBar.Mode.RUNNING, text, allSize == 0 ? 0 : allDone / (float) allSize);
        float seconds = Math.max(0.5f, (SystemClock.uptimeMillis() - startedAt) / 1000f);
        sendDetail.setText(String.format(Locale.ROOT, "%d / %d KB   %.1f KB/S", allDone / 1024, allSize / 1024, allDone / 1024f / seconds));
    }

    @Override void onJobDone(BotLink.Kind kind, int count) {
        cancelButton.setVisibility(View.GONE);
        sendDetail.setText(String.format(Locale.ROOT, "DONE IN %.0f S", (SystemClock.uptimeMillis() - startedAt) / 1000f));
        hold(SendBar.Mode.DONE, "SENT. THE BOT IS SHUFFLING " + count, 3500);
    }

    @Override void onJobFailed(BotLink.Kind kind, String reason) {
        cancelButton.setVisibility(View.GONE);
        sendDetail.setText(reason);
        hold(SendBar.Mode.FAILED, reason.equals("CANCELLED") ? "CANCELLED" : "UPLOAD FAILED", 4000);
    }

    // --------------------------------------------------------------- library

    void onLibraryChanged() {
        List<Qgif> all = host.library.all();
        playlist.removeIf(q -> !all.contains(q));
        renderPlaylist();
        renderFilters();
        renderGrid();
    }

    @Override void onShow() { renderFilters(); renderGrid(); }

    private void renderFilters() {
        filterRow.removeAllViews();
        for (String name : FILTERS) {
            int count = 0;
            for (Qgif g : host.library.all()) if (name.equals("ALL") || g.category().equals(name)) count++;
            if (count == 0 && !name.equals("MINE")) continue;
            TextView chip = Brutal.chip(host, name + " " + count, name.equals(filter));
            chip.setOnClickListener(v -> { filter = name; renderFilters(); renderGrid(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = host.dp(8);
            filterRow.addView(chip, lp);
        }
    }

    private void renderGrid() {
        grid.removeAllViews();
        List<Qgif> shown = new ArrayList<>();
        for (Qgif g : host.library.all()) {
            if (!filter.equals("ALL") && !g.category().equals(filter)) continue;
            if (!query.isEmpty() && !g.label().contains(query)) continue;
            shown.add(g);
        }
        libraryCount.setText(shown.size() + " OF " + host.library.all().size());
        int columns = host.getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(host);
                grid.addView(row, Brutal.spaced(host, -1, -2, i == 0 ? 0 : 14));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % columns != columns - 1) lp.rightMargin = host.dp(12);
            row.addView(tile(shown.get(i)), lp);
        }
        if (row != null) for (int pad = shown.size() % columns; pad != 0 && pad < columns; pad++) row.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        if (shown.isEmpty()) {
            grid.addView(Brutal.mono(host, filter.equals("MINE") ? "NOTHING HERE YET. MAKE ONE IN DRAW OR CONVERT." : "NO MATCH.", 12, Brutal.MUTE, false));
        }
    }

    private View tile(Qgif gif) {
        Context c = host;
        int slot = playlist.indexOf(gif);
        boolean picked = slot >= 0;
        int shadow = picked ? 5 : 0;
        LinearLayout tile = new LinearLayout(c);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(Brutal.block(c, Brutal.PAPER, picked ? Brutal.BLUE : Brutal.INK, picked ? 4 : 3, shadow, Brutal.INK));
        int border = host.dp(picked ? 4 : 3), s = host.dp(shadow);
        tile.setPadding(border, border, border + s, border + s);
        QgifView screen = new QgifView(c, Brutal.OLED_LIT, Brutal.OLED_DARK);
        screen.setGif(gif);
        tile.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout strip = Brutal.row(c);
        strip.setBackgroundColor(picked ? Brutal.BLUE : Brutal.PAPER);
        strip.setPadding(host.dp(8), host.dp(7), host.dp(8), host.dp(8));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = Brutal.cond(c, gif.shortLabel(), 15, picked ? Brutal.PAPER : Brutal.INK);
        name.setSingleLine(true);
        texts.addView(name);
        texts.addView(Brutal.mono(c, gif.category() + " " + gif.sizeKb() + "K", 10, picked ? Brutal.PAPER : Brutal.MUTE, false));
        strip.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (picked) {
            TextView badge = Brutal.mono(c, String.valueOf(slot + 1), 16, Brutal.BLUE, true);
            badge.setBackgroundColor(Brutal.PAPER);
            badge.setGravity(Gravity.CENTER);
            strip.addView(badge, new LinearLayout.LayoutParams(host.dp(28), host.dp(28)));
        }
        tile.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        tile.setContentDescription(gif.label() + (picked ? ", in slot " + (slot + 1) : ""));
        tile.setOnClickListener(v -> toggle(gif));
        if (host.library.isMine(gif)) {
            tile.setOnLongClickListener(v -> { confirmDelete(gif); return true; });
        }
        return tile;
    }

    private void toggle(Qgif gif) {
        if (host.link.isBusy()) return;
        if (playlist.contains(gif)) playlist.remove(gif);
        else if (playlist.size() >= MAX) { host.toast("The playlist holds " + MAX + ". Drop one first."); return; }
        else playlist.add(gif);
        renderPlaylist();
        renderGrid();
    }

    private void confirmDelete(Qgif gif) {
        new AlertDialog.Builder(host)
                .setTitle("Delete " + gif.label() + "?")
                .setMessage("It's removed from this phone. Copies already on the bot stay until the next upload.")
                .setPositiveButton("Delete", (d, w) -> { host.library.delete(gif); host.libraryChanged(); })
                .setNegativeButton("Keep", null)
                .show();
    }
}
