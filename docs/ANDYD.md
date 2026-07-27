# Andy daemon (`andyd`) + CLI

Andy’s center of gravity is a headless daemon that owns agent/project state and
spawns agent CLIs into Andy-managed tmux sessions. The Compose GUI and the Rust
CLI are equal clients over a Unix domain socket.

The CLI is **macOS and Linux only** (not Windows). It auto-starts `andyd` when
the socket is not already live.

## Bundled tmux

Andy installs a managed tmux binary at `~/.andy/bin/tmux` (via
`install-andy.sh`, like bundled `scrcpy-server` for mirroring). `TmuxAndy`
prefers that path before `ANDY_TMUX` or `PATH`. You do not need `brew install
tmux` for agent sessions.

Optional override: `ANDY_TMUX=/path/to/tmux`

## Paths

| Path | Role |
|------|------|
| `~/.andy/andyd.sock` | MCP control plane (Unix domain socket) |
| `~/.andy/andyd.pid` | Daemon pid / lock file |
| `~/.andy/agents.db` | SQLDelight SQLite store for agent/project state |
| `~/.andy/bin/andy` | Rust CLI binary |
| `~/.andy/bin/andyd` | Daemon launcher script |
| `~/.andy/andyd/andyd.jar` | Headless daemon fat JAR |
| `~/.andy/bin/tmux` | Andy-managed tmux for agent sessions |
| `~/.andy/bin/andy-status-hook.sh` | Stable vendor-hook helper (desktop / andyd / installer) |
| `$PWD/.andy/active-task` | Gitignored pointer to the active task id for status hooks |
| `$PWD/.andy/<taskId>/` | Per-task artifacts (`status.json`, plan/review, …) |
| `tmux -L andy` | Dedicated Andy tmux server (live agents / GUI / andyd) |
| `tmux -L andy-test` | Isolated socket used by `desktopTest` so tests cannot `kill-server` live chats |
| `andy-task-<taskId>` | Per-task tmux session name |

Optional socket override: `ANDY_TMUX_SOCKET=name` (defaults to `andy`).

## Run the daemon

`andy` starts `andyd` automatically. For manual control:

```sh
./gradlew runAndyd
./gradlew killAndyd   # stop a running daemon (reads ~/.andy/andyd.pid)
```

Or after `install-andy.sh` / `./gradlew installAndyd`:

```sh
~/.andy/bin/andyd
```

This starts MCP on:

- Unix socket `~/.andy/andyd.sock` (CLI / GUI client mode)
- Loopback HTTP (default port from workspace settings, usually `8565`) so vendor
  agent CLIs can still attach Andy device tools

## Rust CLI

Install from the latest GitHub Release (macOS arm64 / Linux x86_64):

```sh
curl -fsSL https://github.com/j-roskopf/Andy/releases/latest/download/install-andy.sh | bash

# Permanently add ~/.andy/bin to your PATH (pick your shell):
echo 'export PATH="$HOME/.andy/bin:$PATH"' >> ~/.zshrc   # zsh
# echo 'export PATH="$HOME/.andy/bin:$PATH"' >> ~/.bashrc  # bash
```

Restart your shell (or `source` the rc file you edited) so `andy` is on your `PATH`.

The installer places `andy`, `andyd`, `andyd.jar`, bundled `tmux`, and
`andy-status-hook.sh` under `~/.andy`. Requires Java 21+.

From a source checkout:

```sh
./gradlew installAndyCli installAndyd
echo 'export PATH="$HOME/.andy/bin:$PATH"' >> ~/.zshrc   # or ~/.bashrc
```

Release assets:

- `andy-<version>-macos-arm64` / `andy-<version>-linux-x86_64`
- `andyd-<version>-<target>.jar`
- `tmux-<version>-<target>`
- `install-andy.sh` / `andy-status-hook.sh`

Then:

