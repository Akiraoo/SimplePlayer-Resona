@echo off
rem Builds the APK from a plain double-click or cmd.
rem   Build.bat            release APK (signed with keystore.properties when present)
rem   Build.bat test       test APK that installs next to the released app
rem   Build.bat test skip  ... without rebuilding the Rust core (uses app\src\main\jniLibs)
rem The Rust core's build scripts need the MSVC linker (link.exe): msvc-env.bat sets it up
rem from msvc-env.local.bat (this PC's Visual Studio path, git-ignored).
cd /d "%~dp0"
call "%~dp0msvc-env.bat"
set "ARGS=assembleRelease"
if /i "%~1"=="test" set "ARGS=%ARGS% -Ptestbuild"
if /i "%~2"=="skip" set "ARGS=%ARGS% -PskipCore"
call gradlew.bat %ARGS%
echo.
if exist "app\build\outputs\apk\release\app-release.apk" echo APK: %~dp0app\build\outputs\apk\release\app-release.apk
pause
