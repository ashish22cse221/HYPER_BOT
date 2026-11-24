// ==========================================================================
//  HyperBot -- Bluetooth LE animation receiver (protocol 3)
// ==========================================================================
//
//  GATT service 6c4f0001-...:
//    CONTROL  (write)              text commands, see below
//    DATA     (write, write-no-rsp) raw .qgif bytes of the current file
//    STATUS   (read, notify)       text replies; the value always holds the
//                                  latest reply so a phone can re-read it
//    NOW      (read, notify)       filename of the animation on screen
//                                  (live mirror); separate so it never
//                                  overwrites a STATUS reply mid-transfer
//
//  Commands -> replies
//    HELLO                        -> HELLO 3 <freeKB> <totalKB> <qgifCount> <dwellS> <speed>
//    DWELL <seconds>              -> DWELL <seconds>   (min time one GIF stays on screen, saved)
//    SPEED <divisor>              -> SPEED <divisor>   (1 = as drawn, 2 = twice as fast, saved)
//    PLAYLIST <count> <bytes>     -> WIPING, then PLAYLIST <freeKB>
//    BEGIN\n<name>\n<size>        -> READY <size>
//    (DATA writes)                -> ACK <bytesWritten>  every ACK_STEP bytes
//    END                          -> SAVED <n>  (playlist)  /  PLAYING (single)
//                                    PLAYING <count> after the last file
//    POKE <sw> <sl> <tw> <tl>\n<sender>\n<text>
//                                 -> READY <sl+tl>, DATA = sender bitmap then
//                                    text bitmap (SSD1306 page format, up to
//                                    512 px wide, 8 pages), ACKs, END -> POKED
//    ABORT                        -> ABORTED
//    any failure                  -> ERROR:<CODE> [detail]
//
//  Pairing (firmware 2.1+). HELLO, AUTH, PAIR and ABORT work for anyone;
//  everything else needs a paired phone (ERROR:AUTH_REQUIRED otherwise).
//    HELLO  ... now ends with   fw=<ver> auth=<0|1> paired=<n> setup=<0|1> oled=<0|1>
//    PAIR                         -> PAIR WAIT          (4-digit code appears on the OLED)
//    PAIR <code>                  -> PAIRED <token>     / ERROR:PAIR_WRONG <triesLeft>
//                                    / ERROR:PAIR_EXPIRED / ERROR:PAIR_LOCKED
//    AUTH <token>                 -> AUTH OK            / ERROR:AUTH_FAIL
//    FORGET                       -> FORGOTTEN          (this phone is unpaired)
//    RESET                        -> RESETTING, reboot  (forget all phones, setup again)
//
//  Flow control: the phone keeps at most WINDOW bytes un-ACKed in flight.
//  ACKs are sent only after bytes are on flash, and WINDOW is smaller than
//  the stream buffer, so the BLE stack never has to drop a packet.
//
//  Threading: BLE callbacks only copy bytes into a queue / stream buffer.
//  A worker task does all flash I/O and sends every notification. Anything
//  that touches the GIF player's open file runs on the display task through
//  a small job hand-off (bleGifReceiverProcessPending).
// ==========================================================================

#include "ble_gif_receiver.h"

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <LittleFS.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#include <freertos/stream_buffer.h>
#include <freertos/task.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "app_state.h"
#include "display_helpers.h"
#include "gif_player.h"
#include "settings.h"
#include "hb_setup.h"

