//! What Kotlin calls: `com.akira.simpleplayer.core.Core` (see Core.kt for the Kotlin side;
//! the two lists must match).
//!
//! Rules: nothing here may panic across the boundary (every body runs inside [`guard`]);
//! the two audio functions (`eqProcess`, `spectrumFeed`) never take the big state lock.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::ptr::null_mut;
use std::sync::Mutex;

use jni::objects::{JByteBuffer, JClass, JFloatArray, JIntArray, JString};
use jni::sys::{
    jboolean, jfloat, jfloatArray, jint, jintArray, jlong, jlongArray, jobjectArray, jstring,
    JNI_FALSE, JNI_TRUE,
};
use jni::JNIEnv;

use crate::eq;
use crate::queue::Prev;
use crate::server;
use crate::spectrum;
use crate::state::{self, with, Legacy};

fn guard<R>(default: R, f: impl FnOnce() -> R) -> R {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

fn text(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(String::from).unwrap_or_default()
}

fn out_text(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(null_mut())
}

fn out_ints(env: &mut JNIEnv, v: &[i32]) -> jintArray {
    match env.new_int_array(v.len() as i32) {
        Ok(a) => {
            let _ = env.set_int_array_region(&a, 0, v);
            a.into_raw()
        }
        Err(_) => null_mut(),
    }
}

fn out_indices(env: &mut JNIEnv, v: &[u32]) -> jintArray {
    let ints: Vec<i32> = v.iter().map(|&i| i as i32).collect();
    out_ints(env, &ints)
}

fn index(t: Option<u32>) -> jint {
    t.map_or(-1, |t| t as jint)
}

fn flag(b: bool) -> jboolean {
    if b {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/* ---------------- start-up, settings ---------------- */

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_init<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    files_dir: JString<'l>,
    legacy_server: JString<'l>,
    legacy_light: jboolean,
    legacy_eq: JString<'l>,
    legacy_track: JString<'l>,
    legacy_position: jlong,
    legacy_duration: jlong,
    chinese: jboolean,
) {
    guard((), || {
        let dir = PathBuf::from(text(&mut env, &files_dir));
        let legacy = Legacy {
            server: text(&mut env, &legacy_server),
            light: legacy_light != JNI_FALSE,
            eq_json: text(&mut env, &legacy_eq),
            track_id: text(&mut env, &legacy_track),
            position_ms: legacy_position,
            duration_ms: legacy_duration,
            chinese: chinese != JNI_FALSE,
        };
        state::init(&dir, legacy);
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_version<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jstring {
    out_text(&mut env, env!("CARGO_PKG_VERSION"))
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_configJson<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jstring {
    let json = guard(None, || with(|c| c.config_json())).unwrap_or_else(|| "{}".into());
    out_text(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_setConfig<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    key: JString<'l>,
    value: JString<'l>,
) {
    guard((), || {
        let (key, value) = (text(&mut env, &key), text(&mut env, &value));
        with(|c| c.set_config(&key, &value));
    })
}

/* ---------------- resync ---------------- */

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_syncStart<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    scan_first: jboolean,
) {
    guard((), || state::sync_start(scan_first != JNI_FALSE))
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_syncCancel<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) {
    guard((), server::cancel)
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_syncState<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jstring {
    let json = guard("{}".to_string(), state::sync_json);
    out_text(&mut env, &json)
}

/* ---------------- library ---------------- */

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_libGen<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jlong {
    guard(0, || with(|c| c.lib_gen as jlong).unwrap_or(0))
}

/// 7 fields per track (id, title, artist, album, format, folder, cover file), separated by
/// U+0001.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_libStrings<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jstring {
    let s = guard(None, || with(|c| c.lib.strings())).unwrap_or_default();
    out_text(&mut env, &s)
}

/// 3 numbers per track: duration (ms), size (bytes), has cover.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_libNumbers<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jlongArray {
    let v = guard(None, || with(|c| c.lib.numbers())).unwrap_or_default();
    match env.new_long_array(v.len() as i32) {
        Ok(a) => {
            let _ = env.set_long_array_region(&a, 0, &v);
            a.into_raw()
        }
        Err(_) => null_mut(),
    }
}

/// Playlist names, separated by U+0001 (server order).
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_playlistNames<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jstring {
    let s = guard(None, || {
        with(|c| {
            c.lib
                .playlists
                .iter()
                .map(|p| p.name.replace('\u{1}', " "))
                .collect::<Vec<_>>()
                .join("\u{1}")
        })
    })
    .unwrap_or_default();
    out_text(&mut env, &s)
}

/// 2 numbers per playlist: song count, first song with a cached cover (-1 = none).
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_playlistInfo<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jintArray {
    let v = guard(None, || {
        with(|c| {
            let mut out = Vec::with_capacity(c.lib.playlists.len() * 2);
            for p in &c.lib.playlists {
                out.push(p.tracks.len() as i32);
                let cover = p
                    .tracks
                    .iter()
                    .find(|&&t| !c.lib.covers[t as usize].is_empty())
                    .or(p.tracks.first());
                out.push(cover.map_or(-1, |&t| t as i32));
            }
            out
        })
    })
    .unwrap_or_default();
    out_ints(&mut env, &v)
}

/// Track indices of a list ("all" or "pl:<name>"), filtered and sorted.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_view<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    source: JString<'l>,
    query: JString<'l>,
    sort: jint,
    asc: jboolean,
) -> jintArray {
    let rows = guard(Vec::new(), || {
        let (source, query) = (text(&mut env, &source), text(&mut env, &query));
        with(|c| c.view(&source, &query, sort.clamp(0, 255) as u8, asc != JNI_FALSE)).unwrap_or_default()
    });
    out_indices(&mut env, &rows)
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_streamUrl<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    track: jint,
) -> jstring {
    let s = guard(None, || with(|c| c.stream_url(track.max(0) as u32))).unwrap_or_default();
    out_text(&mut env, &s)
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_webUrl<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    kind: jint,
    value: JString<'l>,
) -> jstring {
    let s = guard(String::new(), || {
        let value = text(&mut env, &value);
        with(|c| c.web_url(kind, &value)).unwrap_or_default()
    });
    out_text(&mut env, &s)
}

/// Lyrics as "<seconds or empty>\t<text>" per line. May use the network: not on the main
/// thread.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_lyrics<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    track: jint,
) -> jobjectArray {
    let lines = guard(Vec::new(), || if track < 0 { Vec::new() } else { state::lyrics(track as u32) });
    let Ok(arr) = env.new_object_array(lines.len() as i32, "java/lang/String", JString::default()) else {
        return null_mut();
    };
    for (i, line) in lines.iter().enumerate() {
        if let Ok(s) = env.new_string(line) {
            let _ = env.set_object_array_element(&arr, i as i32, &s);
            let _ = env.delete_local_ref(s);
        }
    }
    arr.into_raw()
}

/* ---------------- play order ---------------- */

/// A list (track indices) becomes the play order, starting at `row`. Returns the song to
/// play, -1 when there is none.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_playList<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    list: JIntArray<'l>,
    row: jint,
) -> jint {
    guard(-1, || {
        let len = env.get_array_length(&list).unwrap_or(0).max(0) as usize;
        let mut buf = vec![0i32; len];
        if env.get_int_array_region(&list, 0, &mut buf).is_err() || row < 0 {
            return -1;
        }
        let rows: Vec<u32> = buf.into_iter().map(|i| i.max(0) as u32).collect();
        index(with(|c| c.play_list(rows, row as usize)).flatten())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_playUpnext<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    qi: jint,
) -> jint {
    guard(-1, || {
        if qi < 0 {
            return -1;
        }
        index(with(|c| c.play_upnext(qi as usize)).flatten())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_current<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jint {
    guard(-1, || index(with(|c| c.q.current).flatten()))
}

/// The song after the current one (to preload); -1 = none.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_planNext<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jint {
    guard(-1, || index(with(|c| c.plan_next()).flatten()))
}

/// The player moved on to the planned song by itself. Returns the song now playing.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_commitPlan<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jint {
    guard(-1, || index(with(|c| c.commit_plan()).flatten()))
}

/// Next song (`auto` = the current one ended by itself); -1 = nothing more to play.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_advance<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    auto: jboolean,
) -> jint {
    guard(-1, || index(with(|c| c.advance(auto != JNI_FALSE)).flatten()))
}

/// Previous song: the song to play, -1 = start the current one over, -2 = nothing.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_prev<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    position_ms: jlong,
) -> jint {
    guard(-2, || match with(|c| c.prev(position_ms)) {
        Some(Prev::Track(t)) => t as jint,
        Some(Prev::Restart) => -1,
        _ => -2,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_queueAdd<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    track: jint,
) {
    guard((), || {
        with(|c| {
            if track >= 0 && (track as usize) < c.lib.tracks.len() {
                c.q.add(track as u32);
            }
        });
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_queueRemove<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    qi: jint,
) -> jboolean {
    guard(JNI_FALSE, || flag(qi >= 0 && with(|c| c.q.remove(qi as usize)).unwrap_or(false)))
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_queueClear<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) {
    guard((), || {
        with(|c| c.q.clear_upnext());
    })
}

/// The user's queue (track indices).
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_upnext<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jintArray {
    let v = guard(None, || with(|c| c.q.upnext.clone())).unwrap_or_default();
    out_indices(&mut env, &v)
}

/// Position in the queue of the song playing from it, -1 when the list is playing.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_upnextCurrent<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jint {
    guard(-1, || with(|c| c.q.upnext_cur.map_or(-1, |i| i as jint)).unwrap_or(-1))
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_saveSession<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    position_ms: jlong,
    duration_ms: jlong,
) {
    guard((), || {
        with(|c| c.save_session(position_ms, duration_ms));
    })
}

/// Where the restored song was left (ms); 0 once something has been played.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_resumePosition<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jlong {
    guard(0, || with(|c| c.resume_ms).unwrap_or(0))
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_resumeDuration<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jlong {
    guard(0, || with(|c| c.resume_duration_ms).unwrap_or(0))
}

/* ---------------- equalizer: settings ---------------- */

static EQ_ERROR: Mutex<String> = Mutex::new(String::new());

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqJson<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jstring {
    let json = guard(None, || with(|c| c.eq_json())).unwrap_or_else(|| "{}".into());
    out_text(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqSetEnabled<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    on: jboolean,
) {
    guard((), || {
        with(|c| c.eq_set_enabled(on != JNI_FALSE));
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqSelect<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    name: JString<'l>,
) {
    guard((), || {
        let name = text(&mut env, &name);
        with(|c| c.eq_select(&name));
    })
}

/// Hear an edit right away without saving it.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqPreview<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    preset: JString<'l>,
) {
    guard((), || {
        if let Some(p) = eq::preset_from_ui(&text(&mut env, &preset)) {
            with(|c| c.eq_preview(&p));
        }
    })
}

/// Saves a preset in place of `replacing` (empty = by its own name / add). Returns the
/// name it was saved under.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqSave<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    preset: JString<'l>,
    replacing: JString<'l>,
) -> jstring {
    let name = guard(String::new(), || {
        let replacing = text(&mut env, &replacing);
        match eq::preset_from_ui(&text(&mut env, &preset)) {
            Some(p) => with(|c| c.eq_save(p, &replacing)).unwrap_or_default(),
            None => String::new(),
        }
    });
    out_text(&mut env, &name)
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqDelete<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    name: JString<'l>,
) {
    guard((), || {
        let name = text(&mut env, &name);
        with(|c| c.eq_delete(&name));
    })
}

/// Adds the presets found in a file's text. Returns how many, -1 on failure (see
/// `eqError`).
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqImport<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    content: JString<'l>,
    fallback_name: JString<'l>,
) -> jint {
    guard(-1, || {
        let (content, name) = (text(&mut env, &content), text(&mut env, &fallback_name));
        match with(|c| c.eq_import(&content, &name)) {
            Some(Ok(n)) => n as jint,
            Some(Err(e)) => {
                if let Ok(mut slot) = EQ_ERROR.lock() {
                    *slot = e;
                }
                -1
            }
            None => -1,
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqError<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jstring {
    let e = EQ_ERROR.lock().map(|s| s.clone()).unwrap_or_default();
    out_text(&mut env, &e)
}

/// All presets in Poweramp's format.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqExport<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
) -> jstring {
    let s = guard(None, || with(|c| eq::export(&c.eq.presets))).unwrap_or_else(|| "[]".into());
    out_text(&mut env, &s)
}

/// Gain (dB, preamp included) of a preset at each of `freqs` (Hz).
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqResponse<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    preset: JString<'l>,
    freqs: JFloatArray<'l>,
) -> jfloatArray {
    let out = guard(Vec::new(), || {
        let len = env.get_array_length(&freqs).unwrap_or(0).max(0) as usize;
        let mut f = vec![0f32; len];
        if env.get_float_array_region(&freqs, 0, &mut f).is_err() {
            return Vec::new();
        }
        match eq::preset_from_ui(&text(&mut env, &preset)) {
            Some(p) => f.iter().map(|&hz| eq::response(&p, hz.max(1.0) as f64) as f32).collect(),
            None => vec![0.0; len],
        }
    });
    match env.new_float_array(out.len() as i32) {
        Ok(a) => {
            let _ = env.set_float_array_region(&a, 0, &out);
            a.into_raw()
        }
        Err(_) => null_mut(),
    }
}

/// Preamp that keeps the loudest point of the curve at 0 dB.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqAutoPreamp<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    preset: JString<'l>,
) -> jfloat {
    guard(0.0, || {
        eq::preset_from_ui(&text(&mut env, &preset)).map_or(0.0, |p| eq::auto_preamp(&p))
    })
}

/* ---------------- audio path (playback thread) ---------------- */

static PROCESSOR: Mutex<Option<eq::Processor>> = Mutex::new(None);

/// `len` bytes of a direct buffer starting at `pos`; None when it is not a direct buffer or
/// the range is outside it.
unsafe fn bytes<'a>(env: &JNIEnv, buf: &JByteBuffer, pos: jint, len: usize) -> Option<&'a mut [u8]> {
    let base = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if base.is_null() || pos < 0 || (pos as usize).checked_add(len)? > cap {
        return None;
    }
    Some(std::slice::from_raw_parts_mut(base.add(pos as usize), len))
}

/// Runs `samples` interleaved samples (`encoding`: 0 16-bit, 1 float, 2 24-bit packed,
/// 3 32-bit; little endian) from `input` at
/// `in_pos` through the equalizer into `output` at `out_pos`. Both must be direct buffers.
/// Returns false when nothing was written (equalizer off or flat: pass the input through).
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqProcess<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    input: JByteBuffer<'l>,
    in_pos: jint,
    output: JByteBuffer<'l>,
    out_pos: jint,
    samples: jint,
    channels: jint,
    encoding: jint,
    rate: jint,
) -> jboolean {
    guard(JNI_FALSE, || {
        if samples <= 0 || channels <= 0 || rate <= 0 {
            return JNI_FALSE;
        }
        let Ok(mut guard) = PROCESSOR.lock() else { return JNI_FALSE };
        let proc = guard.get_or_insert_with(eq::Processor::default);
        if !proc.prepare(rate as u32, channels as usize) {
            return JNI_FALSE;
        }
        let Some(fmt) = eq::Pcm::from_code(encoding) else { return JNI_FALSE };
        let len = samples as usize * fmt.bytes();
        // SAFETY: both ranges were checked against the buffers' capacity; Kotlin keeps the
        // buffers alive for the call and they are two different buffers.
        let (Some(src), Some(dst)) = (unsafe { bytes(&env, &input, in_pos, len) }, unsafe {
            bytes(&env, &output, out_pos, len)
        }) else {
            return JNI_FALSE;
        };
        proc.process_bytes(src, dst, fmt);
        JNI_TRUE
    })
}

/// Seek or a new song: clear the filter memory.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_eqReset<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) {
    guard((), || {
        if let Ok(mut guard) = PROCESSOR.lock() {
            if let Some(p) = guard.as_mut() {
                p.reset();
            }
        }
    })
}

/// Feeds `samples` interleaved samples of a direct buffer to the spectrum analyser.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_spectrumFeed<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    input: JByteBuffer<'l>,
    pos: jint,
    samples: jint,
    channels: jint,
    encoding: jint,
    rate: jint,
) {
    guard((), || {
        if samples <= 0 || channels <= 0 {
            return;
        }
        let Some(fmt) = eq::Pcm::from_code(encoding) else { return };
        let len = samples as usize * fmt.bytes();
        // SAFETY: the range was checked against the buffer's capacity.
        if let Some(src) = unsafe { bytes(&env, &input, pos, len) } {
            spectrum::feed_bytes(src, fmt, channels as usize, rate.max(0) as u32);
        }
    })
}

/// Copies the newest spectrum (0..1 per bar, 48 bars) into `dest`.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_spectrumRead<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    dest: JFloatArray<'l>,
) {
    guard((), || {
        let mut bars = [0f32; spectrum::BARS];
        spectrum::read(&mut bars);
        let len = env.get_array_length(&dest).unwrap_or(0).max(0) as usize;
        let _ = env.set_float_array_region(&dest, 0, &bars[..len.min(spectrum::BARS)]);
    })
}

/// The samples fed next (spectrumFeed / pipeProcess) start at this presentation time.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_spectrumAt<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    pts_us: jlong,
) {
    guard((), || spectrum::at(pts_us as f64 / 1e6))
}

/// The output's playback position, in the same time base as spectrumAt.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_spectrumClock<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    pos_us: jlong,
) {
    guard((), || spectrum::clock(pos_us as f64 / 1e6))
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_spectrumStop<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
) {
    guard((), spectrum::stop)
}

/* ---------------- shared-mode resampling pipeline ---------------- */

/// Song → output mixer rate converter, with the equalizer at the output rate. Owned by
/// Kotlin through an opaque handle (one per song format).
struct Pipe {
    rs: crate::resample::Resampler,
    channels: usize,
    dst: u32,
    samples: Vec<f32>,
    out: Vec<f32>,
    eq: Vec<f32>,
}

impl Pipe {
    /// Resampled samples → equalizer → little-endian float into `dst`. Returns frames.
    fn finish(&mut self, dst: &mut [u8]) -> usize {
        let n = self.out.len().min(dst.len() / 4);
        let frames = n / self.channels;
        let n = frames * self.channels;
        let mut on = false;
        if let Ok(mut guard) = PROCESSOR.lock() {
            let proc = guard.get_or_insert_with(eq::Processor::default);
            if proc.prepare(self.dst, self.channels) {
                self.eq.resize(n, 0.0);
                proc.process_f32(&self.out[..n], &mut self.eq[..n]);
                on = true;
            }
        }
        let src = if on { &self.eq[..n] } else { &self.out[..n] };
        for (v, b) in src.iter().zip(dst.chunks_exact_mut(4)) {
            b.copy_from_slice(&v.to_le_bytes());
        }
        frames
    }
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_pipeNew<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    src_rate: jint,
    dst_rate: jint,
    channels: jint,
) -> jlong {
    guard(0, || {
        let ch = channels.clamp(1, 8) as usize;
        let p = Box::new(Pipe {
            rs: crate::resample::Resampler::new(src_rate.max(1) as u32, dst_rate.max(1) as u32, ch),
            channels: ch,
            dst: dst_rate.max(1) as u32,
            samples: Vec::new(),
            out: Vec::new(),
            eq: Vec::new(),
        });
        Box::into_raw(p) as jlong
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_pipeFree<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: the handle came from pipeNew and Kotlin frees it exactly once
        drop(unsafe { Box::from_raw(handle as *mut Pipe) });
    }
}

fn pipe<'a>(handle: jlong) -> Option<&'a mut Pipe> {
    // SAFETY: a live handle from pipeNew, used from the playback thread only
    (handle != 0).then(|| unsafe { &mut *(handle as *mut Pipe) })
}

#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_pipeReset<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    handle: jlong,
) {
    guard((), || {
        if let Some(p) = pipe(handle) {
            p.rs.reset();
        }
    })
}

/// Output bytes needed for `samples` more input samples.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_pipeMaxBytes<'l>(
    _env: JNIEnv<'l>,
    _c: JClass<'l>,
    handle: jlong,
    samples: jint,
) -> jint {
    guard(0, || {
        pipe(handle).map_or(0, |p| {
            (p.rs.max_output(samples.max(0) as usize / p.channels + 1) * p.channels * 4) as jint
        })
    })
}

