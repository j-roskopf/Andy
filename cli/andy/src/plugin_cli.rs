use crate::dispatch::{call_and_print, CallOpts};
use crate::mcp::McpClient;
use anyhow::{bail, Result};
use clap::Subcommand;
use serde_json::{json, Map};
use std::path::{Path, PathBuf};

#[derive(Subcommand, Debug)]
pub enum PluginCmd {
    /// List installed plugins
    List,
    /// Link a local plugin directory
    Link {
        path: PathBuf,
        #[arg(long)]
        disabled: bool,
    },
    /// Unlink a local plugin
    Unlink { id: String },
    /// Install from GitHub owner/repo[/subdir]
    Install {
        spec: String,
        #[arg(long)]
        r#ref: Option<String>,
        #[arg(long)]
        yes: bool,
    },
    /// Uninstall by id or GitHub spec
    Uninstall { id: String },
    /// Enable a plugin
    Enable { id: String },
    /// Disable a plugin
    Disable { id: String },
    /// Print plugin config directory
    #[command(name = "config-dir")]
    ConfigDir { id: String },
    /// List or invoke actions
    #[command(subcommand)]
    Action(PluginActionCmd),
    /// Inspect plugin command logs
    Log {
        #[arg(long)]
        plugin: Option<String>,
        #[arg(long, default_value_t = 20)]
        limit: u32,
    },
    /// Manage plugin panes
    #[command(subcommand)]
    Pane(PluginPaneCmd),
}

#[derive(Subcommand, Debug)]
pub enum PluginActionCmd {
    List {
        #[arg(long)]
        plugin: Option<String>,
    },
    Invoke {
        action_id: String,
        #[arg(long)]
        plugin: Option<String>,
    },
}

#[derive(Subcommand, Debug)]
pub enum PluginPaneCmd {
    Open {
        #[arg(long)]
        plugin: String,
        #[arg(long)]
        entrypoint: String,
        #[arg(long)]
        placement: Option<String>,
    },
    Focus { pane_id: String },
    Close { pane_id: String },
}

pub async fn run(client: &mut McpClient, cmd: PluginCmd, json_out: bool) -> Result<()> {
    match cmd {
        PluginCmd::List => {
            call_and_print(client, "plugin.list", Map::new(), CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Link { path, disabled } => {
            // Resolve against the CLI process cwd. andyd's cwd is inside Andy.app, so
            // relative paths like samples/plugins/... must be absolutized here.
            let absolute = resolve_plugin_path(&path)?;
            let mut args = Map::new();
            args.insert("path".into(), json!(absolute.display().to_string()));
            args.insert("enabled".into(), json!(!disabled));
            call_and_print(client, "plugin.link", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Unlink { id } => {
            let mut args = Map::new();
            args.insert("id".into(), json!(id));
            call_and_print(client, "plugin.unlink", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Install { spec, r#ref, yes } => {
            let mut args = Map::new();
            args.insert("spec".into(), json!(spec));
            if let Some(r) = r#ref {
                args.insert("ref".into(), json!(r));
            }
            args.insert("yes".into(), json!(yes));
            call_and_print(client, "plugin.install", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Uninstall { id } => {
            let mut args = Map::new();
            args.insert("id".into(), json!(id));
            call_and_print(client, "plugin.uninstall", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Enable { id } => {
            let mut args = Map::new();
            args.insert("id".into(), json!(id));
            call_and_print(client, "plugin.enable", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Disable { id } => {
            let mut args = Map::new();
            args.insert("id".into(), json!(id));
            call_and_print(client, "plugin.disable", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::ConfigDir { id } => {
            let mut args = Map::new();
            args.insert("id".into(), json!(id));
            call_and_print(client, "plugin.config_dir", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Action(PluginActionCmd::List { plugin }) => {
            let mut args = Map::new();
            if let Some(p) = plugin {
                args.insert("pluginId".into(), json!(p));
            }
            call_and_print(client, "plugin.action.list", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Action(PluginActionCmd::Invoke { action_id, plugin }) => {
            let mut args = Map::new();
            args.insert("actionId".into(), json!(action_id));
            if let Some(p) = plugin {
                args.insert("pluginId".into(), json!(p));
            }
            call_and_print(client, "plugin.action.invoke", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Log { plugin, limit } => {
            let mut args = Map::new();
            if let Some(p) = plugin {
                args.insert("pluginId".into(), json!(p));
            }
            args.insert("limit".into(), json!(limit.to_string()));
            call_and_print(client, "plugin.log.list", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Pane(PluginPaneCmd::Open { plugin, entrypoint, placement }) => {
            let mut args = Map::new();
            args.insert("pluginId".into(), json!(plugin));
            args.insert("entrypoint".into(), json!(entrypoint));
            if let Some(p) = placement {
                args.insert("placement".into(), json!(p));
            }
            call_and_print(client, "plugin.pane.open", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Pane(PluginPaneCmd::Focus { pane_id }) => {
            let mut args = Map::new();
            args.insert("paneId".into(), json!(pane_id));
            call_and_print(client, "plugin.pane.focus", args, CallOpts { json_out, ..Default::default() }).await
        }
        PluginCmd::Pane(PluginPaneCmd::Close { pane_id }) => {
            let mut args = Map::new();
            args.insert("paneId".into(), json!(pane_id));
            call_and_print(client, "plugin.pane.close", args, CallOpts { json_out, ..Default::default() }).await
        }
    }
}

/// Absolutize [path] against the CLI cwd and verify it exists (dir or andy-plugin.toml).
fn resolve_plugin_path(path: &Path) -> Result<PathBuf> {
    let joined = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|cwd| cwd.join(path))
            .unwrap_or_else(|_| path.to_path_buf())
    };
    let absolute = joined.canonicalize().unwrap_or(joined);
    let manifest = if absolute.is_file() {
        absolute.clone()
    } else {
        absolute.join("andy-plugin.toml")
    };
    if !manifest.is_file() {
        bail!(
            "plugin_manifest_not_found: {} (resolved from {})",
            manifest.display(),
            path.display()
        );
    }
    Ok(if absolute.is_file() {
        absolute
            .parent()
            .map(|p| p.to_path_buf())
            .unwrap_or(absolute)
    } else {
        absolute
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn resolve_plugin_path_relative_to_cwd() {
        let dir = tempfile::tempdir().unwrap();
        let plugin = dir.path().join("my-plugin");
        fs::create_dir_all(&plugin).unwrap();
        fs::write(plugin.join("andy-plugin.toml"), "id = \"x\"\n").unwrap();
        let prev = std::env::current_dir().unwrap();
        std::env::set_current_dir(dir.path()).unwrap();
        let resolved = resolve_plugin_path(Path::new("my-plugin")).unwrap();
        std::env::set_current_dir(prev).unwrap();
        assert_eq!(resolved, plugin.canonicalize().unwrap());
    }
}
