//! The app's state behind one lock: settings, library, play order, equalizer presets.
//! The JNI layer (`jni_api.rs`) is a thin wrapper around what is here.

use std::fs;
use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::config::{self, write_atomic, Config, Paths};
use crate::eq;
use crate::library::{Library, Source};
use crate::queue::{Prev, Queue, Session};
use crate::server::{self, ServerConfig};

pub struct Core {
    pub paths: Paths,
    pub cfg: Config,
    pub lib: Library,
    /// Bumped whenever `lib` is replaced; the UI reloads its copy when it changes.
    pub lib_gen: u64,
    pub q: Queue,
    pub eq: eq::Store,
    pub server_cfg: ServerConfig,
    /// Where the restored song was left (ms), until it is played.
    pub resume_ms: i64,
    pub resume_duration_ms: i64,
}

static CORE: Mutex<Option<Core>> = Mutex::new(None);

/// Runs `f` on the state. None before [`init`].
pub fn with<R>(f: impl FnOnce(&mut Core) -> R) -> Option<R> {
    let mut guard = CORE.lock().unwrap_or_else(|e| e.into_inner());
    guard.as_mut().map(f)
}

/// Settings of app versions 1.x (kept in SharedPreferences), read once on the first start
/// of this version.
#[derive(Default)]
pub struct Legacy {
    pub server: String,
    pub light: bool,
    pub eq_json: String,
    pub track_id: String,
    pub position_ms: i64,
    pub duration_ms: i64,
    /// The phone is set to Chinese (picks the language on a first start).
    pub chinese: bool,
}

#[derive(Default, Serialize, Deserialize)]
#[serde(default)]
struct Position {
    id: String,
    position_ms: i64,
    duration_ms: i64,
}

/// Loads everything. Safe to call more than once (the activity and the playback service
/// both do); later calls change nothing.
pub fn init(files_dir: &Path, legacy: Legacy) {
    let mut guard = CORE.lock().unwrap_or_else(|e| e.into_inner());
    if guard.is_some() {
        return;
    }
    let paths = Paths::new(files_dir);
    let _ = fs::create_dir_all(&paths.data);

    let cfg = config::load(&paths).unwrap_or_else(|| {
        let cfg = Config {
            server: server::normalize(&legacy.server),
            light: legacy.light,
            lang: if legacy.chinese { "zh-TW".into() } else { "en".into() },
            ..Config::default()
        };
        config::save(&paths, &cfg);
        cfg
    });
    crate::i18n::set_english(cfg.lang == "en");

    let eq_path = paths.eq();
    let eq_store = if eq_path.is_file() || legacy.eq_json.trim().is_empty() {
        eq::Store::load(&eq_path)
    } else {
        let st = eq::Store::from_text(&legacy.eq_json);
        st.save(&eq_path);
        st
    };
    eq::apply(eq_store.enabled, eq_store.current());

    let lib = Library::load(&paths).unwrap_or_default();
    let seed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x9E37_79B9_7F4A_7C15);
    let mut core = Core {
        server_cfg: ServerConfig::load(&paths),
        paths,
        cfg,
        lib,
        lib_gen: 1,
        q: Queue::new(seed),
        eq: eq_store,
        resume_ms: 0,
        resume_duration_ms: 0,
    };
    core.restore_session(&legacy);
    *guard = Some(core);
}

