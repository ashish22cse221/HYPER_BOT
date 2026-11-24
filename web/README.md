# HyperBot website (Netlify)

Static site, no build step. Two pages:

- `index.html` — launch page (what HyperBot is, faces, the app, how it works)
- `build.html` — parts, wiring, one-click browser installer, first boot, pairing, help

## Deploy
1. Go to https://app.netlify.com/drop and drag this whole folder in (or connect a Git repo with publish dir `.`).
2. The Netlify project is `hyperbot-desk` → https://hyperbot-desk.netlify.app (the address the bot's first-boot QR code should open).
   (that's the address the bot's first-boot QR code opens). If it's taken, tell Claude the new URL
   and the firmware QR gets rebuilt to match.

## Updating firmware / app
- Replace the files in `firmware/` (bootloader.bin, partitions.bin, firmware.bin, littlefs.bin) and bump
  `version` in both manifests.
- Replace `downloads/HyperBot-2.2.0.apk` (rename + update the links if the version changes).

## Notes
- The installer uses ESP Web Tools (Web Serial): Chrome / Edge on desktop, over https only.
- Offsets (ESP32-C3): bootloader 0x0, partitions 0x8000, firmware 0x10000, LittleFS 0x2A0000.
- HyperBot is a remix of QBIT by SeanChangX (CC BY-NC-SA 4.0). Fonts are SIL OFL (licences in assets/fonts).
