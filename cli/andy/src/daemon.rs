use anyhow::{bail, Context, Result};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};
use tokio::net::UnixStream;

pub fn andy_home() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".andy")
}

pub fn default_socket_path() -> PathBuf {
    andy_home().join("andyd.sock")
}

pub fn pid_path() -> PathBuf {
    andy_home().join("andyd.pid")
}

pub async fn ensure_running(socket: &Path) -> Result<()> {
    if is_socket_live(socket).await {
        return Ok(());
    }
    remove_stale_artifacts(socket);
    if pid_alive() && wait_for_socket(socket, Duration::from_secs(15)).await {
        return Ok(());
    }
    if !try_launch()? {
        bail!(
            "andyd is not running and could not be started automatically.\n\
             Install the daemon with install-andy.sh, run ./gradlew installAndyd, \
             or launch the Andy desktop app."
        );
    }
    if !wait_for_socket(socket, Duration::from_secs(15)).await {
        bail!(
            "andyd did not become ready at {} within 15s — check ~/.andy/logs/andyd.err.log",
            socket.display()
        );
    }
    Ok(())
}

pub async fn is_socket_live(socket: &Path) -> bool {
    if !socket.exists() {
        return false;
    }
    UnixStream::connect(socket).await.is_ok()
}

fn remove_stale_artifacts(socket: &Path) {
    let pid_file = pid_path();
    if pid_file.is_file() {
        if let Ok(text) = fs::read_to_string(&pid_file) {
            if let Ok(pid) = text.trim().parse::<u32>() {
                if process_alive(pid) {
                    return;
                }
            }
        }
        let _ = fs::remove_file(pid_file);
    }
    if socket.exists() && !socket_exists_sync(socket) {
        let _ = fs::remove_file(socket);
    }
}

fn socket_exists_sync(socket: &Path) -> bool {
    std::os::unix::net::UnixStream::connect(socket).is_ok()
}

fn pid_alive() -> bool {
    let pid_file = pid_path();
    let Ok(text) = fs::read_to_string(pid_file) else {
        return false;
    };
    let Ok(pid) = text.trim().parse::<u32>() else {
        return false;
    };
    process_alive(pid)
}

fn process_alive(pid: u32) -> bool {
    Command::new("kill")
        .args(["-0", &pid.to_string()])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

async fn wait_for_socket(socket: &Path, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if is_socket_live(socket).await {
            return true;
        }
        thread::sleep(Duration::from_millis(100));
    }
    is_socket_live(socket).await
}

fn try_launch() -> Result<bool> {
    let command = resolve_launch_command().context("resolve andyd launch command")?;
    let home = andy_home();
    fs::create_dir_all(&home)?;
    let logs = home.join("logs");
    fs::create_dir_all(&logs)?;
    let stdout = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(logs.join("andyd.log"))?;
    let stderr = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(logs.join("andyd.err.log"))?;
    let path = augmented_path();
    let mut cmd = Command::new(&command[0]);
    cmd.args(&command[1..])
        .current_dir(&home)
        .stdin(Stdio::null())
        .stdout(stdout)
        .stderr(stderr)
        .env("PATH", &path);
    if let Some(tmux) = crate::tmux::bundled_tmux_binary() {
        cmd.env("ANDY_TMUX", tmux);
    }
    cmd.spawn()
        .with_context(|| format!("spawn {}", command.join(" ")))?;
    Ok(true)
}

fn resolve_launch_command() -> Result<Vec<String>> {
    if let Ok(path) = std::env::var("ANDY_ANDYD") {
        if !path.trim().is_empty() {
            let file = PathBuf::from(path.trim());
            if file.is_file() {
                return Ok(vec![file.display().to_string()]);
            }
        }
    }

    let candidates = [
        andy_home().join("bin/andyd"),
        sibling_binary("andyd"),
        PathBuf::from("/Applications/Andy.app/Contents/MacOS/andyd"),
    ];
    for candidate in candidates {
        if candidate.is_file() {
            return Ok(vec![candidate.display().to_string()]);
        }
    }

    bail!(
        "no andyd launcher found (checked ~/.andy/bin/andyd and /Applications/Andy.app/Contents/MacOS/andyd)"
    )
}

fn sibling_binary(name: &str) -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|dir| dir.join(name)))
        .unwrap_or_else(|| PathBuf::from(name))
}

fn augmented_path() -> String {
    let home_bin = andy_home().join("bin");
    let existing = std::env::var("PATH").unwrap_or_default();
    let extras = [
        home_bin.display().to_string(),
        "/opt/homebrew/bin".into(),
        "/usr/local/bin".into(),
    ];
    let mut parts: Vec<String> = extras
        .into_iter()
        .chain(
            existing
                .split(':')
                .filter(|part| !part.is_empty())
                .map(str::to_string),
        )
        .collect();
    parts.sort();
    parts.dedup();
    parts.join(":")
}

pub fn ensure_unix_platform() -> Result<()> {
    if cfg!(windows) {
        bail!(
            "the andy CLI is only supported on macOS and Linux.\n\
             It relies on Unix domain sockets, tmux, and the headless andyd daemon."
        );
    }
    Ok(())
}
