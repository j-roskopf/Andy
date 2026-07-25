use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio::time::sleep;

use crate::mcp::McpClient;
use crate::tmux;

const SESSION_WAIT: Duration = Duration::from_secs(60);
const POLL_INTERVAL: Duration = Duration::from_millis(400);

/// Attach to a live tmux session.
///
/// For a freshly started chat, waits for the session to appear. If it never does,
/// tries quiet provider reattach (ended chats) before failing.
pub async fn attach_or_reattach(client: &mut McpClient, task_id: &str) -> Result<()> {
    if tmux::has_session(task_id) {
        return tmux::attach(task_id);
    }

    // New starts are Queued briefly before tmux exists — wait first.
    if wait_for_tmux(client, task_id).await? {
        return tmux::attach(task_id);
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
        bail!("{err}\nHint: andy chat resume {task_id} \"continue\"");
    }

    if !wait_for_tmux(client, task_id).await? {
        bail!(
            "session did not appear within {}s for {task_id}\nHint: andy chat resume {task_id} \"continue\"",
            SESSION_WAIT.as_secs()
        );
    }

    tmux::attach(task_id)
}

async fn wait_for_tmux(client: &mut McpClient, task_id: &str) -> Result<bool> {
    let deadline = Instant::now() + SESSION_WAIT;
    while Instant::now() < deadline {
        if tmux::has_session(task_id) {
            return Ok(true);
        }
        if let Ok(raw) = client
            .call_tool("chat.status", json!({ "taskId": task_id }))
            .await
        {
            if let Ok(v) = serde_json::from_str::<Value>(&raw) {
                if v.get("tmuxAlive").and_then(|b| b.as_bool()) == Some(true) {
                    return Ok(true);
                }
                // Don't spin for a full minute when the run already finished without a session.
                let status = v
                    .get("taskStatus")
                    .and_then(|s| s.as_str())
                    .unwrap_or("");
                if matches!(status, "Completed" | "Failed" | "Stopped") {
                    return Ok(false);
                }
            }
        }
        sleep(POLL_INTERVAL).await;
    }
    Ok(tmux::has_session(task_id))
}