impl Core {
    fn restore_session(&mut self, legacy: &Legacy) {
        let saved: Option<Session> = fs::read(self.paths.session())
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok());
        let lib = &self.lib;
        match saved {
            Some(sess) => self.q.restore(&sess, |id| lib.by_id.get(id).copied()),
            None => {
                // first start after 1.x: the song that was playing, in the whole library
                if let Some(&i) = lib.by_id.get(&legacy.track_id) {
                    let all: Vec<u32> = (0..lib.tracks.len() as u32).collect();
                    self.q.play_list(all, i as usize, false);
                    self.q.dirty = true;
                }
            }
        }
        let pos: Position = fs::read(self.paths.position())
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or(Position {
                id: legacy.track_id.clone(),
                position_ms: legacy.position_ms,
                duration_ms: legacy.duration_ms,
            });
        let current_id = self.q.current.and_then(|t| self.lib.get(t)).map(|t| t.id.as_str());
        if current_id == Some(pos.id.as_str()) {
            self.resume_ms = pos.position_ms.max(0);
            self.resume_duration_ms = pos.duration_ms.max(0);
        }
    }

    /* ---------- settings ---------- */

    pub fn config_json(&self) -> String {
        json!({
            "server": self.cfg.server,
            "light": self.cfg.light,
            "accent": self.cfg.accent,
            "shuffle": self.cfg.shuffle,
            "repeat": self.cfg.repeat,
            "lang": self.cfg.lang,
            "autoUpdate": self.cfg.auto_update,
            "lastView": self.cfg.last_view,
            "sortCol": self.cfg.sort_col,
            "sortAsc": self.cfg.sort_asc,
            "hires": self.cfg.hires,
            "bitperfect": self.cfg.bitperfect,
            "resample": self.cfg.resample,
        })
        .to_string()
    }

    /// Changes one setting and saves. Returns false for an unknown key.
    pub fn set_config(&mut self, key: &str, value: &str) -> bool {
        let on = value == "true" || value == "1";
        match key {
            "server" => self.cfg.server = server::normalize(value),
            "light" => self.cfg.light = on,
            "accent" => self.cfg.accent = value.trim().to_string(),
            "lang" => {
                self.cfg.lang = if value == "en" { "en".into() } else { "zh-TW".into() };
                crate::i18n::set_english(self.cfg.lang == "en");
            }
            "autoUpdate" => self.cfg.auto_update = on,
            "lastView" => self.cfg.last_view = value.to_string(),
            "sortCol" => self.cfg.sort_col = value.parse().unwrap_or(0),
            "sortAsc" => self.cfg.sort_asc = on,
            "hires" => self.cfg.hires = on,
            "bitperfect" => self.cfg.bitperfect = on,
            "resample" => self.cfg.resample = on,
            "shuffle" => {
                if self.cfg.shuffle != on {
                    self.cfg.shuffle = on;
                    self.q.set_shuffle(on);
                }
            }
            "repeat" => self.cfg.repeat = value.parse::<u8>().unwrap_or(0).min(2),
            _ => return false,
        }
        config::save(&self.paths, &self.cfg);
        true
    }

    /* ---------- library ---------- */

    /// `source`: "all" or "pl:<name>".
    pub fn view(&self, source: &str, query: &str, sort: u8, asc: bool) -> Vec<u32> {
        let src = match source.strip_prefix("pl:") {
            Some(name) => Source::Playlist(name),
            None => Source::All,
        };
        self.lib.view(&src, query, sort, asc)
    }

    /// After a resync: the new snapshot replaces the old one; the play order follows the
    /// songs to their new places.
    pub fn swap_library(&mut self, new: Library) {
        let old = std::mem::replace(&mut self.lib, new);
        let lib = &self.lib;
        self.q.remap(|t| old.get(t).and_then(|tr| lib.by_id.get(&tr.id).copied()));
        self.server_cfg = ServerConfig::load(&self.paths);
        self.lib_gen += 1;
    }

    pub fn stream_url(&self, track: u32) -> String {
        match self.lib.get(track) {
            Some(t) if !self.cfg.server.is_empty() => server::stream_url(&self.cfg.server, &t.id),
            _ => String::new(),
        }
    }

    /// Links to the web player: 0 a song's share page (/s/), 1 a direct download (/d/),
    /// 2 a server playlist (/p/), 3 a custom share playlist (/c/).
    pub fn web_url(&self, kind: i32, value: &str) -> String {
        if self.cfg.server.is_empty() {
            return String::new();
        }
        let prefix = match kind {
            0 => "s",
            1 => "d",
            2 => "p",
            _ => "c",
        };
        format!("{}/{prefix}/{}", self.server_cfg.web_base(&self.cfg.server), server::enc(value))
    }

    /* ---------- play order ---------- */

    pub fn play_list(&mut self, list: Vec<u32>, row: usize) -> Option<u32> {
        self.resume_ms = 0;
        self.q.play_list(list, row, self.cfg.shuffle)
    }

    pub fn play_upnext(&mut self, qi: usize) -> Option<u32> {
        self.resume_ms = 0;
        self.q.play_upnext(qi)
    }

    pub fn plan_next(&mut self) -> Option<u32> {
        self.q.plan_next(self.cfg.shuffle, self.cfg.repeat)
    }

    pub fn commit_plan(&mut self) -> Option<u32> {
        self.resume_ms = 0;
        self.q.commit_plan()
    }

    pub fn advance(&mut self, auto: bool) -> Option<u32> {
        let next = self.q.advance(auto, self.cfg.shuffle, self.cfg.repeat);
        if next.is_some() {
            self.resume_ms = 0;
        }
        next
    }

    pub fn prev(&mut self, position_ms: i64) -> Prev {
        let p = self.q.prev(position_ms);
        if p != Prev::Nothing {
            self.resume_ms = 0;
        }
        p
    }

    /// Remembers where playback is. The position is written every time (a tiny file); the
    /// play order only when it changed.
    pub fn save_session(&mut self, position_ms: i64, duration_ms: i64) {
        let Some(id) = self.q.current.and_then(|t| self.lib.get(t)).map(|t| t.id.clone()) else {
            return;
        };
        if let Ok(bytes) = serde_json::to_vec(&Position { id, position_ms, duration_ms }) {
            write_atomic(&self.paths.position(), &bytes);
        }
        if self.q.dirty {
            self.q.dirty = false;
            let lib = &self.lib;
            let sess = self.q.session(|t| lib.get(t).map(|tr| tr.id.clone()), self.cfg.shuffle);
            if let Ok(bytes) = serde_json::to_vec(&sess) {
                write_atomic(&self.paths.session(), &bytes);
            }
        }
    }

    /* ---------- equalizer ---------- */

    pub fn eq_json(&self) -> String {
        json!({
            "enabled": self.eq.enabled,
            "current": self.eq.current().name,
            "presets": self.eq.presets.iter().map(eq::preset_to_ui).collect::<Vec<_>>(),
        })
        .to_string()
    }

    fn eq_changed(&self) {
        eq::apply(self.eq.enabled, self.eq.current());
        self.eq.save(&self.paths.eq());
    }

    pub fn eq_set_enabled(&mut self, on: bool) {
        self.eq.enabled = on;
        self.eq_changed();
    }

    pub fn eq_select(&mut self, name: &str) {
        if let Some(i) = self.eq.presets.iter().position(|p| p.name == name) {
            self.eq.current = i;
            self.eq_changed();
        }
    }

    /// Hear an edit right away without saving it (a slider being dragged).
    pub fn eq_preview(&self, preset: &eq::Preset) {
        eq::apply(self.eq.enabled, preset);
    }

    /// Saves `preset` in place of the one called `replacing` (or adds it) and makes it the
    /// current one. Returns the name it was saved under (made unique if needed).
    pub fn eq_save(&mut self, preset: eq::Preset, replacing: &str) -> String {
        let mut clean = preset.clean();
        let wanted = if replacing.is_empty() { clean.name.clone() } else { replacing.to_string() };
        let index = self.eq.presets.iter().position(|p| p.name == wanted);
        clean.name = self.eq.unique_name(&clean.name, index);
        let name = clean.name.clone();
        match index {
            Some(i) => {
                self.eq.presets[i] = clean;
                self.eq.current = i;
            }
            None => {
                self.eq.presets.push(clean);
                self.eq.current = self.eq.presets.len() - 1;
            }
        }
        self.eq_changed();
        name
    }

    pub fn eq_delete(&mut self, name: &str) {
        let current = self.eq.current().name.clone();
        self.eq.presets.retain(|p| p.name != name);
        if self.eq.presets.is_empty() {
            self.eq.presets.push(eq::Preset::starter(crate::tr!("預設", "Default")));
        }
        self.eq.current = self.eq.presets.iter().position(|p| p.name == current).unwrap_or(0);
        self.eq_changed();
    }

    /// Adds presets read from a file (renamed when the name is taken) and selects the
    /// first. Returns how many were added.
    pub fn eq_import(&mut self, text: &str, fallback_name: &str) -> Result<usize, String> {
        let list = eq::import(text, fallback_name)?;
        let count = list.len();
        let first = self.eq.presets.len();
        for mut p in list {
            p.name = self.eq.unique_name(&p.name, None);
            self.eq.presets.push(p);
        }
        self.eq.current = first;
        self.eq_changed();
        Ok(count)
    }
}

