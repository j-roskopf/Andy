use andy_cli::attach;
use andy_cli::chats;
use andy_cli::daemon;
use andy_cli::device_cli::{
    self, AppCmd, AvdCmd, DeviceCmd, EmulatorCmd, FileCmd, InputCmd, IntentCmd, NetworkCmd,
    SnapshotCmd, SystemImageCmd,
};
use andy_cli::file_picker;
use andy_cli::mcp::{default_socket_path, McpClient};
use andy_cli::remote::{self, RemoteTunnel};
use andy_cli::tool_cmd::{self, ToolCmd};
use andy_cli::tui;
use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use serde_json::{json, Value};
use std::path::PathBuf;

#[derive(Parser, Debug)]
#[command(
    name = "andy",
    version = andy_cli::VERSION,
    about = "Andy CLI — drive andyd over ~/.andy/andyd.sock",
    long_about = "Scripting client for andyd. Device/emulator/network commands wrap MCP tools; \
use `andy tool call` for any MCP tool by name. Device serial: --serial or ANDY_SERIAL."
)]
struct Cli {
    /// Path to andyd unix socket (also: ANDY_SOCKET)
    #[arg(long, global = true)]
    socket: Option<PathBuf>,

    /// SSH host to tunnel the andyd socket from (one-shot; see also `andy remote`)
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
    /// Open a shell tunneled to a remote andyd (GUI Host switcher)
    Remote {
        /// user@host or ssh Host alias
        target: String,
    },
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

    if let Commands::Remote { target } = &cli.command {
        if cli.remote.is_some() {
            anyhow::bail!("do not combine `andy remote` with --remote; use one or the other");
        }
        if cli.socket.is_some() {
            anyhow::bail!("do not combine `andy remote` with --socket");
        }
        return remote::run_session(target);
    }

    // Keep the tunnel alive for the whole command when --remote is set.
    let mut tunnel: Option<RemoteTunnel> = None;
    let socket = resolve_socket(&cli, &mut tunnel)?;
    let json_out = cli.json;
    let ensure_local_daemon = cli.remote.is_none() && std::env::var_os("ANDY_REMOTE").is_none();

    match cli.command {
        Commands::Tui => tui::run_dashboard(socket, ensure_local_daemon).await?,
        cmd => {
            if ensure_local_daemon {
                daemon::ensure_running(&socket).await?;
            }
            let mut client = McpClient::new(socket);
            dispatch_command(cmd, &mut client, json_out).await?;
        }
    }
    drop(tunnel);
    Ok(())
}

async fn dispatch_command(cmd: Commands, client: &mut McpClient, json_out: bool) -> Result<()> {
    match cmd {
        Commands::Attach { task_id } => attach::attach_or_reattach(client, &task_id).await?,
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
            if let Err(err) = attach::attach_or_reattach(client, task_id).await {
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
        Commands::Device(cmd) => device_cli::run_device(client, cmd, json_out).await?,
        Commands::Emulator(cmd) => device_cli::run_emulator(client, cmd, json_out).await?,
        Commands::Avd(cmd) => device_cli::run_avd(client, cmd, json_out).await?,
        Commands::SystemImage(cmd) => device_cli::run_system_image(client, cmd, json_out).await?,
        Commands::Snapshot(cmd) => device_cli::run_snapshot(client, cmd, json_out).await?,
        Commands::Input(cmd) => device_cli::run_input(client, cmd, json_out).await?,
        Commands::App(cmd) => device_cli::run_app(client, cmd, json_out).await?,
        Commands::Intent(cmd) => device_cli::run_intent(client, cmd, json_out).await?,
        Commands::File(cmd) => device_cli::run_file(client, cmd, json_out).await?,
        Commands::Network(cmd) => device_cli::run_network(client, cmd, json_out).await?,
        Commands::Tool(cmd) => tool_cmd::run_tool(client, cmd, json_out).await?,
        Commands::Tui | Commands::Remote { .. } => unreachable!("handled in main"),
    }
    Ok(())
}

fn resolve_socket(cli: &Cli, tunnel_out: &mut Option<RemoteTunnel>) -> Result<PathBuf> {
    if let Some(remote) = cli.remote.as_deref() {
        let tunnel = remote::open_tunnel(remote)?;
        let path = tunnel.local_socket().to_path_buf();
        *tunnel_out = Some(tunnel);
        return Ok(path);
    }
    if let Some(socket) = &cli.socket {
        return Ok(socket.clone());
    }
    if let Ok(path) = std::env::var("ANDY_SOCKET") {
        let path = PathBuf::from(path);
        if !path.as_os_str().is_empty() {
            return Ok(path);
        }
    }
    Ok(default_socket_path())
}
