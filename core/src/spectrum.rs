//! Audio spectrum for the now-playing visualizer.
//!
//! The playback thread feeds decoded PCM into [`feed_bytes`]; the UI polls [`read`] once
//! per display frame and does its own time-based smoothing. No microphone, no system
//! Visualizer.
//!
//! The player hands audio to the output in bursts, well ahead of what is heard. So every
//! spectrum is queued with the media time of its samples ([`at`]), and [`read`] shows the
//! one at the output's playback position ([`clock`]). Without a clock it shows the newest.
//!
//! 2048-point FFT with 50 % overlap (a new spectrum roughly every 23 ms at 44.1 kHz),
//! reduced to 48 logarithmic bands between 40 Hz and 16 kHz.

use std::f64::consts::PI;
use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub const BARS: usize = 48;
const FFT: usize = 2048;
const HOP: usize = FFT / 2;
const MIN_FREQ: f64 = 40.0;
const MAX_FREQ: f64 = 16_000.0;
/// Bars hit the top too often → raise the ceiling; quiet passages look dead → lower the floor.
const DB_FLOOR: f32 = -70.0;
const DB_CEILING: f32 = -10.0;
const MIN_POWER: f32 = 1e-10;
/// No new frame for this long = playback is paused or stopped.
const STALE: Duration = Duration::from_millis(120);
/// No clock update for this long = the output is paused (it updates every ~10 ms).
const CLOCK_STALE: Duration = Duration::from_millis(250);
/// A queued spectrum further than this from the playback position is not shown.
const MAX_SKEW: f64 = 0.25;
/// Spectra queued ahead of the playback position (~12 s at 44.1 kHz).
const QUEUE: usize = 512;

struct Engine {
    window: Vec<f32>,
    cos: Vec<f32>,
    sin: Vec<f32>,
    rev: Vec<u32>,
    low: [usize; BARS],
    high: [usize; BARS],
    norm_db: f32,
    samples: Vec<f32>,
    count: usize,
    re: Vec<f32>,
    im: Vec<f32>,
    rate: u32,
    /// media time (s) of samples[0]; NaN until [`at`] is called
    t0: f64,
}

struct Frame {
    /// media time (s) of the window's centre, NaN if unknown
    t: f64,
    bars: [f32; BARS],
}

struct Published {
    frames: VecDeque<Frame>,
    at: Option<Instant>,
    /// output playback position (s) and when it was reported
    clock: Option<(f64, Instant)>,
}

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);
static PUBLISHED: Mutex<Published> =
    Mutex::new(Published { frames: VecDeque::new(), at: None, clock: None });

impl Engine {
    fn new() -> Engine {
        let bits = FFT.trailing_zeros();
        let mut e = Engine {
            window: (0..FFT)
                .map(|i| (0.5 - 0.5 * (2.0 * PI * i as f64 / (FFT - 1) as f64).cos()) as f32)
                .collect(),
            cos: (0..FFT / 2).map(|i| (-2.0 * PI * i as f64 / FFT as f64).cos() as f32).collect(),
            sin: (0..FFT / 2).map(|i| (-2.0 * PI * i as f64 / FFT as f64).sin() as f32).collect(),
            rev: (0..FFT as u32).map(|i| i.reverse_bits() >> (32 - bits)).collect(),
            low: [0; BARS],
            high: [0; BARS],
            norm_db: (20.0 * (FFT as f64 / 4.0).log10()) as f32,
            samples: vec![0.0; FFT],
            count: 0,
            re: vec![0.0; FFT],
            im: vec![0.0; FFT],
            rate: 44_100,
            t0: f64::NAN,
        };
        e.rebuild_bands();
        e
    }

    fn rebuild_bands(&mut self) {
        let rate = self.rate as f64;
        let max = (rate / 2.0).min(MAX_FREQ);
        let ratio = max / MIN_FREQ;
        let nyquist = FFT / 2 - 1;
        for bar in 0..BARS {
            let lo = MIN_FREQ * ratio.powf(bar as f64 / BARS as f64);
            let hi = MIN_FREQ * ratio.powf((bar + 1) as f64 / BARS as f64);
            let low = ((lo * FFT as f64 / rate) as usize).max(1).min(nyquist - 1);
            let high = ((hi * FFT as f64 / rate) as usize).max(low + 1).min(nyquist);
            self.low[bar] = low;
            self.high[bar] = high;
        }
    }

    fn push(&mut self, mono: f32) {
        self.samples[self.count] = mono;
        self.count += 1;
        if self.count == FFT {
            self.analyse();
            // keep the newest half for the next (overlapping) frame
            self.samples.copy_within(HOP.., 0);
            self.count = FFT - HOP;
            self.t0 += HOP as f64 / self.rate as f64;
        }
    }

