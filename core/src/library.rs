//! The song library: the server's snapshot kept on the phone, with an index for instant
//! search and sorting. Lists are handed to the UI as track indices.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::Path;

use serde::{Deserialize, Deserializer};
use sha2::{Digest, Sha256};

use crate::config::Paths;

/// A song as the server describes it (`/api/library`).
#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Track {
    #[serde(deserialize_with = "text")]
    pub id: String,
    /// File name.
    #[serde(deserialize_with = "text")]
    pub name: String,
    #[serde(deserialize_with = "text")]
    pub title: String,
    #[serde(deserialize_with = "text")]
    pub artist: String,
    #[serde(deserialize_with = "text")]
    pub album: String,
    #[serde(deserialize_with = "text")]
    pub folder: String,
    #[serde(deserialize_with = "text")]
    pub ext: String,
    #[serde(deserialize_with = "number")]
    pub size: f64,
    #[serde(deserialize_with = "number")]
    pub mtime: f64,
    #[serde(deserialize_with = "flag")]
    pub has_cover: bool,
    /// Seconds (sent by newer servers).
    #[serde(deserialize_with = "number")]
    pub duration: f64,
}

/// Servers and old caches are not strict about types: accept null, numbers as text, etc.
fn text<'de, D: Deserializer<'de>>(d: D) -> Result<String, D::Error> {
    Ok(match serde_json::Value::deserialize(d)? {
        serde_json::Value::String(s) => s,
        serde_json::Value::Number(n) => n.to_string(),
        _ => String::new(),
    })
}

fn number<'de, D: Deserializer<'de>>(d: D) -> Result<f64, D::Error> {
    Ok(match serde_json::Value::deserialize(d)? {
        serde_json::Value::Number(n) => n.as_f64().unwrap_or(0.0),
        serde_json::Value::String(s) => s.trim().parse().unwrap_or(0.0),
        _ => 0.0,
    })
    .map(|v: f64| if v.is_finite() { v } else { 0.0 })
}

fn flag<'de, D: Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    Ok(match serde_json::Value::deserialize(d)? {
        serde_json::Value::Bool(b) => b,
        serde_json::Value::Number(n) => n.as_f64().unwrap_or(0.0) != 0.0,
        serde_json::Value::String(s) => s == "true" || s == "1",
        _ => false,
    })
}

impl Track {
    /// Title shown in lists: the tag, or the file name when there is none.
    pub fn shown_title(&self) -> &str {
        if self.title.trim().is_empty() {
            &self.name
        } else {
            &self.title
        }
    }

    pub fn duration_ms(&self) -> u64 {
        if self.duration > 0.0 {
            (self.duration * 1000.0) as u64
        } else {
            0
        }
    }

    /// Extension without the dot, lower case ("flac").
    pub fn format(&self) -> String {
        self.ext.trim_start_matches('.').to_ascii_lowercase()
    }
}

#[derive(Deserialize)]
struct LibraryResp {
    #[serde(default)]
    tracks: Vec<Track>,
}

/// `/api/library` → songs (entries without an id are dropped).
pub fn parse_tracks(raw: &str) -> Result<Vec<Track>, String> {
    let resp: LibraryResp = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    Ok(resp.tracks.into_iter().filter(|t| !t.id.is_empty()).collect())
}

/// `/api/playlists` → (name, track ids), in the server's order.
pub fn parse_playlists(raw: &str) -> Vec<(String, Vec<String>)> {
    let Ok(map) = serde_json::from_str::<serde_json::Map<String, serde_json::Value>>(raw) else {
        return Vec::new();
    };
    map.into_iter()
        .map(|(name, ids)| {
            let ids = ids
                .as_array()
                .map(|a| {
                    a.iter()
                        .filter_map(|v| v.as_str().map(str::to_string))
                        .collect()
                })
                .unwrap_or_default();
            (name, ids)
        })
        .collect()
}

