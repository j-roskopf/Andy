# Lifecycle Log (start here)

One plugin that makes the CLI ↔ andyd ↔ GUI relationship visible.

Everything interesting is appended to:

```text
~/.andy/plugins/state/examples.lifecycle-log/activity.log
```

## Setup

```sh
# repo root; andyd (or Andy app) running
andy plugin link samples/plugins/lifecycle-log
andy plugin action invoke show-path --plugin examples.lifecycle-log
```

In Andy: **Settings → Plugins → Refresh** → you should see **Lifecycle Log**.

## Try each path

### 1. Startup (andyd / Andy boot)

Quit and reopen Andy (or restart andyd). A `kind:"startup"` line is written.

```sh
tail -n 5 ~/.andy/plugins/state/examples.lifecycle-log/activity.log
```

### 2. Action via CLI (andy → andyd → plugin process)

```sh
andy plugin action invoke ping --plugin examples.lifecycle-log
```

### 3. Action via GUI

Settings → Plugins → Lifecycle Log → **Run Ping (write to activity.log)**

### 4. Events from the GUI (UI → PluginService → plugin process)

With the plugin enabled:

- Switch projects → `workspace.focused`
- Switch Chat / Tasks / … → `tab.focused`
- Start or finish an agent chat → `pane.created` / `pane.agent_status_changed`

### 5. Pane in the Andy Terminal dock

Settings → Plugins → Lifecycle Log → **Open Lifecycle activity**

A dock tab tails `activity.log` live. Prefer opening panes from **Settings** (GUI)
so the PTY lands in the window’s Terminal dock.

## What each line means

| Field | Meaning |
| --- | --- |
| `kind` | `startup` / `event` / `action` |
| `event` | Host event name when `kind` is `event` |
| `workspaceId` / `tabId` / `paneId` | Context Andy injected via `ANDY_*` |
| `via` | Spawned under Andy (`andy-host`) |

## Architecture reminder

```text
CLI action  → andyd PluginService → node log.mjs
GUI action  → GUI PluginService  → node log.mjs
GUI UI event → emitEvent         → node log.mjs
GUI Open pane → startCommand + Terminal dock → node tail.mjs
```

Same `plugins.json` and state dir either way; different *who* spawned the process.
