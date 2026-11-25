# Resona

Simple Player 的 Android 串流端。

音樂庫留在家裡的 Simple Player Web Server 上，Resona 把它串流到手機播放：曲庫同步、搜尋、播放順序、等化器、頻譜與重新取樣都在 Rust 核心裡完成，與電腦版 Simple Player 共用同一套邏輯；介面使用 Jetpack Compose，播放與系統整合使用 AndroidX Media3。

> Resona 是 [SimplePlayer-android-app](https://github.com/Akiraoo/SimplePlayer-android-app)（Simple Player Android）的後繼專案，以 Rust 核心重新打造。沿用相同的 Application ID（`com.akira.simpleplayer`）與簽章，可直接覆蓋安裝在舊版上，伺服器位址、封面快取與等化器設定都會保留。

## 功能

* 歌單格與全部歌曲列表，多關鍵字搜尋（標題、歌手、專輯、資料夾、檔名）
* 排序：預設順序、標題、歌手、專輯、長度、格式、加入時間
* 隨機、全部循環／單曲循環、佇列（播完自動移除，跳過的保留）
* 迷你播放器與全螢幕播放器：封面左右滑動切歌、下滑關閉、內容依序淡入
* 同步歌詞（逐句置中捲動，點一下跳到該句）
* 音訊頻譜
* 參數等化器（最多 10 段，Poweramp JSON 與 AutoEq／Equalizer APO 格式匯入匯出，與電腦版相同）
* Hi-Res 輸出、高品質重新取樣、USB DAC 獨佔（見下方「音訊輸出」）
* 背景播放、通知列／鎖定畫面／藍牙耳機控制
* 曲庫同步在前景服務中執行，App 退到背景也不會中斷
* 歌曲與歌單分享連結、下載連結、自訂分享清單（30 天）
* 深色／淺色主題、8 種主題色、繁體中文／English
* App 內檢查更新（GitHub Releases）

## 架構

```text
Resona
├── core/                Rust 核心（libspcore.so）
│   ├── library.rs       曲庫快照、搜尋索引、排序、歌詞解析
│   ├── server.rs        與 Simple Player Web Server 同步
│   ├── queue.rs         播放順序：隨機、循環、佇列
│   ├── eq.rs            參數等化器（與電腦版相同）
│   ├── resample.rs      重新取樣器（與電腦版相同）
│   ├── spectrum.rs      頻譜分析
│   └── jni_api.rs       給 Kotlin 呼叫的 JNI 介面
└── app/                 Android（Kotlin）
    ├── core/            JNI 綁定
    ├── playback/        Media3 播放服務、音訊輸出
    ├── sync/            同步用前景服務
    └── ui/              Compose 介面
```

播放器一次只保留「目前這首」與「下一首」，下一首由 Rust 核心決定並預先載入，因此歌曲之間沒有間隙，上萬首的清單也不會塞進播放器。

## 音訊輸出

設定 → 輸出：

| 選項 | 說明 | 系統需求 |
| --- | --- | --- |
| Hi-Res 輸出 | 歌曲以原本的取樣率送出，24-bit 以浮點傳遞，不先降成 16-bit | 所有版本 |
| 高品質重新取樣 | 系統混音的取樣率與歌曲不同時（例如藍牙 96 kHz、歌曲 44.1 kHz），由 Resona 以電腦版的重新取樣器轉換，不交給 Android；不同取樣率的歌也能無縫接續 | 所有版本 |
| USB DAC 獨佔（bit-perfect） | 依每首歌切換 DAC 的取樣率與位深，資料原樣送到 DAC；播放時其他 App 的聲音會被靜音，音量由 DAC 控制 | Android 14 以上、USB DAC |

* 喇叭／有線耳機：多數手機固定以 48 kHz 混音；Android 14 以上且手機音訊驅動回報支援時，輸出才會跟著歌曲切換。
* 藍牙：最後的音質由藍牙編碼決定（LDAC 最高 96 kHz / 24-bit，可在開發人員選項設定），無法 bit-perfect。
* 開著等化器時不算 bit-perfect。
* 播放器的「目前」那行會顯示這首歌實際的輸出路徑。

## Android 17 與區域網路權限

Resona 以 Android 17（API 37）為目標版本。Android 17 起，App 要連到區域網路位址（`192.168.x.x`、`10.x.x.x`、`172.16–31.x.x`、`100.64–127.x.x`、`169.254.x.x`、IPv6 `fc00::/7`／`fe80::/10`、`*.local`）需要 `ACCESS_LOCAL_NETWORK` 權限（在系統裡歸在「附近的裝置」）；沒有權限時連線只會逾時。

* 伺服器位址是公網 IP 或網域（解析到公網位址）時，不會要求這個權限。
* 位址在區域網路上時，開啟 App、儲存位址或同步前會先詢問；拒絕後首頁與設定會顯示說明，可再允許或前往應用程式設定開啟。
* Android 16 以下不受影響。

## Requirements

* Android Studio
* JDK 17
* Android SDK Platform 37.2（Android 17）
* Rust（stable）＋ cargo-ndk
* Android NDK 27.2.12479018
* Windows：Visual Studio Build Tools（C++），Rust 的建置腳本需要 `link.exe`

## Build（Windows）

第一次：

1. Android Studio → SDK Manager → SDK Tools → 勾選 Show Package Details → NDK (Side by side) → **27.2.12479018**
2. 執行 `Setup-Rust-Android.bat`（安裝 cargo-ndk 與 Android 的 Rust target）
3. Visual Studio 不在預設位置時，建立 `msvc-env.local.bat`：

```bat
@echo off
set "VSDIR=D:\Visual Studio"
```

之後：

```text
Build.bat              正式版（有 keystore.properties 時自動簽章）
Build.bat test         測試版（套件名稱 com.akira.simpleplayer.remake，可與正式版並存）
Build.bat test skip    測試版，不重編 Rust 核心
```

APK 會輸出至：

```text
app/build/outputs/apk/release/app-release.apk
```

每次 Build 都會先以 cargo-ndk 編譯 `core/` 到 `app/src/main/jniLibs/`（arm64-v8a、armeabi-v7a）。加上 `-PskipCore` 可跳過，直接使用 `jniLibs` 裡現有的檔案。

在 Android Studio 裡 Build 時不會先設定 MSVC 環境；請先用 `Build.bat` 編過一次核心，或在 Gradle 參數加上 `-PskipCore`。

Rust 核心的測試：

```text
cd core
cargo test
```

不想自己 Build 的話，可以直接從 [GitHub Releases](https://github.com/Akiraoo/SimplePlayer-Resona/releases) 下載預先編譯好的 APK。

## Server

Resona 需要搭配 Simple Player Web Server 使用。

[SimplePlayer-Web-Server](https://github.com/Akiraoo/SimplePlayer-Web-Server)

開源版本的 Simple Player Server 預設使用：

| 功能         | Port |
| ---------- | ---: |
| Web Server | 50000 |
| Mobile API | 55555 |

Resona 應連接 Mobile API，例如：

```text
http://192.168.0.100:55555
```

`192.168.0.100` 請換成實際執行 Simple Player Server 的電腦 LAN IP。透過反向代理對外開放時（例如 `https://music.example.com`），直接填入對外網址即可，不需要加上 Port。

Android 裝置上的 `localhost`／`127.0.0.1` 指的是手機本身，不是你的電腦。

### 分享連結

同步時會讀取 Server 的 `/api/config`：

* Server 有設定 `publicOrigin` 時，分享與下載連結使用該公開網址。
* 沒有設定時，以填入的 API 位址推算 Web Player 網址（例如 `:55555` → `:50000`）。

## 本地快取

曲庫清單、歌詞與封面會快取在手機上，只在按下「重新同步」或「掃描並同步」時才向 Server 更新；同步只會下載新增或變更過的歌曲資料，並刪除已不存在歌曲的快取。音樂本身不會下載到手機。

清除 App 資料不會影響 Server 上的音樂檔案。

## Package Name

```text
com.akira.simpleplayer
```

沿用 Simple Player Android 的 Application ID，讓舊版能直接覆蓋安裝。Fork 時請改成自己的：`app/build.gradle.kts` 的 `namespace`／`applicationId`、Kotlin 的 `package` 與對應資料夾；`core/src/jni_api.rs` 的函式名稱（`Java_com_akira_simpleplayer_core_Core_*`）也要跟著改。

## 環境

| Component         |    Version |
| ----------------- | ---------: |
| Compile SDK       |       37.2 |
| Target SDK        |         37 |
| Minimum SDK       |         26 |
| Android Gradle Plugin |   9.5.2 |
| Gradle            |      9.9.2 |
| Kotlin            |     2.4.21 |
| Java              |         17 |
| Compose BOM       | 2026.11.01 |
| AndroidX Media3   |     1.11.1 |
| Coil              |      2.7.0 |
| Haze              |      1.2.2 |
| Kotlin Coroutines |     1.10.1 |
| NDK               | 27.2.12479018 |

## App 內更新

設定 → 更新，會向 GitHub Releases 查詢最新版本：

* 讀取最新 Release（`/releases/latest`）附加的第一個 `.apk`。
* 以 Release 的 Tag（例如 `v1.0.0`）與 App 的 `versionName`（`1.0.0`）比較。
* 測試版（`Build.bat test`）不檢查更新。

### 發布新版本時

1. 在 `app/build.gradle.kts` 調高 `versionCode`（目前從 60 起算，必須高於舊版 Simple Player Android），`versionName` 設為與 Release Tag 相同的版本號（不含 `v`）。
2. 以**同一把** signing key 簽章。簽章不同時，Android 會拒絕覆蓋安裝。
3. 在 GitHub 建立 Release（Tag 例如 `v1.0.0`）並附上簽章後的 `.apk`。

Fork 時請把 `app/src/main/java/com/akira/simpleplayer/update/Updater.kt` 的：

```kotlin
const val REPO = "Akiraoo/SimplePlayer-Resona"
```

改成自己的 `擁有者/Repository名稱`。

## APK 簽名

本專案不包含作者的 signing key。在專案根目錄建立 `keystore.properties`：

```properties
storeFile=E:/keys/simpleplayer.jks
storePassword=你的 keystore 密碼
keyAlias=你的 key alias
keyPassword=你的 key 密碼
```

`storeFile` 可以是絕對路徑，或相對於 `keystore.properties` 的路徑。也可以放在專案以外，再於 `%USERPROFILE%\.gradle\gradle.properties` 加入 `simpleplayer.keystoreProperties=D:/keys/keystore.properties`，或設定環境變數 `SIMPLEPLAYER_KEYSTORE_PROPERTIES`。

沒有 `keystore.properties` 時正式版不會簽章；測試版一律使用 debug key。

請務必備份 keystore，遺失後將無法再發布可覆蓋安裝的更新。以下檔案已列在 `.gitignore`：

```text
*.jks
*.keystore
keystore.properties
msvc-env.local.bat
```

## License

本專案採用 **Apache License 2.0** 授權，完整條款請參閱 `LICENSE`。

```text
SPDX-License-Identifier: Apache-2.0
```

## 相關專案

* [SimplePlayer-Web-Server](https://github.com/Akiraoo/SimplePlayer-Web-Server)：伺服器
* [SimplePlayer-Desktop-APP](https://github.com/Akiraoo/SimplePlayer-Desktop-APP)：電腦版播放器（Windows／macOS）
