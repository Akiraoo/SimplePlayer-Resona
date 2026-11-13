//! Client for Simple Player Web Server (the same API the desktop app uses) and the resync
//! that keeps the phone's copy of the library up to date.
//!
//! The app only talks to the server for the library when it resyncs; lists, covers and
//! lyrics then come from the local copy. Playback streams from `/stream/<id>`.

use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::Path;
use std::sync::atomic::{AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use serde::Deserialize;

use crate::config::{write_atomic, Paths};
use crate::library::{self, Library, Track};

const WORKERS: usize = 4;
/// Covers bigger than this are not worth keeping on a phone.
const MAX_COVER_BYTES: u64 = 24 * 1024 * 1024;

fn agent(read_secs: u64) -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(6))
        .timeout_read(Duration::from_secs(read_secs))
        .timeout_write(Duration::from_secs(20))
        .user_agent(concat!("Resona/", env!("CARGO_PKG_VERSION")))
        .build()
}

/// "192.168.0.10:55555" -> "http://192.168.0.10:55555"; trailing slashes removed.
pub fn normalize(base: &str) -> String {
    let b = base.trim().trim_end_matches('/');
    if b.is_empty() {
        return String::new();
    }
    if b.starts_with("http://") || b.starts_with("https://") {
        b.to_string()
    } else {
        format!("http://{b}")
    }
}

pub fn enc(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

pub fn stream_url(base: &str, id: &str) -> String {
    format!("{base}/stream/{}", enc(id))
}

fn describe(e: ureq::Error) -> String {
    match e {
        ureq::Error::Status(code, _) => crate::tr!("伺服器回應 HTTP {}", "Server replied HTTP {}", code),
        ureq::Error::Transport(t) => crate::tr!("連線失敗：{}", "Connection failed: {}", t),
    }
}

fn get_text(agent: &ureq::Agent, url: &str) -> Result<String, String> {
    agent
        .get(url)
        .set("Accept", "application/json")
        .call()
        .map_err(describe)?
        .into_string()
        .map_err(|e| e.to_string())
}

/* ---------------- server settings (/api/config) ---------------- */

/// What the server says about itself; used for share links.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ServerConfig {
    /// Public URL of the server (its `publicOrigin` setting); empty for a LAN-only server.
    pub public_origin: String,
    /// Port of the web player; 0 when unknown.
    pub web_port: u16,
}

impl ServerConfig {
    pub fn parse(raw: &str) -> ServerConfig {
        #[derive(Deserialize, Default)]
        #[serde(default, rename_all = "camelCase")]
        struct Resp {
            public_origin: Option<String>,
            web_port: Option<f64>,
        }
        let r: Resp = serde_json::from_str(raw).unwrap_or_default();
        ServerConfig {
            public_origin: r
                .public_origin
                .unwrap_or_default()
                .trim()
                .trim_end_matches('/')
                .to_string(),
            web_port: r.web_port.filter(|p| *p > 0.0 && *p < 65536.0).map_or(0, |p| p as u16),
        }
    }

    /// The last answer kept on disk, so share links stay right without a network round trip.
    pub fn load(paths: &Paths) -> ServerConfig {
        fs::read_to_string(paths.api().join("config.json"))
            .map(|raw| ServerConfig::parse(&raw))
            .unwrap_or_default()
    }

    /// Base URL for share / download links.
    ///
    ///  - The server's publicOrigin wins, so shared links work outside the LAN.
    ///  - An API address without a port (https://music.example.com behind a reverse proxy)
    ///    is used as it is: the proxy already serves the web player there.
    ///  - Otherwise the web port the server advertises, or the old convention
    ///    API 8788 -> web 8787, or the same port.
    pub fn web_base(&self, base: &str) -> String {
        if !self.public_origin.is_empty() {
            return self.public_origin.clone();
        }
        let Some((scheme, rest)) = base.split_once("://") else {
            return base.to_string();
        };
        let authority = rest.split('/').next().unwrap_or(rest);
        // host[:port]; an IPv6 host is in brackets
        let (host, port) = match authority.rfind(':') {
            Some(i) if !authority[i..].contains(']') => {
                (&authority[..i], authority[i + 1..].parse::<u16>().ok())
            }
            _ => (authority, None),
        };
        match port {
            None => format!("{scheme}://{host}"),
            Some(p) => {
                let web = if self.web_port > 0 {
                    self.web_port
                } else if p == 8788 {
                    8787
                } else {
                    p
                };
                format!("{scheme}://{host}:{web}")
            }
        }
    }
}

