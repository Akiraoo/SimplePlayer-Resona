//! Play order: the list being played, shuffle, repeat, and the user's queue ("up next").
//! Same rules as the desktop app.
//!
//! The player only ever holds the current song and the one after it. [`Queue::plan_next`]
//! works out that next song in advance so it can be preloaded (no gap between songs);
//! when the player moves on by itself, [`Queue::commit_plan`] brings the state up to date.
//!
//! Songs are track indices into the library.

use serde::{Deserialize, Serialize};

/// What happens when the current song ends by itself.
#[derive(Clone, Debug, PartialEq)]
enum Plan {
    /// repeat one
    Same,
    /// in the queue (a temporary playlist): drop the finished song (`removed`), then play
    /// `pick` (index after the removal)
    Upnext { removed: Option<usize>, pick: usize, track: u32 },
    /// the current list continues at `pos`
    Pos { pos: usize, track: u32 },
}

/// Result of "previous".
#[derive(Debug, PartialEq)]
pub enum Prev {
    Nothing,
    /// Start the current song over.
    Restart,
    Track(u32),
}

#[derive(Default)]
pub struct Queue {
    /// The list being played, in play order.
    pub queue: Vec<u32>,
    /// Play order before shuffling (restored when shuffle is turned off).
    pub unshuffled: Vec<u32>,
    pub pos: usize,
    /// The user's queue: a temporary playlist. A queued song is removed once it has been
    /// played through; skipping keeps it. Other lists never touch it.
    pub upnext: Vec<u32>,
    /// Index in `upnext` of the song playing now, if it came from the queue.
    pub upnext_cur: Option<usize>,
    pub current: Option<u32>,
    plan: Option<Plan>,
    rng: u64,
    /// The play order changed since it was last saved.
    pub dirty: bool,
}

/// Saved between runs (ids, so it survives a resync).
#[derive(Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Session {
    pub queue: Vec<String>,
    pub unshuffled: Vec<String>,
    pub pos: usize,
    pub upnext: Vec<String>,
    pub upnext_cur: Option<usize>,
}

impl Queue {
    pub fn new(seed: u64) -> Queue {
        Queue { rng: seed | 1, ..Queue::default() }
    }

    fn rand(&mut self) -> u64 {
        // xorshift64*
        let mut x = self.rng | 1;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.rng = x;
        x.wrapping_mul(0x2545f4914f6cdd1d)
    }

    /// Puts the current song first and shuffles everything after it.
    fn shuffle_rest(&mut self) {
        if self.queue.is_empty() {
            return;
        }
        let cur = self.queue.remove(self.pos.min(self.queue.len() - 1));
        for i in (1..self.queue.len()).rev() {
            let j = (self.rand() % (i as u64 + 1)) as usize;
            self.queue.swap(i, j);
        }
        self.queue.insert(0, cur);
        self.pos = 0;
    }

    /// A list becomes the play order, starting at `index`; the user's queue is left alone.
    pub fn play_list(&mut self, list: Vec<u32>, index: usize, shuffle: bool) -> Option<u32> {
        if index >= list.len() {
            return None;
        }
        self.unshuffled = list.clone();
        self.queue = list;
        self.pos = index;
        if shuffle {
            self.shuffle_rest();
        }
        self.upnext_cur = None;
        self.plan = None;
        self.dirty = true;
        self.current = self.queue.get(self.pos).copied();
        self.current
    }

    /// Plays that queued song; the queue itself keeps its order.
    pub fn play_upnext(&mut self, qi: usize) -> Option<u32> {
        let t = *self.upnext.get(qi)?;
        self.upnext_cur = Some(qi);
        self.plan = None;
        self.dirty = true;
        self.current = Some(t);
        self.current
    }

    pub fn set_shuffle(&mut self, on: bool) {
        self.plan = None;
        self.dirty = true;
        if on {
            self.unshuffled = self.queue.clone();
            self.shuffle_rest();
        } else if !self.unshuffled.is_empty() {
            let cur = self.queue.get(self.pos).copied();
            self.queue = std::mem::take(&mut self.unshuffled);
            self.pos = cur
                .and_then(|c| self.queue.iter().position(|&t| t == c))
                .unwrap_or(0);
        }
    }

    pub fn add(&mut self, track: u32) {
        self.upnext.push(track);
        self.plan = None;
        self.dirty = true;
    }

    /// Removes a queued song (not the one playing: it stays until it is done).
    pub fn remove(&mut self, qi: usize) -> bool {
        if Some(qi) == self.upnext_cur || qi >= self.upnext.len() {
            return false;
        }
        self.upnext.remove(qi);
        if let Some(k) = self.upnext_cur.as_mut() {
            if *k > qi {
                *k -= 1;
            }
        }
        self.plan = None;
        self.dirty = true;
        true
    }

