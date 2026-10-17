@echo off
rem Rebuilds the APK into dist\PuzzleBot.apk and runs the unit tests. Needs JDK 17 (set JAVA_HOME).
if not defined JAVA_HOME if exist "%USERPROFILE%\.jdks\jdk-17.0.20.1+1" set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-17.0.20.1+1"
call "%~dp0gradlew.bat" -p "%~dp0." :app:assembleRelease :app:testReleaseUnitTest
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)
if not exist "%~dp0dist" mkdir "%~dp0dist"
copy /y "%~dp0app\build\outputs\apk\release\app-release.apk" "%~dp0dist\PuzzleBot.apk"
echo Done: dist\PuzzleBot.apk
pause
