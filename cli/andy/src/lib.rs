//! Andy CLI library — shared by the `andy` binary and integration tests.

/// Same string as the desktop app (`andy.versionName` / `AndyBuildInfo.versionName`).
/// Injected by `build.rs` (Cargo.toml cannot store calendar versions with leading zeros).
pub const VERSION: &str = env!("ANDY_VERSION_NAME");

pub mod acp_view;
pub mod ansi;
pub mod args;
pub mod attach;
pub mod chats;
pub mod compose;
pub mod daemon;
pub mod device_cli;
pub mod device_map;
pub mod dispatch;
pub mod events;
pub mod file_picker;
pub mod mcp;
pub mod skills;
pub mod slash;
pub mod tmux;
pub mod tool_cmd;
pub mod tui;
pub mod viewer_chrome;
