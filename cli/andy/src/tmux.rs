use anyhow::{bail, Context, Result};
use std::process::{Command, Stdio};

pub fn session_name(task_id: &str) -> String {
    format!("andy-task-{task_id}")
}

pub fn has_session(task_id: &str) -> bool {
    let name = session_name(task_id);
    // Null stderr: a missing Andy tmux server otherwise floods the TTY with
    // "no server running on /private/tmp/tmux-*/andy" during attach polls.
    Command::new("tmux")
        .args(["-L", "andy", "has-session", "-t", &name])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

/// True when the session exists but its shell failed to start (e.g. deleted cwd).
///
/// Only the visible pane is checked — full scrollback can false-positive when an
/// agent merely discusses getcwd / shell-init errors, which then blocks attach.
pub fn session_looks_broken(task_id: &str) -> bool {
    if !has_session(task_id) {
        return false;
    }
    let name = session_name(task_id);
    let output = Command::new("tmux")
        .args(["-L", "andy", "capture-pane", "-p", "-t", &name])
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        .unwrap_or_default();
    output.contains("shell-init: error retrieving current directory")
        || output.contains("uv_cwd")
        || (output.contains("getcwd") && output.contains("cannot access parent directories"))
}

const DETACH_HINT: &str =
    "Press F12, Alt+d, or Ctrl-b then d to return to the chat list";

pub fn detach_hint() -> &'static str {
    DETACH_HINT
}

fn ensure_detach_keys() {
    // Idempotent; also covers older andyd builds before these were in SERVER_OPTIONS.
    let _ = Command::new("tmux")
        .args([
            "-L",
            "andy",
            "bind-key",
            "-n",
            "F12",
            "detach-client",
            ";",
            "bind-key",
            "-n",
            "C-]",
            "detach-client",
            ";",
            "bind-key",
            "-n",
            "M-d",
            "detach-client",
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

pub fn attach(task_id: &str) -> Result<()> {
    let name = session_name(task_id);
    ensure_detach_keys();
    eprintln!("Attached to {name}. {DETACH_HINT}.");
    let status = Command::new("tmux")
        .args(["-L", "andy", "attach-session", "-t", &name])
        .status()
        .context("spawn tmux attach")?;
    if !status.success() {
        bail!("tmux attach failed for session {name}");
    }
    Ok(())
}
