//! Hand-rolled newline-delimited JSON-RPC MCP mock for unix-socket integration tests.
//!
//! Speaks the same framing as `andy_cli::mcp::McpClient` / andyd's unix socket server.

use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

pub const CHAT_SUBSCRIBE_METHOD: &str = "notifications/andy/chat.events";

#[derive(Debug, Clone, Default)]
pub struct MockState {
    pub tools: Vec<&'static str>,
    pub backlog: Vec<Value>,
    /// Live events pushed after subscribe starts (drained by the subscribe handler).
    pub live_events: Vec<Value>,
    /// Captured tools/call argument objects keyed by tool name.
    pub calls: Vec<(String, Value)>,
    /// When set, chat.subscribe finishes with this reason after backlog (+ live).
    pub subscribe_finish_reason: Option<String>,
    /// When true, tools/call for chat.subscribe returns a Method not found error.
    pub subscribe_missing: bool,
}

impl MockState {
    pub fn with_subscribe() -> Self {
        Self {
            tools: vec![
                "chat.subscribe",
                "chat.events",
                "chat.resume",
                "chat.queue_follow_up",
                "chat.status",
                "chat.list",
            ],
            backlog: Vec::new(),
            live_events: Vec::new(),
            calls: Vec::new(),
            subscribe_finish_reason: Some("terminal".into()),
            subscribe_missing: false,
        }
    }

    pub fn without_subscribe() -> Self {
        let mut s = Self::with_subscribe();
        s.tools.retain(|t| *t != "chat.subscribe");
        s.subscribe_missing = true;
        s
    }
}

pub struct MockMcpServer {
    pub socket: PathBuf,
    state: Arc<Mutex<MockState>>,
    handle: JoinHandle<()>,
    _dir: tempfile::TempDir,
}

impl MockMcpServer {
    pub async fn spawn(state: MockState) -> Self {
        let dir = tempfile::tempdir().expect("tempdir");
        let socket = dir.path().join("mock-andyd.sock");
        let _ = std::fs::remove_file(&socket);
        let listener = UnixListener::bind(&socket).expect("bind mock socket");
        let state = Arc::new(Mutex::new(state));
        let state_clone = Arc::clone(&state);
        let handle = tokio::spawn(async move {
            loop {
                let Ok((stream, _)) = listener.accept().await else {
                    break;
                };
                let st = Arc::clone(&state_clone);
                tokio::spawn(async move {
                    if let Err(err) = handle_client(stream, st).await {
                        eprintln!("mock mcp client error: {err:#}");
                    }
                });
            }
        });
        Self {
            socket,
            state,
            handle,
            _dir: dir,
        }
    }

    pub fn state(&self) -> Arc<Mutex<MockState>> {
        Arc::clone(&self.state)
    }
}

impl Drop for MockMcpServer {
    fn drop(&mut self) {
        self.handle.abort();
        let _ = std::fs::remove_file(&self.socket);
    }
}