namespace {

constexpr char SERVICE_UUID[] = "6c4f0001-8b5e-4a61-a3c2-7c7ab1d24000";
constexpr char CONTROL_UUID[] = "6c4f0002-8b5e-4a61-a3c2-7c7ab1d24000";
constexpr char DATA_UUID[]    = "6c4f0003-8b5e-4a61-a3c2-7c7ab1d24000";
constexpr char STATUS_UUID[]  = "6c4f0004-8b5e-4a61-a3c2-7c7ab1d24000";
constexpr char NOW_UUID[]     = "6c4f0005-8b5e-4a61-a3c2-7c7ab1d24000";

constexpr uint8_t  PROTOCOL_VERSION  = 3;
constexpr char     TEMP_PATH[]       = "/BT_UPLOAD.tmp";
constexpr char     SELECTED_PATH[]   = "/BT_SELECTED.qgif";
constexpr char     SELECTED_NAME[]   = "BT_SELECTED.qgif";
constexpr size_t   MAX_FILE_SIZE     = 256 * 1024;
constexpr size_t   FS_HEADROOM       = 16 * 1024;   // keep LittleFS out of the red
constexpr size_t   STREAM_SIZE       = 8 * 1024;    // phone window is 6 KB
constexpr size_t   ACK_STEP          = 1536;
constexpr uint8_t  PLAYLIST_MAX      = 8;
constexpr uint32_t DATA_STALL_MS     = 15000;
constexpr uint32_t END_DRAIN_MS      = 3000;
constexpr uint32_t DISPLAY_JOB_MS    = 15000;
constexpr size_t   COMMAND_LEN       = 160;
constexpr uint16_t POKE_MAX_WIDTH    = 512;
constexpr uint8_t  POKE_MAX_PAGES    = 8;
constexpr uint32_t NOW_POLL_MS       = 300;

struct Command {
    char text[COMMAND_LEN];
};

enum class Job : uint8_t { NONE, WIPE, START_PLAYLIST, INSTALL_SINGLE };
enum class Screen : uint8_t { OFF, WIPING, RECEIVING, DONE, FAILED };

// --- Hand-off between BLE callbacks and the worker --------------------------
StreamBufferHandle_t dataStream   = nullptr;
QueueHandle_t        commandQueue = nullptr;
volatile bool        dataOverrun  = false;
volatile bool        commandLost  = false;

// --- Hand-off between the worker and the display task -----------------------
SemaphoreHandle_t    jobDone      = nullptr;
volatile Job         pendingJob   = Job::NONE;
volatile bool        jobResult    = false;

// --- Owned by the worker ----------------------------------------------------
BLECharacteristic   *statusCharacteristic = nullptr;
BLECharacteristic   *nowCharacteristic    = nullptr;
File                 uploadFile;
// A transfer lands either in a file or, for a poke, in RAM.
enum class Sink : uint8_t { FILE, POKE };
Sink                 sink           = Sink::FILE;
uint8_t             *pokeBuffer     = nullptr;
uint16_t             pokeSenderW    = 0;
size_t               pokeSenderLen  = 0;
uint16_t             pokeTextW      = 0;
size_t               pokeTextLen    = 0;
char                 pokeSender[33] = {};
char                 pokeText[65]   = {};
String               lastNowPlaying;
uint32_t             lastNowCheckMs = 0;
char                 uploadName[65] = {};
size_t               expectedSize   = 0;
size_t               receivedSize   = 0;
size_t               lastAckSize    = 0;
bool                 transferActive = false;
uint32_t             lastDataMs     = 0;
bool                 playlistActive = false;
uint8_t              playlistCount  = 0;
uint8_t              playlistStored = 0;
bool                 sessionAuthed  = false;   // this BLE connection proved a token
int                  sessionSlot    = -1;

// --- OLED progress (written by worker, drawn by display task) --------------
portMUX_TYPE         screenMux        = portMUX_INITIALIZER_UNLOCKED;
volatile Screen      screen           = Screen::OFF;
volatile uint8_t     screenFile       = 0;
volatile uint8_t     screenTotal      = 0;
volatile uint32_t    screenBytes      = 0;
volatile uint32_t    screenSize       = 0;
volatile uint32_t    screenUntilMs    = 0;
char                 screenName[22]   = {};
char                 screenDetail[22] = {};

// ---------------------------------------------------------------------------
//  Small helpers
// ---------------------------------------------------------------------------

void notify(const char *message) {
    if (!statusCharacteristic) return;
    statusCharacteristic->setValue(message);
    statusCharacteristic->notify();
    Serial.printf("[BLE] -> %s\n", message);
}

void notifyf(const char *format, ...) {
    char message[112];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    notify(message);
}

size_t fsFreeBytes() {
    const size_t total = LittleFS.totalBytes();
    const size_t used  = LittleFS.usedBytes();
    return used < total ? total - used : 0;
}

// Bytes taken by every .qgif on flash (what a playlist wipe would free).
size_t qgifBytes(uint8_t *count) {
    size_t bytes = 0;
    uint8_t files = 0;
    File root = LittleFS.open("/");
    if (root && root.isDirectory()) {
        File entry = root.openNextFile();
        while (entry) {
            const String name = entry.name();
            if (!entry.isDirectory() && name.endsWith(".qgif")) {
                bytes += entry.size();
                if (files < 255) files++;
            }
            entry.close();
            entry = root.openNextFile();
        }
    }
    if (root) root.close();
    if (count) *count = files;
    return bytes;
}

bool validName(const char *name) {
    const size_t length = strlen(name);
    if (length < 6 || length > 64) return false;
    if (strcmp(name + length - 5, ".qgif") != 0) return false;
    if (strstr(name, "..") != nullptr) return false;
    for (size_t i = 0; i < length; ++i) {
        const char c = name[i];
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                        (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
        if (!ok) return false;
    }
    // Never let an upload replace a reserved file.
    return strcmp(name, SELECTED_NAME) != 0;
}

// A .qgif is: frames(1) width(2) height(2) delays(frames*2) bitmaps(frames*1024)
bool validQgif(const char *path, size_t size) {
    File check = LittleFS.open(path, "r");
    if (!check) return false;
    uint8_t header[5] = {};
    const bool read = check.size() == size && check.read(header, sizeof(header)) == sizeof(header);
    check.close();
    if (!read) return false;
    const uint8_t  frames = header[0];
    const uint16_t width  = header[1] | (static_cast<uint16_t>(header[2]) << 8);
    const uint16_t height = header[3] | (static_cast<uint16_t>(header[4]) << 8);
    const size_t   needed = 5 + static_cast<size_t>(frames) * (2 + 1024);
    return frames > 0 && width == 128 && height == 64 && needed == size;
}

void setScreen(Screen mode, uint32_t holdMs = 0) {
    portENTER_CRITICAL(&screenMux);
    screen = mode;
    screenUntilMs = holdMs ? millis() + holdMs : 0;
    portEXIT_CRITICAL(&screenMux);
}

void setScreenText(char *target, size_t capacity, const char *text) {
    portENTER_CRITICAL(&screenMux);
    strncpy(target, text, capacity - 1);
    target[capacity - 1] = '\0';
    portEXIT_CRITICAL(&screenMux);
}

void fail(const char *code, const char *detail = nullptr) {
    if (detail && detail[0]) notifyf("ERROR:%s %s", code, detail);
    else notifyf("ERROR:%s", code);
    setScreenText(screenDetail, sizeof(screenDetail), code);
    setScreen(Screen::FAILED, 4000);
}

// Run a job on the display task and wait for it to finish.
bool runOnDisplayTask(Job job) {
    xSemaphoreTake(jobDone, 0);   // clear a stale give from a timed-out job
    jobResult  = false;
    pendingJob = job;
    if (xSemaphoreTake(jobDone, pdMS_TO_TICKS(DISPLAY_JOB_MS)) != pdTRUE) {
        pendingJob = Job::NONE;
        return false;
    }
    return jobResult;
}

void discardTransfer() {
    const bool wasFile = sink == Sink::FILE;
    transferActive = false;
    if (uploadFile) uploadFile.close();
    if (wasFile) LittleFS.remove(TEMP_PATH);
    if (pokeBuffer) { free(pokeBuffer); pokeBuffer = nullptr; }
    sink = Sink::FILE;
    expectedSize = receivedSize = lastAckSize = 0;
}

void drainStream() {
    uint8_t scratch[256];
    while (xStreamBufferReceive(dataStream, scratch, sizeof(scratch), 0) > 0) { }
    dataOverrun = false;
}

// If a playlist was cut short, play whatever already landed instead of
// leaving the robot on the idle face forever.
void salvagePlaylist() {
    if (playlistActive && playlistStored > 0) {
        runOnDisplayTask(Job::START_PLAYLIST);
    }
    playlistActive = false;
    playlistCount = playlistStored = 0;
}

// ---------------------------------------------------------------------------
//  Command handlers (worker task)
// ---------------------------------------------------------------------------

void handleHello() {
    uint8_t count = 0;
    qgifBytes(&count);
    notifyf("HELLO %u %u %u %u %u %u fw=%s auth=%u paired=%u setup=%u oled=%u", PROTOCOL_VERSION,
            static_cast<unsigned>(fsFreeBytes() / 1024),
            static_cast<unsigned>(LittleFS.totalBytes() / 1024),
            count, getGifDwellSeconds(), static_cast<unsigned>(getPlaybackSpeed()),
            HYPERBOT_FW_VERSION, sessionAuthed ? 1u : 0u, hbPairedCount(),
            hbSetupDone() ? 1u : 0u, g_oledPresent ? 1u : 0u);
}

void handlePlaylist(const char *args) {
    if (transferActive || playlistActive) { fail("BUSY"); return; }

    unsigned count = 5;
    unsigned long total = 0;
    if (args && *args) sscanf(args, "%u %lu", &count, &total);
    if (count < 1 || count > PLAYLIST_MAX) { fail("BAD_COUNT"); return; }

    // Check space before wiping anything: free now + what the wipe releases.
    const size_t reclaimable = qgifBytes(nullptr);
    const size_t available   = fsFreeBytes() + reclaimable;
    if (total > 0 && available < total + FS_HEADROOM) {
        char detail[24];
        snprintf(detail, sizeof(detail), "%lu %u", (total + FS_HEADROOM) / 1024,
                 static_cast<unsigned>(available / 1024));
        fail("NO_SPACE", detail);
        return;
    }

    notify("WIPING");
    portENTER_CRITICAL(&screenMux);
    screenFile = 0;
    screenTotal = count;
    portEXIT_CRITICAL(&screenMux);
    setScreen(Screen::WIPING);
    if (!runOnDisplayTask(Job::WIPE)) { fail("WIPE_FAILED"); return; }

    playlistActive = true;
    playlistCount  = count;
    playlistStored = 0;
    notifyf("PLAYLIST %u", static_cast<unsigned>(fsFreeBytes() / 1024));
}

void handleBegin(const char *command) {
    // BEGIN\n<name>\n<size>
    const char *nameStart = command + 6;
    const char *nameEnd   = strchr(nameStart, '\n');
    if (!nameEnd || nameEnd == nameStart || nameEnd - nameStart > 64) { fail("BAD_BEGIN"); return; }

    char name[65];
    memcpy(name, nameStart, nameEnd - nameStart);
    name[nameEnd - nameStart] = '\0';
    const size_t size = strtoul(nameEnd + 1, nullptr, 10);

    if (transferActive) { fail("BUSY"); return; }
    if (!validName(name)) { fail("BAD_NAME"); return; }
    if (size < 5 + 1026 || size > MAX_FILE_SIZE) { fail("BAD_SIZE"); return; }
    if (playlistActive && playlistStored >= playlistCount) { fail("PLAYLIST_FULL"); return; }

    size_t freeBytes = fsFreeBytes();
    if (!playlistActive) {
        File previous = LittleFS.open(SELECTED_PATH, "r");   // single mode replaces it
        if (previous) { freeBytes += previous.size(); previous.close(); }
    }
    if (freeBytes < size + FS_HEADROOM) {
        char detail[24];
        snprintf(detail, sizeof(detail), "%u %u", static_cast<unsigned>((size + FS_HEADROOM) / 1024),
                 static_cast<unsigned>(freeBytes / 1024));
        fail("NO_SPACE", detail);
        return;
    }

    drainStream();
    LittleFS.remove(TEMP_PATH);
    uploadFile = LittleFS.open(TEMP_PATH, "w");
    if (!uploadFile) { fail("FS_OPEN"); return; }

    strncpy(uploadName, name, sizeof(uploadName) - 1);
    expectedSize   = size;
    receivedSize   = 0;
    lastAckSize    = 0;
    lastDataMs     = millis();
    transferActive = true;

    char label[22];
    strncpy(label, name, sizeof(label) - 1);
    label[sizeof(label) - 1] = '\0';
    char *dot = strrchr(label, '.');
    if (dot) *dot = '\0';
    setScreenText(screenName, sizeof(screenName), label);
    portENTER_CRITICAL(&screenMux);
    screenFile  = playlistActive ? playlistStored + 1 : 1;
    screenTotal = playlistActive ? playlistCount : 1;
    screenBytes = 0;
    screenSize  = size;
    portEXIT_CRITICAL(&screenMux);
    setScreen(Screen::RECEIVING);

    notifyf("READY %u", static_cast<unsigned>(size));
}

void handleData(const uint8_t *bytes, size_t length) {
    if (!transferActive) return;
    if (receivedSize + length > expectedSize) {
        discardTransfer();
        fail("TOO_MUCH_DATA");
        return;
    }
    if (sink == Sink::POKE) {
        memcpy(pokeBuffer + receivedSize, bytes, length);
    } else if (uploadFile.write(bytes, length) != length) {
        discardTransfer();
        fail("FS_WRITE");
        return;
    }
    receivedSize += length;
    lastDataMs = millis();
    screenBytes = receivedSize;
    if (receivedSize - lastAckSize >= ACK_STEP || receivedSize == expectedSize) {
        lastAckSize = receivedSize;
        notifyf("ACK %u", static_cast<unsigned>(receivedSize));
    }
}

// ---------------------------------------------------------------------------
//  Pokes from the phone. The phone renders sender + message (emoji included)
//  into 1-bit bitmaps; the display task shows them exactly like a cloud poke.
// ---------------------------------------------------------------------------

bool queuePokeEvent(uint8_t *senderBmp, uint8_t *textBmp) {
    NetworkEvent event = {};
    event.kind = senderBmp ? NetworkEvent::POKE_BITMAP : NetworkEvent::POKE;
    strncpy(event.sender, pokeSender, sizeof(event.sender) - 1);
    strncpy(event.text, pokeText, sizeof(event.text) - 1);
    event.senderBmp      = senderBmp;
    event.senderBmpWidth = pokeSenderW;
    event.senderBmpLen   = pokeSenderLen;
    event.textBmp        = textBmp;
    event.textBmpWidth   = pokeTextW;
    event.textBmpLen     = pokeTextLen;
    return networkEventQueue && xQueueSend(networkEventQueue, &event, pdMS_TO_TICKS(200)) == pdTRUE;
}

bool validPokeBitmap(uint16_t width, size_t length) {
    return width > 0 && width <= POKE_MAX_WIDTH && length > 0 && length % width == 0 &&
           length / width <= POKE_MAX_PAGES;
}

void handlePoke(const char *command) {
    // POKE <senderW> <senderLen> <textW> <textLen>\n<sender>\n<text>
    if (transferActive || playlistActive) { fail("BUSY"); return; }
    unsigned sw = 0, tw = 0;
    unsigned long sl = 0, tl = 0;
    const char *header = command + 5;
    if (sscanf(header, "%u %lu %u %lu", &sw, &sl, &tw, &tl) != 4) { fail("BAD_POKE"); return; }
    const char *senderStart = strchr(header, '\n');
    if (!senderStart) { fail("BAD_POKE"); return; }
    senderStart++;
    const char *textStart = strchr(senderStart, '\n');
    const size_t senderLength = textStart ? static_cast<size_t>(textStart - senderStart) : strlen(senderStart);
    memset(pokeSender, 0, sizeof(pokeSender));
    memset(pokeText, 0, sizeof(pokeText));
    memcpy(pokeSender, senderStart, senderLength < sizeof(pokeSender) - 1 ? senderLength : sizeof(pokeSender) - 1);
    if (textStart) strncpy(pokeText, textStart + 1, sizeof(pokeText) - 1);
    if (!pokeSender[0]) strcpy(pokeSender, "Phone");
    if (!pokeText[0]) strcpy(pokeText, "Poke!");

    if (sw == 0 && tw == 0) {           // plain text poke, nothing to upload
        pokeSenderW = pokeTextW = 0;
        pokeSenderLen = pokeTextLen = 0;
        if (!queuePokeEvent(nullptr, nullptr)) { fail("POKE_QUEUE"); return; }
        notify("POKED");
        return;
    }
    if (!validPokeBitmap(sw, sl) || !validPokeBitmap(tw, tl)) { fail("BAD_POKE"); return; }

    drainStream();
    pokeBuffer = static_cast<uint8_t *>(malloc(sl + tl));
    if (!pokeBuffer) { fail("NO_MEMORY"); return; }
    pokeSenderW    = sw;
    pokeSenderLen  = sl;
    pokeTextW      = tw;
    pokeTextLen    = tl;
    sink           = Sink::POKE;
    expectedSize   = sl + tl;
    receivedSize   = 0;
    lastAckSize    = 0;
    lastDataMs     = millis();
    transferActive = true;
    notifyf("READY %u", static_cast<unsigned>(expectedSize));
}

void finishPoke() {
    if (receivedSize != expectedSize) {
        discardTransfer();
        fail("INCOMPLETE");
        return;
    }
    // The display task frees each bitmap separately, so split the buffer.
    uint8_t *senderBmp = static_cast<uint8_t *>(malloc(pokeSenderLen));
    uint8_t *textBmp   = static_cast<uint8_t *>(malloc(pokeTextLen));
    if (!senderBmp || !textBmp) {
        free(senderBmp);
        free(textBmp);
        discardTransfer();
        fail("NO_MEMORY");
        return;
    }
    memcpy(senderBmp, pokeBuffer, pokeSenderLen);
    memcpy(textBmp, pokeBuffer + pokeSenderLen, pokeTextLen);
    discardTransfer();
    if (!queuePokeEvent(senderBmp, textBmp)) {
        free(senderBmp);
        free(textBmp);
        fail("POKE_QUEUE");
        return;
    }
    notify("POKED");
}

// Live mirror: tell the phone whenever the animation on screen changes.
void checkNowPlaying() {
    if (!nowCharacteristic || millis() - lastNowCheckMs < NOW_POLL_MS) return;
    lastNowCheckMs = millis();
    const String current = gifPlayerGetCurrentFile();
    if (current == lastNowPlaying) return;
    lastNowPlaying = current;
    nowCharacteristic->setValue(current.c_str());
    nowCharacteristic->notify();
}

// Pull everything still sitting in the stream buffer onto flash.
void drainIntoFile(uint32_t timeoutMs) {
    uint8_t buffer[512];
    const uint32_t start = millis();
    while (transferActive && receivedSize < expectedSize && millis() - start < timeoutMs) {
        const size_t got = xStreamBufferReceive(dataStream, buffer, sizeof(buffer), pdMS_TO_TICKS(50));
        if (got) handleData(buffer, got);
    }
}

void handleEnd() {
    if (!transferActive) { fail("NO_TRANSFER"); return; }
    drainIntoFile(END_DRAIN_MS);
    if (!transferActive) return;   // handleData already reported the failure
    if (sink == Sink::POKE) { finishPoke(); return; }

    transferActive = false;
    uploadFile.flush();
    uploadFile.close();

    if (receivedSize != expectedSize) {
        char detail[24];
        snprintf(detail, sizeof(detail), "%u/%u", static_cast<unsigned>(receivedSize),
                 static_cast<unsigned>(expectedSize));
        LittleFS.remove(TEMP_PATH);
        fail("INCOMPLETE", detail);
        return;
    }
    if (!validQgif(TEMP_PATH, expectedSize)) {
        LittleFS.remove(TEMP_PATH);
        fail("BAD_QGIF");
        return;
    }

    if (!playlistActive) {
        if (!runOnDisplayTask(Job::INSTALL_SINGLE)) {
            LittleFS.remove(TEMP_PATH);
            fail("INSTALL_FAILED");
            return;
        }
        setScreenText(screenDetail, sizeof(screenDetail), "Playing now");
        setScreen(Screen::DONE, 2000);
        notify("PLAYING");
        return;
    }

    // Playlist files keep their own names, so MQTT / the dashboard show
    // "emotion_uwu" instead of a slot number. Nothing is playing from flash
    // after the wipe, so a plain rename is safe here.
    char path[70];
    snprintf(path, sizeof(path), "/%s", uploadName);
    if (LittleFS.exists(path)) LittleFS.remove(path);
    if (!LittleFS.rename(TEMP_PATH, path)) {
        LittleFS.remove(TEMP_PATH);
        fail("FS_RENAME");
        return;
    }
    playlistStored++;
    Serial.printf("[BLE] stored %u/%u %s\n", playlistStored, playlistCount, uploadName);

    if (playlistStored < playlistCount) {
        notifyf("SAVED %u", playlistStored);
        return;
    }

    playlistActive = false;
    if (!runOnDisplayTask(Job::START_PLAYLIST)) { fail("START_FAILED"); return; }
    char done[22];
    snprintf(done, sizeof(done), "%u animations", playlistCount);
    setScreenText(screenDetail, sizeof(screenDetail), done);
    setScreen(Screen::DONE, 2500);
    notifyf("SAVED %u", playlistStored);
    notifyf("PLAYING %u", playlistStored);
    playlistCount = playlistStored = 0;
}

void handleAbort() {
    const bool wasBusy = transferActive || playlistActive;
    discardTransfer();
    drainStream();
    salvagePlaylist();
    setScreen(wasBusy ? Screen::FAILED : Screen::OFF, wasBusy ? 1500 : 0);
    setScreenText(screenDetail, sizeof(screenDetail), "Cancelled");
    notify("ABORTED");
}

void handleDisconnect() {
    sessionAuthed = false;
    sessionSlot   = -1;
    if (transferActive || playlistActive) {
        Serial.println("[BLE] link lost mid-transfer");
        discardTransfer();
        drainStream();
        salvagePlaylist();
        setScreenText(screenDetail, sizeof(screenDetail), "Link lost");
        setScreen(Screen::FAILED, 3000);
    }
}

void handlePair(const char *args) {
    if (!args || !*args) {
        hbStartPairing();
        notify("PAIR WAIT");
        return;
    }
    char token[HB_TOKEN_LEN + 1];
    uint8_t triesLeft = 0;
    const int r = hbConfirmPairing(args, token, &triesLeft);
    if (r >= 0) {
        sessionAuthed = true;
        sessionSlot   = r;
        notifyf("PAIRED %s", token);
    } else if (r == -2) {
        notifyf("ERROR:PAIR_WRONG %u", triesLeft);
    } else if (r == -3) {
        notify("ERROR:PAIR_LOCKED");
    } else {
        notify("ERROR:PAIR_EXPIRED");
    }
}

void handleAuth(const char *token) {
    const int slot = hbCheckToken(token);
    if (slot < 0) {
        sessionAuthed = false;
        notify("ERROR:AUTH_FAIL");
        return;
    }
    sessionAuthed = true;
    sessionSlot   = slot;
    notify("AUTH OK");
}

void handleCommand(const char *text) {
    // Never echo tokens / codes to the serial log.
    const bool secret = strncmp(text, "AUTH ", 5) == 0 || strncmp(text, "PAIR ", 5) == 0;
    Serial.printf("[BLE] <- %.*s\n", 24, secret ? "AUTH/PAIR ****" : text);
    if (strcmp(text, "#DISCONNECT") == 0) { handleDisconnect(); return; }
    if (strcmp(text, "HELLO") == 0)       { handleHello(); return; }
    if (strcmp(text, "ABORT") == 0)       { handleAbort(); return; }
    if (strcmp(text, "PAIR") == 0)        { handlePair(nullptr); return; }
    if (strncmp(text, "PAIR ", 5) == 0)   { handlePair(text + 5); return; }
    if (strncmp(text, "AUTH ", 5) == 0)   { handleAuth(text + 5); return; }

    // ---- everything below needs a paired phone ----
    if (!sessionAuthed) { notify("ERROR:AUTH_REQUIRED"); return; }   // no OLED banner for strangers

    if (strcmp(text, "FORGET") == 0) {
        hbForgetPhone(sessionSlot);
        sessionAuthed = false;
        sessionSlot   = -1;
        notify("FORGOTTEN");
        return;
    }
    if (strcmp(text, "RESET") == 0) {
        notify("RESETTING");
        hbFactoryReset();
        vTaskDelay(pdMS_TO_TICKS(1500));   // let the reply and the OLED notice go out
        esp_restart();
        return;
    }
    if (strncmp(text, "PLAYLIST", 8) == 0 && (text[8] == '\0' || text[8] == ' ')) {
        handlePlaylist(text[8] ? text + 9 : nullptr);
        return;
    }
    if (strncmp(text, "BEGIN\n", 6) == 0) { handleBegin(text); return; }
    if (strcmp(text, "END") == 0)         { handleEnd(); return; }
    if (strncmp(text, "POKE ", 5) == 0)   { handlePoke(text); return; }
    if (strncmp(text, "DWELL ", 6) == 0) {
        const long seconds = strtol(text + 6, nullptr, 10);
        if (seconds < 2 || seconds > 60) { fail("BAD_DWELL"); return; }
        setGifDwellSeconds(static_cast<uint8_t>(seconds));
        saveSettings();
        notifyf("DWELL %u", getGifDwellSeconds());
        return;
    }
    if (strncmp(text, "SPEED ", 6) == 0) {
        const long divisor = strtol(text + 6, nullptr, 10);
        if (divisor < 1 || divisor > 10) { fail("BAD_SPEED"); return; }
        setPlaybackSpeed(static_cast<uint16_t>(divisor));
        saveSettings();
        notifyf("SPEED %u", static_cast<unsigned>(getPlaybackSpeed()));
        return;
    }
    fail("UNKNOWN_COMMAND");
}

void workerTask(void *) {
    uint8_t buffer[512];
    Command command;
    for (;;) {
        if (transferActive) {
            const size_t got = xStreamBufferReceive(dataStream, buffer, sizeof(buffer), pdMS_TO_TICKS(20));
            if (got) handleData(buffer, got);
            if (transferActive && dataOverrun) {
                discardTransfer();
                drainStream();
                fail("OVERRUN");
            } else if (transferActive && millis() - lastDataMs > DATA_STALL_MS) {
                discardTransfer();
                fail("TIMEOUT");
            }
            if (xQueueReceive(commandQueue, &command, 0) == pdTRUE) handleCommand(command.text);
        } else if (xQueueReceive(commandQueue, &command, pdMS_TO_TICKS(100)) == pdTRUE) {
            handleCommand(command.text);
        } else if (xStreamBufferBytesAvailable(dataStream) > 0) {
            drainStream();   // stray bytes with no BEGIN
        }
        if (commandLost) {
            commandLost = false;
            fail("QUEUE_FULL");
        }
        checkNowPlaying();
    }
}

// ---------------------------------------------------------------------------
//  BLE callbacks -- copy and return, nothing else
// ---------------------------------------------------------------------------

void queueCommand(const char *text, size_t length) {
    Command command;
    if (length >= sizeof(command.text)) length = sizeof(command.text) - 1;
    memcpy(command.text, text, length);
    command.text[length] = '\0';
    // trim trailing whitespace / CR / LF
    while (length > 0 && (command.text[length - 1] == '\n' || command.text[length - 1] == '\r' ||
                          command.text[length - 1] == ' ')) {
        command.text[--length] = '\0';
    }
    if (xQueueSend(commandQueue, &command, 0) != pdTRUE) commandLost = true;
}

class DataCallbacks final : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *characteristic) override {
        const uint8_t *bytes = characteristic->getData();
        const size_t length  = characteristic->getLength();
        if (!bytes || length == 0) return;
        // Normally never blocks (the phone respects its window). A client
        // that ignores ACKs gets back-pressure here instead of lost bytes.
        if (xStreamBufferSend(dataStream, bytes, length, pdMS_TO_TICKS(500)) != length) {
            dataOverrun = true;
        }
    }
};

