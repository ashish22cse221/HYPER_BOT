# HyperBot for Android

Companion app for the HyperBot desk robot (ESP32-C3, 128x64 OLED). It talks to the bot over
Bluetooth LE; no account, no cloud, no pairing.

## Pages

| Tab | What it does |
|---|---|
| **01 BOT** | Connect, see free space, **live mirror** of the animation on the bot's screen right now, **poke** the bot with a message or emoji (rendered on the phone, so emoji show up as pixels), and a log. |
| **02 PLAYLIST** | **Mood presets** (Chill, Hyper, Sleepy, Love, Grumpy), a playlist of 1 to 8 animations, and the library with filters. Sending replaces every animation on the bot and shuffles the new set. |
| **03 DRAW** | 128x64 pixel editor: pen, eraser, fill, brush size, mirror symmetry, onion skin, undo, nudge, up to 60 frames with adjustable timing, live playback. Start blank or remix a library animation. Save to *Mine*, or send it to play right now. |
| **04 CONVERT** | Pick any GIF or picture from the phone. Choose fit/fill/stretch, dithering (none, pattern, smooth), brightness cut, invert, speed and frame count, all with a live OLED preview. Save to *Mine* or send it to play right now. |

Your own animations live in the app's private storage and appear under **PLAYLIST > LIBRARY > MINE**
(long-press one to delete it).

## Requirements

- Bot firmware with BLE receiver **protocol 3** (`firmware/` in this repo, 2026-11-18 or later).
  Older firmware shows **WRONG FIRMWARE** on connect.
- Android 8.0 or later. On Android 11 and older, Location must be on for Bluetooth scanning.

## Build and install

Open this `android` folder in Android Studio and press **Run** with the phone connected, or:

```powershell
.\gradlew.bat assembleDebug
```

The package is `com.hyperbot.remote`, so it installs alongside the old *QBIT* app. Uninstall that
one once HyperBot works.

## Bluetooth protocol 3

Service `6c4f0001-8b5e-4a61-a3c2-7c7ab1d24000`.

| Characteristic | UUID suffix | Use |
|---|---|---|
| CONTROL | `0002` | write: text commands |
| DATA | `0003` | write without response: file or bitmap bytes |
| STATUS | `0004` | read/notify: replies; the value always holds the latest one |
| NOW | `0005` | read/notify: file name of the animation on screen (live mirror) |

| Phone writes (CONTROL) | Bot replies (STATUS) |
|---|---|
| `HELLO` | `HELLO 3 <freeKB> <totalKB> <gifs>` |
| `PLAYLIST <count> <bytes>` | `WIPING`, then `PLAYLIST <freeKB>` |
| `BEGIN\n<name>\n<size>` | `READY <size>` |
| data on DATA | `ACK <bytes stored>` every 1.5 KB |
| `END` | playlist: `SAVED <n>`, then `PLAYING <n>` after the last; single file: `PLAYING` |
| `POKE <sw> <sl> <tw> <tl>\n<from>\n<text>` | `READY <sl+tl>`; send sender + message bitmaps (SSD1306 page format) on DATA; `END` -> `POKED` |
| `ABORT` | `ABORTED` |

The phone keeps at most 6 KB un-acknowledged (the bot buffers 8 KB), re-reads STATUS if a
notification is lost, and checks free space before anything is deleted. If the link drops halfway
through a playlist, the bot keeps and plays the files that already arrived.

## Troubleshooting

The LOG on the BOT tab shows each step. `adb logcat -s HyperBot` prints the same lines; the bot
prints `[BLE]` lines on its serial port at 115200 baud.
