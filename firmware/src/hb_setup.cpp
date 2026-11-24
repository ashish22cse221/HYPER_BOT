// ==========================================================================
//  HyperBot -- first-run setup + phone pairing (see hb_setup.h)
// ==========================================================================
#include "hb_setup.h"

#include <Preferences.h>
#include <esp_random.h>
#include <freertos/FreeRTOS.h>
#include <string.h>

#include "app_state.h"
#include "display_helpers.h"
#include "settings.h"
#include "qrcode.h"

namespace {

constexpr char     NS[]               = "hbsetup";
constexpr uint8_t  SETUP_VERSION      = 1;      // bump if a new setup step is added
constexpr uint8_t  RESET_BOOTS        = 5;      // quick power cycles that trigger a reset
constexpr uint32_t BOOT_STABLE_MS     = 6000;   // uptime after which a boot "counts as normal"
constexpr uint32_t CYCLE_MS           = 45000;  // first-run loop length
constexpr uint32_t HELLO_MS           = 3000;
constexpr uint32_t QR_UNTIL_MS        = 15000;  // hello + QR; rest of the cycle plays GIFs

enum class Ui : uint8_t { NONE, PAIR_CODE, ALL_SET, PAIRED, RESET_NOTICE };

Preferences   prefs;
portMUX_TYPE  mux            = portMUX_INITIALIZER_UNLOCKED;

// guarded by mux
bool          setupDone      = false;
char          tokens[HB_MAX_PHONES][HB_TOKEN_LEN + 1] = {};
uint8_t       nextSlot       = 0;
Ui            ui             = Ui::NONE;
uint32_t      uiUntilMs      = 0;
char          pairCode[5]    = {};
uint32_t      pairExpiresMs  = 0;
uint8_t       pairTries      = 0;
volatile bool phoneOn        = false;
bool          bootCounterCleared = false;
bool          resetAtBoot    = false;

String botName() { return "HyperBot-" + getDeviceId().substring(8); }

void randomHex(char *out, size_t chars) {
    static const char hex[] = "0123456789abcdef";
    for (size_t i = 0; i < chars; ++i) out[i] = hex[esp_random() & 0x0F];
    out[chars] = '\0';
}

void loadFromNvs() {
    setupDone = prefs.getUChar("setupV", 0) >= SETUP_VERSION;
    nextSlot  = prefs.getUChar("next", 0) % HB_MAX_PHONES;
    for (uint8_t i = 0; i < HB_MAX_PHONES; ++i) {
        char key[4] = {'t', static_cast<char>('0' + i), 0, 0};
        String t = prefs.getString(key, "");
        if (t.length() == HB_TOKEN_LEN) strncpy(tokens[i], t.c_str(), HB_TOKEN_LEN + 1);
        else tokens[i][0] = '\0';
    }
}

void clearAllLocked() {   // caller holds nothing; touches NVS then RAM
    prefs.clear();
    portENTER_CRITICAL(&mux);
    setupDone = false;
    memset(tokens, 0, sizeof(tokens));
    nextSlot = 0;
    portEXIT_CRITICAL(&mux);
}

void setUi(Ui mode, uint32_t holdMs) {
    portENTER_CRITICAL(&mux);
    ui = mode;
    uiUntilMs = holdMs ? millis() + holdMs : 0;
    portEXIT_CRITICAL(&mux);
}

// ---------------------------------------------------------------- drawing
void finish() {
    rotateBuffer180();
    u8g2.sendBuffer();
}

void centerStr(int y, const char *s) {
    int w = u8g2.getStrWidth(s);
    u8g2.drawStr((128 - w) / 2, y, s);
}

void drawHello() {
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_helvB14_tr);
    centerStr(20, "HI! I'M");
    u8g2.setFont(u8g2_font_helvB10_tr);
    String n = botName();
    centerStr(40, n.c_str());
    u8g2.setFont(u8g2_font_5x7_tr);
    char v[24];
    snprintf(v, sizeof(v), "firmware v%s", HYPERBOT_FW_VERSION);
    centerStr(60, v);
    finish();
}

void drawQr() {
    QRCode qr;
    uint8_t data[qrcode_getBufferSize(3)];
    qrcode_initText(&qr, data, 3, ECC_LOW, HYPERBOT_APP_URL);
    u8g2.clearBuffer();
    const uint8_t scale = 2, ox = 2, oy = (64 - qr.size * 2) / 2;
    for (uint8_t y = 0; y < qr.size; ++y)
        for (uint8_t x = 0; x < qr.size; ++x)
            if (qrcode_getModule(&qr, x, y)) u8g2.drawBox(ox + x * scale, oy + y * scale, scale, scale);
    u8g2.setFont(u8g2_font_helvB10_tr);
    u8g2.drawStr(66, 16, "SCAN");
    u8g2.drawStr(66, 30, "FOR MY");
    u8g2.drawStr(66, 44, "APP!");
    u8g2.setFont(u8g2_font_5x7_tr);
    String n = botName();
    u8g2.drawStr(66, 60, n.c_str() + 9);   // just the 4-char id
    finish();
}

