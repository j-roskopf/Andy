# Workspace Watch

Proves that **GUI project UI** drives plugin events.

| You do in Andy | Event written to `watch.log` |
| --- | --- |
| New project | `workspace.created` |
| Switch project in the sidebar | `workspace.focused` |
| Edit project (same name) | `workspace.updated` |
| Rename project | `workspace.renamed` (+ updated) |
| Delete project | `workspace.closed` |

## Setup

```sh
andy plugin link samples/plugins/workspace-watch
```

Settings → Plugins → Refresh → **Open Workspace watch** (optional live tail).

Or in a shell:

```sh
tail -f ~/.andy/plugins/state/examples.workspace-watch/watch.log
```

## Dump via CLI

```sh
andy plugin action invoke dump --plugin examples.workspace-watch
```

## Why this sample exists

`workspace.*` events are emitted by the **Andy GUI** when you manipulate projects.
The CLI does not create those events by itself — it can only *invoke actions* or
*open panes*. Linking is enough; then use the app.
