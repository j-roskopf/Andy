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
| `~/.andy/agents.db` | SQLDelight SQLite store (imported from `agents.toml`) |
| `~/.andy/agents.toml` | Legacy TOML (mirrored / one-time migrated) |
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

Install a local release binary to `~/.andy/bin/andy`:

```sh
./gradlew installAndyCli
export PATH="$HOME/.andy/bin:$PATH"   # once; add to shell rc if you want
```

GitHub Releases also publish prebuilt CLI binaries:

- `andy-<version>-macos-arm64`
- `andy-<version>-linux-x86_64`
- `andy-<version>-windows-x86_64.exe`

Download, `chmod +x`, and put on your `PATH` (or replace `~/.andy/bin/andy`).

Then:

```sh
andy chat list                 # grouped by project (Inbox / projectId)
andy chat list --json          # raw MCP payload
andy chat start --agent ClaudeCode --directory "$PWD" "Reply with pong"
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
streams PTY bytes.

## launchd (macOS)

1. Install `andyd` next to the Andy.app binary (or point the plist at
   `./gradlew runAndyd` / a packaged `andyd` launcher).
2. Copy [`packaging/macos/com.joetr.andyd.plist`](../packaging/macos/com.joetr.andyd.plist)
   into `~/Library/LaunchAgents/`, replace `REPLACE_ME` with your username, then:

```sh
launchctl load ~/Library/LaunchAgents/com.joetr.andyd.plist
```

## GUI modes

`createDesktopRuntime()` detects the socket:

- **DaemonClient** — GUI talks to `andyd` via MCP; KetraTerm attaches to tmux
- **EmbeddedDaemon** — in-process fallback when no socket is present (also
  publishes `andyd.sock` so the CLI can attach)

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
