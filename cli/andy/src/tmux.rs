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

pub fn attach(task_id: &str) -> Result<()> {
    let name = session_name(task_id);
    let status = Command::new("tmux")
        .args(["-L", "andy", "attach-session", "-t", &name])
        .status()
        .context("spawn tmux attach")?;
    if !status.success() {
        bail!("tmux attach failed for session {name}");
    }
    Ok(())
}
