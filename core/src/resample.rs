//! Sample-rate conversion for interleaved f32 audio (any channel count): a windowed-sinc (Kaiser) polyphase filter,
//! the same family of converter good players use. Passband to about 20 kHz for 44.1/48 kHz
//! audio with ~90 dB image/alias rejection; when downsampling (e.g. 96 → 48 kHz) the cutoff
//! follows the lower rate so nothing folds back. Exact rational stepping: no drift.

pub struct Resampler {
    passthrough: bool,
    channels: usize,
    /// source frames per output frame = step_int + step_num / den
    step_int: usize,
    step_num: u64,
    den: u64,
    /// half the filter length in source frames (taps = 2 * half)
    half: usize,
    /// (PHASES + 1) rows of 2 * half coefficients
    table: Vec<f32>,
    /// interleaved history; frame `base` is the current output position
    buf: Vec<f32>,
    base: usize,
    frac: u64,
}

const PHASES: usize = 256;
const ZERO_CROSSINGS: f64 = 48.0;
const BETA: f64 = 9.0;
const CUTOFF: f64 = 0.95;

fn gcd(a: u64, b: u64) -> u64 {
    if b == 0 {
        a
    } else {
        gcd(b, a % b)
    }
}

/// Modified Bessel function of the first kind, order 0 (for the Kaiser window).
fn bessel_i0(x: f64) -> f64 {
    let mut sum = 1.0;
    let mut term = 1.0;
    let q = x * x / 4.0;
    for k in 1..64 {
        term *= q / (k as f64 * k as f64);
        sum += term;
        if term < sum * 1e-17 {
            break;
        }
    }
    sum
}

impl Resampler {
    pub fn new(src: u32, dst: u32, channels: usize) -> Resampler {
        let ch = channels.max(1);
        let (src, dst) = (src.max(1) as u64, dst.max(1) as u64);
        let g = gcd(src, dst);
        let (s, d) = (src / g, dst / g);
        let passthrough = s == d;
        // normalised cutoff (1.0 = source Nyquist), lowered when downsampling
        let r = CUTOFF * (d as f64 / s as f64).min(1.0);
        let half = if passthrough {
            0
        } else {
            (ZERO_CROSSINGS / r).ceil() as usize
        };
        let taps = 2 * half;
        let mut table = vec![0f32; (PHASES + 1) * taps];
        if !passthrough {
            let i0b = bessel_i0(BETA);
            for p in 0..=PHASES {
                let f = p as f64 / PHASES as f64;
                let row = &mut table[p * taps..(p + 1) * taps];
                let mut sum = 0.0;
                let mut vals = vec![0f64; taps];
                for (j, v) in vals.iter_mut().enumerate() {
                    // tap j sits at source frame base - half + 1 + j; distance from the output time
                    let t = (j as f64 - half as f64 + 1.0) - f;
                    let x = t / half as f64;
                    if x.abs() >= 1.0 {
                        continue;
                    }
                    let a = std::f64::consts::PI * r * t;
                    let sinc = if a.abs() < 1e-12 { 1.0 } else { a.sin() / a };
                    let w = bessel_i0(BETA * (1.0 - x * x).sqrt()) / i0b;
                    *v = r * sinc * w;
                    sum += *v;
                }
                // unity gain at DC for every phase
                for (o, v) in row.iter_mut().zip(vals) {
                    *o = (v / sum) as f32;
                }
            }
        }
        let mut buf = Vec::new();
        // history before the first sample is silence
        buf.resize(half.saturating_sub(1) * ch, 0.0);
        Resampler {
            passthrough,
            channels: ch,
            step_int: (s / d) as usize,
            step_num: s % d,
            den: d,
            half,
            table,
            buf,
            base: half.saturating_sub(1),
            frac: 0,
        }
    }

    pub fn reset(&mut self) {
        self.buf.clear();
        self.buf.resize(self.half.saturating_sub(1) * self.channels, 0.0);
        self.base = self.half.saturating_sub(1);
        self.frac = 0;
    }

    /// The last few output frames still held back by the filter (call at the end of a song,
    /// so the next one follows without losing that sliver).
    pub fn flush(&mut self) -> Vec<f32> {
        if self.passthrough {
            return Vec::new();
        }
        let zeros = vec![0f32; self.half * self.channels];
        self.process(&zeros)
    }

    /// Output frames that `frames` more input frames can produce at most (for sizing buffers).
    pub fn max_output(&self, frames: usize) -> usize {
        if self.passthrough {
            return frames;
        }
        let have = self.buf.len() / self.channels + frames;
        let step = self.step_int as f64 + self.step_num as f64 / self.den as f64;
        (have as f64 / step.max(1e-9)).ceil() as usize + 2
    }

