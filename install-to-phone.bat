@echo off
rem Installs dist\PuzzleBot.apk on a phone connected over USB (USB debugging on).
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo adb not found at %ADB%
  pause
  exit /b 1
)
"%ADB%" start-server >nul
echo Connected devices:
"%ADB%" devices
"%ADB%" install -r "%~dp0dist\PuzzleBot.apk"
if errorlevel 1 (
  echo.
  echo Install failed. Is the phone plugged in, unlocked, and "Allow USB debugging" accepted?
) else (
  echo.
  echo Installed. Open "Puzzle Bot" on the phone to finish setup.
)
pause
