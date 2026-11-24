package com.hyperbot.remote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth LE link to a HyperBot (receiver protocol 3).
 *
 * Every Android BLE callback is re-posted to the main thread and only one GATT
 * operation is ever outstanding. File bytes go out as write-without-response,
 * never more than WINDOW bytes ahead of the board's last ACK.
 */
@SuppressLint("MissingPermission")
final class BotLink {
    static final UUID SERVICE_UUID = UUID.fromString("6c4f0001-8b5e-4a61-a3c2-7c7ab1d24000");
    static final UUID CONTROL_UUID = UUID.fromString("6c4f0002-8b5e-4a61-a3c2-7c7ab1d24000");
    static final UUID DATA_UUID    = UUID.fromString("6c4f0003-8b5e-4a61-a3c2-7c7ab1d24000");
    static final UUID STATUS_UUID  = UUID.fromString("6c4f0004-8b5e-4a61-a3c2-7c7ab1d24000");
    static final UUID NOW_UUID     = UUID.fromString("6c4f0005-8b5e-4a61-a3c2-7c7ab1d24000");
    static final UUID CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int PROTOCOL = 3;
    static final String SELECTED_FILE = "BT_SELECTED.qgif";
    private static final int WINDOW = 6144;          // board stream buffer is 8 KB
    private static final long SCAN_MS = 12000;
    private static final long OP_TIMEOUT_MS = 4000;
    private static final long POLL_MS = 1500;
    private static final long HELLO_MS = 5000;
    private static final long WIPE_MS = 25000;
    private static final long READY_MS = 8000;
    private static final long ACK_STALL_MS = 15000;
    private static final long DONE_MS = 12000;
    private static final int MAX_CONNECT_ATTEMPTS = 3;

    enum Link { OFF, SCANNING, CONNECTING, HANDSHAKE, PAIRING, READY, OLD_FIRMWARE }
    enum Kind { PLAYLIST, SINGLE, POKE }

    interface Listener {
        void onLink(Link link, String detail);
        void onBoard(String name, int freeKb, int totalKb, int gifsOnBoard, int mtu);
        void onNowPlaying(String fileName);
        void onPlayback(int dwellSeconds, int speed);
        void onProgress(Kind kind, int part, int parts, String label, long partDone, long partSize, long allDone, long allSize);
        void onJobDone(Kind kind, int count);
        void onJobFailed(Kind kind, String reason);
        void onLog(String line);
        /** Firmware 2.1+: version, whether the bot found its OLED, phones paired to it. */
        void onBoardInfo(String firmware, boolean oledOk, int pairedPhones);
        /** Show (or refresh) the "type the code on your bot" prompt. message may be null. */
        void onPairCodeNeeded(String message);
        void onPairDone();
    }

    static final class Item {
        final String name;
        final byte[] bytes;
        Item(String name, byte[] bytes) { this.name = name; this.bytes = bytes; }
    }

    /** One upload: the command that opens it, the bytes, and a label for the UI. */
    private static final class Part {
        final String begin;
        final byte[] bytes;
        final String label;
        Part(String begin, byte[] bytes, String label) { this.begin = begin; this.bytes = bytes; this.label = label; }
    }

    // --- GATT operation queue ------------------------------------------------
    private static final int OP_MTU = 1, OP_DISCOVER = 2, OP_CCCD = 3, OP_WRITE = 4, OP_WRITE_NR = 5, OP_READ = 6;

    private static final class Op {
        final int type;
        final byte[] value;
        final UUID target;
        int busyRetries;
        Op(int type, byte[] value, UUID target) { this.type = type; this.value = value; this.target = target; }
    }

    private enum Step { IDLE, WAIT_WIPE, WAIT_READY, SENDING, WAIT_PART_DONE, WAIT_FINAL }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Link link = Link.OFF;
    private BluetoothLeScanner scanner;
    private BluetoothDevice device;
    private String deviceName = "HyperBot";
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic control, data, status, now;
    private int connectAttempts;
    private int mtu = 23;
    private int chunk = 20;
    private int scanHits;
    private final Set<String> scanNames = new LinkedHashSet<>();

    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private Op current;
    private long currentStartedAt;

    private Step step = Step.IDLE;
    private Kind kind;
    private final List<Part> parts = new ArrayList<>();
    private int index;
    private long totalBytes;
    private long doneBeforeCurrent;
    private int sent;
    private int acked;
    private boolean dataOpQueued;
    private boolean readQueued;
    private long stepDeadline;
    private long lastHeardAt;
    private long lastAckAt;
    private boolean helloPending;
    private String lastSingleName;
    private String nowPlaying = "";
    private int dwellSeconds = -1;
    private int speed = -1;

