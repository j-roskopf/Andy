//! SSH tunnel to a remote `andyd` socket — CLI counterpart of the GUI Host switcher.
//!
//! `andy remote user@host` opens a ControlMaster forward of the remote andyd Unix
//! socket, then spawns a subshell with `ANDY_SOCKET` / `ANDY_REMOTE` set so subsequent
//! `andy …` commands talk to that machine until the shell exits.
//!
//! OpenSSH does not expand `~` in `-L` remote socket paths, so we resolve
//! `$HOME/.andy/andyd.sock` to an absolute path over SSH before forwarding (same as
//! the GUI Host switcher).

use anyhow::{bail, Context, Result};
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

/// Live SSH ControlMaster + local Unix socket forward.
pub struct RemoteTunnel {
    target: String,
    local_socket: PathBuf,
    control_path: PathBuf,
    /// Parent dir `/tmp/andy-r{pid}` — removed on drop if empty.
    work_dir: PathBuf,
}

impl RemoteTunnel {
    pub fn local_socket(&self) -> &Path {
        &self.local_socket
    }

    pub fn target(&self) -> &str {
        &self.target
    }
}

impl Drop for RemoteTunnel {
    fn drop(&mut self) {
        exit_master(&self.control_path);
        let _ = std::fs::remove_file(&self.local_socket);
        let _ = std::fs::remove_file(&self.control_path);
        let _ = std::fs::remove_dir(&self.work_dir);
    }
}

/// Open an SSH tunnel to `target`'s andyd socket. Caller must keep the returned
/// value alive for the duration of use; Drop tears down the ControlMaster.
pub fn open_tunnel(target: &str) -> Result<RemoteTunnel> {
    let target = target.trim();
    if target.is_empty() {
        bail!("remote target must be a non-empty user@host or ssh Host alias");
    }

    let pid = std::process::id();
    let key = target_key(target);
    let work_dir = PathBuf::from(format!("/tmp/andy-r{pid}"));
    std::fs::create_dir_all(&work_dir)
        .with_context(|| format!("create {}", work_dir.display()))?;

    let local_socket = work_dir.join(format!("{key}.a"));
    let control_path = work_dir.join(format!("{key}.c"));
    let _ = std::fs::remove_file(&local_socket);
    let _ = std::fs::remove_file(&control_path);

    if let Err(err) = start_master(target, &control_path) {
        let _ = std::fs::remove_dir(&work_dir);
        return Err(err);
    }

    let remote_sock = match resolve_remote_andyd(target, &control_path) {
        Ok(path) => path,
        Err(err) => {
            exit_master(&control_path);
            let _ = std::fs::remove_file(&control_path);
            let _ = std::fs::remove_dir(&work_dir);
            return Err(err);
        }
    };

    if let Err(err) = add_unix_forward(&control_path, &local_socket, &remote_sock) {
        exit_master(&control_path);
        let _ = std::fs::remove_file(&control_path);
        let _ = std::fs::remove_dir(&work_dir);
        return Err(err).with_context(|| {
            format!("forward {remote_sock} from {target}")
        });
    }

    let tunnel = RemoteTunnel {
        target: target.to_string(),
        local_socket,
        control_path,
        work_dir,
    };

    wait_for_socket_file(&tunnel.local_socket, Duration::from_secs(5)).with_context(|| {
        format!(
            "ssh tunnel to {target} did not create local socket at {}",
            tunnel.local_socket.display()
        )
    })?;

    probe_mcp(tunnel.local_socket(), Duration::from_secs(10)).with_context(|| {
        format!(
            "remote andyd at {remote_sock} did not speak MCP — is andyd running on {target}?"
        )
    })?;

    Ok(tunnel)
}

/// Connect to `target`, then spawn an interactive shell with `ANDY_SOCKET` pointing
/// at the tunnel. Exiting the shell disconnects.
pub fn run_session(target: &str) -> Result<()> {
    let tunnel = open_tunnel(target)?;

    let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".to_string());
    eprintln!(
        "Connected to {} (andyd via SSH).\n\
         andy commands in this shell talk to the remote daemon.\n\
         Type exit (or Ctrl-D) to disconnect.\n\
         ANDY_SOCKET={}",
        tunnel.target(),
        tunnel.local_socket().display()
    );

    let status = Command::new(&shell)
        .env("ANDY_SOCKET", tunnel.local_socket())
        .env("ANDY_REMOTE", tunnel.target())
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .status()
        .with_context(|| format!("spawn shell {shell}"))?;

    let code = status.code();
    // Drop before process::exit so ControlMaster is torn down.
    drop(tunnel);

    match code {
        Some(0) => Ok(()),
        Some(code) => std::process::exit(code),
        None => bail!("shell exited with {status}"),
    }
}

fn start_master(target: &str, control_path: &Path) -> Result<()> {
    let status = Command::new("ssh")
        .args([
            "-f",
            "-N",
            "-o",
            "ExitOnForwardFailure=yes",
            "-o",
            "StrictHostKeyChecking=yes",
            "-o",
            "ForwardAgent=no",
            "-o",
            "ConnectTimeout=20",
            "-o",
            "ServerAliveInterval=15",
            "-o",
            "ServerAliveCountMax=3",
            "-o",
            &format!("ControlPath={}", control_path.display()),
            "-o",
            "ControlMaster=yes",
            "-o",
            "ControlPersist=yes",
            target,
        ])
        .status()
        .with_context(|| format!("ssh master to {target}"))?;
    if !status.success() {
        bail!("ssh master to {target} failed with status {status}");
    }
    // ControlPath appears once the master is up.
    wait_for_socket_file(control_path, Duration::from_secs(5))
        .with_context(|| format!("ssh master to {target} did not create ControlPath"))?;
    Ok(())
}

