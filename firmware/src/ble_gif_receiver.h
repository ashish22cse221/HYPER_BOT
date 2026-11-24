#ifndef BLE_GIF_RECEIVER_H
#define BLE_GIF_RECEIVER_H

// Start the QBIT BLE GATT service and its worker task. Call after LittleFS
// has been mounted (gifPlayerInit) and before the display task starts.
void bleGifReceiverInit();

// Call from the display task on every loop. Runs GIF-player jobs the BLE
// worker asked for (wipe, start playlist) and draws the upload progress
// screen. Returns true while that screen owns the OLED; the caller should
// skip its own rendering for that iteration.
bool bleGifReceiverProcessPending();

#endif