```sh
andy chat list                 # grouped by project (Inbox / projectId)
andy chat list --json          # raw MCP payload
andy chat start --agent ClaudeCode --directory "$PWD" "Reply with pong"
andy chat start --no-attach --agent ClaudeCode "fire and forget JSON only"
andy attach <taskId>           # live tmux, or quiet provider reattach then attach
andy tui                       # n new chat · grouped projects · a / Enter attach
andy chat resume <taskId> "…"  # when quiet reattach isn't possible
andy --remote user@mac.local chat list   # SSH tunnel the socket
```

### Device / network scripting

The CLI also wraps every device-side MCP tool as noun-verb commands (plus a
generic escape hatch). Output is human-readable by default; pass global
`--json` for the raw MCP payload. Target a device with `--serial` on the
command or `ANDY_SERIAL` in the environment. Destructive commands never prompt.

```sh
andy device list
andy emulator start Pixel_7 --wait --timeout 180
andy network proxy start 8080
andy network rule upsert --url-pattern '*/api/*' --status-code 500 --name boom
andy device screenshot -o /tmp/screen.png --serial emulator-5554
andy tool list                              # all MCP tools (device + agent)
andy tool call chat.archive --arg taskId=…  # agent/workflow tools: escape hatch only
```

Curated groups: `device`, `emulator`, `avd`, `system-image`, `snapshot`,
`input`, `app`, `intent`, `file`, `network`. Extra tool args:
`--arg key=value` (JSON literals coerced) and `--json-args '{…}'`.

`andy attach` / TUI attach first checks for a live `tmux -L andy` session. If the
chat has ended but Andy can reopen the provider CLI (same as GUI reattach), it
calls `chat.reattach`, waits for tmux, then attaches. Otherwise it tells you to
use `andy chat resume`.

Dev loop without installing: `cargo run --manifest-path cli/andy/Cargo.toml -- chat list`

Live terminal view is always `tmux -L andy attach -t andy-task-<id>` — MCP never
streams PTY bytes. From the TUI or `andy attach`, press **F12**, **Alt+d**, or the
usual tmux **Ctrl-b** then **d** to detach back to the chat list without stopping the agent.

## launchd (macOS)

1. Install `andyd` next to the Andy.app binary (or point the plist at
   `./gradlew runAndyd` / a packaged `andyd` launcher).
2. Copy [`packaging/macos/com.joetr.andyd.plist`](../packaging/macos/com.joetr.andyd.plist)
   into `~/Library/LaunchAgents/`, replace `REPLACE_ME` with your username, then:

```sh
launchctl load ~/Library/LaunchAgents/com.joetr.andyd.plist
```

## GUI modes

On launch the GUI calls `resolveRuntimeMode()`:

1. **EmbeddedDaemon** (default) — agents run in-process with KetraTerm + tmux attach.
   Fully self-contained; also binds `~/.andy/andyd.sock` for the CLI when no external
   daemon owns it.
2. **DaemonClient** — only when a **standalone** `andyd` is already running (pidfile +
   live socket), e.g. launchd or `./gradlew runAndyd`. The GUI attaches KetraTerm viewers
   to tmux sessions owned by that process.

The GUI does **not** spawn `andyd` and switch to client mode — that split left chats
finishing in headless tmux before a terminal viewer could attach. Use `runAndyd` or
launchd when you want a persistent background daemon for the CLI while the GUI is closed.

Stale `andyd.sock` / `andyd.pid` files left after a crash are removed automatically.

## MCP tools (agents)

In addition to the existing device tools:

- `chat.list` / `chat.composer_options` / `chat.start` / `chat.stop` / `chat.resume` / `chat.respond`
- `chat.status` / `chat.attach_command` / `chat.reattach`
- `project.list`
- `workflow.run_spec` / `workflow.start_build`

## Terminal modes

| Env `ANDY_TERMINAL_MODE` | Behavior |
|--------------------------|----------|
| (default) | `TmuxWithAttach` — create tmux session + KetraTerm attach |
| `headless` | `TmuxHeadless` — daemon executor, no Swing |
| `direct` | Legacy direct Pty4J (tests / no-tmux fallback) |
