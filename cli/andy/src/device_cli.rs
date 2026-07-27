use crate::args::resolve_serial_flag;
use crate::dispatch::{call_and_print, map_base, wait_avd_state, CallOpts};
use crate::mcp::McpClient;
use anyhow::Result;
use clap::Subcommand;
use serde_json::{json, Map};
use std::time::Duration;

#[derive(Subcommand, Debug)]
pub enum DeviceCmd {
    /// List connected devices (`list_devices`)
    List {
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    /// Run a shell command on a device (`shell`)
    Shell {
        command: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    /// Capture a screenshot (`screenshot`); requires `-o`
    Screenshot {
        #[arg(short = 'o', long = "output")]
        output: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    /// Dump accessibility UI tree (`ui_dump`)
    #[command(name = "ui-dump")]
    UiDump {
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    /// Snapshot filtered logcat (`logcat_snapshot`)
    Logcat {
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        search: Option<String>,
        #[arg(long)]
        limit: Option<i64>,
        #[arg(long)]
        level: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum EmulatorCmd {
    /// Start an AVD (`start_emulator`)
    Start {
        name: String,
        /// Poll until the AVD reports running
        #[arg(long)]
        wait: bool,
        /// Timeout seconds for `--wait` (default 120)
        #[arg(long, default_value_t = 120)]
        timeout: u64,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    /// Stop an AVD (`stop_emulator`)
    Stop {
        name: String,
        #[arg(long)]
        wait: bool,
        #[arg(long, default_value_t = 120)]
        timeout: u64,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum AvdCmd {
    List {
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Create {
        name: String,
        #[arg(long)]
        profile_id: String,
        #[arg(long)]
        system_image_package: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Clone {
        source_name: String,
        new_name: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Delete {
        name: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum SystemImageCmd {
    List {
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Install {
        package_id: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum SnapshotCmd {
    List {
        avd_name: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Save {
        avd_name: String,
        snapshot_name: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Load {
        avd_name: String,
        snapshot_name: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Delete {
        avd_name: String,
        snapshot_name: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum InputCmd {
    Tap {
        x: i64,
        y: i64,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Swipe {
        start_x: i64,
        start_y: i64,
        end_x: i64,
        end_y: i64,
        #[arg(long)]
        duration_millis: Option<i64>,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Text {
        text: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Key {
        key: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum AppCmd {
    List {
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Launch {
        package_name: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Stop {
        package_name: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Clear {
        package_name: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Uninstall {
        package_name: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Install {
        apk_path: String,
        #[arg(long)]
        replace: bool,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Permissions {
        package_name: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Activities {
        package_name: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum IntentCmd {
    Send {
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        mode: Option<String>,
        #[arg(long)]
        action: Option<String>,
        #[arg(long)]
        component: Option<String>,
        #[arg(long)]
        data_uri: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum FileCmd {
    Ls {
        path: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Pull {
        remote_path: String,
        local_path: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Push {
        local_path: String,
        remote_path: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Delete {
        remote_path: String,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum NetworkCmd {
    #[command(subcommand)]
    Proxy(NetworkProxyCmd),
    #[command(subcommand)]
    Rule(NetworkRuleCmd),
    #[command(subcommand)]
    Request(NetworkRequestCmd),
}

#[derive(Subcommand, Debug)]
pub enum NetworkProxyCmd {
    Start {
        port: i64,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Stop {
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Configure {
        host: String,
        port: i64,
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum NetworkRuleCmd {
    List {
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Upsert {
        #[arg(long)]
        id: Option<String>,
        #[arg(long)]
        name: Option<String>,
        #[arg(long)]
        url_pattern: Option<String>,
        #[arg(long)]
        method: Option<String>,
        #[arg(long)]
        status_code: Option<i64>,
        #[arg(long)]
        response_body: Option<String>,
        #[arg(long)]
        enabled: Option<bool>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    /// Replace all rules; pass JSON via `--json-args '{"rules":[...]}'`
    Set {
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Delete {
        id: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum NetworkRequestCmd {
    List {
        #[arg(long)]
        limit: Option<i64>,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Get {
        id: String,
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
    Clear {
        #[arg(long)]
        arg: Vec<String>,
        #[arg(long)]
        json_args: Option<String>,
    },
}

fn opts(
    serial: Option<String>,
    arg: Vec<String>,
    json_args: Option<String>,
    json_out: bool,
    inject_serial: bool,
) -> CallOpts {
    CallOpts {
        serial: resolve_serial_flag(serial.as_deref()),
        arg_flags: arg,
        json_args,
        json_out,
        output_path: None,
        inject_serial,
    }
}

pub async fn run_device(client: &mut McpClient, cmd: DeviceCmd, json_out: bool) -> Result<()> {
    match cmd {
        DeviceCmd::List { arg, json_args } => {
            call_and_print(
                client,
                "list_devices",
                Map::new(),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        DeviceCmd::Shell {
            command,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "shell",
                map_base(&[("command", json!(command))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        DeviceCmd::Screenshot {
            output,
            serial,
            arg,
            json_args,
        } => {
            let mut o = opts(serial, arg, json_args, json_out, true);
            o.output_path = Some(output);
            call_and_print(client, "screenshot", Map::new(), o).await
        }
        DeviceCmd::UiDump {
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "ui_dump",
                Map::new(),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        DeviceCmd::Logcat {
            serial,
            search,
            limit,
            level,
            arg,
            json_args,
        } => {
            let mut base = Map::new();
            if let Some(s) = search {
                base.insert("search".into(), json!(s));
            }
            if let Some(l) = limit {
                base.insert("limit".into(), json!(l));
            }
            if let Some(lvl) = level {
                base.insert("level".into(), json!(lvl));
            }
            call_and_print(
                client,
                "logcat_snapshot",
                base,
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
    }
}

pub async fn run_emulator(client: &mut McpClient, cmd: EmulatorCmd, json_out: bool) -> Result<()> {
    match cmd {
        EmulatorCmd::Start {
            name,
            wait,
            timeout,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "start_emulator",
                map_base(&[("name", json!(name.clone()))]),
                opts(None, arg, json_args, json_out, false),
            )
            .await?;
            if wait {
                wait_avd_state(client, &name, true, Duration::from_secs(timeout)).await?;
                if !json_out {
                    println!("AVD {name} is running");
                }
            }
            Ok(())
        }
        EmulatorCmd::Stop {
            name,
            wait,
            timeout,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "stop_emulator",
                map_base(&[("name", json!(name.clone()))]),
                opts(None, arg, json_args, json_out, false),
            )
            .await?;
            if wait {
                wait_avd_state(client, &name, false, Duration::from_secs(timeout)).await?;
                if !json_out {
                    println!("AVD {name} is stopped");
                }
            }
            Ok(())
        }
    }
}

pub async fn run_avd(client: &mut McpClient, cmd: AvdCmd, json_out: bool) -> Result<()> {
    match cmd {
        AvdCmd::List { arg, json_args } => {
            call_and_print(
                client,
                "list_avds",
                Map::new(),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        AvdCmd::Create {
            name,
            profile_id,
            system_image_package,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "create_avd",
                map_base(&[
                    ("name", json!(name)),
                    ("profileId", json!(profile_id)),
                    ("systemImagePackage", json!(system_image_package)),
                ]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        AvdCmd::Clone {
            source_name,
            new_name,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "clone_avd",
                map_base(&[
                    ("sourceName", json!(source_name)),
                    ("newName", json!(new_name)),
                ]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        AvdCmd::Delete {
            name,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "delete_avd",
                map_base(&[("name", json!(name))]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
    }
}

pub async fn run_system_image(
    client: &mut McpClient,
    cmd: SystemImageCmd,
    json_out: bool,
) -> Result<()> {
    match cmd {
        SystemImageCmd::List { arg, json_args } => {
            call_and_print(
                client,
                "list_system_images",
                Map::new(),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        SystemImageCmd::Install {
            package_id,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "install_system_image",
                map_base(&[("packageId", json!(package_id))]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
    }
}

pub async fn run_snapshot(client: &mut McpClient, cmd: SnapshotCmd, json_out: bool) -> Result<()> {
    match cmd {
        SnapshotCmd::List {
            avd_name,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "list_snapshots",
                map_base(&[("avdName", json!(avd_name))]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        SnapshotCmd::Save {
            avd_name,
            snapshot_name,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "save_snapshot",
                map_base(&[
                    ("avdName", json!(avd_name)),
                    ("snapshotName", json!(snapshot_name)),
                ]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        SnapshotCmd::Load {
            avd_name,
            snapshot_name,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "load_snapshot",
                map_base(&[
                    ("avdName", json!(avd_name)),
                    ("snapshotName", json!(snapshot_name)),
                ]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        SnapshotCmd::Delete {
            avd_name,
            snapshot_name,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "delete_snapshot",
                map_base(&[
                    ("avdName", json!(avd_name)),
                    ("snapshotName", json!(snapshot_name)),
                ]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
    }
}

pub async fn run_input(client: &mut McpClient, cmd: InputCmd, json_out: bool) -> Result<()> {
    match cmd {
        InputCmd::Tap {
            x,
            y,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "tap",
                map_base(&[("x", json!(x)), ("y", json!(y))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        InputCmd::Swipe {
            start_x,
            start_y,
            end_x,
            end_y,
            duration_millis,
            serial,
            arg,
            json_args,
        } => {
            let duration = duration_millis.unwrap_or(300);
            call_and_print(
                client,
                "swipe",
                map_base(&[
                    ("startX", json!(start_x)),
                    ("startY", json!(start_y)),
                    ("endX", json!(end_x)),
                    ("endY", json!(end_y)),
                    ("durationMillis", json!(duration)),
                ]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        InputCmd::Text {
            text,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "input_text",
                map_base(&[("text", json!(text))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        InputCmd::Key {
            key,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "press_key",
                map_base(&[("key", json!(key))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
    }
}

pub async fn run_app(client: &mut McpClient, cmd: AppCmd, json_out: bool) -> Result<()> {
    match cmd {
        AppCmd::List {
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "list_apps",
                Map::new(),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        AppCmd::Launch {
            package_name,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "launch_app",
                map_base(&[("packageName", json!(package_name))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        AppCmd::Stop {
            package_name,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "stop_app",
                map_base(&[("packageName", json!(package_name))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        AppCmd::Clear {
            package_name,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "clear_app_data",
                map_base(&[("packageName", json!(package_name))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        AppCmd::Uninstall {
            package_name,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "uninstall_app",
                map_base(&[("packageName", json!(package_name))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        AppCmd::Install {
            apk_path,
            replace,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "install_app",
                map_base(&[("apkPath", json!(apk_path)), ("replace", json!(replace))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        AppCmd::Permissions {
            package_name,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "list_permissions",
                map_base(&[("packageName", json!(package_name))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        AppCmd::Activities {
            package_name,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "list_activities",
                map_base(&[("packageName", json!(package_name))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
    }
}

pub async fn run_intent(client: &mut McpClient, cmd: IntentCmd, json_out: bool) -> Result<()> {
    match cmd {
        IntentCmd::Send {
            serial,
            mode,
            action,
            component,
            data_uri,
            arg,
            json_args,
        } => {
            let mut base = Map::new();
            if let Some(v) = mode {
                base.insert("mode".into(), json!(v));
            }
            if let Some(v) = action {
                base.insert("action".into(), json!(v));
            }
            if let Some(v) = component {
                base.insert("component".into(), json!(v));
            }
            if let Some(v) = data_uri {
                base.insert("dataUri".into(), json!(v));
            }
            call_and_print(
                client,
                "send_intent",
                base,
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
    }
}

pub async fn run_file(client: &mut McpClient, cmd: FileCmd, json_out: bool) -> Result<()> {
    match cmd {
        FileCmd::Ls {
            path,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "file_list_dir",
                map_base(&[("path", json!(path))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        FileCmd::Pull {
            remote_path,
            local_path,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "file_pull",
                map_base(&[
                    ("remotePath", json!(remote_path)),
                    ("localPath", json!(local_path)),
                ]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        FileCmd::Push {
            local_path,
            remote_path,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "file_push",
                map_base(&[
                    ("localPath", json!(local_path)),
                    ("remotePath", json!(remote_path)),
                ]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        FileCmd::Delete {
            remote_path,
            serial,
            arg,
            json_args,
        } => {
            call_and_print(
                client,
                "file_delete",
                map_base(&[("remotePath", json!(remote_path))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
    }
}

pub async fn run_network(client: &mut McpClient, cmd: NetworkCmd, json_out: bool) -> Result<()> {
    match cmd {
        NetworkCmd::Proxy(NetworkProxyCmd::Start {
            port,
            arg,
            json_args,
        }) => {
            call_and_print(
                client,
                "start_network_proxy",
                map_base(&[("port", json!(port))]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        NetworkCmd::Proxy(NetworkProxyCmd::Stop { arg, json_args }) => {
            call_and_print(
                client,
                "stop_network_proxy",
                Map::new(),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        NetworkCmd::Proxy(NetworkProxyCmd::Configure {
            host,
            port,
            serial,
            arg,
            json_args,
        }) => {
            call_and_print(
                client,
                "configure_device_proxy",
                map_base(&[("host", json!(host)), ("port", json!(port))]),
                opts(serial, arg, json_args, json_out, true),
            )
            .await
        }
        NetworkCmd::Rule(NetworkRuleCmd::List { arg, json_args }) => {
            call_and_print(
                client,
                "list_network_mock_rules",
                Map::new(),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        NetworkCmd::Rule(NetworkRuleCmd::Upsert {
            id,
            name,
            url_pattern,
            method,
            status_code,
            response_body,
            enabled,
            arg,
            json_args,
        }) => {
            let mut base = Map::new();
            if let Some(v) = id {
                base.insert("id".into(), json!(v));
            }
            if let Some(v) = name {
                base.insert("name".into(), json!(v));
            }
            if let Some(v) = url_pattern {
                base.insert("urlPattern".into(), json!(v));
            }
            if let Some(v) = method {
                base.insert("method".into(), json!(v));
            }
            if let Some(v) = status_code {
                base.insert("statusCode".into(), json!(v));
            }
            if let Some(v) = response_body {
                base.insert("responseBody".into(), json!(v));
            }
            if let Some(v) = enabled {
                base.insert("enabled".into(), json!(v));
            }
            call_and_print(
                client,
                "upsert_network_mock_rule",
                base,
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        NetworkCmd::Rule(NetworkRuleCmd::Set { arg, json_args }) => {
            call_and_print(
                client,
                "set_network_mock_rules",
                Map::new(),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        NetworkCmd::Rule(NetworkRuleCmd::Delete { id, arg, json_args }) => {
            call_and_print(
                client,
                "delete_network_mock_rule",
                map_base(&[("id", json!(id))]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        NetworkCmd::Request(NetworkRequestCmd::List {
            limit,
            arg,
            json_args,
        }) => {
            let mut base = Map::new();
            if let Some(l) = limit {
                base.insert("limit".into(), json!(l));
            }
            call_and_print(
                client,
                "list_network_requests",
                base,
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        NetworkCmd::Request(NetworkRequestCmd::Get { id, arg, json_args }) => {
            call_and_print(
                client,
                "get_network_request",
                map_base(&[("id", json!(id))]),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
        NetworkCmd::Request(NetworkRequestCmd::Clear { arg, json_args }) => {
            call_and_print(
                client,
                "clear_network_requests",
                Map::new(),
                opts(None, arg, json_args, json_out, false),
            )
            .await
        }
    }
}