/* ---------------- resync ---------------- */

#[derive(Clone, Debug, Default)]
pub struct Status {
    pub running: bool,
    pub stage: String,
    pub done: u32,
    pub total: u32,
    /// Why the last resync failed, or that some songs could not be fetched.
    pub error: String,
}

static STATUS: Mutex<Status> = Mutex::new(Status {
    running: false,
    stage: String::new(),
    done: 0,
    total: 0,
    error: String::new(),
});
/// The resync that is allowed to report; a newer one replaces a running one.
static TOKEN: AtomicU64 = AtomicU64::new(0);
static DONE: AtomicU32 = AtomicU32::new(0);

pub fn status() -> Status {
    let mut s = STATUS.lock().map(|s| s.clone()).unwrap_or_default();
    if s.running && s.total > 0 {
        s.done = DONE.load(Ordering::Relaxed).min(s.total);
    }
    s
}

fn update(token: u64, f: impl FnOnce(&mut Status)) {
    if TOKEN.load(Ordering::SeqCst) != token {
        return;
    }
    if let Ok(mut s) = STATUS.lock() {
        f(&mut s);
    }
}

fn stage(token: u64, text: String) {
    update(token, |s| s.stage = text);
}

/// Starts a new resync (a running one stops reporting and gives up).
pub fn begin(scan_first: bool) -> u64 {
    let token = TOKEN.fetch_add(1, Ordering::SeqCst) + 1;
    DONE.store(0, Ordering::Relaxed);
    if let Ok(mut s) = STATUS.lock() {
        *s = Status {
            running: true,
            stage: if scan_first {
                crate::tr!("掃描音樂庫", "Scanning the music library")
            } else {
                crate::tr!("取得最新清單", "Fetching the library")
            },
            ..Status::default()
        };
    }
    token
}

pub fn is_current(token: u64) -> bool {
    TOKEN.load(Ordering::SeqCst) == token
}

pub fn finish(token: u64, error: Option<String>) {
    update(token, |s| {
        s.running = false;
        s.stage = crate::tr!("完成", "Done");
        s.error = error.unwrap_or_default();
    });
}

/// Stops a running resync (the previous snapshot stays as it was).
pub fn cancel() {
    TOKEN.fetch_add(1, Ordering::SeqCst);
    if let Ok(mut s) = STATUS.lock() {
        s.running = false;
    }
}

struct Job<'a> {
    track: &'a Track,
    metadata: bool,
    cover: bool,
}

