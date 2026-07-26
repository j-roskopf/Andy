# Andy daemon (`andyd`) + CLI

Andy’s center of gravity is a headless daemon that owns agent/project state and
spawns agent CLIs into a dedicated tmux server. The Compose GUI and the Rust CLI
are equal clients over a Unix domain socket.

## Dependencies

- **tmux** — required for agent sessions (`brew install tmux`)
- Optional: set `ANDY_TMUX=/path/to/tmux` if it is not on `PATH`

## Paths

| Path | Role |
|------|------|
| `~/.andy/andyd.sock` | MCP control plane (Unix domain socket) |
| `~/.andy/andyd.pid` | Daemon pid / lock file |
| `~/.andy/agents.db` | SQLDelight SQLite store for agent/project state |
| `~/.andy/bin/andy` | Rust CLI binary |
| `~/.andy/bin/andy-status-hook.sh` | Stable vendor-hook helper (desktop / andyd / installer) |
| `$PWD/.andy/active-task` | Gitignored pointer to the active task id for status hooks |
| `$PWD/.andy/<taskId>/` | Per-task artifacts (`status.json`, plan/review, …) |
| `tmux -L andy` | Dedicated Andy tmux server |
| `andy-task-<taskId>` | Per-task tmux session name |

## Run the daemon

```sh
./gradlew runAndyd
./gradlew killAndyd   # stop a running daemon (reads ~/.andy/andyd.pid)
```

This starts MCP on:

- Unix socket `~/.andy/andyd.sock` (CLI / GUI client mode)
- Loopback HTTP (default port from workspace settings, usually `8565`) so vendor
  agent CLIs can still attach Andy device tools

## Rust CLI

Install from the latest GitHub Release (macOS arm64 / Linux x86_64):

```sh
curl -fsSL https://github.com/j-roskopf/Andy/releases/latest/download/install-andy.sh | bash
export PATH="$HOME/.andy/bin:$PATH"   # once; add to shell rc if you want
```

The installer places `andy` and `andy-status-hook.sh` in `~/.andy/bin`. The desktop
app and `andyd` also install the status helper on startup so agent vendor hooks can
call a stable `"$HOME/.andy/bin/andy-status-hook.sh"` path; the active task is
selected via gitignored `.andy/active-task` in the project directory.

From a source checkout:

```sh
./gradlew installAndyCli
export PATH="$HOME/.andy/bin:$PATH"
```

Release assets also include the raw binaries:

- `andy-<version>-macos-arm64`
- `andy-<version>-linux-x86_64`
- `andy-<version>-windows-x86_64.exe`
- `install-andy.sh` / `andy-status-hook.sh` (stable names)

Windows: download the `.exe` from the release page (the curl installer is bash-only).

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
