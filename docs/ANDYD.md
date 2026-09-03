# Andy daemon (`andyd`) + CLI

Andy’s center of gravity is a headless daemon that owns agent/project state.
Terminal-lane agents (Antigravity, Hermes, OpenClaw, …) run in Andy-managed
tmux sessions; ACP-lane agents (Claude Code, Codex, Cursor, OpenCode, Pi, Goose) use a
structured protocol subprocess and a JSONL transcript. The Compose GUI and the
Rust CLI are equal clients over a Unix domain socket — `andy attach` routes to
tmux or the native ACP viewer by lane.

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
| `tmux -L andy-test[-wN]` | Isolated socket(s) used by `desktopTest` so tests cannot `kill-server` live chats; parallel forks append `-w<worker>` |
| `andy-task-<taskId>` | Per-task tmux session name |

Optional socket override: `ANDY_TMUX_SOCKET=name` (defaults to `andy`), or an absolute
path for `tmux -S` (used by `andy remote` / `--remote` for the forwarded remote server).

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

### Network Access (optional web client)

Off by default. In Settings → MCP → Network Access, enable “Allow access from
other devices on my network” to serve a small static web chat UI at `/`
(ACP-lane chats only). There are two modes, controlled by the "Tailscale only"
toggle:

**Tailscale only (default on):** the HTTP listener binds to `127.0.0.1` —
nothing is exposed on your LAN or Tailscale interface directly. The *only* way
to reach it from another device is through `tailscale serve`, which forwards
tailnet traffic to `127.0.0.1` on this host:

```sh
# with Network Access on and MCP port 8565 (or your configured port):
tailscale serve --bg 8565
# then open the https://…ts.net URL Tailscale prints (Andy access token still required)
```

Because `tailscale serve` requires the backend on loopback anyway, this mode
gets HTTPS for free — no separate step needed for Web Push or iOS
"Add to Home Screen". Run the `tailscale serve` command once per boot; Settings
shows a "Copy command" button.

**LAN (Tailscale-only off):** the HTTP listener binds to `0.0.0.0`, so any
device that can reach this Mac's IP may attempt access (still gated by the
token). This is a real TCP listener on your LAN — prefer Tailscale-only unless
you specifically need plain-network access without Tailscale. Andy does not
terminate TLS in this mode either; front it with `tailscale serve` (works the
same way) or your own reverse proxy (Caddy, nginx, …) pointing at
`127.0.0.1:<mcpServerPort>` if you need HTTPS.

**Auth:** When Network Access is on, the shared access token is required for
every non-public route — including loopback. That way Tailscale Serve (or any
localhost reverse proxy) cannot bypass the token. Use
`Authorization: Bearer …` (or `?token=` on WebSockets). Static assets at `/`
stay public so the login screen can load. When Network Access is off, loopback
stays open without a token so local vendor CLIs keep working.

Standalone `andyd` watches `~/.andy/workspace.properties` and rebinds when
these settings change; regenerating the token drops live WebSockets.

Turning Network Access on exposes the full MCP tool surface (not just chat) to
anyone who has the token.