fn resolve_remote_andyd(target: &str, control_path: &Path) -> Result<String> {
    // Same probe as DesktopRemoteSessionService.resolveRemotePaths — expand $HOME and
    // require a live socket before forwarding (OpenSSH will not expand ~ in -L paths).
    let remote_command = concat!(
        "set -e; ",
        "ANDY_SOCK=\"$HOME/.andy/andyd.sock\"; ",
        "if [ ! -S \"$ANDY_SOCK\" ]; then echo \"andyd_missing:$ANDY_SOCK\" >&2; exit 2; fi; ",
        "printf '%s\\n' \"$ANDY_SOCK\"",
    );
    let output = mux_ssh(control_path, target, &[remote_command])
        .with_context(|| format!("resolve andyd socket on {target}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);
    if output.status.code() == Some(2) || stderr.contains("andyd_missing") {
        bail!(
            "Remote andyd is not running (missing ~/.andy/andyd.sock). \
             Start standalone andyd (launchd/systemd) on {target}, then retry."
        );
    }
    if !output.status.success() {
        let detail = stderr.trim();
        if detail.is_empty() {
            bail!(
                "could not resolve remote andyd socket on {target} (exit {})",
                output.status
            );
        }
        bail!("could not resolve remote andyd socket on {target}: {detail}");
    }
    let path = stdout
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .unwrap_or("")
        .to_string();
    if path.is_empty() || !path.starts_with('/') {
        bail!("unexpected remote andyd path from {target}: {stdout:?}");
    }
    Ok(path)
}

fn add_unix_forward(control_path: &Path, local: &Path, remote_abs: &str) -> Result<()> {
    let forward = format!("{}:{}", local.display(), remote_abs);
    let status = Command::new("ssh")
        .args([
            "-O",
            "forward",
            "-L",
            &forward,
            "-o",
            &format!("ControlPath={}", control_path.display()),
            "unused",
        ])
        .status()
        .context("ssh -O forward")?;
    if !status.success() {
        bail!("ssh -O forward failed with status {status}");
    }
    Ok(())
}

fn mux_ssh(control_path: &Path, target: &str, remote_args: &[&str]) -> Result<std::process::Output> {
    let mut cmd = Command::new("ssh");
    cmd.args([
        "-o",
        &format!("ControlPath={}", control_path.display()),
        "-o",
        "ControlMaster=no",
        "-o",
        "BatchMode=yes",
        target,
    ]);
    cmd.args(remote_args);
    cmd.output().context("ssh mux exec")
}

fn target_key(target: &str) -> String {
    let mut hasher = DefaultHasher::new();
    target.trim().hash(&mut hasher);
    format!("{:08x}", hasher.finish() as u32)
}

fn exit_master(control_path: &Path) {
    if !control_path.exists() {
        return;
    }
    let _ = Command::new("ssh")
        .args([
            "-O",
            "exit",
            "-o",
            &format!("ControlPath={}", control_path.display()),
            "unused",
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

fn wait_for_socket_file(path: &Path, timeout: Duration) -> Result<()> {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if path.exists() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(50));
    }
    bail!("timed out waiting for {}", path.display())
}

/// Confirm the forward reaches a live andyd (local accept alone is not enough —
/// SSH accepts even when the remote Unix socket path is wrong, then resets).
fn probe_mcp(path: &Path, timeout: Duration) -> Result<()> {
    let deadline = Instant::now() + timeout;
    let mut last_err = None;
    while Instant::now() < deadline {
        match try_initialize(path) {
            Ok(()) => return Ok(()),
            Err(err) => last_err = Some(err),
        }
        thread::sleep(Duration::from_millis(150));
    }
    match last_err {
        Some(err) => Err(err),
        None => bail!("timed out probing {}", path.display()),
    }
}

fn try_initialize(path: &Path) -> Result<()> {
    let mut stream = UnixStream::connect(path)
        .with_context(|| format!("connect {}", path.display()))?;
    stream
        .set_read_timeout(Some(Duration::from_secs(3)))
        .ok();
    stream
        .set_write_timeout(Some(Duration::from_secs(3)))
        .ok();
    let init = concat!(
        r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"#,
        r#""protocolVersion":"2024-11-05","capabilities":{},"#,
        r#""clientInfo":{"name":"andy-cli-probe","version":"0"}}}"#,
        "\n",
    );
    stream
        .write_all(init.as_bytes())
        .context("write initialize")?;
    let mut buf = [0u8; 4096];
    let n = stream.read(&mut buf).context("read initialize result")?;
    if n == 0 {
        bail!("empty response from andyd (broken SSH unix forward?)");
    }
    let text = String::from_utf8_lossy(&buf[..n]);
    if !text.contains("jsonrpc") {
        bail!("unexpected initialize response: {}", text.trim());
    }
    Ok(())
}