void drawPairCode(const char *code, uint32_t secondsLeft) {
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_6x13_tr);
    centerStr(12, "PAIR CODE");
    u8g2.setFont(u8g2_font_logisoso28_tn);
    char spaced[8] = {code[0], ' ', code[1], ' ', code[2], ' ', code[3], 0};
    centerStr(46, spaced);
    u8g2.setFont(u8g2_font_5x7_tr);
    char line[28];
    snprintf(line, sizeof(line), "type it in the app  %lus", static_cast<unsigned long>(secondsLeft));
    centerStr(62, line);
    finish();
}

void drawBig(const char *big, const char *small) {
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_helvB14_tr);
    centerStr(32, big);
    u8g2.setFont(u8g2_font_6x13_tr);
    centerStr(54, small);
    finish();
}

}  // namespace

// ==========================================================================
//  Public API
// ==========================================================================

void hbSetupInit() {
    prefs.begin(NS, false);
    // Power-cycle reset: count boots that die before BOOT_STABLE_MS.
    uint8_t boots = prefs.getUChar("boots", 0) + 1;
    if (boots >= RESET_BOOTS) {
        Serial.println("[Setup] 5 quick power cycles -> resetting pairing + setup");
        prefs.clear();               // also zeroes the boot counter
        resetAtBoot = true;
    } else {
        prefs.putUChar("boots", boots);
    }
    loadFromNvs();
    Serial.printf("[Setup] setup %s, %u phone(s) paired\n", setupDone ? "done" : "NOT done", hbPairedCount());
    if (resetAtBoot) setUi(Ui::RESET_NOTICE, 4000);
}

bool hbSetupDone() {
    portENTER_CRITICAL(&mux);
    bool d = setupDone;
    portEXIT_CRITICAL(&mux);
    return d;
}

uint8_t hbPairedCount() {
    uint8_t n = 0;
    portENTER_CRITICAL(&mux);
    for (auto &t : tokens) if (t[0]) n++;
    portEXIT_CRITICAL(&mux);
    return n;
}

int hbCheckToken(const char *token) {
    if (!token || strlen(token) != HB_TOKEN_LEN) return -1;
    int found = -1;
    portENTER_CRITICAL(&mux);
    for (int i = 0; i < HB_MAX_PHONES; ++i) {
        if (!tokens[i][0]) continue;
        uint8_t diff = 0;                         // no early exit on mismatch
        for (int k = 0; k < HB_TOKEN_LEN; ++k) diff |= tokens[i][k] ^ token[k];
        if (diff == 0) found = i;
    }
    portEXIT_CRITICAL(&mux);
    return found;
}

void hbStartPairing() {
    char code[5];
    snprintf(code, sizeof(code), "%04u", static_cast<unsigned>(1000 + esp_random() % 9000));
    portENTER_CRITICAL(&mux);
    memcpy(pairCode, code, sizeof(pairCode));
    pairExpiresMs = millis() + HB_PAIR_TIMEOUT_MS;
    pairTries     = 0;
    ui            = Ui::PAIR_CODE;
    uiUntilMs     = 0;
    portEXIT_CRITICAL(&mux);
    Serial.println("[Setup] pairing started, code on screen");
}

