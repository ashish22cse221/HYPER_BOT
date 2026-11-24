@echo off
title QBIT firmware flasher (BLE protocol 2)
cd /d "%~dp0"
echo.
echo  Flashing QBIT firmware with BLE protocol 2
echo  Wi-Fi settings and the GIFs/dashboard on LittleFS are NOT touched.
echo.
set "ESPTOOL=%LOCALAPPDATA%\Temp\qbit-pio-core\packages\tool-esptoolpy\esptool.py"
set "ARGS=--chip esp32c3 --baud 460800 --before default_reset --after hard_reset write_flash -z 0x0 bootloader.bin 0x8000 partitions.bin 0x10000 firmware.bin"

if exist "%ESPTOOL%" (
  python "%ESPTOOL%" %ARGS% && goto done
  py "%ESPTOOL%" %ARGS% && goto done
)
python -m esptool %ARGS% && goto done
py -m esptool %ARGS% && goto done

echo.
echo  Could not run esptool. Install it once with:  python -m pip install esptool
echo  then double-click this file again.
echo  If it says the port is busy, close any serial monitor / Arduino IDE and retry.
echo  If it cannot connect, hold BOOT, tap RESET, release BOOT, and retry.
goto end

:done
echo.
echo  DONE. The board has restarted with the new firmware.
:end
echo.
pause