    /// Empties the queue, except for the song that is playing from it.
    pub fn clear_upnext(&mut self) {
        match self.upnext_cur {
            Some(k) if k < self.upnext.len() => {
                let keep = self.upnext[k];
                self.upnext = vec![keep];
                self.upnext_cur = Some(0);
            }
            _ => {
                self.upnext.clear();
                self.upnext_cur = None;
            }
        }
        self.plan = None;
        self.dirty = true;
    }

    /// The song after the current one when it ends by itself (mirrors `advance(true)`),
    /// remembered until [`commit_plan`]. None = playback stops (or needs a reshuffle).
    pub fn plan_next(&mut self, shuffle: bool, repeat: u8) -> Option<u32> {
        self.plan = self.work_out_plan(shuffle, repeat);
        match self.plan.as_ref()? {
            Plan::Same => self.current,
            Plan::Upnext { track, .. } | Plan::Pos { track, .. } => Some(*track),
        }
    }

    fn work_out_plan(&mut self, shuffle: bool, repeat: u8) -> Option<Plan> {
        self.current?;
        if self.queue.is_empty() && self.upnext_cur.is_none() {
            return None;
        }
        if repeat == 2 {
            return Some(Plan::Same);
        }
        if let Some(k) = self.upnext_cur {
            let removed = (k < self.upnext.len()).then_some(k);
            let n = self.upnext.len() - removed.map_or(0, |_| 1);
            let pick = if n == 0 {
                return None;
            } else if shuffle && n > 1 {
                (self.rand() % n as u64) as usize
            } else if k < n {
                k
            } else if repeat >= 1 {
                0
            } else {
                return None;
            };
            // index before the removal, to read the song now
            let before = match removed {
                Some(r) if pick >= r => pick + 1,
                _ => pick,
            };
            let track = *self.upnext.get(before)?;
            return Some(Plan::Upnext { removed, pick, track });
        }
        let pos = if self.pos + 1 < self.queue.len() {
            self.pos + 1
        } else if repeat >= 1 && !(shuffle && self.queue.len() > 2) {
            0
        } else {
            return None;
        };
        Some(Plan::Pos { pos, track: *self.queue.get(pos)? })
    }

    /// The player moved on to the planned song by itself. Returns the song now playing.
    pub fn commit_plan(&mut self) -> Option<u32> {
        let plan = self.plan.take()?;
        self.dirty = true;
        match plan {
            Plan::Same => {}
            Plan::Upnext { removed, pick, track } => {
                if let Some(r) = removed {
                    if r < self.upnext.len() {
                        self.upnext.remove(r);
                    }
                }
                let i = if self.upnext.get(pick) == Some(&track) {
                    Some(pick)
                } else {
                    self.upnext.iter().position(|&t| t == track)
                };
                self.upnext_cur = i;
                self.current = Some(track);
            }
            Plan::Pos { pos, track } => {
                let i = if self.queue.get(pos) == Some(&track) {
                    Some(pos)
                } else {
                    self.queue.iter().position(|&t| t == track)
                };
                if let Some(i) = i {
                    self.pos = i;
                }
                self.upnext_cur = None;
                self.current = Some(track);
            }
        }
        self.current
    }

    /// Next song. `auto` = the current one ended by itself (repeat-one replays it).
    /// Queued songs come first; after them the current list continues where it was.
    /// None = nothing more to play.
    pub fn advance(&mut self, auto: bool, shuffle: bool, repeat: u8) -> Option<u32> {
        self.plan = None;
        if self.queue.is_empty() && self.upnext_cur.is_none() {
            return None;
        }
        self.dirty = true;
        if auto && repeat == 2 {
            return self.current;
        }
        if let Some(k) = self.upnext_cur {
            let mut next = k + 1;
            if auto && k < self.upnext.len() {
                self.upnext.remove(k);
                next = k;
            }
            self.upnext_cur = None;
            let n = self.upnext.len();
            let pick = if n == 0 {
                None
            } else if shuffle && n > 1 {
                let mut r = (self.rand() % n as u64) as usize;
                if !auto && r == k {
                    r = (r + 1) % n; // a skip should move on
                }
                Some(r)
            } else if next < n {
                Some(next)
            } else if repeat >= 1 {
                Some(0) // repeat all: the songs that were skipped come round again
            } else {
                None
            };
            return match pick {
                Some(i) => self.play_upnext(i),
                None => {
                    // the queue is done: carry on with the list it interrupted
                    self.current = self.queue.get(self.pos).copied();
                    None
                }
            };
        }
        if self.pos + 1 < self.queue.len() {
            self.pos += 1;
        } else if repeat >= 1 {
            // repeat all: start over (a fresh order when shuffling)
            self.pos = 0;
            if shuffle && self.queue.len() > 2 {
                let last = self.queue.last().copied();
                self.shuffle_rest();
                // don't play the song that just ended right away again
                if self.queue.first().copied() == last {
                    self.queue.swap(0, 1);
                }
            }
        } else {
            return None;
        }
        self.current = self.queue.get(self.pos).copied();
        self.current
    }