This is additive to SSH + `andy tui` / `andy attach`.

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
andy attach <taskId>           # ACP: native viewer · Terminal: tmux (or quiet reattach)
andy tui                       # n new chat · grouped projects · a / Enter attach
andy chat resume <taskId> "…"  # when quiet reattach isn't possible
andy remote user@mac.local     # subshell tunneled to remote andyd (exit to disconnect)
andy --remote user@mac.local chat list   # one-shot SSH tunnel
```

`andy remote` mirrors the GUI Host switcher: it forwards `~/.andy/andyd.sock` and the
remote `tmux -L andy` server over SSH, sets `ANDY_SOCKET` / `ANDY_TMUX_SOCKET` /
`ANDY_REMOTE` in a child shell, then tears the tunnel down when that shell exits.
The remote host must already be running `andyd`.

### Remote access from mobile (SSH + Tailscale)

Andy's CLI only runs on macOS/Linux, so to drive chats from a phone you SSH
into a Mac or Linux box that already has `andy`/`andyd` installed, then run
`andy` commands inside that SSH session. The phone itself doesn't need the
CLI installed.

**1. Keep `andyd` running on the host, even when the GUI is closed.**

The embedded daemon (default GUI mode) only binds the socket while the
Andy.app window is open — see [GUI modes](#gui-modes). For phone access you
want a standalone daemon that survives GUI closes/logouts: set up
[launchd](#launchd-macos) on macOS, a systemd/user service on Linux, or leave
`./gradlew runAndyd` running in a terminal.

**2. Enable SSH on the host.**

- macOS: System Settings → General → Sharing → turn on **Remote Login**. It
  shows the username/address to connect to (`ssh user@host-address`).
- Linux: install/enable `sshd` (e.g. `sudo systemctl enable --now ssh`).

Verify from another machine on the same LAN (`ssh user@host`) before dealing
with remote networking.

**3. Put the phone and host on the same network with Tailscale** (skip this
if they're already reachable — same LAN, existing VPN, port-forwarded SSH,
etc.). Mobile networks and most home NATs block inbound SSH, so an overlay
network is the easiest fix:

- Install [Tailscale](https://tailscale.com/download) on the host (macOS/Linux)
  and sign in to your tailnet.
- Install the Tailscale app on the phone (App Store / Play Store), sign in
  with the same account, and turn the VPN toggle on before connecting.
- On the host, run `tailscale ip -4` (or check the Tailscale menu-bar item)
  for its `100.x.y.z` tailnet IP, or use its MagicDNS name
  (`hostname.tailnet-name.ts.net`).

**4. SSH in from the phone.**

Any SSH client app works:

- iOS: [Termius](https://termius.com/), [Blink Shell](https://blink.sh), or
  [Prompt](https://apps.apple.com/app/prompt-3/id1594420480).
- Android: [Termius](https://termius.com/), [JuiceSSH](https://juicessh.com/),
  or [Termux](https://termux.dev/) (`pkg install openssh && ssh user@host`).

Connect to `user@<tailscale-ip-or-magicdns-name>`. Save the connection with
key-based auth if the app supports it — typing a passphrase on a phone
keyboard every time gets old fast. Since chats live in tmux, pick an app with
an extra key row for **Ctrl**, **Esc**, and arrow keys (Termius and Blink both
have one) — you'll need them for tmux detach and TUI navigation.

**5. Run the CLI once connected.** You're in a normal shell on the host now —
use `andy` exactly as documented above:

```sh
andy chat list
andy chat start --agent ClaudeCode --directory ~/project "…"
andy tui
```

**6. Attach to an existing chat.**

```sh
andy attach <taskId>
```

This opens the lane-appropriate viewer: the native ACP chat UI for Claude Code /
Codex / Cursor / OpenCode / Pi / Goose, or a live tmux pane for Terminal-lane agents
(with quiet provider reattach when needed, same as GUI reattach). `andy tui`
lists chats grouped by project — press **Enter** / **a** to attach without
remembering a task id.

**7. Detach without stopping the agent.** For Terminal/tmux: press **F12**,
**Alt+d**, or the usual tmux **Ctrl-b** then **d**. For the ACP viewer: press
**Esc** / **q** / **Ctrl-C**. The agent keeps running on the host; reconnect
later — even from a different phone or SSH session — with `andy attach <taskId>`.
The ACP viewer defaults to conversation-only (user/assistant); press **v** for
tools/commands/raw/usage detail, or set `ANDY_ACP_VIEW_DETAILS=1`.

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

`andy attach` / TUI attach resolves the task `lane` first:

- **Acp** — opens the native CLI viewer (history via `chat.events` / live
  updates via `chat.subscribe`). Requires a daemon that advertises
  `chat.subscribe`; older andyd builds hard-fail with an upgrade message.
- **Terminal** — checks for a live `tmux -L andy` session. If the chat has
  ended but Andy can reopen the provider CLI (same as GUI reattach), it calls
  `chat.reattach`, waits for tmux, then attaches. Otherwise it tells you to use
  `andy chat resume`.

Dev loop without installing: `cargo run --manifest-path cli/andy/Cargo.toml -- chat list`

CLI tests (unit + mock-MCP integration): `cargo test --manifest-path cli/andy/Cargo.toml`

Terminal-lane live view is always `tmux -L andy attach -t andy-task-<id>` — MCP
never streams PTY bytes. `andy attach` wraps that session with the same
header/status/hotkey chrome as the ACP viewer (session-local tmux status line +
banner), then hands the TTY to tmux. From the TUI or `andy attach` on a Terminal
chat, press **F12**, **Alt+d**, or the usual tmux **Ctrl-b** then **d** to
detach back to the chat list without stopping the agent. ACP chats use
Esc/q/Ctrl-C for the same “detach, keep running” behavior.

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

1. **EmbeddedDaemon** (default) — agents run in-process with BossTerm + tmux attach.
   Fully self-contained; also binds `~/.andy/andyd.sock` for the CLI when no external
   daemon owns it.
2. **DaemonClient** — only when a **standalone** `andyd` is already running (pidfile +
   live socket), e.g. launchd or `./gradlew runAndyd`. The GUI attaches BossTerm viewers
   to tmux sessions owned by that process.

The GUI does **not** spawn `andyd` and switch to client mode — that split left chats
finishing in headless tmux before a terminal viewer could attach. Use `runAndyd` or
launchd when you want a persistent background daemon for the CLI while the GUI is closed.

Stale `andyd.sock` / `andyd.pid` files left after a crash are removed automatically.

## MCP tools (agents)

In addition to the existing device tools:

- `chat.list` / `chat.events` / `chat.composer_options` / `chat.refresh_providers` / `chat.start` / `chat.stop` / `chat.resume` / `chat.respond` / `chat.set_mode`
- `chat.status` / `chat.attach_command` / `chat.reattach`
- `project.list`
- `workflow.run_spec` / `workflow.start_build`
- `automation.list` / `automation.get` / `automation.create` / `automation.update` / `automation.pause` / `automation.resume` / `automation.delete` / `automation.run`

`automation.create` starts **paused**; `automation.resume` arms the schedule. Fires run while `andyd` is up.

## Terminal modes

| Env `ANDY_TERMINAL_MODE` | Behavior |
|--------------------------|----------|
| (default) | `TmuxWithAttach` — create tmux session + BossTerm attach |
| `headless` | `TmuxHeadless` — daemon executor, no Swing |
| `direct` | Legacy direct Pty4J (tests / no-tmux fallback) |

## Agent transport lanes

Claude Code, Codex, Cursor, OpenCode, Pi, and Goose default to the ACP stdio lane. ACP
sessions use the official Kotlin ACP client, persist structured JSONL transcripts
under `~/.andy/agents/<task-id>/transcript.jsonl`, and keep an ACP session id that
Andy also mirrors into `vendorSessionId` when possible so **External** can open a
native CLI `--resume` of the same conversation. The GUI renders ACP events with the
structured transcript surface; the CLI uses the same event stream via
`chat.subscribe` in `andy attach`. Both can continue a stored session after restart.

Antigravity, Hermes, and OpenClaw remain on the terminal/tmux lane (`andy attach`
→ `tmux -L andy`). If ACP spawn or initialization fails for an ACP-capable
provider, the task ends in error and stays on the ACP lane — Andy does not demote
it to terminal/tmux. `ANDY_AGENT_LANE=terminal|acp` (or
`ANDY_AGENT_LANE_<AGENT_KIND>`) remains a test/rollout override only.
