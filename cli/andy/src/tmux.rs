use anyhow::{bail, Context, Result};
use std::path::PathBuf;
use std::process::{Command, Stdio};

pub fn session_name(task_id: &str) -> String {
    format!("andy-task-{task_id}")
}

pub fn bundled_tmux_binary() -> Option<String> {
    let bundled = dirs::home_dir()?.join(".andy/bin/tmux");
    bundled.is_file().then(|| bundled.display().to_string())
}

fn tmux_binary() -> Result<String> {
    if let Ok(path) = std::env::var("ANDY_TMUX") {
        if !path.trim().is_empty() {
            return Ok(path);
        }
    }
    if let Some(path) = bundled_tmux_binary() {
        return Ok(path);
    }
    which_tmux().ok_or_else(|| {
        anyhow::anyhow!(
            "tmux is required for Andy agent sessions. Re-run install-andy.sh or set ANDY_TMUX."
        )
    })
}

fn which_tmux() -> Option<String> {
    for dir in std::env::var("PATH")
        .unwrap_or_default()
        .split(':')
        .chain(["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"].iter().copied())
    {
        let candidate = PathBuf::from(dir).join("tmux");
        if candidate.is_file() {
            return Some(candidate.display().to_string());
        }
    }
    None
}

fn tmux_command(args: &[&str]) -> Result<Command> {
    let mut command = Command::new(tmux_binary()?);
    command.args(args);
    Ok(command)
}

pub fn has_session(task_id: &str) -> bool {
    let name = session_name(task_id);
    // Null stderr: a missing Andy tmux server otherwise floods the TTY with
    // "no server running on /private/tmp/tmux-*/andy" during attach polls.
    tmux_command(&["-L", "andy", "has-session", "-t", &name])
        .ok()
        .and_then(|mut command| {
            command
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .ok()
        })
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
    let output = tmux_command(&["-L", "andy", "capture-pane", "-p", "-t", &name])
        .ok()
        .and_then(|mut command| command.output().ok())
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
    let _ = tmux_command(&[
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
    .ok()
    .and_then(|mut command| {
        command
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .ok()
    });
}

pub fn attach(task_id: &str) -> Result<()> {
    let name = session_name(task_id);
    ensure_detach_keys();
    eprintln!("Attached to {name}. {DETACH_HINT}.");
    let status = tmux_command(&["-L", "andy", "attach-session", "-t", &name])?
        .status()
        .context("spawn tmux attach")?;
    if !status.success() {
        bail!("tmux attach failed for session {name}");
    }
    Ok(())
}