async fn handle_client(stream: UnixStream, state: Arc<Mutex<MockState>>) -> anyhow::Result<()> {
    let (reader, mut writer) = stream.into_split();
    let mut lines = BufReader::new(reader).lines();

    while let Some(line) = lines.next_line().await? {
        if line.trim().is_empty() {
            continue;
        }
        let root: Value = serde_json::from_str(&line)?;
        let method = root.get("method").and_then(|m| m.as_str()).unwrap_or("");
        let id = root.get("id").cloned();

        match method {
            "initialize" => {
                let resp = json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {},
                        "serverInfo": { "name": "mock-andyd", "version": "test" }
                    }
                });
                writer.write_all(format!("{resp}\n").as_bytes()).await?;
            }
            "notifications/initialized" => {}
            "tools/list" => {
                let tools = {
                    let st = state.lock().unwrap();
                    st.tools
                        .iter()
                        .map(|name| {
                            json!({
                                "name": name,
                                "description": name,
                                "inputSchema": { "type": "object", "properties": {} }
                            })
                        })
                        .collect::<Vec<_>>()
                };
                let resp = json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "result": { "tools": tools }
                });
                writer.write_all(format!("{resp}\n").as_bytes()).await?;
            }
            "tools/call" => {
                let params = root.get("params").cloned().unwrap_or(Value::Null);
                let name = params
                    .get("name")
                    .and_then(|n| n.as_str())
                    .unwrap_or("")
                    .to_string();
                let args = params
                    .get("arguments")
                    .cloned()
                    .unwrap_or(Value::Object(Default::default()));
                {
                    let mut st = state.lock().unwrap();
                    st.calls.push((name.clone(), args.clone()));
                }

                if name == "chat.subscribe" {
                    let (backlog, live, finish, missing) = {
                        let mut st = state.lock().unwrap();
                        let backlog = st.backlog.clone();
                        let live = std::mem::take(&mut st.live_events);
                        let finish = st.subscribe_finish_reason.clone();
                        let missing = st.subscribe_missing
                            || !st.tools.iter().any(|t| *t == "chat.subscribe");
                        (backlog, live, finish, missing)
                    };
                    if missing {
                        let resp = json!({
                            "jsonrpc": "2.0",
                            "id": id,
                            "error": {
                                "code": -32601,
                                "message": "Method not found: chat.subscribe"
                            }
                        });
                        writer.write_all(format!("{resp}\n").as_bytes()).await?;
                        continue;
                    }

                    let task_id = args
                        .get("taskId")
                        .and_then(|t| t.as_str())
                        .unwrap_or("task")
                        .to_string();
                    let sub_id = "sub-test";

                    let batch = json!({
                        "jsonrpc": "2.0",
                        "method": CHAT_SUBSCRIBE_METHOD,
                        "params": {
                            "_meta": {
                                "subscriptionId": sub_id,
                                "taskId": task_id,
                                "events": backlog,
                                "done": false
                            }
                        }
                    });
                    writer.write_all(format!("{batch}\n").as_bytes()).await?;

                    if !live.is_empty() {
                        let live_batch = json!({
                            "jsonrpc": "2.0",
                            "method": CHAT_SUBSCRIBE_METHOD,
                            "params": {
                                "_meta": {
                                    "subscriptionId": sub_id,
                                    "taskId": task_id,
                                    "events": live,
                                    "done": false
                                }
                            }
                        });
                        writer
                            .write_all(format!("{live_batch}\n").as_bytes())
                            .await?;
                    }

                    if let Some(reason) = finish {
                        let done_batch = json!({
                            "jsonrpc": "2.0",
                            "method": CHAT_SUBSCRIBE_METHOD,
                            "params": {
                                "_meta": {
                                    "subscriptionId": sub_id,
                                    "taskId": task_id,
                                    "events": [],
                                    "done": true
                                }
                            }
                        });
                        writer
                            .write_all(format!("{done_batch}\n").as_bytes())
                            .await?;
                        let result_text = json!({ "ok": true, "reason": reason }).to_string();
                        let resp = json!({
                            "jsonrpc": "2.0",
                            "id": id,
                            "result": {
                                "content": [{ "type": "text", "text": result_text }],
                                "isError": false
                            }
                        });
                        writer.write_all(format!("{resp}\n").as_bytes()).await?;
                    } else {
                        while let Some(extra) = lines.next_line().await? {
                            let _ = extra;
                        }
                        break;
                    }
                    continue;
                }

                let text = match name.as_str() {
                    "chat.start" | "chat.resume" | "chat.queue_follow_up" => {
                        json!({ "ok": true, "tool": name, "arguments": args }).to_string()
                    }
                    "chat.status" => json!({
                        "status": "Done",
                        "lane": "Acp",
                        "cwd": "/tmp",
                    })
                    .to_string(),
                    "chat.list" => json!([{
                        "id": "task-1",
                        "title": "Mock",
                        "lane": "Acp",
                    }])
                    .to_string(),
                    "chat.events" => {
                        let backlog = state.lock().unwrap().backlog.clone();
                        Value::Array(backlog).to_string()
                    }
                    other => json!({ "ok": true, "tool": other }).to_string(),
                };
                let resp = json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "result": {
                        "content": [{ "type": "text", "text": text }],
                        "isError": false
                    }
                });
                writer.write_all(format!("{resp}\n").as_bytes()).await?;
            }
            _ => {
                if id.is_some() {
                    let resp = json!({
                        "jsonrpc": "2.0",
                        "id": id,
                        "error": { "code": -32601, "message": format!("Method not found: {method}") }
                    });
                    writer.write_all(format!("{resp}\n").as_bytes()).await?;
                }
            }
        }
    }
    Ok(())
}

/// Drain subscribe messages until Finished/Disconnected or timeout.
pub async fn collect_subscribe(
    client: &mut andy_cli::mcp::McpClient,
    task_id: &str,
) -> Vec<andy_cli::mcp::SubscribeMessage> {
    let (tx, mut rx) = mpsc::channel(32);
    let mut sub = andy_cli::mcp::McpClient::new(client.socket_path());
    let task = task_id.to_string();
    let join = tokio::spawn(async move {
        let _ = sub.subscribe_chat_events(&task, tx).await;
    });
    let mut out = Vec::new();
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(2);
    loop {
        tokio::select! {
            msg = rx.recv() => {
                match msg {
                    Some(m) => {
                        let terminal = matches!(
                            m,
                            andy_cli::mcp::SubscribeMessage::Finished { .. }
                                | andy_cli::mcp::SubscribeMessage::Disconnected(_)
                        );
                        out.push(m);
                        if terminal {
                            break;
                        }
                    }
                    None => break,
                }
            }
            _ = tokio::time::sleep_until(deadline) => break,
        }
    }
    join.abort();
    out
}

pub fn calls_for(state: &MockState, tool: &str) -> Vec<Value> {
    state
        .calls
        .iter()
        .filter(|(n, _)| n == tool)
        .map(|(_, a)| a.clone())
        .collect()
}
