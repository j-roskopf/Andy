use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Value};
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

pub fn default_socket_path() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".andy/andyd.sock")
}

pub struct McpClient {
    socket: PathBuf,
    next_id: u64,
}

impl McpClient {
    pub fn new(socket: PathBuf) -> Self {
        Self {
            socket,
            next_id: 1,
        }
    }

    pub async fn call_tool(&mut self, name: &str, arguments: Value) -> Result<String> {
        if !self.socket.exists() {
            bail!(
                "andyd socket not found at {} — is andyd running? (./gradlew runAndyd)",
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
                "name": name,
                "arguments": arguments
            }
        });
        writer
            .write_all(format!("{call}\n").as_bytes())
            .await
            .context("write tools/call")?;

        let line = lines
            .next_line()
            .await
            .context("read tools/call response")?
            .ok_or_else(|| anyhow!("empty response from andyd"))?;
        let root: Value = serde_json::from_str(&line).context("parse response json")?;
        if let Some(err) = root.get("error") {
            bail!("{}", err);
        }
        let is_error = root
            .pointer("/result/isError")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let content = root
            .pointer("/result/content/0/text")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if is_error {
            bail!("{content}");
        }
        Ok(content.to_string())
    }
}
