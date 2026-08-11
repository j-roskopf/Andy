use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Value};
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tokio::sync::mpsc;

pub const CHAT_SUBSCRIBE_METHOD: &str = "notifications/andy/chat.events";

pub const SUBSCRIBE_MISSING_ERROR: &str =
    "andyd does not support the chat viewer yet (missing chat.subscribe) — restart Andy / update andyd, then retry.";

#[derive(Debug, Clone)]
pub struct SubscribeBatch {
    pub subscription_id: String,
    pub task_id: String,
    pub events: Vec<Value>,
    /// When set, replace the client's event list from this index with [events]
    /// (ACP in-place coalescing keeps list size stable while mutating entries).
    pub replace_from: Option<usize>,
    pub done: bool,
    pub error: Option<String>,
}

#[derive(Debug)]
pub enum SubscribeMessage {
    Batch(SubscribeBatch),
    Finished { reason: String },
    Disconnected(String),
}

pub fn default_socket_path() -> PathBuf {
    crate::daemon::default_socket_path()
}

#[derive(Debug, Clone)]
pub struct ToolInfo {
    pub name: String,
    pub description: String,
    pub input_schema: Value,
}

impl ToolInfo {
    pub fn accepts_serial(&self) -> bool {
        self.input_schema.pointer("/properties/serial").is_some()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContentPart {
    Text(String),
    Image { data: String, mime_type: String },
}

#[derive(Debug, Clone)]
pub struct ToolResult {
    pub content: Vec<ContentPart>,
    /// Full JSON-RPC `result` object from tools/call.
    pub raw_result: Value,
}

impl ToolResult {
    pub fn text(&self) -> String {
        self.content
            .iter()
            .filter_map(|part| match part {
                ContentPart::Text(t) => Some(t.as_str()),
                ContentPart::Image { .. } => None,
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    pub fn first_image(&self) -> Option<(&str, &str)> {
        self.content.iter().find_map(|part| match part {
            ContentPart::Image { data, mime_type } => Some((data.as_str(), mime_type.as_str())),
            ContentPart::Text(_) => None,
        })
    }

    pub fn parse_content(result: &Value) -> Vec<ContentPart> {
        let Some(items) = result.get("content").and_then(|c| c.as_array()) else {
            return Vec::new();
        };
        items
            .iter()
            .filter_map(|item| {
                let ty = item.get("type").and_then(|t| t.as_str()).unwrap_or("");
                match ty {
                    "text" => item
                        .get("text")
                        .and_then(|t| t.as_str())
                        .map(|t| ContentPart::Text(t.to_string())),
                    "image" => {
                        let data = item.get("data").and_then(|d| d.as_str())?;
                        let mime = item
                            .get("mimeType")
                            .or_else(|| item.get("mime_type"))
                            .and_then(|m| m.as_str())
                            .unwrap_or("application/octet-stream");
                        Some(ContentPart::Image {
                            data: data.to_string(),
                            mime_type: mime.to_string(),
                        })
                    }
                    _ => None,
                }
            })
            .collect()
    }
}

pub struct McpClient {
    socket: PathBuf,
    next_id: u64,
}

impl McpClient {
    pub fn new(socket: PathBuf) -> Self {
        Self { socket, next_id: 1 }
    }

    pub fn socket_path(&self) -> PathBuf {
        self.socket.clone()
    }

    /// Backward-compatible helper: returns concatenated text content.
    pub async fn call_tool(&mut self, name: &str, arguments: Value) -> Result<String> {
        Ok(self.call_tool_result(name, arguments).await?.text())
    }

    pub async fn call_tool_result(&mut self, name: &str, arguments: Value) -> Result<ToolResult> {
        let root = self
            .rpc(
                "tools/call",
                json!({
                    "name": name,
                    "arguments": arguments
                }),
            )
            .await?;
        if let Some(err) = root.get("error") {
            bail!("{}", err);
        }
        let result = root
            .get("result")
            .cloned()
            .unwrap_or(Value::Object(Default::default()));
        let is_error = result
            .get("isError")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let content = ToolResult::parse_content(&result);
        if is_error {
            let msg = content
                .iter()
                .filter_map(|p| match p {
                    ContentPart::Text(t) => Some(t.as_str()),
                    _ => None,
                })
                .collect::<Vec<_>>()
                .join("\n");
            bail!("{}", if msg.is_empty() { "tool error" } else { &msg });
        }
        Ok(ToolResult {
            content,
            raw_result: result,
        })
    }

    pub async fn list_tools(&mut self) -> Result<Vec<ToolInfo>> {
        let root = self.rpc("tools/list", json!({})).await?;
        if let Some(err) = root.get("error") {
            bail!("{}", err);
        }
        let tools = root
            .pointer("/result/tools")
            .and_then(|t| t.as_array())
            .cloned()
            .unwrap_or_default();
        Ok(tools
            .into_iter()
            .filter_map(|tool| {
                let name = tool.get("name")?.as_str()?.to_string();
                let description = tool
                    .get("description")
                    .and_then(|d| d.as_str())
                    .unwrap_or("")
                    .to_string();
                let input_schema = tool
                    .get("inputSchema")
                    .cloned()
                    .unwrap_or(Value::Object(Default::default()));
                Some(ToolInfo {
                    name,
                    description,
                    input_schema,
                })
            })
            .collect())
    }

    pub async fn requires_chat_subscribe(&mut self) -> Result<()> {
        let tools = self.list_tools().await?;
        if tools.iter().any(|t| t.name == "chat.subscribe") {
            return Ok(());
        }
        bail!("{SUBSCRIBE_MISSING_ERROR}");
    }

    /// Opens a long-lived `chat.subscribe` session and streams batches on [tx].
    ///
    /// Drops when the receiver is closed or the daemon ends the subscription.
    pub async fn subscribe_chat_events(
        &mut self,
        task_id: &str,
        tx: mpsc::Sender<SubscribeMessage>,
    ) -> Result<()> {
        if !self.socket.exists() {
            bail!(
                "andyd socket not found at {} — could not start or connect to the daemon",
                self.socket.display()
            );
        }
        let stream = UnixStream::connect(&self.socket)
            .await
            .with_context(|| format!("connect {}", self.socket.display()))?;
        let (reader, mut writer) = stream.into_split();
        let mut lines = BufReader::new(reader).lines();

        let init_id = self.next_id;
        self.next_id += 1;
        let init = json!({
            "jsonrpc": "2.0",
            "id": init_id,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": { "name": "andy-cli", "version": "0.1.0" }
            }
        });
        writer
            .write_all(format!("{init}\n").as_bytes())
            .await
            .context("write initialize")?;
        let _ = lines.next_line().await.context("read initialize result")?;

        writer
            .write_all(b"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n")
            .await
            .context("write initialized")?;

        let call_id = self.next_id;
        self.next_id += 1;
        let call = json!({
            "jsonrpc": "2.0",
            "id": call_id,
            "method": "tools/call",
            "params": {
                "name": "chat.subscribe",
                "arguments": { "taskId": task_id }
            }
        });
        writer
            .write_all(format!("{call}\n").as_bytes())
            .await
            .context("write chat.subscribe")?;

        loop {
            let line = match lines.next_line().await {
                Ok(Some(line)) => line,
                Ok(None) => {
                    let _ = tx
                        .send(SubscribeMessage::Disconnected(
                            "lost connection to andyd".into(),
                        ))
                        .await;
                    break;
                }
                Err(err) => {
                    let _ = tx
                        .send(SubscribeMessage::Disconnected(format!(
                            "lost connection to andyd: {err}"
                        )))
                        .await;
                    break;
                }
            };
            let root: Value = match serde_json::from_str(&line) {
                Ok(v) => v,
                Err(_) => continue,
            };

            if root.get("id").and_then(|id| id.as_u64()) == Some(call_id)
                || root.get("id").and_then(|id| id.as_i64()) == Some(call_id as i64)
            {
                if let Some(err) = root.get("error") {
                    let msg = err
                        .get("message")
                        .and_then(|m| m.as_str())
                        .unwrap_or("chat.subscribe failed");
                    if msg.contains("Method not found") || msg.contains("tool not found") {
                        bail!("{SUBSCRIBE_MISSING_ERROR}");
                    }
                    bail!("{msg}");
                }
                let result = root.get("result").cloned().unwrap_or(Value::Null);
                let is_error = result
                    .get("isError")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                let text = ToolResult::parse_content(&result)
                    .into_iter()
                    .filter_map(|p| match p {
                        ContentPart::Text(t) => Some(t),
                        _ => None,
                    })
                    .collect::<Vec<_>>()
                    .join("\n");
                if is_error {
                    if text.contains("chat.subscribe") || text.contains("Method not found") {
                        bail!("{SUBSCRIBE_MISSING_ERROR}");
                    }
                    bail!("{text}");
                }
                let reason = serde_json::from_str::<Value>(&text)
                    .ok()
                    .and_then(|v| {
                        v.get("reason")
                            .and_then(|r| r.as_str())
                            .map(|s| s.to_string())
                    })
                    .unwrap_or_else(|| "done".to_string());
                let _ = tx.send(SubscribeMessage::Finished { reason }).await;
                break;
            }

            let method = root.get("method").and_then(|m| m.as_str()).unwrap_or("");
            if method != CHAT_SUBSCRIBE_METHOD {
                continue;
            }
            let params = root.get("params").cloned().unwrap_or(Value::Null);
            let meta = params
                .get("_meta")
                .or_else(|| params.get("meta"))
                .cloned()
                .unwrap_or(params);
            let batch = SubscribeBatch {
                subscription_id: meta
                    .get("subscriptionId")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                task_id: meta
                    .get("taskId")
                    .and_then(|v| v.as_str())
                    .unwrap_or(task_id)
                    .to_string(),
                events: meta
                    .get("events")
                    .and_then(|v| v.as_array())
                    .cloned()
                    .unwrap_or_default(),
                replace_from: meta.get("replaceFrom").and_then(|v| {
                    v.as_u64()
                        .or_else(|| v.as_i64().map(|n| n as u64))
                        .map(|n| n as usize)
                }),
                done: meta.get("done").and_then(|v| v.as_bool()).unwrap_or(false),
                error: meta
                    .get("error")
                    .and_then(|v| v.as_str())
                    .map(|s| s.to_string()),
            };
            if tx
                .send(SubscribeMessage::Batch(batch.clone()))
                .await
                .is_err()
            {
                break;
            }
            if batch.done {
                // Final tool result should follow; keep reading until id matches.
                continue;
            }
        }
        Ok(())
    }

    async fn rpc(&mut self, method: &str, params: Value) -> Result<Value> {
        if !self.socket.exists() {
            bail!(
                "andyd socket not found at {} — could not start or connect to the daemon",
                self.socket.display()
            );
        }
        let stream = UnixStream::connect(&self.socket)
            .await
            .with_context(|| format!("connect {}", self.socket.display()))?;
        let (reader, mut writer) = stream.into_split();
        let mut lines = BufReader::new(reader).lines();

        let init_id = self.next_id;
        self.next_id += 1;
        let init = json!({
            "jsonrpc": "2.0",
            "id": init_id,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": { "name": "andy-cli", "version": "0.1.0" }
            }
        });
        writer
            .write_all(format!("{init}\n").as_bytes())
            .await
            .context("write initialize")?;
        let _ = lines.next_line().await.context("read initialize result")?;

        writer
            .write_all(b"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n")
            .await
            .context("write initialized")?;

        let call_id = self.next_id;
        self.next_id += 1;
        let call = json!({
            "jsonrpc": "2.0",
            "id": call_id,
            "method": method,
            "params": params
        });
        writer
            .write_all(format!("{call}\n").as_bytes())
            .await
            .with_context(|| format!("write {method}"))?;

        let line = lines
            .next_line()
            .await
            .with_context(|| format!("read {method} response"))?
            .ok_or_else(|| anyhow!("empty response from andyd"))?;
        serde_json::from_str(&line).context("parse response json")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_text_and_image_content() {
        let result = json!({
            "content": [
                { "type": "text", "text": "hello" },
                { "type": "image", "data": "aGVsbG8=", "mimeType": "image/png" }
            ]
        });
        let parts = ToolResult::parse_content(&result);
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0], ContentPart::Text("hello".into()));
        assert_eq!(
            parts[1],
            ContentPart::Image {
                data: "aGVsbG8=".into(),
                mime_type: "image/png".into()
            }
        );
    }
}
