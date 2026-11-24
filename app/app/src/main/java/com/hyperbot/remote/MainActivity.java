package com.hyperbot.remote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Window;
import android.widget.EditText;
import android.graphics.Insets;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** App shell: header, four pages, bottom tab bar. Owns the library and the Bluetooth link. */
public class MainActivity extends Activity implements BotLink.Listener {
    static final String TAG = "HyperBot";
    private static final int REQUEST_BLUETOOTH = 31;
    private static final int REQUEST_PICK = 32;
    private static final int LOG_LINES = 8;

    Library library;
    BotLink link;
    SharedPreferences prefs;

    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private BotPage botPage;
    private PlaylistPage playlistPage;
    private DrawPage drawPage;
    private ConvertPage convertPage;
    private Page[] pages;
    private TabBar tabBar;
    private int currentPage;

    private View linkDot;
    private TextView linkLabel;
    private Dialog pairDialog;
    private TextView pairMessage;
    private EditText pairField;

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Brutal.init(this);
        prefs = getSharedPreferences("hyperbot", Context.MODE_PRIVATE);
        library = new Library(this);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        link = new BotLink(this, adapter, this);

        botPage = new BotPage(this);
        playlistPage = new PlaylistPage(this);
        drawPage = new DrawPage(this);
        convertPage = new ConvertPage(this);
        pages = new Page[]{botPage, playlistPage, drawPage, convertPage};