int hbConfirmPairing(const char *code, char *outToken, uint8_t *triesLeft) {
    portENTER_CRITICAL(&mux);
    const bool active = pairCode[0] && static_cast<int32_t>(millis() - pairExpiresMs) < 0;
    if (!active) { pairCode[0] = '\0'; if (ui == Ui::PAIR_CODE) ui = Ui::NONE; }
    const bool match = active && code && strlen(code) == 4 && memcmp(code, pairCode, 4) == 0;
    if (active && !match) pairTries++;
    const uint8_t tries = pairTries;
    const bool locked = active && !match && tries >= HB_PAIR_TRIES;
    if (locked) { pairCode[0] = '\0'; ui = Ui::NONE; }
    portEXIT_CRITICAL(&mux);

    if (triesLeft) *triesLeft = tries >= HB_PAIR_TRIES ? 0 : HB_PAIR_TRIES - tries;
    if (!active) return -1;
    if (locked)  return -3;
    if (!match)  return -2;

    // Success: new token in the next slot (oldest phone is replaced when full).
    char token[HB_TOKEN_LEN + 1];
    randomHex(token, HB_TOKEN_LEN);
    portENTER_CRITICAL(&mux);
    int slot = -1;
    for (int i = 0; i < HB_MAX_PHONES; ++i) if (!tokens[i][0]) { slot = i; break; }
    if (slot < 0) { slot = nextSlot; }
    nextSlot = (slot + 1) % HB_MAX_PHONES;
    memcpy(tokens[slot], token, sizeof(token));
    const bool firstSetup = !setupDone;
    setupDone   = true;
    pairCode[0] = '\0';
    portEXIT_CRITICAL(&mux);

    char key[4] = {'t', static_cast<char>('0' + slot), 0, 0};
    prefs.putString(key, token);
    prefs.putUChar("next", nextSlot);
    prefs.putUChar("setupV", SETUP_VERSION);   // saved only now, at the very end
    memcpy(outToken, token, sizeof(token));
    setUi(firstSetup ? Ui::ALL_SET : Ui::PAIRED, firstSetup ? 3500 : 2000);
    Serial.printf("[Setup] phone paired in slot %d%s\n", slot, firstSetup ? " - setup complete" : "");
    return slot;
}

void hbCancelPairing() {
    portENTER_CRITICAL(&mux);
    pairCode[0] = '\0';
    if (ui == Ui::PAIR_CODE) ui = Ui::NONE;
    portEXIT_CRITICAL(&mux);
}

void hbForgetPhone(int slot) {
    if (slot < 0 || slot >= HB_MAX_PHONES) return;
    portENTER_CRITICAL(&mux);
    tokens[slot][0] = '\0';
    portEXIT_CRITICAL(&mux);
    char key[4] = {'t', static_cast<char>('0' + slot), 0, 0};
    prefs.remove(key);
}

void hbFactoryReset() {
    clearAllLocked();
    setUi(Ui::RESET_NOTICE, 0);
    Serial.println("[Setup] reset: phones forgotten, setup will run again");
}

void hbSetupPhoneConnected(bool connected) {
    phoneOn = connected;
    if (!connected) hbCancelPairing();
}

bool hbSetupDisplayTick(bool idleOk) {
    const uint32_t now = millis();

    // A boot that survived this long was a normal one: clear the counter.
    if (!bootCounterCleared && now > BOOT_STABLE_MS) {
        bootCounterCleared = true;
        prefs.putUChar("boots", 0);
    }

    Ui mode; uint32_t until; char code[5]; uint32_t expires; bool done;
    portENTER_CRITICAL(&mux);
    mode = ui; until = uiUntilMs; memcpy(code, pairCode, 5); expires = pairExpiresMs; done = setupDone;
    portEXIT_CRITICAL(&mux);

    static Ui       lastMode   = Ui::NONE;
    static uint32_t lastDrawMs = 0;
    static int      lastPhase  = -1;

    if (mode != Ui::NONE && until != 0 && static_cast<int32_t>(now - until) >= 0) {
        setUi(Ui::NONE, 0);
        mode = Ui::NONE;
    }
    if (mode == Ui::PAIR_CODE && (!code[0] || static_cast<int32_t>(now - expires) >= 0)) {
        hbCancelPairing();
        mode = Ui::NONE;
    }

    if (mode != Ui::NONE) {
        if (!g_oledPresent) return false;
        const bool changed = mode != lastMode;
        if (mode == Ui::PAIR_CODE) {
            if (changed || now - lastDrawMs >= 500) {
                drawPairCode(code, (expires - now + 999) / 1000);
                lastDrawMs = now;
            }
        } else if (changed) {
            if (mode == Ui::ALL_SET)      drawBig("ALL SET!", "enjoy your bot :)");
            else if (mode == Ui::PAIRED)  drawBig("PAIRED!", "new phone added");
            else                          drawBig("RESET DONE", "setup runs again");
        }
        lastMode = mode;
        lastPhase = -1;
        return true;
    }
    lastMode = Ui::NONE;

    // First run: hello -> QR -> GIFs, repeating, until a phone pairs.
    if (done || phoneOn || !idleOk || !g_oledPresent) { lastPhase = -1; return false; }
    const uint32_t t = now % CYCLE_MS;
    const int phase = t < HELLO_MS ? 0 : (t < QR_UNTIL_MS ? 1 : 2);
    if (phase == 2) { lastPhase = 2; return false; }
    if (phase != lastPhase) {
        if (phase == 0) drawHello(); else drawQr();
        lastPhase = phase;
    }
    return true;
}
