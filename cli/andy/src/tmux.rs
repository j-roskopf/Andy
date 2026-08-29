use anyhow::{bail, Context, Result};
use std::path::PathBuf;
use std::process::{Command, Stdio};

use crate::viewer_chrome;

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
    for dir in std::env::var("PATH").unwrap_or_default().split(':').chain(
        ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"]
            .iter()
            .copied(),
    ) {
        let candidate = PathBuf::from(dir).join("tmux");
        if candidate.is_file() {
            return Some(candidate.display().to_string());
        }
    }
    None
}

/// Socket selector for Andy's tmux server.
///
/// - Absolute `ANDY_TMUX_SOCKET` path → `tmux -S <path>` (SSH-forwarded remote server)
/// - Bare name → `tmux -L <name>` (defaults to `andy`)
pub fn socket_args() -> Vec<String> {
    if let Ok(raw) = std::env::var("ANDY_TMUX_SOCKET") {
        let value = raw.trim();
        if !value.is_empty() {
            if value.starts_with('/') {
                return vec!["-S".into(), value.to_string()];
            }
            return vec!["-L".into(), value.to_string()];
        }
    }
    vec!["-L".into(), "andy".into()]
}

fn tmux_command(args: &[&str]) -> Result<Command> {
    let mut command = Command::new(tmux_binary()?);
    command.args(socket_args());
    command.args(args);
    Ok(command)
}

/// Run a tmux command with null stdio; returns Err when the process fails to spawn
/// or exits non-zero. Socket selector is prepended automatically.
pub fn run_tmux(args: &[&str]) -> Result<()> {
    let status = tmux_command(args)?
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .with_context(|| format!("tmux {}", args.join(" ")))?;
    if !status.success() {
        bail!("tmux {} failed", args.join(" "));
    }
    Ok(())
}

pub fn has_session(task_id: &str) -> bool {
    let name = session_name(task_id);
    // Null stderr: a missing Andy tmux server otherwise floods the TTY with
    // "no server running on /private/tmp/tmux-*/andy" during attach polls.
    tmux_command(&["has-session", "-t", &name])
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
    let output = tmux_command(&["capture-pane", "-p", "-t", &name])
        .ok()
        .and_then(|mut command| command.output().ok())
        .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        .unwrap_or_default();
    output.contains("shell-init: error retrieving current directory")
        || output.contains("uv_cwd")
        || (output.contains("getcwd") && output.contains("cannot access parent directories"))
}

const DETACH_HINT: &str = "Press F12, Alt+d, or Ctrl-b then d to return to the chat list";

pub fn detach_hint() -> &'static str {
    DETACH_HINT
}

fn ensure_detach_keys() {
    // Idempotent; also covers older andyd builds before these were in SERVER_OPTIONS.
    let _ = tmux_command(&[
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

/// Attach to a Terminal-lane session with the same header/status/hotkey framing
/// used by the ACP viewer. Tmux remains the transport; chrome is a session-local
/// status line plus a brief banner before the TTY is handed over.
pub fn attach(task_id: &str, title: &str, status: &str) -> Result<()> {
    let name = session_name(task_id);
    ensure_detach_keys();
    let title = if title.is_empty() { task_id } else { title };
    let status = if status.is_empty() {
        "Attached"
    } else {
        status
    };
    viewer_chrome::print_attach_banner(task_id, title, status);
    // Best-effort: older/broken tmux still gets the banner + detach keys.
    let _ = viewer_chrome::apply_tmux_session_chrome(task_id, title, status);
    let attach_status = tmux_command(&["attach-session", "-t", &name])?
        .status()
        .context("spawn tmux attach")?;
    let _ = viewer_chrome::clear_tmux_session_chrome(task_id);
    if !attach_status.success() {
        bail!("tmux attach failed for session {name}");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn socket_args_default_to_andy_server() {
        // Avoid depending on ambient ANDY_TMUX_SOCKET from the test runner.
        let prev = std::env::var("ANDY_TMUX_SOCKET").ok();
        std::env::remove_var("ANDY_TMUX_SOCKET");
        assert_eq!(socket_args(), vec!["-L".to_string(), "andy".to_string()]);
        match prev {
            Some(v) => std::env::set_var("ANDY_TMUX_SOCKET", v),
            None => std::env::remove_var("ANDY_TMUX_SOCKET"),
        }
    }

    #[test]
    fn socket_args_absolute_path_uses_dash_s() {
        let prev = std::env::var("ANDY_TMUX_SOCKET").ok();
        std::env::set_var("ANDY_TMUX_SOCKET", "/tmp/andy-remote-tmux.sock");
        assert_eq!(
            socket_args(),
            vec!["-S".to_string(), "/tmp/andy-remote-tmux.sock".to_string()]
        );
        match prev {
            Some(v) => std::env::set_var("ANDY_TMUX_SOCKET", v),
            None => std::env::remove_var("ANDY_TMUX_SOCKET"),
        }
    }

    #[test]
    fn socket_args_named_server_uses_dash_l() {
        let prev = std::env::var("ANDY_TMUX_SOCKET").ok();
        std::env::set_var("ANDY_TMUX_SOCKET", "andy-test");
        assert_eq!(
            socket_args(),
            vec!["-L".to_string(), "andy-test".to_string()]
        );
        match prev {
            Some(v) => std::env::set_var("ANDY_TMUX_SOCKET", v),
            None => std::env::remove_var("ANDY_TMUX_SOCKET"),
        }
    }
}
