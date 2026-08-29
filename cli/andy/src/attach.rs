use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio::time::sleep;

use crate::acp_view;
use crate::mcp::McpClient;
use crate::tmux;

const SESSION_WAIT: Duration = Duration::from_secs(60);
const POLL_INTERVAL: Duration = Duration::from_millis(400);

/// Attach to a chat — ACP lane opens the native viewer; Terminal lane uses tmux
/// with the same header/status/hotkey chrome framing.
///
/// For a freshly started Terminal chat, waits for the session to appear. If it
/// never does, tries quiet provider reattach (ended chats) before failing.
pub async fn attach_or_reattach(client: &mut McpClient, task_id: &str) -> Result<()> {
    let lane = acp_view::resolve_lane(client, task_id).await?;
    if lane.eq_ignore_ascii_case("Acp") {
        return acp_view::run_acp_viewer(client, task_id).await;
    }

    let (title, status) = load_terminal_chrome(client, task_id).await;

    if tmux::has_session(task_id) && !tmux::session_looks_broken(task_id) {
        return tmux::attach(task_id, &title, &status);
    }

    // New starts are Queued briefly before tmux exists — wait first.
    match wait_for_tmux(client, task_id, AbortOnTerminalStatus::Yes).await? {
        WaitOutcome::Ready if !tmux::session_looks_broken(task_id) => {
            return tmux::attach(task_id, &title, &status);
        }
        WaitOutcome::Ready | WaitOutcome::TimedOut | WaitOutcome::TerminalStatus => {}
    }

    let raw = client
        .call_tool("chat.reattach", json!({ "taskId": task_id }))
        .await
        .context("chat.reattach")?;
    let parsed: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
    let ok = parsed.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    if !ok {
        let err = parsed
            .get("error")
            .and_then(|v| v.as_str())
            .unwrap_or("cannot reattach");
        if err.contains("missing vendor session") {
            client
                .call_tool(
                    "chat.resume",
                    json!({ "taskId": task_id, "followUp": "continue" }),
                )
                .await
                .context("chat.resume after reattach failure")?;
            match wait_for_tmux(client, task_id, AbortOnTerminalStatus::No).await? {
                WaitOutcome::Ready if !tmux::session_looks_broken(task_id) => {
                    return tmux::attach(task_id, &title, &status);
                }
                WaitOutcome::Ready | WaitOutcome::TimedOut | WaitOutcome::TerminalStatus => {}
            }
        }
        bail!("{err}\nHint: andy chat resume {task_id} \"continue\"");
    }

    // chat.reattach now waits for the session server-side; still poll locally in case
    // the CLI and daemon briefly disagree. Do not abort on Done — reattach clears status
    // then may roll back to Done if launch fails, which is reported via ok:false above.
    match wait_for_tmux(client, task_id, AbortOnTerminalStatus::No).await? {
        WaitOutcome::Ready => {}
        WaitOutcome::TimedOut | WaitOutcome::TerminalStatus => {
            if parsed.get("tmuxAlive").and_then(|b| b.as_bool()) == Some(true) {
                let socket = tmux::socket_args().join(" ");
                bail!(
                    "andyd reports a live session for {task_id}, but `tmux {socket}` cannot see it\n\
                     Hint: ensure the CLI and Andy share the same tmux server \
                     (check TMPDIR/TMUX_TMPDIR, or use `andy remote` / ANDY_TMUX_SOCKET for remote hosts)"
                );
            }
            bail!(
                "session did not appear within {}s for {task_id}\nHint: andy chat resume {task_id} \"continue\"",
                SESSION_WAIT.as_secs()
            );
        }
    }
    if tmux::session_looks_broken(task_id) {
        bail!(
            "tmux session for {task_id} failed to start (missing working directory?)\nHint: andy chat resume {task_id} \"continue\""
        );
    }

    let (title, status) = load_terminal_chrome(client, task_id).await;
    tmux::attach(task_id, &title, &status)
}

async fn load_terminal_chrome(client: &mut McpClient, task_id: &str) -> (String, String) {
    let status_raw = client
        .call_tool("chat.status", json!({ "taskId": task_id }))
        .await
        .unwrap_or_default();
    let status_v: Value = serde_json::from_str(&status_raw).unwrap_or(Value::Null);
    let list_raw = client
        .call_tool("chat.list", Value::Object(Default::default()))
        .await
        .unwrap_or_else(|_| "[]".into());
    let list: Value = serde_json::from_str(&list_raw).unwrap_or(Value::Null);
    let row = list.as_array().and_then(|arr| {
        arr.iter()
            .find(|e| e.get("id").and_then(|id| id.as_str()) == Some(task_id))
    });
    let title = row
        .and_then(|r| r.get("title"))
        .and_then(|v| v.as_str())
        .unwrap_or(task_id)
        .to_string();
    let status = status_v
        .get("status")
        .or_else(|| status_v.get("taskStatus"))
        .and_then(|v| v.as_str())
        .unwrap_or("Attached")
        .to_string();
    (title, status)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AbortOnTerminalStatus {
    Yes,
    No,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WaitOutcome {
    Ready,
    TimedOut,
    TerminalStatus,
}

async fn wait_for_tmux(
    client: &mut McpClient,
    task_id: &str,
    abort_on_terminal: AbortOnTerminalStatus,
) -> Result<WaitOutcome> {
    let deadline = Instant::now() + SESSION_WAIT;
    while Instant::now() < deadline {
        if tmux::has_session(task_id) {
            return Ok(WaitOutcome::Ready);
        }
        if let Ok(raw) = client
            .call_tool("chat.status", json!({ "taskId": task_id }))
            .await
        {
            if let Ok(v) = serde_json::from_str::<Value>(&raw) {
                if v.get("tmuxAlive").and_then(|b| b.as_bool()) == Some(true) {
                    // Prefer a real local session before declaring ready — otherwise
                    // attach fails immediately against a server the CLI cannot see.
                    if tmux::has_session(task_id) {
                        return Ok(WaitOutcome::Ready);
                    }
                }
                if abort_on_terminal == AbortOnTerminalStatus::Yes {
                    let status = v
                        .get("status")
                        .or_else(|| v.get("taskStatus"))
                        .and_then(|s| s.as_str())
                        .unwrap_or("");
                    if matches!(
                        status,
                        "Done" | "Error" | "Completed" | "Failed" | "Stopped"
                    ) {
                        return Ok(WaitOutcome::TerminalStatus);
                    }
                }
            }
        }
        sleep(POLL_INTERVAL).await;
    }
    Ok(if tmux::has_session(task_id) {
        WaitOutcome::Ready
    } else {
        WaitOutcome::TimedOut
    })
}