/// Full resync:
///  1. (optional) ask the server to rescan its music folder
///  2. read /api/config, so share links use the server's public address
///  3. fetch library + playlists
///  4. download metadata (lyrics) / covers of new or changed songs, and any that are missing
///  5. delete cached files of songs that no longer exist
///  6. only then replace library.json / playlists.json
/// If anything fails before step 6 the previous snapshot stays intact.
///
/// Returns the new library and how many songs could not be fetched completely.
pub fn sync(base: &str, paths: &Paths, scan_first: bool, token: u64) -> Result<(Library, u32), String> {
    let api = paths.api();
    let cover_dir = paths.covers();
    fs::create_dir_all(&api).map_err(|e| e.to_string())?;
    fs::create_dir_all(&cover_dir).map_err(|e| e.to_string())?;
    let http = agent(30);
    let cancelled = || -> Result<(), String> {
        if is_current(token) {
            Ok(())
        } else {
            Err("cancelled".into())
        }
    };

    if scan_first {
        // The server scans its music folder and reads the tags of new or changed files
        // before it answers, so give it far more time than a normal request.
        agent(600)
            .post(&format!("{base}/api/scan"))
            .set("Accept", "application/json")
            .send_bytes(&[])
            .map_err(|e| crate::tr!("伺服器掃描失敗：{}", "Server scan failed: {}", describe(e)))?;
        cancelled()?;
        stage(token, crate::tr!("取得最新清單", "Fetching the library"));
    }

    // a server without the endpoint (older build) simply keeps what we had
    if let Ok(raw) = get_text(&http, &format!("{base}/api/config")) {
        if serde_json::from_str::<serde_json::Value>(&raw).is_ok() {
            write_atomic(&api.join("config.json"), raw.as_bytes());
        }
    }

    let old: HashMap<String, Track> = fs::read_to_string(api.join("library.json"))
        .ok()
        .and_then(|raw| library::parse_tracks(&raw).ok())
        .unwrap_or_default()
        .into_iter()
        .map(|t| (t.id.clone(), t))
        .collect();

    let raw_library = get_text(&http, &format!("{base}/api/library"))?;
    let raw_playlists = get_text(&http, &format!("{base}/api/playlists"))?;
    let tracks = library::parse_tracks(&raw_library)
        .map_err(|e| crate::tr!("曲庫格式錯誤：{}", "Bad library data: {}", e))?;
    let playlists = library::parse_playlists(&raw_playlists);
    cancelled()?;

    let have_api = library::file_names(&api);
    let have_covers = library::file_names(&cover_dir);
    let jobs: Vec<Job> = tracks
        .iter()
        .filter_map(|t| {
            let changed = old.get(&t.id) != Some(t);
            let metadata = changed || !have_api.contains(&library::metadata_name(&t.id));
            let cover =
                t.has_cover && (changed || !have_covers.contains(&library::cover_name(&t.id)));
            (metadata || cover).then_some(Job { track: t, metadata, cover })
        })
        .collect();

    DONE.store(0, Ordering::Relaxed);
    update(token, |s| {
        s.total = jobs.len() as u32;
        s.done = 0;
        s.stage = if jobs.is_empty() {
            crate::tr!("整理本機快取", "Tidying the local cache")
        } else {
            crate::tr!("同步歌曲資料", "Syncing song data")
        };
    });

    let next = AtomicUsize::new(0);
    let failed = AtomicU32::new(0);
    std::thread::scope(|scope| {
        for _ in 0..WORKERS.min(jobs.len()) {
            scope.spawn(|| {
                let http = agent(30);
                loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    let Some(job) = jobs.get(i) else { break };
                    if !is_current(token) {
                        break;
                    }
                    let id = &job.track.id;
                    let mut ok = true;
                    if job.metadata {
                        ok &= fetch_metadata(&http, base, paths, id).is_ok();
                    }
                    if job.cover {
                        ok &= fetch_cover(&http, base, paths, id).is_ok();
                    }
                    if !ok {
                        failed.fetch_add(1, Ordering::Relaxed);
                    }
                    DONE.fetch_add(1, Ordering::Relaxed);
                }
            });
        }
    });
    cancelled()?;

    prune(&tracks, paths);
    if !write_atomic(&api.join("library.json"), raw_library.as_bytes()) {
        return Err(crate::tr!("無法寫入本機快取", "Could not write the local cache"));
    }
    write_atomic(&api.join("playlists.json"), raw_playlists.as_bytes());

    Ok((Library::build(tracks, playlists, paths), failed.load(Ordering::Relaxed)))
}

/// Downloads `/api/track/<id>/metadata` (tags + lyrics) into the cache and returns it.
pub fn fetch_metadata(http: &ureq::Agent, base: &str, paths: &Paths, id: &str) -> Result<String, String> {
    let raw = get_text(http, &format!("{base}/api/track/{}/metadata", enc(id)))?;
    // only keep real answers (a proxy's error page must not become "no lyrics")
    serde_json::from_str::<serde_json::Value>(&raw).map_err(|e| e.to_string())?;
    write_atomic(&paths.api().join(library::metadata_name(id)), raw.as_bytes());
    Ok(raw)
}

fn fetch_cover(http: &ureq::Agent, base: &str, paths: &Paths, id: &str) -> Result<(), String> {
    let resp = http
        .get(&format!("{base}/api/track/{}/cover", enc(id)))
        .call()
        .map_err(describe)?;
    let mut bytes = Vec::new();
    resp.into_reader()
        .take(MAX_COVER_BYTES)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.is_empty() {
        return Err("empty cover".into());
    }
    if write_atomic(&paths.covers().join(library::cover_name(id)), &bytes) {
        Ok(())
    } else {
        Err("write failed".into())
    }
}

/// Deletes cached files that do not belong to any song in `tracks`.
fn prune(tracks: &[Track], paths: &Paths) {
    let mut keep_api: std::collections::HashSet<String> =
        tracks.iter().map(|t| library::metadata_name(&t.id)).collect();
    for name in ["library.json", "playlists.json", "config.json"] {
        keep_api.insert(name.to_string());
    }
    remove_others(&paths.api(), &keep_api);
    let keep_covers = tracks
        .iter()
        .filter(|t| t.has_cover)
        .map(|t| library::cover_name(&t.id))
        .collect();
    remove_others(&paths.covers(), &keep_covers);
}

