mod attach;
mod chats;
mod compose;
mod mcp;
mod tmux;
mod tui;

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use mcp::{default_socket_path, McpClient};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::process::Command;

#[derive(Parser, Debug)]
#[command(name = "andy", about = "Andy CLI — drive andyd over ~/.andy/andyd.sock")]
struct Cli {
    /// Path to andyd unix socket
    #[arg(long, global = true)]
    socket: Option<PathBuf>,

    /// SSH host to tunnel the andyd socket from (macOS/Linux remote)
    #[arg(long, global = true)]
    remote: Option<String>,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand, Debug)]
enum Commands {
    /// Interactive ratatui dashboard (chats grouped by project)
    Tui,
    /// Attach to a chat's live terminal (quietly reattaches ended sessions when possible)
    Attach { task_id: String },
    #[command(subcommand)]
    Chat(ChatCmd),
    #[command(subcommand)]
    Project(ProjectCmd),
}

#[derive(Subcommand, Debug)]
enum ChatCmd {
    /// List chats grouped by project (use --json for raw MCP payload)
    List {
        #[arg(long)]
        json: bool,
    },
    Start {
        #[arg(long)]
        agent: String,
        #[arg(long)]
        project: Option<String>,
        #[arg(long)]
        directory: Option<String>,
        #[arg(long)]
        title: Option<String>,
        /// Print the MCP response and exit without attaching to tmux.
        #[arg(long)]
        no_attach: bool,
        prompt: String,
    },
    Stop { task_id: String },
    Status { task_id: String },
    Resume {
        task_id: String,
        follow_up: String,
    },
}

#[derive(Subcommand, Debug)]
enum ProjectCmd {
    List,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let socket = resolve_socket(&cli).await?;
    let mut client = McpClient::new(socket);

    match cli.command {
        Commands::Tui => tui::run_dashboard(client).await?,
        Commands::Attach { task_id } => attach::attach_or_reattach(&mut client, &task_id).await?,
        Commands::Chat(ChatCmd::List { json }) => {
            let raw = client
                .call_tool("chat.list", Value::Object(Default::default()))
                .await?;
            if json {
                println!("{raw}");
            } else {
                let entries = chats::grouped_entries(chats::parse_chats(&raw));
                println!("{}", chats::format_grouped(&entries));
            }
        }
        Commands::Chat(ChatCmd::Start {
            agent,
            project,
            directory,
            title,
            no_attach,
            prompt,
        }) => {
            let mut args = json!({
                "agent": agent,
                "prompt": prompt,
            });
            if let Some(p) = project {
                args["projectId"] = json!(p);
            }
            if let Some(d) = directory {
                args["directory"] = json!(d);
            }
            if let Some(t) = title {
                args["title"] = json!(t);
            }
            let raw = client.call_tool("chat.start", args).await?;
            if no_attach {
                println!("{raw}");
                return Ok(());
            }
            let v: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
            if let Some(err) = v.as_str().filter(|s| s.starts_with("Error:")) {
                anyhow::bail!("{err}");
            }
            let task_id = v
                .get("id")
                .and_then(|id| id.as_str())
                .with_context(|| format!("unexpected chat.start response: {raw}"))?;
            if let Err(err) = attach::attach_or_reattach(&mut client, task_id).await {
                eprintln!("started {task_id} but attach failed: {err:#}");
                println!("{raw}");
            }
        }
        Commands::Chat(ChatCmd::Stop { task_id }) => {
            let raw = client
                .call_tool("chat.stop", json!({ "taskId": task_id }))
                .await?;
            println!("{raw}");
        }
        Commands::Chat(ChatCmd::Status { task_id }) => {
            let raw = client
                .call_tool("chat.status", json!({ "taskId": task_id }))
                .await?;
            println!("{raw}");
        }
        Commands::Chat(ChatCmd::Resume {
            task_id,
            follow_up,
        }) => {
            let raw = client
                .call_tool(
                    "chat.resume",
                    json!({ "taskId": task_id, "followUp": follow_up }),
                )
                .await?;
            println!("{raw}");
        }
        Commands::Project(ProjectCmd::List) => {
            let raw = client
                .call_tool("project.list", Value::Object(Default::default()))
                .await?;
            println!("{raw}");
        }
    }
    Ok(())
}

async fn resolve_socket(cli: &Cli) -> Result<PathBuf> {
    let Some(remote) = cli.remote.as_deref() else {
        return Ok(cli.socket.clone().unwrap_or_else(default_socket_path));
    };

    // Always bind the forward to a fresh local path so an existing ~/.andy/andyd.sock
    // is never mistaken for the remote tunnel.
    let local = PathBuf::from(format!("/tmp/andy-remote-local-{}.sock", std::process::id()));
    let _ = std::fs::remove_file(&local);
    let remote_path = "~/.andy/andyd.sock";
    let status = Command::new("ssh")
        .args([
            "-f",
            "-N",
            "-o",
            "ExitOnForwardFailure=yes",
            "-L",
            &format!("{}:{}", local.display(), remote_path),
            remote,
        ])
        .status()
        .with_context(|| format!("ssh tunnel to {remote}"))?;
    if !status.success() {
        anyhow::bail!("ssh tunnel to {remote} failed with status {status}");
    }
    for _ in 0..20 {
        if local.exists() {
            return Ok(local);
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    anyhow::bail!(
        "ssh tunnel to {remote} did not create local socket at {}",
        local.display()
    )
}