class ControlCallbacks final : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *characteristic) override {
        const uint8_t *bytes = characteristic->getData();
        const size_t length  = characteristic->getLength();
        if (!bytes || length == 0) return;
        queueCommand(reinterpret_cast<const char *>(bytes), length);
    }
};

class ServerCallbacks final : public BLEServerCallbacks {
    void onConnect(BLEServer *) override {
        Serial.println("[BLE] phone connected");
        hbSetupPhoneConnected(true);
    }
    void onDisconnect(BLEServer *) override {
        Serial.println("[BLE] phone disconnected");
        hbSetupPhoneConnected(false);
        queueCommand("#DISCONNECT", 11);
        BLEDevice::startAdvertising();
    }
};

// ---------------------------------------------------------------------------
//  Display-task side
// ---------------------------------------------------------------------------

void runPendingJob() {
    const Job job = pendingJob;
    if (job == Job::NONE) return;
    bool ok = false;
    switch (job) {
        case Job::WIPE:
            ok = gifPlayerPrepareBluetoothPlaylist();
            break;
        case Job::START_PLAYLIST: {
            gifPlayerSetIdleAnimation(nullptr);
            gifPlayerBuildShuffleBag();
            gifPlayerSetAutoAdvance(1);
            const String next = gifPlayerNextShuffle();
            ok = next.length() > 0;
            if (ok) gifPlayerSetFile(next);
            break;
        }
        case Job::INSTALL_SINGLE:
            ok = gifPlayerInstallBluetoothFile(TEMP_PATH, SELECTED_PATH, SELECTED_NAME);
            if (ok) {
                gifPlayerBuildShuffleBag();
                gifPlayerSetAutoAdvance(1);
            }
            break;
        case Job::NONE:
            break;
    }
    jobResult  = ok;
    pendingJob = Job::NONE;
    xSemaphoreGive(jobDone);
}

