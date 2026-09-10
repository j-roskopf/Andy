# Andy sample plugins

Executable workflow packages for Andy's plugin host. Each subdirectory is a
standalone plugin with an `andy-plugin.toml`.

## How CLI, andyd, and the Andy app fit together

```text
┌─────────────────┐     MCP over ~/.andy/andyd.sock      ┌──────────────────┐
│  andy CLI       │ ───────────────────────────────────► │  andyd (daemon)  │
│  plugin link    │                                      │  PluginService   │
│  plugin action  │                                      │  starts plugin   │
│  plugin pane    │                                      │  processes       │
└─────────────────┘                                      └────────┬─────────┘
                                                                  │
                         shared on disk                           │
              ~/.andy/plugins.json  (registry)                    │
              ~/.andy/plugins/config/<id>/                        │
              ~/.andy/plugins/state/<id>/                         │
                                                                  │
┌─────────────────┐     reads same registry / may also            │
│  Andy GUI app   │◄──── run its own PluginService ───────────────┘
│  Settings→Plugins│
│  Terminal docks │◄──── pane open → focusTerminalRun (GUI only)
│  project UI     │───── emitEvent(workspace/tab/pane/…)
└─────────────────┘
```

| Piece | Role |
| --- | --- |
| **andy CLI** | Thin client. Resolves paths in *your* cwd, talks to andyd over the socket. |
| **andyd** | Long-lived host. Owns the registry writes from CLI, runs startup/event/action commands when *it* receives them. |
| **Andy GUI** | Settings UI, Terminal docks, and most event *sources* (project switch, canvas tab, agent status). Opens panes into the dock. |
| **Plugin process** | Your Node/Bash/… script. No SDK — only `ANDY_*` env vars and exit code. |

**Mental model:** the plugin never “runs inside” Compose. Andy *spawns* it. Who spawns it depends on who handled the request (CLI→andyd vs Settings→GUI). The registry file is shared, so after `andy plugin link …` hit **Refresh** in Settings → Plugins.

**Seeing output**

| Kind | Where to look |
| --- | --- |
| `[[startup]]` / `[[events]]` / `[[actions]]` | Usually short-lived; check `andy plugin log list` or files under `~/.andy/plugins/state/<id>/` |
| `[[panes]]` | Andy **Terminal dock** (open from Settings → Plugins → Open …, while the app is running) |

## Samples

| Sample | Teaches |
| --- | --- |
| [`lifecycle-log`](lifecycle-log/) | **Start here.** One activity log for startup + workspace/tab/agent events + action + live tail pane |
| [`workspace-watch`](workspace-watch/) | Project create / focus / rename / close events only |
| [`agent-status-log`](agent-status-log/) | Agent wire statuses (`working` / `blocked` / `done` / `idle`) |
| [`webhook-notify`](webhook-notify/) | Same status event, but POST to `WEBHOOK_URL` |
| [`workspace-hello`](workspace-hello/) | Smallest startup + manual action |
| [`pane-echo`](pane-echo/) | Plugin-owned Terminal dock tabs |

```sh
# from the Andy repo root, with andyd running:
andy plugin link samples/plugins/lifecycle-log
andy plugin link samples/plugins/workspace-watch
andy plugin link samples/plugins/agent-status-log
andy plugin list
```

Then open Andy → **Settings → Plugins → Refresh**, and follow each sample’s README.

See [docs/PLUGINS.md](../../docs/PLUGINS.md) for the full authoring guide.
