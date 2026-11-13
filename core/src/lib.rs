//! Simple Player core.
//!
//! Everything that is not user interface or Android plumbing: the library snapshot and
//! its search index, the resync with Simple Player Web Server, the play order (shuffle,
//! repeat, the user's queue), the parametric equalizer and the spectrum analyser.
//! It mirrors the modules of the desktop app, so both behave the same way.
//!
//! The Android app loads this as `libspcore.so`; `jni_api` is the surface Kotlin calls.

pub mod config;
pub mod eq;
pub mod i18n;
pub mod jni_api;
pub mod library;
pub mod queue;
pub mod resample;
pub mod server;
pub mod spectrum;
pub mod state;