void drawScreen(Screen mode) {
    char line2[22], line3[22], line4[22];
    char name[22], detail[22];
    uint8_t file, total;
    uint32_t bytes, size;
    portENTER_CRITICAL(&screenMux);
    memcpy(name, screenName, sizeof(name));
    memcpy(detail, screenDetail, sizeof(detail));
    file  = screenFile;
    total = screenTotal;
    bytes = screenBytes;
    size  = screenSize;
    portEXIT_CRITICAL(&screenMux);

    switch (mode) {
        case Screen::WIPING:
            snprintf(line3, sizeof(line3), "Clearing old GIFs");
            snprintf(line4, sizeof(line4), "%u incoming", total);
            showText("[ HyperBot ]", "", line3, line4);
            break;
        case Screen::RECEIVING: {
            const unsigned percent = size ? static_cast<unsigned>((bytes * 100ULL) / size) : 0;
            char bar[11];
            const unsigned filled = percent / 10;
            for (unsigned i = 0; i < 10; ++i) bar[i] = i < filled ? '#' : '-';
            bar[10] = '\0';
            snprintf(line2, sizeof(line2), "File %u of %u", file, total);
            snprintf(line4, sizeof(line4), "%s %3u%%", bar, percent);
            showText("[ HyperBot ]", line2, name, line4);
            break;
        }
        case Screen::DONE:
            showText("[ HyperBot ]", "", "Done", detail);
            break;
        case Screen::FAILED:
            showText("[ HyperBot ]", "", "Upload stopped", detail);
            break;
        case Screen::OFF:
            break;
    }
}

}  // namespace