/// Name of a song's cached files: the SHA-256 of its id (as app versions 1.x did).
pub fn cache_key(id: &str) -> String {
    let digest = Sha256::digest(id.as_bytes());
    let mut s = String::with_capacity(64);
    for b in digest {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

pub fn metadata_name(id: &str) -> String {
    format!("metadata_{}.json", cache_key(id))
}

pub fn cover_name(id: &str) -> String {
    format!("{}.img", cache_key(id))
}

/// File names in a folder (empty when it does not exist).
pub fn file_names(dir: &Path) -> HashSet<String> {
    fs::read_dir(dir)
        .map(|rd| {
            rd.filter_map(Result::ok)
                .filter_map(|e| e.file_name().into_string().ok())
                .collect()
        })
        .unwrap_or_default()
}

pub struct Playlist {
    pub name: String,
    pub tracks: Vec<u32>,
}

#[derive(Default)]
pub struct Library {
    pub tracks: Vec<Track>,
    pub by_id: HashMap<String, u32>,
    /// Lower-case text a search looks through, per track.
    hay: Vec<String>,
    /// Cached cover file per track ("" = none).
    pub covers: Vec<String>,
    pub playlists: Vec<Playlist>,
}

/// What a list shows.
pub enum Source<'a> {
    All,
    Playlist(&'a str),
}

impl Library {
    pub fn build(tracks: Vec<Track>, playlists: Vec<(String, Vec<String>)>, paths: &Paths) -> Library {
        let cover_dir = paths.covers();
        let cached = file_names(&cover_dir);
        let mut by_id = HashMap::with_capacity(tracks.len());
        let mut hay = Vec::with_capacity(tracks.len());
        let mut covers = Vec::with_capacity(tracks.len());
        for (i, t) in tracks.iter().enumerate() {
            by_id.insert(t.id.clone(), i as u32);
            hay.push(
                format!("{} {} {} {} {}", t.shown_title(), t.artist, t.album, t.folder, t.name)
                    .to_lowercase(),
            );
            let mut cover = String::new();
            if t.has_cover {
                let name = cover_name(&t.id);
                if cached.contains(&name) {
                    cover = cover_dir.join(name).to_string_lossy().into_owned();
                }
            }
            covers.push(cover);
        }
        let playlists = playlists
            .into_iter()
            .map(|(name, ids)| Playlist {
                name,
                tracks: ids.iter().filter_map(|id| by_id.get(id).copied()).collect(),
            })
            .collect();
        Library { tracks, by_id, hay, covers, playlists }
    }

    /// The snapshot saved by the last sync (None before the first one).
    pub fn load(paths: &Paths) -> Option<Library> {
        let api = paths.api();
        let tracks = parse_tracks(&fs::read_to_string(api.join("library.json")).ok()?).ok()?;
        let playlists = fs::read_to_string(api.join("playlists.json"))
            .map(|raw| parse_playlists(&raw))
            .unwrap_or_default();
        Some(Library::build(tracks, playlists, paths))
    }

    pub fn get(&self, index: u32) -> Option<&Track> {
        self.tracks.get(index as usize)
    }

    /// Songs of a list that match the search (every word, anywhere in title / artist /
    /// album / folder / file name), in the chosen order.
    ///
    /// `sort`: 0 the list's own order, 1 title, 2 artist, 3 album, 4 duration, 5 format,
    /// 6 date added (file time).
    pub fn view(&self, source: &Source, query: &str, sort: u8, asc: bool) -> Vec<u32> {
        let q = query.trim().to_lowercase();
        let words: Vec<&str> = q.split_whitespace().collect();
        let hit = |i: &u32| words.iter().all(|w| self.hay[*i as usize].contains(w));
        let mut rows: Vec<u32> = match source {
            Source::All => (0..self.tracks.len() as u32).filter(hit).collect(),
            Source::Playlist(name) => self
                .playlists
                .iter()
                .find(|p| p.name == *name)
                .map(|p| p.tracks.iter().copied().filter(hit).collect())
                .unwrap_or_default(),
        };
        let t = |i: u32| &self.tracks[i as usize];
        match sort {
            1 => rows.sort_by_cached_key(|&i| t(i).shown_title().to_lowercase()),
            2 => rows.sort_by_cached_key(|&i| {
                format!("{}\u{1}{}", t(i).artist.to_lowercase(), t(i).album.to_lowercase())
            }),
            3 => rows.sort_by_cached_key(|&i| {
                format!("{}\u{1}{}", t(i).album.to_lowercase(), t(i).shown_title().to_lowercase())
            }),
            4 => rows.sort_by_key(|&i| t(i).duration_ms()),
            5 => rows.sort_by_cached_key(|&i| t(i).format()),
            6 => rows.sort_by(|&a, &b| t(a).mtime.total_cmp(&t(b).mtime)),
            _ => return rows,
        }
        if !asc {
            rows.reverse();
        }
        rows
    }

    /// Everything the UI lists about the songs, in one string: 7 fields per track
    /// (id, title, artist, album, format, folder, cover file), separated by U+0001.
    pub fn strings(&self) -> String {
        let clean = |s: &str| s.replace('\u{1}', " ");
        let mut out = String::with_capacity(self.tracks.len() * 96);
        for (i, t) in self.tracks.iter().enumerate() {
            if i > 0 {
                out.push('\u{1}');
            }
            for (k, field) in [
                clean(&t.id),
                clean(t.shown_title()),
                clean(&t.artist),
                clean(&t.album),
                t.format(),
                clean(&t.folder),
                clean(&self.covers[i]),
            ]
            .iter()
            .enumerate()
            {
                if k > 0 {
                    out.push('\u{1}');
                }
                out.push_str(field);
            }
        }
        out
    }

    /// Numbers that go with [`strings`]: duration (ms), size (bytes), has-cover per track.
    pub fn numbers(&self) -> Vec<i64> {
        let mut out = Vec::with_capacity(self.tracks.len() * 3);
        for t in &self.tracks {
            out.push(t.duration_ms() as i64);
            out.push(t.size.max(0.0) as i64);
            out.push(t.has_cover as i64);
        }
        out
    }
}

/// Parses LRC ("[mm:ss.xx]text", several stamps per line allowed). Lines without a
/// time stamp are returned as unsynced text.
pub fn parse_lrc(text: &str) -> Vec<(Option<f64>, String)> {
    let mut timed: Vec<(f64, String)> = Vec::new();
    let mut plain: Vec<String> = Vec::new();
    for raw in text.lines() {
        let mut rest = raw.trim();
        let mut stamps = Vec::new();
        while let Some(stripped) = rest.strip_prefix('[') {
            let Some(end) = stripped.find(']') else { break };
            let inner = &stripped[..end];
            match parse_stamp(inner) {
                Some(t) => stamps.push(t),
                None => {
                    // [ar:...] style metadata: skip the whole line
                    if inner.contains(':') && stamps.is_empty() {
                        rest = "";
                        break;
                    }
                }
            }
            rest = stripped[end + 1..].trim_start();
        }
        if stamps.is_empty() {
            if !rest.is_empty() {
                plain.push(rest.to_string());
            }
        } else {
            for t in stamps {
                timed.push((t, rest.to_string()));
            }
        }
    }
    if !timed.is_empty() {
        timed.sort_by(|a, b| a.0.total_cmp(&b.0));
        return timed.into_iter().map(|(t, s)| (Some(t), s)).collect();
    }
    plain.into_iter().map(|s| (None, s)).collect()
}

fn parse_stamp(s: &str) -> Option<f64> {
    let (m, sec) = s.split_once(':')?;
    let m: f64 = m.trim().parse().ok()?;
    let sec: f64 = sec.trim().replace(':', ".").parse().ok()?;
    Some(m * 60.0 + sec)
}

/// Lyrics from a `/api/track/<id>/metadata` answer: `lyrics: [{time, text}]` (`time` is
/// null for unsynced lines).
pub fn parse_lyrics(raw: &str) -> Vec<(Option<f64>, String)> {
    #[derive(Deserialize)]
    struct Meta {
        #[serde(default)]
        lyrics: Vec<serde_json::Value>,
    }
    let Ok(meta) = serde_json::from_str::<Meta>(raw) else {
        return Vec::new();
    };
    meta.lyrics
        .iter()
        .filter_map(|l| {
            let text = l.get("text").and_then(|t| t.as_str()).unwrap_or("").to_string();
            let time = l.get("time").and_then(|t| t.as_f64()).filter(|t| t.is_finite());
            (time.is_some() || !text.trim().is_empty()).then_some((time, text))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lib() -> Library {
        let raw = r#"{"tracks":[
            {"id":"a/One.flac","name":"One.flac","title":"One","artist":"Alpha","album":"First","folder":"a","ext":".flac","size":1000,"mtime":30,"hasCover":true,"duration":200.5},
            {"id":"a/two.mp3","name":"two.mp3","title":"","artist":"Beta","album":"Second","folder":"a","ext":".MP3","size":"2000","mtime":10.5,"hasCover":false},
            {"id":"b/夜に駆ける.flac","name":"夜に駆ける.flac","title":"夜に駆ける","artist":"YOASOBI","album":"THE BOOK","folder":"b","ext":".flac","size":3000,"mtime":20,"hasCover":null,"duration":261},
            {"name":"no id"}
        ]}"#;
        let pl = r#"{"Zed":["b/夜に駆ける.flac","a/One.flac","gone"],"Abc":["a/two.mp3"]}"#;
        Library::build(parse_tracks(raw).unwrap(), parse_playlists(pl), &Paths::default())
    }

    #[test]
    fn parses_loosely() {
        let l = lib();
        assert_eq!(l.tracks.len(), 3);
        assert_eq!(l.tracks[1].shown_title(), "two.mp3");
        assert_eq!(l.tracks[1].size, 2000.0);
        assert_eq!(l.tracks[1].format(), "mp3");
        assert_eq!(l.tracks[0].duration_ms(), 200_500);
        assert!(!l.tracks[2].has_cover);
        // playlists keep the server's order and drop songs that no longer exist
        assert_eq!(l.playlists[0].name, "Zed");
        assert_eq!(l.playlists[0].tracks, vec![2, 0]);
        assert_eq!(l.playlists[1].name, "Abc");
    }

    #[test]
    fn search_and_sort() {
        let l = lib();
        assert_eq!(l.view(&Source::All, "", 0, true), vec![0, 1, 2]);
        assert_eq!(l.view(&Source::All, "  yoasobi  book ", 0, true), vec![2]);
        assert_eq!(l.view(&Source::All, "夜に", 0, true), vec![2]);
        assert_eq!(l.view(&Source::All, "TWO", 0, true), vec![1]);
        assert_eq!(l.view(&Source::All, "alpha beta", 0, true), Vec::<u32>::new());
        assert_eq!(l.view(&Source::Playlist("Zed"), "", 0, true), vec![2, 0]);
        assert_eq!(l.view(&Source::Playlist("Zed"), "one", 0, true), vec![0]);
        assert_eq!(l.view(&Source::Playlist("nope"), "", 0, true), Vec::<u32>::new());
        assert_eq!(l.view(&Source::All, "", 1, true), vec![0, 1, 2]); // one, two.mp3, 夜
        assert_eq!(l.view(&Source::All, "", 2, false), vec![2, 1, 0]);
        assert_eq!(l.view(&Source::All, "", 4, true), vec![1, 0, 2]);
        assert_eq!(l.view(&Source::All, "", 6, false), vec![0, 2, 1]);
    }

    #[test]
    fn strings_line_up() {
        let l = lib();
        let s = l.strings();
        let fields: Vec<&str> = s.split('\u{1}').collect();
        assert_eq!(fields.len(), 7 * 3);
        assert_eq!(fields[7 + 1], "two.mp3");
        assert_eq!(fields[7 + 4], "mp3");
        assert_eq!(l.numbers().len(), 9);
        assert_eq!(l.numbers()[0], 200_500);
    }

    #[test]
    fn cache_names_match_v1() {
        // same as Kotlin: SHA-256 of the id, lower-case hex
        assert_eq!(
            cache_key("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert!(metadata_name("abc").starts_with("metadata_ba78"));
        assert!(cover_name("abc").ends_with("15ad.img"));
    }

    #[test]
    fn lyrics() {
        let raw = r#"{"id":"x","lyrics":[{"time":1.5,"text":"a"},{"time":null,"text":"b"},{"time":null,"text":"  "}]}"#;
        assert_eq!(parse_lyrics(raw), vec![(Some(1.5), "a".into()), (None, "b".into())]);
        assert_eq!(parse_lrc("[ar:x]\n[00:02.50]two\n[00:01.00][00:03.00]one"), vec![
            (Some(1.0), "one".into()),
            (Some(2.5), "two".into()),
            (Some(3.0), "one".into()),
        ]);
    }
}
