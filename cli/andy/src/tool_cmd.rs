use crate::args::{inject_serial, merge_tool_args, resolve_serial_flag};
use crate::dispatch::print_result;
use crate::mcp::McpClient;
use anyhow::Result;
use clap::Subcommand;
use serde_json::Map;

#[derive(Subcommand, Debug)]
pub enum ToolCmd {
    /// List MCP tools exposed by andyd
    List,
    /// Call any MCP tool by exact name (device + agent/workflow)
    Call {
        /// Exact MCP tool name (e.g. start_emulator, chat.archive)
        name: String,
        /// Repeated key=value arguments (JSON literals coerced when valid)
        #[arg(long = "arg")]
        arg: Vec<String>,
        /// JSON object merged into arguments
        #[arg(long = "json-args")]
        json_args: Option<String>,
        /// Device serial (also reads ANDY_SERIAL); injected when tool accepts serial
        #[arg(long)]
        serial: Option<String>,
    },
}

pub async fn run_tool(client: &mut McpClient, cmd: ToolCmd, json_out: bool) -> Result<()> {
    match cmd {
        ToolCmd::List => {
            let tools = client.list_tools().await?;
            if json_out {
                let arr: Vec<_> = tools
                    .iter()
                    .map(|t| {
                        serde_json::json!({
                            "name": t.name,
                            "description": t.description,
                            "inputSchema": t.input_schema,
                        })
                    })
                    .collect();
                println!("{}", serde_json::to_string_pretty(&arr)?);
            } else {
                for t in tools {
                    if t.description.is_empty() {
                        println!("{}", t.name);
                    } else {
                        println!("{}\t{}", t.name, t.description);
                    }
                }
            }
            Ok(())
        }
        ToolCmd::Call {
            name,
            arg,
            json_args,
            serial,
        } => {
            let mut arguments = merge_tool_args(Map::new(), json_args.as_deref(), &arg)?;
            let serial = resolve_serial_flag(serial.as_deref());
            // Inject serial when the live schema accepts it (or always if schema unknown).
            let accepts = match client.list_tools().await {
                Ok(tools) => tools
                    .iter()
                    .find(|t| t.name == name)
                    .map(|t| t.accepts_serial())
                    .unwrap_or(true),
                Err(_) => true,
            };
            if accepts {
                inject_serial(&mut arguments, serial.as_deref());
            }
            let result = client.call_tool_result(&name, arguments).await?;
            print_result(&result, json_out)?;
            Ok(())
        }
    }
}