        showSplash = state == null;
        setContentView(buildShell());
        showPage(state == null ? 0 : state.getInt("page", 0));
        onLink(BotLink.Link.OFF, "NOT LINKED");
        log(library.all().size() + " animations ready, " + library.mineCount() + " of them yours");
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", currentPage);
    }

    @Override protected void onDestroy() {
        link.disconnect();
        convertPage.shutdown();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (currentPage != 0) showPage(0);
        else super.onBackPressed();
    }

    // =========================================================================
    //  Shell
    // =========================================================================

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Brutal.PAPER);

        // --- header: wordmark + mono tagline + logo tile (NOVA layout)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Brutal.PAPER);
        LinearLayout row = Brutal.row(this);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        TextView title = Brutal.display(this, "HYPERBOT", 26);
        title.setLetterSpacing(-0.02f);
        row.addView(title);
        TextView tagline = Brutal.mono(this, "DESK ROBOT\nTINY SCREEN\nBIG MOOD", 8, Brutal.INK, false);
        tagline.setLineSpacing(0, 1.15f);
        tagline.setPadding(dp(10), 0, 0, 0);
        row.addView(tagline, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout logo = new FrameLayout(this);
        logo.setBackground(Brutal.block(this, Brutal.BLUE, Brutal.INK, 2));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.hb_mark);
        mark.setPadding(dp(2), dp(2), dp(2), dp(2));
        logo.addView(mark, new FrameLayout.LayoutParams(-1, -1));
        linkDot = new View(this);
        FrameLayout.LayoutParams dot = new FrameLayout.LayoutParams(dp(12), dp(12));
        dot.gravity = Gravity.END | Gravity.BOTTOM;
        logo.addView(linkDot, dot);
        logo.setOnClickListener(v -> showPage(0));
        row.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        header.addView(row);
        linkLabel = Brutal.mono(this, "", 1, Brutal.INK, false);   // kept for status text, not shown
        header.addView(new Deco.Ribbon(this, "HYPERBOT"));
        root.addView(header);

        // --- pages
        FrameLayout content = new FrameLayout(this);
        for (Page page : pages) content.addView(page.root, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- tab bar: compact line icons
        final TabBar bar = new TabBar(this, this::showPage);
        tabBar = bar;
        root.addView(bar);

        // Edge-to-edge (target API 35): keep the header below the status bar and the
        // tab bar above the navigation bar, and lift everything above the keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, 0);
            bar.setPadding(0, 0, 0, bottom);
            return insets;
        });
        bar.enter();
        FrameLayout stack = new FrameLayout(this);
        stack.addView(root, new FrameLayout.LayoutParams(-1, -1));
        if (showSplash) stack.addView(new Splash(this, () -> pages[currentPage].enter()), new FrameLayout.LayoutParams(-1, -1));
        return stack;
    }

    private boolean showSplash;

    void showPage(int index) {
        int previous = currentPage;
        currentPage = Math.max(0, Math.min(pages.length - 1, index));
        if (previous != currentPage) pages[previous].leave();
        for (int i = 0; i < pages.length; i++) {
            pages[i].root.setVisibility(i == currentPage ? View.VISIBLE : View.GONE);
        }
        tabBar.select(currentPage);
        pages[currentPage].onShow();
        // On a cold start the splash plays first and starts the page entrance when it lifts.
        if (!showSplash) pages[currentPage].enter();
        showSplash = false;
    }

    /** Called after anything is saved to or deleted from your library. */
    void libraryChanged() {
        playlistPage.onLibraryChanged();
        botPage.onLibraryChanged();
    }

    // =========================================================================
    //  BotLink.Listener -> pages
    // =========================================================================

    @Override public void onLink(BotLink.Link state, String detail) {
        boolean ready = state == BotLink.Link.READY;
        if (state != BotLink.Link.PAIRING) closePairDialog();
        linkDot.setBackground(Brutal.block(this, ready ? Brutal.LIME : (state == BotLink.Link.OLD_FIRMWARE ? Brutal.RED : Brutal.MUTE), Brutal.INK, 2));
        linkLabel.setText(ready ? link.deviceName().toUpperCase(Locale.ROOT) : labelFor(state));
        for (Page p : pages) p.onLinkChanged(state, detail);
    }

    private static String labelFor(BotLink.Link state) {
        switch (state) {
            case SCANNING: return "SCANNING";
            case CONNECTING:
            case HANDSHAKE: return "LINKING";
            case PAIRING: return "PAIRING";
            case OLD_FIRMWARE: return "WRONG FW";
            default: return "OFFLINE";
        }
    }

    @Override public void onBoard(String name, int freeKb, int totalKb, int gifsOnBoard, int mtu) {
        botPage.onBoard(freeKb, totalKb, gifsOnBoard, mtu);
    }

    @Override public void onNowPlaying(String fileName) { botPage.onNowPlaying(fileName); }

    @Override public void onPlayback(int dwellSeconds, int speed) {
        botPage.onPlayback(dwellSeconds, speed);
        playlistPage.onPlayback(dwellSeconds, speed);
    }

    @Override public void onProgress(BotLink.Kind kind, int part, int parts, String label, long partDone, long partSize, long allDone, long allSize) {
        pageFor(kind).onProgress(kind, part, parts, label, partDone, partSize, allDone, allSize);
    }

    @Override public void onJobDone(BotLink.Kind kind, int count) {
        pageFor(kind).onJobDone(kind, count);
        for (Page p : pages) p.onBusyChanged();
    }

    @Override public void onJobFailed(BotLink.Kind kind, String reason) {
        pageFor(kind).onJobFailed(kind, reason);
        for (Page p : pages) p.onBusyChanged();
    }

    @Override public void onLog(String line) { log(line); }

    @Override public void onBoardInfo(String firmware, boolean oledOk, int pairedPhones) {
        botPage.onBoardInfo(firmware, oledOk, pairedPhones);
    }

    @Override public void onPairCodeNeeded(String message) {
        if (pairDialog == null) openPairDialog();
        pairMessage.setText(message == null ? "LOOK AT YOUR BOT'S SCREEN" : message);
        pairMessage.setTextColor(message == null ? Brutal.INK : Brutal.RED);
        pairField.setText("");
        pairField.setEnabled(true);
    }

    @Override public void onPairDone() {
        closePairDialog();
        toast("Paired! Only your phones can control this bot now.");
    }

    /** "Type the code on your bot" sheet, in the app's brutalist style. Auto-sends at 4 digits. */
    private void openPairDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Brutal.block(this, Brutal.PAPER, Brutal.INK, 3, 6, Brutal.INK));
        box.setPadding(dp(20), dp(18), dp(20), dp(20));
        ImageView angel = new ImageView(this);
        angel.setImageBitmap(Motion.art(this, "img/angel.webp"));
        angel.setAdjustViewBounds(true);
        angel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams angelLp = new LinearLayout.LayoutParams(-2, dp(150));
        angelLp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(angel, angelLp);
        if (Motion.on()) {
            angel.setAlpha(0f);
            angel.setTranslationY(dp(14));
            angel.animate().alpha(1f).translationY(0).setDuration(700).setInterpolator(Motion.OUT).start();
        }
        TextView tag = Brutal.mono(this, "01 · PAIR YOUR BOT", 10, Brutal.BLUE, true);
        box.addView(tag, Brutal.spaced(this, -2, -2, 6));
        TextView head = Brutal.display(this, "TYPE THE\nCODE", 34);
        head.setLineSpacing(0, 0.9f);
        box.addView(head, Brutal.spaced(this, -1, -2, 6));
        pairMessage = Brutal.mono(this, "LOOK AT YOUR BOT'S SCREEN", 11, Brutal.INK, true);
        box.addView(pairMessage, Brutal.spaced(this, -1, -2, 8));
        pairField = Brutal.field(this, "0000", 4);
        pairField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairField.setTextSize(34);
        pairField.setTypeface(Brutal.MONO_BOLD);
        pairField.setGravity(Gravity.CENTER);
        pairField.setLetterSpacing(0.4f);
        box.addView(pairField, Brutal.spaced(this, -1, -2, 14));
        TextView note = Brutal.mono(this, "THE BOT REMEMBERS THIS PHONE. NEXT TIME IT CONNECTS BY ITSELF.", 9, Brutal.MUTE, false);
        note.setLineSpacing(0, 1.25f);
        box.addView(note, Brutal.spaced(this, -1, -2, 12));
        TextView cancel = Brutal.button(this, "CANCEL", false);
        box.addView(cancel, Brutal.spaced(this, -1, -2, 16));

        pairField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (e.length() == 4 && pairField.isEnabled()) {
                    pairField.setEnabled(false);
                    pairMessage.setText("CHECKING…");
                    pairMessage.setTextColor(Brutal.INK);
                    link.submitPairCode(e.toString());
                }
            }
        });
        pairDialog = new Dialog(this);
        pairDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        pairDialog.setContentView(box);
        pairDialog.setCancelable(false);
        if (pairDialog.getWindow() != null) pairDialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        cancel.setOnClickListener(v -> { closePairDialog(); link.disconnect(); });
        pairDialog.show();
        pairField.requestFocus();
    }

    private void closePairDialog() {
        if (pairDialog != null) {
            try { pairDialog.dismiss(); } catch (Exception ignored) { }
            pairDialog = null;
        }
    }

    void confirm(String title, String body, String yes, Runnable action) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton(yes, (d, w) -> action.run())
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private Page singleOrigin;

    void startedSingleFrom(Page page) { singleOrigin = page; for (Page p : pages) p.onBusyChanged(); }
    void startedJob() { for (Page p : pages) p.onBusyChanged(); }

    private Page pageFor(BotLink.Kind kind) {
        if (kind == BotLink.Kind.PLAYLIST) return playlistPage;
        if (kind == BotLink.Kind.POKE) return botPage;
        return singleOrigin != null ? singleOrigin : drawPage;
    }

    // =========================================================================
    //  Shared helpers for pages
    // =========================================================================

    void log(String line) {
        Log.i(TAG, line);
        logLines.addLast(clock.format(new Date()) + "  " + line.toUpperCase(Locale.ROOT));
        while (logLines.size() > LOG_LINES) logLines.removeFirst();
        if (botPage != null) botPage.showLog(String.join("\n", logLines));
    }

    void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    int dp(float value) { return Brutal.dp(this, value); }

    void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/gif", "image/png", "image/jpeg", "image/webp"});
        try {
            startActivityForResult(intent, REQUEST_PICK);
        } catch (Exception e) {
            toast("No file picker on this device");
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && result != null && result.getData() != null) {
            Uri uri = result.getData();
            showPage(3);
            convertPage.load(uri);
        }
    }

    // =========================================================================
    //  Connect + permissions
    // =========================================================================

    void connectOrDisconnect() {
        BotLink.Link state = link.link();
        if (state == BotLink.Link.READY || state == BotLink.Link.HANDSHAKE || state == BotLink.Link.PAIRING
                || state == BotLink.Link.CONNECTING || state == BotLink.Link.SCANNING) {
            link.disconnect();
            return;
        }
        if (!hasBluetoothPermissions()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLUETOOTH);
            }
            return;
        }
        if (Build.VERSION.SDK_INT < 31 && !locationServicesOn()) {
            botPage.showWarning("ANDROID " + Build.VERSION.RELEASE + " ONLY RETURNS BLUETOOTH SCAN RESULTS WITH LOCATION SWITCHED ON. TURN ON LOCATION, THEN CONNECT.");
            return;
        }
        link.connect();
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationServicesOn() {
        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return true;
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_BLUETOOTH) return;
        boolean granted = results.length > 0;
        for (int r : results) granted &= r == PackageManager.PERMISSION_GRANTED;
        if (granted) connectOrDisconnect();
        else botPage.showWarning("BLUETOOTH PERMISSION WAS DENIED. THE APP CANNOT SEE THE BOT WITHOUT IT.");
    }
}
