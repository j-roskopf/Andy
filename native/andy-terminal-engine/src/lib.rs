//! Phase-0 spike: wrap `alacritty_terminal` for JVM consumption.
//!
//! This crate proves the hard parts of a BossTerm replacement:
//! feed raw PTY bytes, read grid/cell/attribute/cursor state, resize,
//! alternate screen, and DEC 2026 synchronized updates.
//!
//! The production Compose/BossTerm path is intentionally untouched.

uniffi::setup_scaffolding!();

mod color;
mod engine;
mod ffi_jni;
mod ffi_uniffi;

pub use color::ColorPalette;
pub use engine::{
    CellAttrFlags, CellSnapshot, CursorSnapshot, EngineSize, GridSnapshot, TerminalEngine,
};
pub use ffi_uniffi::uniffi_round_trip_add;
