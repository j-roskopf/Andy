use crate::args::{inject_serial, merge_tool_args};
use crate::mcp::{McpClient, ToolResult};
use anyhow::{bail, Context, Result};
use base64::Engine;
use serde_json::{Map, Value};
use std::path::Path;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Default)]
pub struct CallOpts {
    pub serial: Option<String>,
    pub arg_flags: Vec<String>,
    pub json_args: Option<String>,
    pub json_out: bool,
    /// When set, write first image content to this path (screenshot).
    pub output_path: Option<String>,
    pub inject_serial: bool,
}

pub async fn call_and_print(
    client: &mut McpClient,
    tool: &str,
    base: Map<String, Value>,
    opts: CallOpts,
) -> Result<()> {
    let mut args = merge_tool_args(base, opts.json_args.as_deref(), &opts.arg_flags)?;
    if opts.inject_serial {
        inject_serial(&mut args, opts.serial.as_deref());
    }
    let result = client.call_tool_result(tool, args).await?;
    if let Some(path) = opts.output_path.as_deref() {
        write_image_to_file(&result, path)?;
        if opts.json_out {
            println!("{}", serde_json::to_string_pretty(&result.raw_result)?);
        } else {
            println!("wrote {path}");
        }
        return Ok(());
    }
    print_result(&result, opts.json_out)?;
    Ok(())
}

pub fn print_result(result: &ToolResult, json_out: bool) -> Result<()> {
    if json_out {
        println!("{}", serde_json::to_string_pretty(&result.raw_result)?);
        return Ok(());
    }
    let text = result.text();
    if text.is_empty() {
        if result.first_image().is_some() {
            bail!("tool returned image content; pass -o/--output to write a file");
        }
        println!("{}", serde_json::to_string_pretty(&result.raw_result)?);
    } else {
        println!("{text}");
    }
    Ok(())
}

pub fn write_image_to_file(result: &ToolResult, path: &str) -> Result<()> {
    let (data, _mime) = result
        .first_image()
        .context("tool result has no image content")?;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(data)
        .context("decode image base64")?;
    if let Some(parent) = Path::new(path).parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("create parent dirs for {path}"))?;
        }
    }
    std::fs::write(path, bytes).with_context(|| format!("write {path}"))?;
    Ok(())
}

/// Decode image bytes from a ToolResult (for tests / helpers).
#[cfg(test)]
pub fn decode_image_bytes(result: &ToolResult) -> Result<Vec<u8>> {
    let (data, _) = result
        .first_image()
        .context("tool result has no image content")?;
    base64::engine::general_purpose::STANDARD
        .decode(data)
        .context("decode image base64")
}

pub async fn wait_avd_state(
    client: &mut McpClient,
    avd_name: &str,
    want_running: bool,
    timeout: Duration,
) -> Result<()> {
    let deadline = Instant::now() + timeout;
    loop {
        let raw = client
            .call_tool("list_avds", Value::Object(Default::default()))
            .await?;
        let running = avd_is_running(&raw, avd_name)?;
        if running == want_running {
            return Ok(());
        }
        if Instant::now() >= deadline {
            bail!(
                "timed out waiting for AVD {avd_name} to become {}",
                if want_running { "running" } else { "stopped" }
            );
        }
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
}

pub fn avd_is_running(list_avds_text: &str, avd_name: &str) -> Result<bool> {
    let arr: Value = serde_json::from_str(list_avds_text)
        .with_context(|| format!("parse list_avds: {list_avds_text}"))?;
    let Value::Array(items) = arr else {
        bail!("list_avds did not return a JSON array");
    };
    for item in items {
        if item.get("name").and_then(|n| n.as_str()) == Some(avd_name) {
            return Ok(item
                .get("running")
                .and_then(|r| r.as_bool())
                .unwrap_or(false));
        }
    }
    // Not listed yet — treat as not running.
    Ok(false)
}

pub fn map_base(pairs: &[(&str, Value)]) -> Map<String, Value> {
    let mut m = Map::new();
    for (k, v) in pairs {
        m.insert((*k).into(), v.clone());
    }
    m
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mcp::{ContentPart, ToolResult};
    use serde_json::json;

    #[test]
    fn avd_running_lookup() {
        let raw = r#"[{"name":"Pixel_7","running":true},{"name":"Tablet","running":false}]"#;
        assert!(avd_is_running(raw, "Pixel_7").unwrap());
        assert!(!avd_is_running(raw, "Tablet").unwrap());
        assert!(!avd_is_running(raw, "Missing").unwrap());
    }

    #[test]
    fn decode_screenshot_fixture() {
        // "hi" as base64
        let result = ToolResult {
            content: vec![ContentPart::Image {
                data: "aGk=".into(),
                mime_type: "image/png".into(),
            }],
            raw_result: json!({}),
        };
        let bytes = decode_image_bytes(&result).unwrap();
        assert_eq!(bytes, b"hi");
    }
}
