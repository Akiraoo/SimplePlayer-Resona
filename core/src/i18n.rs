//! Interface language of the text the core produces (errors, sync stages): Traditional
//! Chinese (the original text) or English. Same `tr!` macro as the desktop app.

use std::sync::atomic::{AtomicBool, Ordering};

static EN: AtomicBool = AtomicBool::new(false);

pub fn english() -> bool {
    EN.load(Ordering::Relaxed)
}

pub fn set_english(on: bool) {
    EN.store(on, Ordering::Relaxed);
}

/// `tr!("中文", "English")` → String; extra arguments are passed to `format!` (use `{}`).
#[macro_export]
macro_rules! tr {
    ($zh:literal, $en:literal $(, $arg:expr)* $(,)?) => {
        if $crate::i18n::english() {
            format!($en $(, $arg)*)
        } else {
            format!($zh $(, $arg)*)
        }
    };
}
