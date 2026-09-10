# Andy plugins

Plugins are shareable, out-of-process workflow packages. A plugin is a directory
with `andy-plugin.toml` plus commands Andy can launch (Node, Bash, …). Andy owns
install, events, actions, and Terminal panes. Plugins own their language, deps,
and durable state.

There is no in-process SDK. The plugin API is the `andy` CLI / `andyd` socket /
MCP tools (`plugin.*`).

## Quick start

```sh
# from the Andy repo (relative paths are resolved by the CLI against your cwd)
andy plugin link samples/plugins/webhook-notify
andy plugin config-dir examples.webhook-notify
# put WEBHOOK_URL=… in that directory's .env

andy plugin action invoke test --plugin examples.webhook-notify
andy plugin list
```

Settings → **Plugins** lists the same registry (`~/.andy/plugins.json`). Use **Refresh**
after linking from the CLI, or paste an **absolute** path into Link (the app’s cwd is
inside Andy.app, so relative paths there will not resolve to the repo).

## Manifest (`andy-plugin.toml`)

Required: `id`, `name`, `version`, `min_andy_version`.

Optional sections:

| Section | Purpose |
| --- | --- |
| `[[build]]` | Install-time commands (`plugin install` only) |
| `[[startup]]` | One-shot after andyd is ready |
| `[[actions]]` | User / CLI / Settings commands |
| `[[events]]` | Host event → spawn command |
| `[[panes]]` | Plugin-owned Terminal dock |

Pane placements: `split`, `tab`, `overlay`, `zoomed`. (`popup` is not accepted.)

## Hook events

| Prefix | Andy meaning |
| --- | --- |
| `workspace.*` | project |
| `tab.*` | project UI tab (Chat, Tasks, …) |
| `pane.*` | agent chat / task |
| `worktree.*` | git worktree |

Hookable names (accepted in `[[events]]`):

`workspace.created`, `workspace.updated`, `workspace.closed`, `workspace.renamed`,
`workspace.moved`, `workspace.reordered`, `workspace.focused`,
`worktree.created`, `worktree.opened`, `worktree.removed`,
`tab.created`, `tab.closed`, `tab.renamed`, `tab.moved`, `tab.focused`,
`pane.created`, `pane.closed`, `pane.focused`, `pane.moved`, `pane.exited`,
`pane.agent_detected`, `pane.agent_status_changed`.

Emitted by Andy today:

| Event | Source |
| --- | --- |
| `workspace.focused` | project switch in the shell |
| `workspace.created` / `updated` / `renamed` / `closed` | project dialog save/delete |
| `tab.focused` | project canvas tab change (Chat, Tasks, …) |
| `pane.*` + `pane.agent_*` | agent chat lifecycle / status / focus |
| `worktree.created` / `opened` / `removed` | agent worktree attach/detach |

Reserved for later layouts (valid in manifests, not yet emitted):
`workspace.moved`, `workspace.reordered`, `tab.created` / `closed` / `renamed` / `moved`,
`pane.moved`.

Not hookable (high volume): `pane.updated`, `pane.output_changed`,
`layout.updated`, `workspace.metadata_updated`.

### Agent status on the wire

| Andy | Wire |
| --- | --- |
| Working | `working` |
| Blocked | `blocked` |
| Done (unseen) | `done` |
| Done (viewing) | `idle` |
| Error | `unknown` |

## Environment

| Var | Meaning |
| --- | --- |
| `ANDY_ENV` | `1` when under Andy |
| `ANDY_BIN_PATH` | path to `andy` |
| `ANDY_SOCKET_PATH` | andyd socket |
| `ANDY_PLUGIN_ID` | plugin id |
| `ANDY_PLUGIN_ROOT` | plugin directory |
| `ANDY_PLUGIN_CONFIG_DIR` | user config (`.env`) |
| `ANDY_PLUGIN_STATE_DIR` | durable state |
| `ANDY_PLUGIN_CONTEXT_JSON` | invocation context |
| `ANDY_PLUGIN_EVENT` / `ANDY_PLUGIN_EVENT_JSON` | event hooks |
| `ANDY_PLUGIN_ACTION_ID` | actions |
| `ANDY_PLUGIN_ENTRYPOINT_ID` | panes |
| `ANDY_WORKSPACE_ID` / `ANDY_TAB_ID` / `ANDY_PANE_ID` | ids when known |

## CLI

```text
andy plugin list
andy plugin link <path>
andy plugin unlink <id>
andy plugin install owner/repo[/subdir] [--ref] [--yes]
andy plugin uninstall <id|spec>
andy plugin enable|disable <id>
andy plugin config-dir <id>
andy plugin action list [--plugin ID]
andy plugin action invoke <action_id> [--plugin ID]
andy plugin log list [--plugin ID]
andy plugin pane open --plugin ID --entrypoint ID [--placement …]
andy plugin pane focus|close <pane_id>
```

## Trust

Plugins run as your user with full CLI/MCP access. Review manifests and scripts
before linking or installing. There is no sandbox in v1.

## Samples

See [samples/plugins/](../samples/plugins/) — start with **`lifecycle-log`**, which
has a walkthrough of CLI vs andyd vs the Andy GUI and writes everything to one
`activity.log` you can `tail -f`.

| Sample | What it shows |
| --- | --- |
| `lifecycle-log` | Startup + UI events + CLI/GUI actions + Terminal tail pane |
| `workspace-watch` | Project create/focus/rename/close |
| `agent-status-log` | Agent wire statuses without a webhook |
| `webhook-notify` | HTTP notify on done/blocked |
| `workspace-hello` | Minimal startup + action |
| `pane-echo` | Dock panes only |
