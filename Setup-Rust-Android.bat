@echo off
rem One-time setup for building the Rust core for Android.
cd /d "%~dp0"
call "%~dp0msvc-env.bat"
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi
echo.
echo Also install NDK 27.2.12479018: Android Studio ^> SDK Manager ^> SDK Tools ^> NDK (Side by side)
pause
