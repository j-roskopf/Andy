use andy_cli::attach;
use andy_cli::chats;
use andy_cli::daemon;
use andy_cli::device_cli::{
    self, AppCmd, AvdCmd, DeviceCmd, EmulatorCmd, FileCmd, InputCmd, IntentCmd, NetworkCmd,
    SnapshotCmd, SystemImageCmd,
};
use andy_cli::file_picker;
use andy_cli::mcp::{default_socket_path, McpClient};
use andy_cli::tool_cmd::{self, ToolCmd};
use andy_cli::tui;
use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::process::Command;

#[derive(Parser, Debug)]
#[command(
    name = "andy",
    about = "Andy CLI — drive andyd over ~/.andy/andyd.sock",
    long_about = "Scripting client for andyd. Device/emulator/network commands wrap MCP tools; \
use `andy tool call` for any MCP tool by name. Device serial: --serial or ANDY_SERIAL."
)]
struct Cli {
    /// Path to andyd unix socket
    #[arg(long, global = true)]
    socket: Option<PathBuf>,

    /// SSH host to tunnel the andyd socket from (macOS/Linux remote)
    #[arg(long, global = true)]
    remote: Option<String>,

    /// Print raw MCP / machine-readable JSON
    #[arg(long, global = true)]
    json: bool,

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
    /// Connected devices, shell, screenshot, logcat
    #[command(subcommand)]
    Device(DeviceCmd),
    /// Start / stop emulators
    #[command(subcommand)]
    Emulator(EmulatorCmd),
    /// AVD create / clone / delete / list
    #[command(subcommand)]
    Avd(AvdCmd),
    /// System images
    #[command(name = "system-image", subcommand)]
    SystemImage(SystemImageCmd),
    /// Emulator snapshots
    #[command(subcommand)]
    Snapshot(SnapshotCmd),
    /// Tap / swipe / text / key
    #[command(subcommand)]
    Input(InputCmd),
    /// Installed apps
    #[command(subcommand)]
    App(AppCmd),
    /// Send intents / deeplinks
    #[command(subcommand)]
    Intent(IntentCmd),
    /// Device filesystem
    #[command(subcommand)]
    File(FileCmd),
    /// Network proxy, mock rules, recorded requests
    #[command(subcommand)]
    Network(NetworkCmd),
    /// Generic MCP tool list / call (full parity escape hatch)
    #[command(subcommand)]
    Tool(ToolCmd),
}

#[derive(Subcommand, Debug)]
enum ChatCmd {
    /// List chats grouped by project (use --json for raw MCP payload)
    List,
    Start {
        #[arg(long)]
        agent: String,
        #[arg(long)]
        project: Option<String>,
        #[arg(long)]
        directory: Option<String>,
        #[arg(long)]
        title: Option<String>,
        /// Attach image file(s) to the first user message (repeatable).
        #[arg(long = "image", value_name = "PATH")]
        images: Vec<PathBuf>,
        /// Open the filesystem image picker before starting (can combine with --image).
        #[arg(long)]
        pick_image: bool,
        /// Print the MCP response and exit without attaching to tmux.
        #[arg(long)]
        no_attach: bool,
        prompt: String,
    },
    Stop {
        task_id: String,
    },
    Status {
        task_id: String,
    },
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
    daemon::ensure_unix_platform()?;
    let cli = Cli::parse();
    let socket = resolve_socket(&cli).await?;
    if cli.remote.is_none() {
        daemon::ensure_running(&socket).await?;
    }
    let mut client = McpClient::new(socket);
    let json_out = cli.json;

    match cli.command {
        Commands::Tui => tui::run_dashboard(client).await?,
        Commands::Attach { task_id } => attach::attach_or_reattach(&mut client, &task_id).await?,
        Commands::Chat(ChatCmd::List) => {
            let raw = client
                .call_tool("chat.list", Value::Object(Default::default()))
                .await?;
            if json_out {
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
            images,
            pick_image,
            no_attach,
            prompt,
        }) => {
            let mut image_paths: Vec<String> = images
                .into_iter()
                .map(|p| p.canonicalize().unwrap_or(p).display().to_string())
                .collect();
            if pick_image {
                let start = directory.as_ref().map(PathBuf::from).unwrap_or_else(|| {
                    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
                });
                if let Some(path) = file_picker::pick_image_standalone(start)? {
                    image_paths.push(path.display().to_string());
                }
            }
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
            if !image_paths.is_empty() {
                args["imagePaths"] = json!(image_paths);
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
        Commands::Chat(ChatCmd::Resume { task_id, follow_up }) => {
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
        Commands::Device(cmd) => device_cli::run_device(&mut client, cmd, json_out).await?,
        Commands::Emulator(cmd) => device_cli::run_emulator(&mut client, cmd, json_out).await?,
        Commands::Avd(cmd) => device_cli::run_avd(&mut client, cmd, json_out).await?,
        Commands::SystemImage(cmd) => {
            device_cli::run_system_image(&mut client, cmd, json_out).await?
        }
        Commands::Snapshot(cmd) => device_cli::run_snapshot(&mut client, cmd, json_out).await?,
        Commands::Input(cmd) => device_cli::run_input(&mut client, cmd, json_out).await?,
        Commands::App(cmd) => device_cli::run_app(&mut client, cmd, json_out).await?,
        Commands::Intent(cmd) => device_cli::run_intent(&mut client, cmd, json_out).await?,
        Commands::File(cmd) => device_cli::run_file(&mut client, cmd, json_out).await?,
        Commands::Network(cmd) => device_cli::run_network(&mut client, cmd, json_out).await?,
        Commands::Tool(cmd) => tool_cmd::run_tool(&mut client, cmd, json_out).await?,
    }
    Ok(())
}

async fn resolve_socket(cli: &Cli) -> Result<PathBuf> {
    let Some(remote) = cli.remote.as_deref() else {
        return Ok(cli.socket.clone().unwrap_or_else(default_socket_path));
    };

    // Always bind the forward to a fresh local path so an existing ~/.andy/andyd.sock
    // is never mistaken for the remote tunnel.
    let local = PathBuf::from(format!(
        "/tmp/andy-remote-local-{}.sock",
        std::process::id()
    ));
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
