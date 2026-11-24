// ==========================================================================
//  HyperBot -- first-run setup + phone pairing
// ==========================================================================
//
//  * First run: until a phone pairs, the OLED cycles a hello screen and a QR
//    code that opens the app download page, then plays the GIFs for a while
//    so the bot never looks stuck.
//  * Pairing: the app sends PAIR, the bot shows a 4-digit code, the user types
//    it in the app, the bot hands back a random token. Later connections send
//    AUTH <token>. Up to HB_MAX_PHONES phones are remembered.
//  * "Setup done" and the tokens live in NVS (namespace "hbsetup"), so a power
//    cycle never brings setup back. Only a reset does:
//      - RESET from a paired phone, or
//      - unplugging / replugging the bot 5 times in a row (each time within
//        6 seconds of power-on) when no paired phone is around.
//    A reset forgets phones and the setup flag; GIFs and settings stay.
// ==========================================================================
#ifndef HB_SETUP_H
#define HB_SETUP_H

#include <Arduino.h>

#ifndef HYPERBOT_APP_URL
#define HYPERBOT_APP_URL "https://hyperbot-desk.netlify.app"
#endif

#define HB_MAX_PHONES      4
#define HB_TOKEN_LEN       16     // hex chars
#define HB_PAIR_TIMEOUT_MS 120000
#define HB_PAIR_TRIES      3

// Call once in setup(), after settingsInit().
void hbSetupInit();

bool    hbSetupDone();
uint8_t hbPairedCount();

// Token check for AUTH. Returns slot index or -1.
int  hbCheckToken(const char *token);

// Pairing (called from the BLE worker task).
void hbStartPairing();                       // shows a fresh code on the OLED
// Returns slot index (>=0) on success and writes the token to outToken
// (HB_TOKEN_LEN+1 bytes). Negative: -1 no code active / expired,
// -2 wrong code (tries left in *triesLeft), -3 too many wrong tries.
int  hbConfirmPairing(const char *code, char *outToken, uint8_t *triesLeft);
void hbCancelPairing();

void hbForgetPhone(int slot);
void hbFactoryReset();                       // clears phones + setup flag

// BLE link state, so the first-run screens step aside while a phone is on.
void hbSetupPhoneConnected(bool connected);

// Display task: call every loop. Returns true while a setup / pairing screen
// owns the OLED (caller skips its own drawing for that iteration).
// idleOk: true when the bot is just playing GIFs (first-run screens only show then).
bool hbSetupDisplayTick(bool idleOk);

#endif // HB_SETUP_H