fn remove_others(dir: &Path, keep: &std::collections::HashSet<String>) {
    for name in library::file_names(dir) {
        if !keep.contains(&name) {
            let _ = fs::remove_file(dir.join(name));
        }
    }
}

/// Lyrics of a song: from the local cache (filled by the resync); a song that was never
/// cached is fetched once.
pub fn lyrics(base: &str, paths: &Paths, id: &str) -> Vec<(Option<f64>, String)> {
    let file = paths.api().join(library::metadata_name(id));
    if let Ok(raw) = fs::read_to_string(&file) {
        return library::parse_lyrics(&raw);
    }
    if base.is_empty() {
        return Vec::new();
    }
    fetch_metadata(&agent(15), base, paths, id)
        .map(|raw| library::parse_lyrics(&raw))
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses() {
        assert_eq!(normalize(" 192.168.0.10:55555/ "), "http://192.168.0.10:55555");
        assert_eq!(normalize("https://music.example.com//"), "https://music.example.com");
        assert_eq!(normalize("  "), "");
        assert_eq!(enc("a b/夜.flac"), "a%20b%2F%E5%A4%9C.flac");
        assert_eq!(stream_url("http://h:1", "x y"), "http://h:1/stream/x%20y");
    }

    #[test]
    fn share_link_base() {
        let none = ServerConfig::default();
        assert_eq!(none.web_base("http://192.168.0.10:55555"), "http://192.168.0.10:55555");
        assert_eq!(none.web_base("http://192.168.0.10:8788"), "http://192.168.0.10:8787");
        assert_eq!(none.web_base("https://music.example.com"), "https://music.example.com");
        assert_eq!(none.web_base("https://music.example.com/sub"), "https://music.example.com");
        assert_eq!(none.web_base("http://[fe80::1]:55555"), "http://[fe80::1]:55555");
        assert_eq!(none.web_base("http://[fe80::1]"), "http://[fe80::1]");

        let lan = ServerConfig::parse(r#"{"publicOrigin":"","webPort":50000}"#);
        assert_eq!(lan.web_base("http://192.168.0.10:55555"), "http://192.168.0.10:50000");
        assert_eq!(lan.web_base("https://music.example.com"), "https://music.example.com");

        let public = ServerConfig::parse(r#"{"publicOrigin":" https://sp.example.org/ ","webPort":50000}"#);
        assert_eq!(public.web_base("http://192.168.0.10:55555"), "https://sp.example.org");
        assert_eq!(ServerConfig::parse("<html>"), ServerConfig::default());
    }

    /// A tiny stand-in for Simple Player Web Server. Returns its address and the list of
    /// requests it got ("GET /api/library").
    fn fake_server(library: &'static str) -> (String, std::sync::Arc<Mutex<Vec<String>>>) {
        use std::io::{BufRead, BufReader, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let log = std::sync::Arc::new(Mutex::new(Vec::new()));
        let seen = log.clone();
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { break };
                let seen = seen.clone();
                std::thread::spawn(move || {
                    let mut reader = BufReader::new(stream.try_clone().unwrap());
                    loop {
                        let mut line = String::new();
                        if reader.read_line(&mut line).unwrap_or(0) == 0 {
                            return;
                        }
                        let request = line.trim().trim_end_matches(" HTTP/1.1").to_string();
                        loop {
                            let mut header = String::new();
                            if reader.read_line(&mut header).unwrap_or(0) == 0 || header == "\r\n" {
                                break;
                            }
                        }
                        seen.lock().unwrap().push(request.clone());
                        let path = request.split(' ').nth(1).unwrap_or("");
                        let (code, body): (u16, Vec<u8>) = match path {
                            "/api/scan" => (200, b"{\"tracks\":2}".to_vec()),
                            "/api/config" => (200, b"{\"publicOrigin\":\"https://sp.example.org\",\"webPort\":50000}".to_vec()),
                            "/api/library" => (200, library.as_bytes().to_vec()),
                            "/api/playlists" => (200, "{\"Fav\":[\"dir/b 夜.flac\",\"a.flac\"]}".as_bytes().to_vec()),
                            "/api/track/a.flac/metadata" => (200, b"{\"id\":\"a.flac\",\"lyrics\":[{\"time\":1,\"text\":\"la\"}]}".to_vec()),
                            "/api/track/a.flac/cover" => (200, vec![0xFF, 0xD8, 1, 2, 3]),
                            "/api/track/dir%2Fb%20%E5%A4%9C.flac/metadata" => (200, b"{\"lyrics\":[]}".to_vec()),
                            _ => (404, b"{}".to_vec()),
                        };
                        let head = format!("HTTP/1.1 {code} X\r\nContent-Length: {}\r\n\r\n", body.len());
                        if stream.write_all(head.as_bytes()).is_err() || stream.write_all(&body).is_err() {
                            return;
                        }
                    }
                });
            }
        });
        (base, log)
    }

    #[test]
    fn resync_against_a_server() {
        const LIB: &str = r#"{"tracks":[
            {"id":"a.flac","name":"a.flac","title":"A","ext":".flac","size":10,"mtime":1,"hasCover":true,"duration":100},
            {"id":"dir/b 夜.flac","name":"b 夜.flac","title":"夜","ext":".flac","size":20,"mtime":2,"hasCover":false},
            {"id":"c.mp3","name":"c.mp3","ext":".mp3","size":30,"mtime":3,"hasCover":true}]}"#;
        let (base, log) = fake_server(LIB);
        let dir = std::env::temp_dir().join(format!("spcore-sync-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let paths = Paths::new(&dir);
        // leftovers of a song that no longer exists
        fs::create_dir_all(paths.api()).unwrap();
        fs::create_dir_all(paths.covers()).unwrap();
        fs::write(paths.api().join(library::metadata_name("gone")), "{}").unwrap();
        fs::write(paths.covers().join(library::cover_name("gone")), "x").unwrap();

        let token = begin(true);
        let (lib, failed) = sync(&base, &paths, true, token).unwrap();
        finish(token, None);
        assert!(!status().running);
        assert_eq!(status().total, 3);
        assert_eq!(status().done, 0); // only reported while running
        // c.mp3 has neither metadata nor cover on the fake server
        assert_eq!(failed, 1);
        assert_eq!(lib.tracks.len(), 3);
        assert_eq!(lib.playlists[0].tracks, vec![1, 0]);
        assert!(lib.covers[0].ends_with(".img"));
        assert_eq!(lib.covers[2], "");
        assert_eq!(fs::read(&lib.covers[0]).unwrap(), vec![0xFF, 0xD8, 1, 2, 3]);
        assert!(!paths.api().join(library::metadata_name("gone")).exists());
        assert!(!paths.covers().join(library::cover_name("gone")).exists());
        assert_eq!(ServerConfig::load(&paths).web_port, 50000);
        assert_eq!(lyrics(&base, &paths, "a.flac"), vec![(Some(1.0), "la".to_string())]);
        {
            let seen = log.lock().unwrap();
            assert_eq!(seen[0], "POST /api/scan");
            assert!(seen.contains(&"GET /api/track/dir%2Fb%20%E5%A4%9C.flac/metadata".to_string()));
            // a song without a cover is never asked for one
            assert!(!seen.iter().any(|r| r.contains("b%20") && r.ends_with("/cover")));
        }

        // second resync: nothing changed, so only the song that failed is tried again
        log.lock().unwrap().clear();
        let token = begin(false);
        let (lib2, failed2) = sync(&base, &paths, false, token).unwrap();
        assert_eq!((lib2.tracks.len(), failed2), (3, 1));
        {
            let seen = log.lock().unwrap();
            assert!(!seen.iter().any(|r| r.contains("/api/scan")));
            let per_track: Vec<&String> = seen.iter().filter(|r| r.contains("/api/track/")).collect();
            assert_eq!(per_track.len(), 2, "{per_track:?}");
            assert!(per_track.iter().all(|r| r.contains("c.mp3")));
        }

        // a resync that was replaced gives up without touching the snapshot
        let stale = begin(false);
        let _newer = begin(false);
        assert!(sync(&base, &paths, false, stale).is_err());
        // an unreachable server reports why
        let token = begin(false);
        let err = sync("http://127.0.0.1:9", &paths, false, token).err().unwrap();
        assert!(err.contains("連線失敗") || err.contains("Connection failed"), "{err}");
        let _ = fs::remove_dir_all(&dir);
    }
}
