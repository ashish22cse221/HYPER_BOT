HyperBot (app 2.2.0 · firmware 2.1.1 · website) · 2026-11-24
===========================================================

app/        Android companion app: Android Studio project.
            Open this folder in Android Studio > Sync Project with Gradle Files > Run.

firmware/   ESP32-C3 (XIAO) + SSD1306 firmware: PlatformIO project.
            Open in VS Code with PlatformIO > Upload.
            firmware/flash_ready/ = prebuilt binaries:
              bootloader.bin @ 0x0, partitions.bin @ 0x8000, firmware.bin @ 0x10000

web/        Website (hyperbot-desk.netlify.app): launch page, build guide, web installer.
            Deploy: drag this folder onto the Deploys tab of the Netlify project.
            web/firmware/ holds the installer images (fresh install also writes the faces).

Based on QBIT, licensed CC BY-NC-SA 4.0. Non-commercial; share changes under the same licence.