    fn analyse(&mut self) {
        // windowing + bit-reversal permutation in one pass
        for i in 0..FFT {
            let j = self.rev[i] as usize;
            self.re[j] = self.samples[i] * self.window[i];
            self.im[j] = 0.0;
        }
        self.fft();
        let mut bars = [0f32; BARS];
        for (bar, out) in bars.iter_mut().enumerate() {
            let (lo, hi) = (self.low[bar], self.high[bar]);
            let mut power = 0f32;
            for bin in lo..=hi {
                power += self.re[bin] * self.re[bin] + self.im[bin] * self.im[bin];
            }
            power /= (hi - lo + 1) as f32;
            *out = if power <= MIN_POWER {
                0.0
            } else {
                let db = 10.0 * power.log10() - self.norm_db;
                ((db - DB_FLOOR) / (DB_CEILING - DB_FLOOR)).clamp(0.0, 1.0)
            };
        }
        let t = self.t0 + (FFT / 2) as f64 / self.rate as f64;
        if let Ok(mut p) = PUBLISHED.lock() {
            if p.frames.len() >= QUEUE {
                p.frames.pop_front();
            }
            p.frames.push_back(Frame { t, bars });
            p.at = Some(Instant::now());
        }
    }

    /// In-place radix-2 FFT on already bit-reversed data, using the precomputed twiddles.
    fn fft(&mut self) {
        let (re, im) = (&mut self.re, &mut self.im);
        let mut length = 2;
        while length <= FFT {
            let half = length / 2;
            let step = FFT / length;
            let mut i = 0;
            while i < FFT {
                let mut t = 0;
                for k in 0..half {
                    let (wr, wi) = (self.cos[t], self.sin[t]);
                    let a = i + k;
                    let b = a + half;
                    let xr = re[b] * wr - im[b] * wi;
                    let xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    t += step;
                }
                i += length;
            }
            length *= 2;
        }
    }
}

fn with_engine(rate: u32, f: impl FnOnce(&mut Engine)) {
    // only the playback thread and stop() take this lock; never wait on the audio path
    let Ok(mut guard) = ENGINE.try_lock() else { return };
    let e = guard.get_or_insert_with(Engine::new);
    if rate > 0 && rate != e.rate {
        e.rate = rate;
        e.rebuild_bands();
    }
    f(e);
}

/// Interleaved 16-bit PCM from the playback thread.
pub fn feed_i16(data: &[i16], channels: usize, rate: u32) {
    if channels == 0 {
        return;
    }
    let inv = 1.0 / (32768.0 * channels as f32);
    with_engine(rate, |e| {
        for frame in data.chunks_exact(channels) {
            let sum: i32 = frame.iter().map(|&s| s as i32).sum();
            e.push(sum as f32 * inv);
        }
    });
}

/// Interleaved float PCM from the playback thread.
pub fn feed_f32(data: &[f32], channels: usize, rate: u32) {
    if channels == 0 {
        return;
    }
    let inv = 1.0 / channels as f32;
    with_engine(rate, |e| {
        for frame in data.chunks_exact(channels) {
            e.push(frame.iter().sum::<f32>() * inv);
        }
    });
}

/// Interleaved little-endian PCM straight from the player's buffer.
pub fn feed_bytes(data: &[u8], fmt: crate::eq::Pcm, channels: usize, rate: u32) {
    if channels == 0 {
        return;
    }
    let size = fmt.bytes();
    let inv = 1.0 / channels as f64;
    with_engine(rate, |e| {
        for frame in data.chunks_exact(size * channels) {
            let sum: f64 = frame.chunks_exact(size).map(|b| fmt.read(b)).sum();
            e.push((sum * inv) as f32);
        }
    });
}

/// The samples fed next start at media time `secs` (the buffer's presentation time).
pub fn at(secs: f64) {
    if !secs.is_finite() {
        return;
    }
    if let Ok(mut guard) = ENGINE.try_lock() {
        let e = guard.get_or_insert_with(Engine::new);
        // the samples still held from earlier buffers came right before
        e.t0 = secs - e.count as f64 / e.rate as f64;
    }
}

/// The output's playback position in media time (s), reported while it plays.
pub fn clock(secs: f64) {
    if !secs.is_finite() {
        return;
    }
    if let Ok(mut p) = PUBLISHED.lock() {
        p.clock = Some((secs, Instant::now()));
    }
}