    // --- pairing (firmware 2.1+) ---
    private final SharedPreferences pairing;
    private String[] helloParts;          // HELLO reply held until the phone is authorised
    private String firmware = "";
    private boolean oledOk = true;
    private int pairedPhones;
    private boolean authPending;          // sent AUTH, waiting for AUTH OK
    private boolean pairWaiting;          // sent PAIR, waiting for PAIR WAIT
    private boolean pairSubmitted;        // sent PAIR <code>, waiting for PAIRED / error

    BotLink(Context context, BluetoothAdapter adapter, Listener listener) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
        this.listener = listener;
        this.pairing = this.context.getSharedPreferences("hyperbot_pairing", Context.MODE_PRIVATE);
    }

    Link link() { return link; }
    boolean isBusy() { return step != Step.IDLE; }
    Kind busyKind() { return step == Step.IDLE ? null : kind; }
    String deviceName() { return deviceName; }
    boolean ready() { return link == Link.READY && step == Step.IDLE; }
    int dwellSeconds() { return dwellSeconds; }
    int speed() { return speed; }
    String firmware() { return firmware; }
    boolean oledOk() { return oledOk; }
    int pairedPhones() { return pairedPhones; }

    /** The user typed the 4-digit code shown on the bot. */
    void submitPairCode(String code) {
        if (link != Link.PAIRING || code == null || !code.matches("\\d{4}")) return;
        pairSubmitted = true;
        lastHeardAt = SystemClock.uptimeMillis();
        enqueue(new Op(OP_WRITE, ascii("PAIR " + code), CONTROL_UUID));
    }

    /** Unpair this phone (the bot forgets it, this phone forgets the bot). */
    boolean forgetThisPhone() {
        if (!ready()) return false;
        enqueue(new Op(OP_WRITE, ascii("FORGET"), CONTROL_UUID));
        clearToken();
        return true;
    }

    /** Wipe every paired phone and re-run first-time setup on the bot. GIFs stay. */
    boolean resetBot() {
        if (!ready()) return false;
        enqueue(new Op(OP_WRITE, ascii("RESET"), CONTROL_UUID));
        clearToken();
        return true;
    }

    private String tokenKey() {
        String id = null;
        try { id = device == null ? null : device.getAddress(); } catch (SecurityException ignored) { }
        return "token_" + (id != null ? id : deviceName);
    }
    private String savedToken() { return pairing.getString(tokenKey(), null); }
    private void saveToken(String token) { pairing.edit().putString(tokenKey(), token).apply(); }
    private void clearToken() { pairing.edit().remove(tokenKey()).apply(); }

    private void startPairing() {
        pairWaiting = true;
        pairSubmitted = false;
        stepDeadline = SystemClock.uptimeMillis() + HELLO_MS;
        lastHeardAt = SystemClock.uptimeMillis();
        enqueue(new Op(OP_WRITE, ascii("PAIR"), CONTROL_UUID));
    }

    /** Authorised (or old firmware without pairing): report the board and go READY. */
    private void finishHandshake() {
        String[] p = helloParts;
        helloParts = null;
        if (p == null) return;
        listener.onBoard(deviceName, intAt(p, 2), intAt(p, 3), intAt(p, 4), mtu);
        dwellSeconds = intAt(p, 5);
        speed = intAt(p, 6);
        if (dwellSeconds > 0) listener.onPlayback(dwellSeconds, speed);
        if (!firmware.isEmpty()) listener.onBoardInfo(firmware, oledOk, pairedPhones);
        log("board ready: " + intAt(p, 2) + " KB free, " + intAt(p, 4) + " gifs"
                + (firmware.isEmpty() ? "" : ", firmware " + firmware));
        setLink(Link.READY, deviceName);
    }

    private static String kv(String[] parts, String key) {
        for (String part : parts) if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        return null;
    }

    /** Minimum seconds one animation stays on the bot's screen (it repeats whole loops). */
    boolean setDwell(int seconds) {
        if (!ready() || seconds < 2 || seconds > 60) return false;
        enqueue(new Op(OP_WRITE, ascii("DWELL " + seconds), CONTROL_UUID));
        return true;
    }

    /** 1 = frames play as drawn, 2 = twice as fast, ... */
    boolean setSpeed(int divisor) {
        if (!ready() || divisor < 1 || divisor > 10) return false;
        enqueue(new Op(OP_WRITE, ascii("SPEED " + divisor), CONTROL_UUID));
        return true;
    }

    /** The file on screen, with the single-upload slot mapped back to the animation you sent. */
    String nowPlaying() {
        if (SELECTED_FILE.equalsIgnoreCase(nowPlaying) && lastSingleName != null) return lastSingleName;
        return nowPlaying;
    }

    // =========================================================================
    //  Scan + connect
    // =========================================================================

    void connect() {
        if (link != Link.OFF && link != Link.OLD_FIRMWARE) return;
        if (adapter == null || !adapter.isEnabled()) { setLink(Link.OFF, "BLUETOOTH IS OFF"); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { setLink(Link.OFF, "NO BLE SCANNER"); return; }
        scanHits = 0;
        scanNames.clear();
        connectAttempts = 0;
        setLink(Link.SCANNING, "LOOKING FOR HYPERBOT");
        log("scan started");
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(Collections.emptyList(), settings, scanCallback);
        } catch (SecurityException e) {
            setLink(Link.OFF, "BLUETOOTH PERMISSION MISSING");
            return;
        }
        main.postDelayed(scanTimeout, SCAN_MS);
    }

    void disconnect() {
        stopScan();
        if (step != Step.IDLE) failJob("DISCONNECTED");
        closeGatt();
        nowPlaying = "";
        setLink(Link.OFF, "NOT LINKED");
        log("disconnected");
    }

    private final Runnable scanTimeout = () -> {
        if (link != Link.SCANNING) return;
        stopScan();
        String seen = scanNames.isEmpty() ? "nothing nearby" : String.join(", ", scanNames);
        log("scan: " + scanHits + " adverts, no HyperBot (" + seen + ")");
        setLink(Link.OFF, "NO HYPERBOT FOUND");
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { main.post(() -> handleScanResult(result)); }

        @Override public void onScanFailed(int errorCode) {
            main.post(() -> {
                if (link != Link.SCANNING) return;
                main.removeCallbacks(scanTimeout);
                setLink(Link.OFF, "SCAN FAILED (" + errorCode + ")");
            });
        }
    };

    private void handleScanResult(ScanResult result) {
        if (link != Link.SCANNING) return;
        scanHits++;
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        if (name == null) {
            try { name = result.getDevice().getName(); } catch (SecurityException ignored) { }
        }
        if (name != null && !name.isEmpty() && scanNames.size() < 4) scanNames.add(name);
        boolean byService = record != null && record.getServiceUuids() != null
                && record.getServiceUuids().contains(new ParcelUuid(SERVICE_UUID));
        String upper = name == null ? "" : name.toUpperCase(Locale.ROOT);
        boolean byName = upper.startsWith("HYPERBOT") || upper.startsWith("QBIT");   // QBIT = not yet reflashed
        if (!byService && !byName) return;

        stopScan();
        device = result.getDevice();
        deviceName = name == null ? "HyperBot" : name;
        log("found " + deviceName + " (" + result.getRssi() + " dBm)");
        openGatt();
    }

    private void openGatt() {
        connectAttempts++;
        setLink(Link.CONNECTING, "LINKING " + deviceName);
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            setLink(Link.OFF, "BLUETOOTH PERMISSION MISSING");
        }
    }

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (link == Link.SCANNING && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
    }

    private void closeGatt() {
        main.removeCallbacks(watchdog);
        ops.clear();
        current = null;
        dataOpQueued = false;
        readQueued = false;
        helloPending = false;
        authPending = pairWaiting = pairSubmitted = false;
        helloParts = null;
        BluetoothGatt old = gatt;
        gatt = null;
        control = data = status = now = null;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) { }
            try { old.close(); } catch (Exception ignored) { }
        }
    }

    // =========================================================================
    //  GATT callbacks (binder thread -> main thread)
    // =========================================================================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            main.post(() -> handleConnectionState(g, st, newState));
        }

        @Override public void onMtuChanged(BluetoothGatt g, int newMtu, int st) {
            main.post(() -> {
                if (g != gatt) return;
                if (st == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
                completeOp(OP_MTU, true, null);
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            main.post(() -> { if (g == gatt) completeOp(OP_DISCOVER, st == BluetoothGatt.GATT_SUCCESS, null); });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            main.post(() -> { if (g == gatt) completeOp(OP_CCCD, st == BluetoothGatt.GATT_SUCCESS, null); });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            main.post(() -> {
                if (g != gatt || current == null) return;
                completeOp(current.type, st == BluetoothGatt.GATT_SUCCESS, null);
            });
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            final byte[] value = c.getValue();
            main.post(() -> { if (g == gatt) completeOp(OP_READ, st == BluetoothGatt.GATT_SUCCESS, value); });
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int st) {
            main.post(() -> { if (g == gatt) completeOp(OP_READ, st == BluetoothGatt.GATT_SUCCESS, value); });
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] value = c.getValue();
            final UUID uuid = c.getUuid();
            main.post(() -> { if (g == gatt) onNotification(uuid, value); });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            final UUID uuid = c.getUuid();
            main.post(() -> { if (g == gatt) onNotification(uuid, value); });
        }
    };

    private void onNotification(UUID uuid, byte[] value) {
        if (STATUS_UUID.equals(uuid)) onStatus(decode(value));
        else if (NOW_UUID.equals(uuid)) onNow(decode(value));
    }

    private void onNow(String file) {
        nowPlaying = file;
        listener.onNowPlaying(nowPlaying());
    }

    private void handleConnectionState(BluetoothGatt g, int st, int newState) {
        if (g != gatt) {
            try { g.close(); } catch (Exception ignored) { }
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
            log("connected, negotiating");
            setLink(Link.HANDSHAKE, "TALKING TO " + deviceName);
            mtu = 23;
            main.removeCallbacks(watchdog);
            main.postDelayed(watchdog, 500);
            main.postDelayed(() -> {      // avoids a known race between connect and the first GATT op
                if (g != gatt) return;
                enqueue(new Op(OP_MTU, null, null));
                enqueue(new Op(OP_DISCOVER, null, null));
            }, 300);
            return;
        }

        boolean wasUp = link == Link.READY || link == Link.HANDSHAKE || link == Link.PAIRING;
        closeGatt();
        if (link == Link.CONNECTING && connectAttempts < MAX_CONNECT_ATTEMPTS) {
            log("connect failed (status " + st + "), retrying");
            main.postDelayed(this::openGatt, 700);
            return;
        }
        if (step != Step.IDLE) failJob("LINK LOST");
        if (link == Link.OLD_FIRMWARE) return;
        nowPlaying = "";
        log(wasUp ? "link lost (status " + st + ")" : "could not connect (status " + st + ")");
        setLink(Link.OFF, wasUp ? "LINK LOST" : "COULD NOT CONNECT");
    }

    // =========================================================================
    //  Op queue
    // =========================================================================

    private void enqueue(Op op) {
        ops.add(op);
        pump();
    }

    private void pump() {
        if (current != null || gatt == null) return;
        Op next = ops.poll();
        if (next == null) return;
        current = next;
        currentStartedAt = SystemClock.uptimeMillis();
        int result = start(next);
        if (result == 0) return;
        current = null;
        if (result == 201 && next.busyRetries++ < 40) {   // stack busy, try again shortly
            ops.addFirst(next);
            main.postDelayed(this::pump, 10);
            return;
        }
        opFailed(next, "COULD NOT START BLE OP " + next.type + " (" + result + ")");
    }

    private BluetoothGattCharacteristic characteristic(UUID uuid) {
        if (CONTROL_UUID.equals(uuid)) return control;
        if (DATA_UUID.equals(uuid)) return data;
        if (NOW_UUID.equals(uuid)) return now;
        return status;
    }

    /** @return 0 when the op started, otherwise an error code. */
    @SuppressWarnings("deprecation")
    private int start(Op op) {
        BluetoothGatt g = gatt;
        if (g == null) return -1;
        try {
            switch (op.type) {
                case OP_MTU:
                    return g.requestMtu(517) ? 0 : -2;
                case OP_DISCOVER:
                    return g.discoverServices() ? 0 : -3;
                case OP_CCCD: {
                    BluetoothGattCharacteristic c = characteristic(op.target);
                    if (c == null) return -4;
                    g.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor cccd = c.getDescriptor(CCCD_UUID);
                    if (cccd == null) return -4;
                    if (Build.VERSION.SDK_INT >= 33) return g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    return g.writeDescriptor(cccd) ? 0 : 201;
                }
                case OP_WRITE:
                case OP_WRITE_NR: {
                    BluetoothGattCharacteristic c = op.type == OP_WRITE ? control : data;
                    int type = op.type == OP_WRITE ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                    if (Build.VERSION.SDK_INT >= 33) {
                        int code = g.writeCharacteristic(c, op.value, type);
                        return code == BluetoothStatusCodes.SUCCESS ? 0 : code;
                    }
                    c.setWriteType(type);
                    c.setValue(op.value);
                    return g.writeCharacteristic(c) ? 0 : 201;
                }
                case OP_READ: {
                    BluetoothGattCharacteristic c = characteristic(op.target);
                    if (c == null) return -5;
                    return g.readCharacteristic(c) ? 0 : 201;
                }
                default:
                    return -9;
            }
        } catch (SecurityException e) {
            return -10;
        }
    }

    private void completeOp(int type, boolean ok, byte[] value) {
        if (current == null || current.type != type) return;
        Op done = current;
        current = null;
        if (type == OP_WRITE_NR) dataOpQueued = false;
        if (type == OP_READ && STATUS_UUID.equals(done.target)) readQueued = false;
        if (!ok) {
            opFailed(done, "BLE OP " + type + " FAILED");
            pump();
            return;
        }
        switch (type) {
            case OP_MTU:
                chunk = Math.max(20, Math.min(509, mtu - 3));
                if (gatt != null) {
                    try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (SecurityException ignored) { }
                }
                log("mtu " + mtu + ", chunk " + chunk + " B");
                break;
            case OP_DISCOVER:
                if (!bindCharacteristics()) {
                    log("gif service missing on " + deviceName);
                    closeGatt();
                    setLink(Link.OLD_FIRMWARE, "NO GIF SERVICE ON BOARD");
                    return;
                }
                enqueue(new Op(OP_CCCD, null, STATUS_UUID));
                if (now != null) {
                    enqueue(new Op(OP_CCCD, null, NOW_UUID));
                    enqueue(new Op(OP_READ, null, NOW_UUID));
                }
                break;
            case OP_CCCD:
                if (STATUS_UUID.equals(done.target)) {
                    helloPending = true;
                    stepDeadline = SystemClock.uptimeMillis() + HELLO_MS;
                    lastHeardAt = SystemClock.uptimeMillis();
                    enqueue(new Op(OP_WRITE, ascii("HELLO"), CONTROL_UUID));
                }
                break;
            case OP_READ:
                if (value != null) {
                    if (NOW_UUID.equals(done.target)) onNow(decode(value));
                    else onStatus(decode(value));
                }
                break;
            case OP_WRITE_NR:
                pumpData();
                break;
            default:
                break;
        }
        pump();
    }

    private void opFailed(Op op, String message) {
        log(message.toLowerCase(Locale.ROOT));
        if (op.type == OP_WRITE_NR) dataOpQueued = false;
        if (op.type == OP_READ) {
            if (STATUS_UUID.equals(op.target)) readQueued = false;
            return;                                   // polling is best effort
        }
        if (op.type == OP_CCCD && NOW_UUID.equals(op.target)) return;   // mirror is optional
        if (link == Link.HANDSHAKE) {
            closeGatt();
            setLink(Link.OFF, "HANDSHAKE FAILED");
            return;
        }
        if (step != Step.IDLE) failJob(message);
    }

    private boolean bindCharacteristics() {
        BluetoothGattService service = gatt == null ? null : gatt.getService(SERVICE_UUID);
        if (service == null) return false;
        control = service.getCharacteristic(CONTROL_UUID);
        data = service.getCharacteristic(DATA_UUID);
        status = service.getCharacteristic(STATUS_UUID);
        now = service.getCharacteristic(NOW_UUID);
        return control != null && data != null && status != null;
    }

    // =========================================================================
    //  Watchdog: stuck ops, lost notifications, stage deadlines
    // =========================================================================

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (gatt == null) return;
            long t = SystemClock.uptimeMillis();

            if (current != null && t - currentStartedAt > OP_TIMEOUT_MS) {
                Op stuck = current;
                current = null;
                if (stuck.type == OP_WRITE_NR) dataOpQueued = false;
                if (stuck.type == OP_READ && STATUS_UUID.equals(stuck.target)) readQueued = false;
                opFailed(stuck, "BLE OP " + stuck.type + " TIMED OUT");
                pump();
            }

            boolean waiting = helloPending || authPending || pairWaiting || pairSubmitted || step == Step.WAIT_WIPE || step == Step.WAIT_READY
                    || step == Step.WAIT_PART_DONE || step == Step.WAIT_FINAL
                    || (step == Step.SENDING && (sent - acked >= WINDOW || sent >= partSize()));
            if (waiting && t - lastHeardAt > POLL_MS && !readQueued) {
                // A notification may have been dropped; the status value always holds the latest reply.
                readQueued = true;
                enqueue(new Op(OP_READ, null, STATUS_UUID));
            }

            if (helloPending && t > stepDeadline) {
                helloPending = false;
                log("no HELLO reply from board");
                closeGatt();
                setLink(Link.OLD_FIRMWARE, "BOARD DID NOT ANSWER");
                return;
            }
            if ((authPending || pairWaiting) && t > stepDeadline) {
                log("bot did not answer the pairing check");
                closeGatt();
                setLink(Link.OFF, "HANDSHAKE FAILED");
                return;
            }
            if (step == Step.SENDING && t - lastAckAt > ACK_STALL_MS) {
                failJob("BOARD STOPPED ACKNOWLEDGING");
            } else if (step != Step.IDLE && step != Step.SENDING && t > stepDeadline) {
                failJob("TIMED OUT WAITING FOR BOARD");
            }
            main.postDelayed(this, 250);
        }
    };

    // =========================================================================
    //  Status replies from the board
    // =========================================================================

    private void onStatus(String message) {
        if (message.isEmpty()) return;
        lastHeardAt = SystemClock.uptimeMillis();
        String[] p = message.split(" ");
        String head = p[0];

        if (authPending) {
            if (message.equals("AUTH OK")) {
                authPending = false;
                finishHandshake();
            } else if (message.startsWith("ERROR:AUTH_FAIL")) {
                authPending = false;
                clearToken();
                log("the bot no longer knows this phone, pairing again");
                startPairing();
            }
            return;
        }
        if (pairWaiting) {
            if (message.equals("PAIR WAIT")) {
                pairWaiting = false;
                setLink(Link.PAIRING, "TYPE THE CODE ON YOUR BOT");
                listener.onPairCodeNeeded(null);
            }
            return;
        }
        if (link == Link.PAIRING) {
            if (head.equals("PAIRED") && p.length > 1) {
                pairSubmitted = false;
                saveToken(p[1]);
                pairedPhones = Math.max(1, pairedPhones + 1);
                log("paired: this phone is now trusted by " + deviceName);
                listener.onPairDone();
                finishHandshake();
            } else if (head.equals("ERROR:PAIR_WRONG")) {
                pairSubmitted = false;
                int left = intAt(p, 1);
                listener.onPairCodeNeeded("WRONG CODE · " + left + (left == 1 ? " TRY" : " TRIES") + " LEFT");
            } else if (head.equals("ERROR:PAIR_LOCKED") || head.equals("ERROR:PAIR_EXPIRED")) {
                pairSubmitted = false;
                listener.onPairCodeNeeded(head.endsWith("LOCKED") ? "TOO MANY TRIES · NEW CODE ON THE BOT" : "CODE EXPIRED · NEW CODE ON THE BOT");
                pairWaiting = true;
                stepDeadline = SystemClock.uptimeMillis() + HELLO_MS;
                enqueue(new Op(OP_WRITE, ascii("PAIR"), CONTROL_UUID));
            }
            return;
        }

        if (head.equals("FORGOTTEN") || head.equals("RESETTING")) {
            log(head.equals("FORGOTTEN") ? "the bot forgot this phone" : "bot is resetting and restarting");
            disconnect();
            return;
        }

        if (helloPending) {
            if (head.equals("HELLO")) {
                helloPending = false;
                int version = intAt(p, 1);
                if (version < PROTOCOL) {
                    log("board speaks protocol " + version + ", app needs " + PROTOCOL);
                    closeGatt();
                    setLink(Link.OLD_FIRMWARE, "BOARD FIRMWARE IS OLDER THAN THE APP");
                    return;
                }
                helloParts = p;
                String fw = kv(p, "fw");
                if (fw == null) {               // firmware 2.0: no pairing, straight in
                    firmware = "";
                    finishHandshake();
                    return;
                }
                firmware = fw;
                oledOk = !"0".equals(kv(p, "oled"));
                String paired = kv(p, "paired");
                pairedPhones = paired == null ? 0 : intAt(new String[]{paired}, 0);
                String token = savedToken();
                if (token != null) {
                    authPending = true;
                    stepDeadline = SystemClock.uptimeMillis() + HELLO_MS;
                    enqueue(new Op(OP_WRITE, ascii("AUTH " + token), CONTROL_UUID));
                } else {
                    log("new bot: asking it for a pairing code");
                    startPairing();
                }
            } else if (message.equals("IDLE") || message.startsWith("ERROR:unknown") || message.startsWith("ERROR:invalid")) {
                helloPending = false;   // replies only the oldest firmware sends
                log("board answered '" + message + "': firmware predates the protocol");
                closeGatt();
                setLink(Link.OLD_FIRMWARE, "BOARD FIRMWARE IS OUT OF DATE");
            }
            return;
        }

        if (head.equals("DWELL") || head.equals("SPEED")) {
            if (head.equals("DWELL")) dwellSeconds = intAt(p, 1); else speed = intAt(p, 1);
            log("bot " + (head.equals("DWELL") ? "keeps each gif " + dwellSeconds + " s" : "plays at speed " + speed + "x"));
            listener.onPlayback(dwellSeconds, speed);
            return;
        }

        if (head.startsWith("ERROR:")) {
            if (step != Step.IDLE) failJob(explain(message));
            else log("board: " + message.toLowerCase(Locale.ROOT));
            return;
        }

        switch (step) {
            case WAIT_WIPE:
                if (head.equals("WIPING")) log("board is clearing old gifs");
                else if (head.equals("PLAYLIST")) {
                    log("board cleared, " + intAt(p, 1) + " KB free");
                    beginPart(0);
                }
                break;
            case WAIT_READY:
                if (head.equals("READY") && intAt(p, 1) == partSize()) {
                    step = Step.SENDING;
                    lastAckAt = SystemClock.uptimeMillis();
                    pumpData();
                }
                break;
            case SENDING:
                if (head.equals("ACK")) {
                    int n = intAt(p, 1);
                    if (n > acked && n <= partSize()) {
                        acked = n;
                        lastAckAt = SystemClock.uptimeMillis();
                        reportProgress();
                        if (acked == partSize()) {
                            step = Step.WAIT_PART_DONE;
                            stepDeadline = SystemClock.uptimeMillis() + DONE_MS;
                            enqueue(new Op(OP_WRITE, ascii("END"), CONTROL_UUID));
                        } else {
                            pumpData();
                        }
                    }
                }
                break;
            case WAIT_PART_DONE:
                onPartDone(head, p);
                break;
            case WAIT_FINAL:
                if (head.equals("PLAYING")) finishJob();
                break;
            default:
                break;
        }
    }

    private void onPartDone(String head, String[] p) {
        switch (kind) {
            case PLAYLIST:
                if (head.equals("SAVED") && intAt(p, 1) == index + 1) {
                    log("saved " + (index + 1) + "/" + parts.size() + " " + parts.get(index).label.toLowerCase(Locale.ROOT));
                    if (index + 1 < parts.size()) beginPart(index + 1);
                    else {
                        step = Step.WAIT_FINAL;
                        stepDeadline = SystemClock.uptimeMillis() + DONE_MS;
                    }
                } else if (head.equals("PLAYING") && index + 1 == parts.size()) {
                    finishJob();
                }
                break;
            case SINGLE:
                if (head.equals("PLAYING")) finishJob();
                break;
            case POKE:
                if (head.equals("POKED")) finishJob();
                break;
        }
    }

    // =========================================================================
    //  Jobs
    // =========================================================================

    /** Replace everything on the board with these animations, then shuffle them. */
    boolean sendPlaylist(List<Item> items) {
        if (!ready() || items.isEmpty() || items.size() > 8) return false;
        List<Part> list = new ArrayList<>();
        long total = 0;
        for (Item item : items) {
            list.add(new Part("BEGIN\n" + item.name + "\n" + item.bytes.length, item.bytes, label(item.name)));
            total += item.bytes.length;
        }
        startJob(Kind.PLAYLIST, list);
        step = Step.WAIT_WIPE;
        stepDeadline = SystemClock.uptimeMillis() + WIPE_MS;
        log("sending " + items.size() + " gifs, " + (total / 1024) + " KB");
        listener.onProgress(kind, 0, parts.size(), "CLEARING BOARD", 0, parts.get(0).bytes.length, 0, totalBytes);
        enqueue(new Op(OP_WRITE, ascii("PLAYLIST " + items.size() + " " + total), CONTROL_UUID));
        return true;
    }

    /** Play one animation right now; everything already on the board stays. */
    boolean sendSingle(Item item) {
        if (!ready()) return false;
        List<Part> list = new ArrayList<>();
        list.add(new Part("BEGIN\n" + item.name + "\n" + item.bytes.length, item.bytes, label(item.name)));
        startJob(Kind.SINGLE, list);
        lastSingleName = item.name;
        log("sending " + item.name.toLowerCase(Locale.ROOT) + " to play now");
        beginPart(0);
        return true;
    }

    /** Show a poke on the bot's screen (pre-rendered so emoji work). */
    boolean sendPoke(String sender, String text, PokeRenderer.Rendered r) {
        if (!ready()) return false;
        byte[] both = new byte[r.senderPages.length + r.textPages.length];
        System.arraycopy(r.senderPages, 0, both, 0, r.senderPages.length);
        System.arraycopy(r.textPages, 0, both, r.senderPages.length, r.textPages.length);
        String safeSender = ascii7(sender, 32, "Phone");
        String safeText = ascii7(text, 64, "Poke!");
        String begin = "POKE " + r.senderW + " " + r.senderPages.length + " " + r.textW + " " + r.textPages.length
                + "\n" + safeSender + "\n" + safeText;
        List<Part> list = new ArrayList<>();
        list.add(new Part(begin, both, "POKE"));
        startJob(Kind.POKE, list);
        log("poking " + deviceName);
        beginPart(0);
        return true;
    }

    void cancel() {
        if (step == Step.IDLE) return;
        Kind was = kind;
        log("cancelled by you");
        ops.removeIf(op -> op.type == OP_WRITE_NR);
        step = Step.IDLE;
        enqueue(new Op(OP_WRITE, ascii("ABORT"), CONTROL_UUID));
        listener.onJobFailed(was, "CANCELLED");
    }

    private void startJob(Kind k, List<Part> list) {
        kind = k;
        parts.clear();
        parts.addAll(list);
        totalBytes = 0;
        for (Part part : list) totalBytes += part.bytes.length;
        index = 0;
        doneBeforeCurrent = 0;
        lastHeardAt = SystemClock.uptimeMillis();
    }

    private void beginPart(int i) {
        index = i;
        doneBeforeCurrent = 0;
        for (int k = 0; k < i; k++) doneBeforeCurrent += parts.get(k).bytes.length;
        sent = 0;
        acked = 0;
        step = Step.WAIT_READY;
        stepDeadline = SystemClock.uptimeMillis() + READY_MS;
        reportProgress();
        enqueue(new Op(OP_WRITE, ascii(parts.get(i).begin), CONTROL_UUID));
    }

    private void pumpData() {
        if (step != Step.SENDING || dataOpQueued) return;
        byte[] bytes = parts.get(index).bytes;
        if (sent >= bytes.length || sent - acked >= WINDOW) return;
        int length = Math.min(chunk, bytes.length - sent);
        length = Math.min(length, WINDOW - (sent - acked));
        byte[] piece = new byte[length];
        System.arraycopy(bytes, sent, piece, 0, length);
        sent += length;
        dataOpQueued = true;
        enqueue(new Op(OP_WRITE_NR, piece, DATA_UUID));
    }

    private void reportProgress() {
        Part part = parts.get(index);
        listener.onProgress(kind, index, parts.size(), part.label, acked, part.bytes.length, doneBeforeCurrent + acked, totalBytes);
    }

    private void finishJob() {
        Kind was = kind;
        int count = parts.size();
        step = Step.IDLE;
        parts.clear();
        if (was == Kind.PLAYLIST) log("board is shuffling the new set");
        else if (was == Kind.SINGLE) log("board is playing it now");
        else log("poke delivered");
        listener.onJobDone(was, count);
    }

    private void failJob(String reason) {
        if (step == Step.IDLE) return;
        Kind was = kind;
        step = Step.IDLE;
        ops.removeIf(op -> op.type == OP_WRITE_NR);
        dataOpQueued = false;
        log("failed: " + reason.toLowerCase(Locale.ROOT));
        if (gatt != null) enqueue(new Op(OP_WRITE, ascii("ABORT"), CONTROL_UUID));
        listener.onJobFailed(was, reason);
    }

    private int partSize() { return index < parts.size() ? parts.get(index).bytes.length : 0; }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private static String explain(String error) {
        String body = error.substring("ERROR:".length());
        String code = body.split(" ")[0];
        switch (code) {
            case "NO_SPACE": {
                String[] p = body.split(" ");
                return p.length >= 3 ? "NOT ENOUGH SPACE: NEED " + p[1] + " KB, HAVE " + p[2] + " KB" : "NOT ENOUGH SPACE ON BOARD";
            }
            case "BUSY": return "BOARD IS BUSY. TRY AGAIN";
            case "BAD_QGIF": return "BOARD REJECTED A FILE (NOT 128x64)";
            case "BAD_NAME": return "BOARD REJECTED THE FILE NAME";
            case "INCOMPLETE": return "FILE ARRIVED INCOMPLETE";
            case "OVERRUN": return "BOARD COULD NOT KEEP UP";
            case "TIMEOUT": return "BOARD STOPPED RECEIVING";
            case "WIPE_FAILED": return "BOARD COULD NOT CLEAR OLD GIFS";
            case "BAD_POKE": return "POKE TOO BIG FOR THE SCREEN";
            case "POKE_QUEUE": return "BOARD IS BUSY SHOWING SOMETHING";
            case "NO_MEMORY": return "BOARD IS OUT OF MEMORY";
            case "FS_WRITE":
            case "FS_OPEN":
            case "FS_RENAME": return "BOARD FLASH WRITE FAILED";
            default: return body.replace('_', ' ').toUpperCase(Locale.ROOT);
        }
    }

    static String label(String file) {
        String base = Library.bare(file);
        if (base.startsWith("my_")) base = base.substring(3);
        return base.replace('_', ' ').replace('-', ' ').toUpperCase(Locale.ROOT);
    }

    /** The plain-text copy of a poke (history, Home Assistant) must be single-line ASCII. */
    private static String ascii7(String s, int max, String fallback) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            if (c >= 32 && c < 127) out.append(c);
        }
        String r = out.toString().trim();
        return r.isEmpty() ? fallback : r;
    }

    private void setLink(Link next, String detail) {
        link = next;
        listener.onLink(next, detail);
    }

    private void log(String line) { listener.onLog(line); }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    private static String decode(byte[] value) {
        if (value == null) return "";
        return new String(value, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    private static int intAt(String[] parts, int i) {
        if (i >= parts.length) return -1;
        try { return Integer.parseInt(parts[i]); } catch (NumberFormatException e) { return -1; }
    }
}