    /// Converts a block of interleaved frames. Output lags input by `half` frames (< 1 ms);
    /// state carries over between blocks.
    pub fn process(&mut self, input: &[f32]) -> Vec<f32> {
        let mut out = Vec::new();
        self.process_into(input, &mut out);
        out
    }

    pub fn process_into(&mut self, input: &[f32], out: &mut Vec<f32>) {
        out.clear();
        if self.passthrough {
            out.extend_from_slice(input);
            return;
        }
        let ch = self.channels;
        self.buf.extend_from_slice(input);
        let frames = self.buf.len() / ch;
        let half = self.half;
        let taps = 2 * half;
        let mut acc = vec![0f32; ch];
        // need frames base - half + 1 ..= base + half
        while self.base + half < frames {
            let fp = self.frac as f64 / self.den as f64 * PHASES as f64;
            let p = (fp as usize).min(PHASES - 1);
            let w = (fp - p as f64) as f32;
            let r0 = &self.table[p * taps..(p + 1) * taps];
            let r1 = &self.table[(p + 1) * taps..(p + 2) * taps];
            let start = (self.base + 1 - half) * ch;
            let src = &self.buf[start..start + taps * ch];
            acc.iter_mut().for_each(|a| *a = 0.0);
            for j in 0..taps {
                let c = r0[j] + (r1[j] - r0[j]) * w;
                let frame = &src[j * ch..(j + 1) * ch];
                for (a, s) in acc.iter_mut().zip(frame) {
                    *a += s * c;
                }
            }
            out.extend_from_slice(&acc);
            self.base += self.step_int;
            self.frac += self.step_num;
            if self.frac >= self.den {
                self.frac -= self.den;
                self.base += 1;
            }
        }
        // drop history that no future output needs
        let keep_from = (self.base + 1).saturating_sub(half).min(frames);
        if keep_from > 0 {
            self.buf.drain(..keep_from * ch);
            self.base -= keep_from;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    fn sine(rate: u32, hz: f64, frames: usize, ch: usize) -> Vec<f32> {
        (0..frames)
            .flat_map(|i| {
                let v = (2.0 * PI * hz * i as f64 / rate as f64).sin() as f32 * 0.5;
                std::iter::repeat(v).take(ch)
            })
            .collect()
    }

    #[test]
    fn rates_and_lengths() {
        for (src, dst) in [(44_100, 48_000), (96_000, 48_000), (44_100, 96_000), (48_000, 44_100)] {
            let mut r = Resampler::new(src, dst, 2);
            let input = sine(src, 1000.0, src as usize, 2);
            let mut total = 0;
            // in uneven blocks, like a decoder hands them over
            for block in input.chunks(2 * 1153) {
                total += r.process(block).len() / 2;
            }
            total += r.flush().len() / 2;
            let expect = dst as usize;
            assert!((total as i64 - expect as i64).abs() <= 2, "{src}->{dst}: {total}");
        }
    }

    #[test]
    fn keeps_a_tone_clean() {
        // 1 kHz at 44.1k -> 48k: same level out, nothing much else
        let mut r = Resampler::new(44_100, 48_000, 1);
        let out = r.process(&sine(44_100, 1000.0, 44_100, 1));
        let body = &out[4800..out.len() - 4800];
        let peak = body.iter().fold(0f32, |m, v| m.max(v.abs()));
        assert!((peak - 0.5).abs() < 0.005, "{peak}");
        // compare with an ideal 48k sine at the best matching phase
        let lag = 47; // filter delay in output frames, roughly
        let mut best = f64::MAX;
        for d in lag - 5..lag + 5 {
            let err: f64 = body
                .iter()
                .enumerate()
                .map(|(i, v)| {
                    let t = (i + 4800) as f64 - d as f64;
                    let ideal = (2.0 * PI * 1000.0 * t / 48_000.0).sin() * 0.5;
                    (*v as f64 - ideal).powi(2)
                })
                .sum::<f64>()
                / body.len() as f64;
            best = best.min(err);
        }
        // error power well below the signal (about -60 dB or better)
        assert!(best < 0.125 * 1e-6, "{best}");
    }

    #[test]
    fn same_rate_passes_through() {
        let mut r = Resampler::new(48_000, 48_000, 2);
        let x = sine(48_000, 440.0, 1000, 2);
        assert_eq!(r.process(&x), x);
        assert!(r.flush().is_empty());
    }
}