    /// Previous song; more than 3 s into a song (or at the start of the list) it starts over.
    pub fn prev(&mut self, position_ms: i64) -> Prev {
        if self.queue.is_empty() && self.upnext_cur.is_none() {
            return Prev::Nothing;
        }
        self.plan = None;
        if let Some(k) = self.upnext_cur {
            if position_ms > 3000 || k == 0 {
                return Prev::Restart;
            }
            return match self.play_upnext(k - 1) {
                Some(t) => Prev::Track(t),
                None => Prev::Restart,
            };
        }
        if position_ms > 3000 || self.pos == 0 {
            return Prev::Restart;
        }
        self.pos -= 1;
        self.dirty = true;
        self.current = self.queue.get(self.pos).copied();
        match self.current {
            Some(t) => Prev::Track(t),
            None => Prev::Nothing,
        }
    }

    /// After a resync: `map` gives a song's new index (None = it no longer exists).
    pub fn remap(&mut self, map: impl Fn(u32) -> Option<u32>) {
        let cur = self.current.and_then(&map);
        let relist = |list: &[u32], at: Option<usize>| -> (Vec<u32>, Option<usize>) {
            let mut out = Vec::with_capacity(list.len());
            let mut new_at = None;
            for (i, &t) in list.iter().enumerate() {
                if let Some(n) = map(t) {
                    if Some(i) == at {
                        new_at = Some(out.len());
                    }
                    out.push(n);
                }
            }
            (out, new_at)
        };
        let (queue, pos) = relist(&self.queue, Some(self.pos));
        let (unshuffled, _) = relist(&self.unshuffled, None);
        let (upnext, up_cur) = relist(&self.upnext, self.upnext_cur);
        self.pos = pos
            .or_else(|| cur.and_then(|c| queue.iter().position(|&t| t == c)))
            .unwrap_or(0)
            .min(queue.len().saturating_sub(1));
        self.queue = queue;
        self.unshuffled = unshuffled;
        self.upnext = upnext;
        self.upnext_cur = up_cur;
        self.current = cur;
        self.plan = None;
        self.dirty = true;
    }

    pub fn session(&self, id: impl Fn(u32) -> Option<String>, shuffle: bool) -> Session {
        let ids = |list: &[u32]| list.iter().filter_map(|&t| id(t)).collect::<Vec<_>>();
        Session {
            queue: ids(&self.queue),
            unshuffled: if shuffle { ids(&self.unshuffled) } else { Vec::new() },
            pos: self.pos,
            upnext: ids(&self.upnext),
            upnext_cur: self.upnext_cur,
        }
    }