/// Converts `samples` input samples (`encoding` as for eqProcess) into float at the output
/// rate, equalizer applied, written to `output` from 0. Also feeds the spectrum. Returns
/// output frames (-1 on bad arguments).
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_pipeProcess<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    handle: jlong,
    input: JByteBuffer<'l>,
    in_pos: jint,
    samples: jint,
    encoding: jint,
    src_rate: jint,
    output: JByteBuffer<'l>,
    out_cap: jint,
) -> jint {
    guard(-1, || {
        let (Some(p), Some(fmt)) = (pipe(handle), eq::Pcm::from_code(encoding)) else { return -1 };
        if samples < 0 || out_cap < 0 {
            return -1;
        }
        let len = samples as usize * fmt.bytes();
        // SAFETY: ranges checked against the buffers' capacity; distinct buffers
        let (Some(src), Some(dst)) = (unsafe { bytes(&env, &input, in_pos, len) }, unsafe {
            bytes(&env, &output, 0, out_cap as usize)
        }) else {
            return -1;
        };
        spectrum::feed_bytes(src, fmt, p.channels, src_rate.max(0) as u32);
        p.samples.clear();
        p.samples.extend(src.chunks_exact(fmt.bytes()).map(|b| fmt.read(b) as f32));
        let samples = std::mem::take(&mut p.samples);
        let mut out = std::mem::take(&mut p.out);
        p.rs.process_into(&samples, &mut out);
        p.samples = samples;
        p.out = out;
        p.finish(dst) as jint
    })
}

/// The last output frames the filter still holds (end of a song). Returns frames.
#[no_mangle]
pub extern "system" fn Java_com_akira_simpleplayer_core_Core_pipeFlush<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    handle: jlong,
    output: JByteBuffer<'l>,
    out_cap: jint,
) -> jint {
    guard(-1, || {
        let Some(p) = pipe(handle) else { return -1 };
        // SAFETY: range checked against the buffer's capacity
        let Some(dst) = (unsafe { bytes(&env, &output, 0, out_cap.max(0) as usize) }) else { return -1 };
        p.out = p.rs.flush();
        p.finish(dst) as jint
    })
}