/// The spectrum of what is playing now (0..1 per bar); zeros once playback has paused or
/// stopped, so the bars can fall to rest.
pub fn read(dest: &mut [f32]) {
    let n = dest.len().min(BARS);
    let Ok(mut p) = PUBLISHED.lock() else {
        dest[..n].fill(0.0);
        return;
    };
    let timed = p.frames.back().is_some_and(|f| f.t.is_finite());
    match p.clock {
        Some((pos, when)) if timed => {
            let since = when.elapsed();
            if since > CLOCK_STALE {
                dest[..n].fill(0.0);
                return;
            }
            let now = pos + since.as_secs_f64();
            // drop what has been heard: keep the newest spectrum at or before `now`
            while p.frames.len() > 1 && p.frames[1].t <= now {
                p.frames.pop_front();
            }
            match p.frames.front() {
                Some(f) if (now - f.t).abs() <= MAX_SKEW => dest[..n].copy_from_slice(&f.bars[..n]),
                _ => dest[..n].fill(0.0),
            }
        }
        _ => match p.frames.back() {
            Some(f) if p.at.is_some_and(|t| t.elapsed() <= STALE) => {
                dest[..n].copy_from_slice(&f.bars[..n])
            }
            _ => dest[..n].fill(0.0),
        },
    }
}

/// Seek, new format or stop: forget the samples collected so far.
pub fn stop() {
    if let Ok(mut guard) = ENGINE.lock() {
        if let Some(e) = guard.as_mut() {
            e.count = 0;
            e.t0 = f64::NAN;
        }
    }
    if let Ok(mut p) = PUBLISHED.lock() {
        p.frames.clear();
        p.at = None;
        p.clock = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sine_lights_the_right_bar() {
        stop();
        let rate = 48_000u32;
        // 1 kHz stereo sine at -6 dBFS
        let data: Vec<f32> = (0..FFT * 3)
            .flat_map(|i| {
                let x = (2.0 * PI * 1000.0 * i as f64 / rate as f64).sin() as f32 * 0.5;
                [x, x]
            })
            .collect();
        feed_f32(&data, 2, rate);
        let mut bars = [0f32; BARS];
        read(&mut bars);
        let (peak, value) = bars
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.total_cmp(b.1))
            .map(|(i, v)| (i, *v))
            .unwrap();
        // bar whose band contains 1 kHz: 40 * 400^(bar/48) = 1000 → bar ≈ 25.8
        assert!((25..=26).contains(&peak), "peak at {peak}");
        assert!(value > 0.8, "{value}");
        assert!(bars[2] < 0.3 && bars[45] < 0.3);
        // the 16-bit path gives the same picture
        stop();
        let d16: Vec<i16> = data.iter().map(|x| (x * 32767.0) as i16).collect();
        feed_i16(&d16, 2, rate);
        let mut bars16 = [0f32; BARS];
        read(&mut bars16);
        assert!((bars16[peak] - value).abs() < 0.02);
        // and so does the byte path
        stop();
        let bytes: Vec<u8> = d16.iter().flat_map(|x| x.to_le_bytes()).collect();
        feed_bytes(&bytes, crate::eq::Pcm::I16, 2, rate);
        let mut bars_b = [0f32; BARS];
        read(&mut bars_b);
        assert_eq!(bars_b, bars16);
        stop();
        read(&mut bars);
        assert!(bars.iter().all(|v| *v == 0.0));

        // A burst: 1 s of silence, then 1 s of the sine, fed at once (as the player does).
        // The clock decides what shows: silence at 0.5 s, the sine at 1.5 s, nothing once
        // the clock stops (paused).
        stop();
        let tone: Vec<f32> = (0..rate as usize)
            .flat_map(|i| {
                let x = (2.0 * PI * 1000.0 * i as f64 / rate as f64).sin() as f32 * 0.5;
                [x, x]
            })
            .collect();
        at(10.0);
        feed_f32(&vec![0.0; rate as usize * 2], 2, rate);
        at(11.0);
        feed_f32(&tone, 2, rate);
        clock(10.5);
        read(&mut bars);
        assert!(bars.iter().all(|v| *v == 0.0), "silence while the silence plays");
        clock(11.5);
        read(&mut bars);
        assert!(bars[peak] > 0.8, "the tone once it plays: {}", bars[peak]);
        if let Ok(mut p) = PUBLISHED.lock() {
            p.clock = Some((11.5, Instant::now() - CLOCK_STALE * 2));
        }
        read(&mut bars);
        assert!(bars.iter().all(|v| *v == 0.0), "rest when the clock stops");
        stop();
    }
}