    /// Puts a saved session back (nothing plays yet). Songs that are gone are dropped.
    pub fn restore(&mut self, sess: &Session, index: impl Fn(&str) -> Option<u32>) {
        let pick = |keys: &[String]| keys.iter().filter_map(|k| index(k)).collect::<Vec<u32>>();
        // The saved position counts duplicates: find the same occurrence again.
        let locate = |keys: &[String], list: &[u32], pos: usize| -> Option<usize> {
            let key = keys.get(pos)?;
            let track = index(key)?;
            let nth = keys[..pos].iter().filter(|k| *k == key).count();
            list.iter()
                .enumerate()
                .filter(|(_, &t)| t == track)
                .map(|(i, _)| i)
                .nth(nth)
                .or_else(|| list.iter().position(|&t| t == track))
        };
        let queue = pick(&sess.queue);
        let unshuffled = pick(&sess.unshuffled);
        let upnext = pick(&sess.upnext);
        let ctx_pos = locate(&sess.queue, &queue, sess.pos);
        let up_pos = sess.upnext_cur.and_then(|i| locate(&sess.upnext, &upnext, i));
        self.unshuffled = if unshuffled.is_empty() { queue.clone() } else { unshuffled };
        self.queue = queue;
        self.pos = ctx_pos.unwrap_or(0);
        self.upnext = upnext;
        self.upnext_cur = up_pos;
        self.plan = None;
        self.current = match up_pos {
            Some(k) => self.upnext.get(k).copied(),
            None => ctx_pos.and_then(|p| self.queue.get(p).copied()),
        };
        self.dirty = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_list() {
        let mut q = Queue::new(1);
        assert_eq!(q.play_list(vec![10, 11, 12], 1, false), Some(11));
        assert_eq!(q.plan_next(false, 0), Some(12));
        assert_eq!(q.commit_plan(), Some(12));
        assert_eq!(q.pos, 2);
        assert_eq!(q.plan_next(false, 0), None); // end of the list
        assert_eq!(q.advance(true, false, 0), None);
        assert_eq!(q.plan_next(false, 1), Some(10)); // repeat all wraps
        assert_eq!(q.plan_next(false, 2), Some(12)); // repeat one
        assert_eq!(q.commit_plan(), Some(12));
        // a manual skip ignores repeat one (and wraps, since repeating is on)
        assert_eq!(q.advance(false, false, 2), Some(10));
        assert_eq!(q.prev(0), Prev::Restart); // first song
        assert_eq!(q.advance(false, false, 0), Some(11));
        assert_eq!(q.prev(5000), Prev::Restart);
        assert_eq!(q.prev(1000), Prev::Track(10));
    }

    #[test]
    fn shuffle_keeps_current_first() {
        let mut q = Queue::new(42);
        let list: Vec<u32> = (0..50).collect();
        assert_eq!(q.play_list(list.clone(), 7, true), Some(7));
        assert_eq!(q.queue[0], 7);
        assert_ne!(q.queue, list);
        let mut sorted = q.queue.clone();
        sorted.sort();
        assert_eq!(sorted, list);
        // turning shuffle off goes back to the original order, at the same song
        q.advance(false, true, 0);
        let cur = q.current.unwrap();
        q.set_shuffle(false);
        assert_eq!(q.queue, list);
        assert_eq!(q.queue[q.pos], cur);
        // end of a shuffled list with repeat all: no plan (reshuffle), advance starts over
        q.set_shuffle(true);
        q.pos = q.queue.len() - 1;
        let last = q.queue[q.pos];
        assert_eq!(q.plan_next(true, 1), None);
        let next = q.advance(true, true, 1).unwrap();
        assert_ne!(next, last);
        assert_eq!(q.pos, 0);
    }

    #[test]
    fn user_queue() {
        let mut q = Queue::new(3);
        q.play_list(vec![1, 2, 3], 0, false);
        q.add(7);
        q.add(8);
        q.add(9);
        // the queue only plays once you start it
        assert_eq!(q.plan_next(false, 0), Some(2));
        assert_eq!(q.play_upnext(0), Some(7));
        // played through: removed, the next queued song follows
        assert_eq!(q.plan_next(false, 0), Some(8));
        assert_eq!(q.commit_plan(), Some(8));
        assert_eq!(q.upnext, vec![8, 9]);
        assert_eq!(q.upnext_cur, Some(0));
        // skipped: stays in the queue
        assert_eq!(q.advance(false, false, 0), Some(9));
        assert_eq!(q.upnext, vec![8, 9]);
        assert_eq!(q.upnext_cur, Some(1));
        assert!(!q.remove(1)); // the playing one stays
        assert_eq!(q.prev(0), Prev::Track(8));
        assert!(q.remove(1));
        // last one ends: queue empty, nothing planned, the list is where it was
        assert_eq!(q.plan_next(false, 0), None);
        assert_eq!(q.advance(true, false, 0), None);
        assert!(q.upnext.is_empty());
        assert_eq!(q.current, Some(1));
        assert_eq!(q.upnext_cur, None);
    }

    #[test]
    fn remap_and_session() {
        let mut q = Queue::new(5);
        q.play_list(vec![0, 1, 2, 3], 2, false);
        q.add(3);
        // song 1 disappears, the others shift down
        q.remap(|t| match t {
            1 => None,
            t if t > 1 => Some(t - 1),
            t => Some(t),
        });
        assert_eq!(q.queue, vec![0, 1, 2]);
        assert_eq!(q.pos, 1);
        assert_eq!(q.current, Some(1));
        assert_eq!(q.upnext, vec![2]);

        let names = ["a", "b", "c"];
        let sess = q.session(|t| names.get(t as usize).map(|s| s.to_string()), false);
        let text = serde_json::to_string(&sess).unwrap();
        let back: Session = serde_json::from_str(&text).unwrap();
        let mut r = Queue::new(9);
        r.restore(&back, |k| names.iter().position(|n| *n == k).map(|i| i as u32));
        assert_eq!(r.queue, vec![0, 1, 2]);
        assert_eq!(r.current, Some(1));
        assert_eq!(r.upnext, vec![2]);
        assert_eq!(r.plan_next(false, 0), Some(2));
    }
}