// ==========================================================================
//  Public API
// ==========================================================================

void bleGifReceiverInit() {
    dataStream   = xStreamBufferCreate(STREAM_SIZE, 1);
    commandQueue = xQueueCreate(6, sizeof(Command));
    jobDone      = xSemaphoreCreateBinary();
    if (!dataStream || !commandQueue || !jobDone) {
        Serial.println("[BLE] out of memory, receiver disabled");
        return;
    }

    const String name = "HyperBot-" + getDeviceId().substring(8);
    BLEDevice::init(name.c_str());
    BLEDevice::setMTU(517);

    BLEServer *server = BLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());
    BLEService *service = server->createService(SERVICE_UUID);

    BLECharacteristic *control = service->createCharacteristic(
        CONTROL_UUID, BLECharacteristic::PROPERTY_WRITE);
    control->setCallbacks(new ControlCallbacks());

    BLECharacteristic *data = service->createCharacteristic(
        DATA_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    data->setCallbacks(new DataCallbacks());

    statusCharacteristic = service->createCharacteristic(
        STATUS_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    statusCharacteristic->addDescriptor(new BLE2902());
    statusCharacteristic->setValue("HYPERBOT 3");

    nowCharacteristic = service->createCharacteristic(
        NOW_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    nowCharacteristic->addDescriptor(new BLE2902());
    nowCharacteristic->setValue("");   // never "IDLE": that reply marks old firmware
    service->start();

    BLEAdvertising *advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->setScanResponse(true);
    advertising->start();

    xTaskCreate(workerTask, "ble_rx", 6144, nullptr, 2, nullptr);
    Serial.printf("[BLE] receiver v%u advertising as %s\n", PROTOCOL_VERSION, name.c_str());
}

bool bleGifReceiverProcessPending() {
    runPendingJob();

    Screen mode;
    uint32_t until;
    portENTER_CRITICAL(&screenMux);
    mode  = screen;
    until = screenUntilMs;
    portEXIT_CRITICAL(&screenMux);
    if (mode == Screen::OFF) return false;

    if (until != 0 && static_cast<int32_t>(millis() - until) >= 0) {
        setScreen(Screen::OFF);
        return false;
    }

    // Redraw at most 4x a second; the I2C bus is shared with GIF playback.
    static uint32_t lastDrawMs = 0;
    static Screen   lastMode   = Screen::OFF;
    const uint32_t  now        = millis();
    if (mode != lastMode || now - lastDrawMs >= 250) {
        drawScreen(mode);
        lastDrawMs = now;
        lastMode   = mode;
    }
    return true;
}
