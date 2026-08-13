//! Phase-0 spike: wrap `alacritty_terminal` for JVM consumption.
//!
//! This crate proves the hard parts of a BossTerm replacement:
//! feed raw PTY bytes, read grid/cell/attribute/cursor state, resize,
//! alternate screen, and DEC 2026 synchronized updates.
//!
//! The production Compose/BossTerm path is intentionally untouched.

mod engine;

pub use engine::{
    CellAttrFlags, CellSnapshot, CursorSnapshot, EngineSize, GridSnapshot, TerminalEngine,
};
