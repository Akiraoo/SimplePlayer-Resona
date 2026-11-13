//! Settings (config.json in the app's private files) and where things are kept.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// Folders inside the app's private storage.
#[derive(Clone, Debug, Default)]
pub struct Paths {
    /// `files/core`: config.json, eq.json, session.json
    pub data: PathBuf,
    /// `files/simple-player-cache`: the server snapshot. Same layout as app versions 1.x
    /// (`api/library.json`, `api/metadata_<sha256>.json`, `cover-files/<sha256>.img`), so an
    /// update keeps everything that was synced before.
    pub cache: PathBuf,
}

impl Paths {
    pub fn new(files_dir: &Path) -> Paths {
        Paths {
            data: files_dir.join("core"),
            cache: files_dir.join("simple-player-cache"),
        }
    }
    pub fn api(&self) -> PathBuf {
        self.cache.join("api")
    }
    pub fn covers(&self) -> PathBuf {
        self.cache.join("cover-files")
    }
    pub fn config(&self) -> PathBuf {
        self.data.join("config.json")
    }
    pub fn eq(&self) -> PathBuf {
        self.data.join("eq.json")
    }
    pub fn session(&self) -> PathBuf {
        self.data.join("session.json")
    }
    pub fn position(&self) -> PathBuf {
        self.data.join("position.json")
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct Config {
    /// Simple Player Web Server address (mobile API port), e.g. http://192.168.0.10:55555
    pub server: String,
    pub light: bool,
    /// Theme (accent) colour, "#rrggbb".
    pub accent: String,
    pub shuffle: bool,
    /// 0 off, 1 repeat all, 2 repeat one
    pub repeat: u8,
    /// Interface language: "zh-TW" or "en".
    pub lang: String,
    /// Look for a newer release on GitHub when the app starts.
    pub auto_update: bool,
    /// List opened last: "all" or "pl:<name>".
    pub last_view: String,
    /// Song list order: 0 server order, 1 title, 2 artist, 3 album, 4 duration, 5 format,
    /// 6 date added.
    pub sort_col: u8,
    pub sort_asc: bool,
    /// Hi-Res output: songs go out at their own sample rate, high-resolution ones as
    /// float (not cut to 16-bit first); on Android 14+ the output mixer follows the song
    /// where the device allows it.
    pub hires: bool,
    /// USB DAC exclusive / bit-perfect (Android 14+).
    pub bitperfect: bool,
    /// When the system mixer runs at another rate than the song, convert with the core's
    /// own high-quality resampler (as the desktop app's shared mode) instead of Android's.
    pub resample: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            server: String::new(),
            light: false,
            accent: "#f2a65a".into(),
            shuffle: false,
            repeat: 0,
            lang: String::new(),
            auto_update: true,
            last_view: "all".into(),
            sort_col: 0,
            sort_asc: true,
            hires: true,
            bitperfect: false,
            resample: true,
        }
    }
}

pub fn load(paths: &Paths) -> Option<Config> {
    serde_json::from_slice(&fs::read(paths.config()).ok()?).ok()
}

pub fn save(paths: &Paths, cfg: &Config) {
    if let Ok(json) = serde_json::to_vec_pretty(cfg) {
        write_atomic(&paths.config(), &json);
    }
}

/// Writes through a temporary file, so a crash never leaves half a file behind.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> bool {
    let Some(dir) = path.parent() else { return false };
    let _ = fs::create_dir_all(dir);
    let mut tmp = path.as_os_str().to_owned();
    tmp.push(".tmp");
    let tmp = PathBuf::from(tmp);
    if fs::write(&tmp, bytes).is_err() {
        return false;
    }
    if fs::rename(&tmp, path).is_ok() {
        return true;
    }
    let ok = fs::write(path, bytes).is_ok();
    let _ = fs::remove_file(&tmp);
    ok
}
