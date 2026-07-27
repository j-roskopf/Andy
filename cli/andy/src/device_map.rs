//! Curated CLI paths → MCP tool names for all device-side tools.
//!
//! Keep [`DEVICE_TOOL_NAMES`] in sync with
//! `DesktopMcpServerService.getToolNames()` (device entries only).
#![allow(dead_code)]

/// Canonical list of device MCP tools (excludes agent/project tools).
pub const DEVICE_TOOL_NAMES: &[&str] = &[
    "list_devices",
    "shell",
    "list_avds",
    "list_system_images",
    "create_avd",
    "clone_avd",
    "delete_avd",
    "start_emulator",
    "stop_emulator",
    "install_system_image",
    "tap",
    "swipe",
    "input_text",
    "press_key",
    "screenshot",
    "ui_dump",
    "list_apps",
    "launch_app",
    "stop_app",
    "clear_app_data",
    "uninstall_app",
    "install_app",
    "list_permissions",
    "list_activities",
    "send_intent",
    "file_list_dir",
    "file_pull",
    "file_push",
    "file_delete",
    "start_network_proxy",
    "list_network_mock_rules",
    "upsert_network_mock_rule",
    "set_network_mock_rules",
    "delete_network_mock_rule",
    "stop_network_proxy",
    "clear_network_requests",
    "list_network_requests",
    "get_network_request",
    "configure_device_proxy",
    "save_snapshot",
    "load_snapshot",
    "delete_snapshot",
    "list_snapshots",
    "logcat_snapshot",
];

/// Every curated CLI command path that maps to an MCP tool.
/// Paths use space-separated segments after `andy` (e.g. `device list`).
pub const CURATED_COMMANDS: &[(&str, &str)] = &[
    ("device list", "list_devices"),
    ("device shell", "shell"),
    ("device screenshot", "screenshot"),
    ("device ui-dump", "ui_dump"),
    ("device logcat", "logcat_snapshot"),
    ("emulator start", "start_emulator"),
    ("emulator stop", "stop_emulator"),
    ("avd list", "list_avds"),
    ("avd create", "create_avd"),
    ("avd clone", "clone_avd"),
    ("avd delete", "delete_avd"),
    ("system-image list", "list_system_images"),
    ("system-image install", "install_system_image"),
    ("snapshot list", "list_snapshots"),
    ("snapshot save", "save_snapshot"),
    ("snapshot load", "load_snapshot"),
    ("snapshot delete", "delete_snapshot"),
    ("input tap", "tap"),
    ("input swipe", "swipe"),
    ("input text", "input_text"),
    ("input key", "press_key"),
    ("app list", "list_apps"),
    ("app launch", "launch_app"),
    ("app stop", "stop_app"),
    ("app clear", "clear_app_data"),
    ("app uninstall", "uninstall_app"),
    ("app install", "install_app"),
    ("app permissions", "list_permissions"),
    ("app activities", "list_activities"),
    ("intent send", "send_intent"),
    ("file ls", "file_list_dir"),
    ("file pull", "file_pull"),
    ("file push", "file_push"),
    ("file delete", "file_delete"),
    ("network proxy start", "start_network_proxy"),
    ("network proxy stop", "stop_network_proxy"),
    ("network proxy configure", "configure_device_proxy"),
    ("network rule list", "list_network_mock_rules"),
    ("network rule upsert", "upsert_network_mock_rule"),
    ("network rule set", "set_network_mock_rules"),
    ("network rule delete", "delete_network_mock_rule"),
    ("network request list", "list_network_requests"),
    ("network request get", "get_network_request"),
    ("network request clear", "clear_network_requests"),
];

pub fn curated_mcp_names() -> Vec<&'static str> {
    let mut names: Vec<&'static str> = CURATED_COMMANDS.iter().map(|(_, mcp)| *mcp).collect();
    names.sort_unstable();
    names.dedup();
    names
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn curated_map_covers_all_device_tools() {
        let expected: HashSet<&str> = DEVICE_TOOL_NAMES.iter().copied().collect();
        let actual: HashSet<&str> = curated_mcp_names().into_iter().collect();
        let missing: Vec<_> = expected.difference(&actual).copied().collect();
        let extra: Vec<_> = actual.difference(&expected).copied().collect();
        assert!(
            missing.is_empty() && extra.is_empty(),
            "curated map drift — missing: {missing:?}, extra: {extra:?}"
        );
        assert_eq!(DEVICE_TOOL_NAMES.len(), 44);
        assert_eq!(CURATED_COMMANDS.len(), 44);
    }
}
