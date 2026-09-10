# Agent Status Log

Shows how Andy maps agent attention onto the plugin wire:

| Andy UI | Wire `agent_status` |
| --- | --- |
| Working | `working` |
| Blocked | `blocked` |
| Done (you haven’t looked) | `done` |
| Done (you’re viewing the chat) | `idle` |
| Error | `unknown` |

## Setup

```sh
andy plugin link samples/plugins/agent-status-log
```

Settings → Plugins → **Open Agent status**, then start/stop an agent chat.

```sh
tail -f ~/.andy/plugins/state/examples.agent-status-log/status.log
# or
andy plugin action invoke dump --plugin examples.agent-status-log
```

## vs webhook-notify

Same event (`pane.agent_status_changed`). This sample only logs; webhook-notify
POSTs when status is `done` or `blocked`. Use this one to learn the wire values
before wiring a real webhook.