/* ---------- resync (runs on its own thread) ---------- */

pub fn sync_start(scan_first: bool) {
    let Some((base, paths)) = with(|c| (c.cfg.server.clone(), c.paths.clone())) else {
        return;
    };
    if base.is_empty() {
        return;
    }
    let token = server::begin(scan_first);
    std::thread::spawn(move || {
        let result = server::sync(&base, &paths, scan_first, token);
        if !server::is_current(token) {
            return;
        }
        match result {
            Ok((lib, failed)) => {
                with(|c| c.swap_library(lib));
                server::finish(
                    token,
                    (failed > 0).then(|| {
                        crate::tr!(
                            "{} 首歌曲的資料同步失敗，可再按一次重新同步",
                            "{} songs could not be synced completely; resync to try again",
                            failed
                        )
                    }),
                );
            }
            Err(e) => server::finish(token, Some(e)),
        }
    });
}

pub fn sync_json() -> String {
    let s = server::status();
    json!({
        "running": s.running,
        "stage": s.stage,
        "done": s.done,
        "total": s.total,
        "error": s.error,
        "gen": with(|c| c.lib_gen).unwrap_or(0),
    })
    .to_string()
}

/// Lyrics of a song as lines of "<seconds or empty>\t<text>". May fetch from the server
/// when the song was never cached: call it off the main thread.
pub fn lyrics(track: u32) -> Vec<String> {
    let Some(Some((base, paths, id))) = with(|c| {
        c.lib.get(track).map(|t| (c.cfg.server.clone(), c.paths.clone(), t.id.clone()))
    }) else {
        return Vec::new();
    };
    server::lyrics(&base, &paths, &id)
        .into_iter()
        .map(|(time, text)| match time {
            Some(t) => format!("{t}\t{}", text.replace(['\n', '\r'], " ")),
            None => format!("\t{}", text.replace(['\n', '\r'], " ")),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One test for everything that goes through the global state (tests run in parallel).
    #[test]
    fn first_start_after_v1_and_play() {
        let _alone = eq::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = std::env::temp_dir().join(format!("spcore-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let api = dir.join("simple-player-cache/api");
        fs::create_dir_all(&api).unwrap();
        fs::write(
            api.join("library.json"),
            r#"{"tracks":[{"id":"a","name":"a.flac","title":"A","ext":".flac","hasCover":true},
                          {"id":"b","name":"b.flac","title":"B","ext":".flac"},
                          {"id":"c","name":"c.flac","title":"C","ext":".flac"}]}"#,
        )
        .unwrap();
        fs::write(api.join("playlists.json"), r#"{"Fav":["c","a"]}"#).unwrap();
        fs::write(api.join("config.json"), r#"{"publicOrigin":"https://sp.example.org"}"#).unwrap();
        let covers = dir.join("simple-player-cache/cover-files");
        fs::create_dir_all(&covers).unwrap();
        fs::write(covers.join(crate::library::cover_name("a")), b"img").unwrap();

        init(
            &dir,
            Legacy {
                server: "192.168.0.10:55555".into(),
                light: true,
                eq_json: r#"{"enabled":true,"current":"X","presets":[{"name":"X","preamp":-2,"bands":[{"type":"PEAK","freq":1000,"gain":3,"q":1}]}]}"#.into(),
                track_id: "b".into(),
                position_ms: 42_000,
                duration_ms: 200_000,
                chinese: true,
            },
        );
        with(|c| {
            // settings carried over from 1.x
            assert_eq!(c.cfg.server, "http://192.168.0.10:55555");
            assert!(c.cfg.light);
            assert_eq!(c.cfg.lang, "zh-TW");
            assert!(c.eq.enabled);
            assert_eq!(c.eq.current().name, "X");
            assert!(c.paths.eq().is_file());
            // the song that was playing is back, paused where it was
            assert_eq!(c.q.current, Some(1));
            assert_eq!(c.resume_ms, 42_000);
            assert_eq!(c.plan_next(), Some(2));
            // library
            assert_eq!(c.lib.tracks.len(), 3);
            assert!(c.lib.covers[0].ends_with(".img"));
            assert_eq!(c.lib.covers[1], "");
            assert_eq!(c.view("pl:Fav", "", 0, true), vec![2, 0]);
            assert_eq!(c.stream_url(0), "http://192.168.0.10:55555/stream/a");
            assert_eq!(c.web_url(0, "a"), "https://sp.example.org/s/a");
            assert_eq!(c.web_url(3, "xyz"), "https://sp.example.org/c/xyz");

            // play the playlist from its second row, queue a song, skip around
            let list = c.view("pl:Fav", "", 0, true);
            assert_eq!(c.play_list(list, 1), Some(0));
            assert_eq!(c.resume_ms, 0);
            c.q.add(1);
            assert_eq!(c.plan_next(), None);
            assert!(c.set_config("repeat", "1"));
            assert_eq!(c.plan_next(), Some(2));
            assert_eq!(c.commit_plan(), Some(2));
            c.save_session(1234, 5678);
            assert!(c.paths.session().is_file());
            assert!(!c.q.dirty);

            // a resync where "a" is gone and the order changed
            let tracks = crate::library::parse_tracks(
                r#"{"tracks":[{"id":"c","name":"c.flac"},{"id":"b","name":"b.flac"}]}"#,
            )
            .unwrap();
            let gen = c.lib_gen;
            c.swap_library(Library::build(tracks, Vec::new(), &c.paths.clone()));
            assert_eq!(c.lib_gen, gen + 1);
            assert_eq!(c.q.current, Some(0)); // "c" is now index 0
            assert_eq!(c.q.queue, vec![0]);
            assert_eq!(c.q.upnext, vec![1]);

            // equalizer editing
            let mut p = c.eq.current().clone();
            p.name = "Y".into();
            assert_eq!(c.eq_save(p.clone(), "X"), "Y");
            assert_eq!(c.eq.presets.len(), 1);
            assert_eq!(c.eq_save(p, ""), "Y"); // same name: replaces itself
            assert_eq!(c.eq_import("Filter 1: ON PK Fc 220 Hz Gain -2.1 dB Q 1.2", "Y").unwrap(), 1);
            assert_eq!(c.eq.current().name, "Y (2)");
            c.eq_delete("Y (2)");
            assert_eq!(c.eq.presets.len(), 1);
            c.eq_delete("Y");
            assert_eq!(c.eq.presets.len(), 1); // never empty
            assert!(c.eq_json().contains("\"PEAK\""));
            assert!(!c.set_config("nope", "1"));
        })
        .unwrap();

        // a second init changes nothing
        init(&dir, Legacy::default());
        assert_eq!(with(|c| c.cfg.server.clone()).unwrap(), "http://192.168.0.10:55555");
        let _ = fs::remove_dir_all(&dir);
    }
}
