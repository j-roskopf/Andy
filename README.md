<p align="center">
  <img src="src/desktopMain/resources/icons/andy.png" alt="Andy logo" width="128">
</p>

# Andy

[![Featured in Android Weekly Issue #736](https://img.shields.io/badge/Featured%20in-Android%20Weekly%20Issue%20%23736-blue?logo=android&logoColor=white)](https://androidweekly.net/issues/issue-736)
[![As Seen In - jetc.dev Newsletter Issue #323](https://img.shields.io/badge/As_Seen_In-jetc.dev_Newsletter_Issue_%23323-blue?logo=Jetpack+Compose&logoColor=white)](https://jetc.dev/issues/323.html)
[![Featured in Kotlin Weekly Issue #521](https://img.shields.io/badge/Featured%20in-Kotlin%20Weekly%20Issue%20%23521-blue?logo=kotlin&logoColor=white)](https://mailchi.mp/kotlinweekly/kotlin-weekly-521)


Andy is a desktop helper for Android, Kotlin, and Compose Multiplatform
developers. Use it to manage devices and emulators, manage projects with AI, mirror screens, inspect
apps, and drive day-to-day mobile workflows from one place. The desktop app is
the recommended experience and includes the full feature set. A smaller subset
of Andy is also available on the web at
[andy.joetr.com](https://andy.joetr.com).

**iOS support** is available on macOS and covers most day-to-day Simulator
workflows: create/boot/clone/erase/rename/delete simulators, stream a live
mirror with touch input, browse apps and their sandboxed files/Prefs/SQLite
databases, tail `simctl log stream`, drive URL-scheme intents, capture bugs,
and reach Simulator-only controls (appearance, Dynamic Type incl. a sweep
board, status bar studio + screenshot, location, a privacy grant/revoke/reset
matrix, clipboard, and a push-notification workbench). Crash reports
(`.ips`) are listed from `~/Library/Logs/DiagnosticReports` with best-effort
`atos` symbolication when a matching `.dSYM` can be found via Spotlight.

**Physical iOS devices are view-only.** Andy can mirror a physical iPhone/iPad
over USB once you trust the computer on-device — no Developer Mode required
for that. Everything else (touch input, Apps, Files, Logcat, Controls) needs
Developer Mode enabled on the device (Settings → Privacy & Security →
Developer Mode, then restart); Andy shows a banner on Live explaining this
when it detects a physical target. Android remains the primary platform, and
on-device Accessibility inspection for iOS is not implemented yet.

In my own words as the author: I find myself in Android Studio less these days, but still want some of the tooling offered in Android Studio in a lower performance overhead option. 

## Download

[Download the latest release](https://github.com/j-roskopf/Andy/releases/latest)

## Features

### Devices

Discover connected Android devices and created emulators in one place. Search and filter by device type or API level, start emulators, jump into a live session, and stop running emulators without leaving Andy. Pair physical devices over Wi‑Fi from a QR/pairing dialog, then reconnect or forget saved pairs without re-plugging USB.

Opt into multi-select to fan out Install, Uninstall, Clear data, Launch, Stop, emulator start/stop, Controls toggles, and Screenshot across a primary device plus fan-out targets (bounded concurrency). Save device groups, labels, and notes so serials stay readable across many emulators.

On macOS, the Devices screen also lists iOS Simulators for basic management: boot, shut down, open in Simulator.app, and jump into Live.

### Remote Sessions

Connect Andy Desktop to another Mac or Linux host over SSH (sidebar host switcher). Andy tunnels the remote `andyd` socket and tmux sessions, routes ADB through SSH for mirroring and device tools, and swaps the agent backend to the remote machine — credentials go through the system SSH askpass; Andy does not store secrets. Saved hosts reconnect with one click; remote mirror tuning presets help over high-latency links. While connected, local-only panes (Catalog, Computer Files, Network, Snapshots, Performance, Tracing, Design, Inspector, Bugs, Recordings) stay hidden. Desktop only.

### Virtual Device Creation

Create new Android Virtual Devices from SDK profiles and system images. Andy can install the selected image, configure orientation, RAM, storage, CPU, GPU, locale, cameras, hardware keyboard, and optionally launch the emulator after creation.

### System Image Catalog

Browse installed and available Android emulator system images. Filter by API, variant, or ABI, download missing images, and remove unused installed images when no AVD depends on them.

### Snapshots

Save, restore, and delete emulator snapshots for any created AVD. This makes it quick to return a test device to a known state before reproducing bugs or validating flows.

### Live Mirror

Stream a selected Android device or emulator into Andy with an embedded H.264 mirror. Send touch, keyboard, navigation, power, volume, rotation, screenshot, and text input commands directly from the desktop UI. Record from the Live toolbar into Andy's Recordings library (trim + GIF/WebP/MP4/PNG-sequence export), and annotate screenshots with redaction, shapes, text, and an optional device frame before saving. Drag an APK onto the mirror to install it, pull clipboard text from the device, and tune max size, bitrate, FPS, and renderer mode (accelerated vs legacy) from the Live side panel. Foldable AVDs can switch hinge posture from Live or Controls.

Grid mode mirrors up to four batch targets at once (lower resolution/FPS), with optional synchronized input scaled per device. Bug capture, network proxy, and Perfetto tracing remain single-target.

Dock Live, Logcat, a project Terminal, or an embedded Browser beside or below the main content when you want the mirror and another workspace open at once. Terminal docks support recursive row/column splits. The Browser pane (macOS WKWebView) loads URLs with back/forward/refresh, element inspection, and annotation — select a DOM node, add a comment, and drop the snapshot into the active chat composer. A Local Servers flyout in the top chrome scans localhost listeners, opens them in the Browser dock, and can stop the process. On macOS, Andy can also mirror a booted iOS Simulator with touch input. Open the simulator in Simulator.app when you want the system UI, then return to Andy's embedded mirror when you are done.

### Recordings

Browse saved screen recordings independently of bug reports. Reveal in Finder/Explorer, copy the file path, export trimmed clips (GIF/WebP/MP4/PNG sequence) with a size estimate, and surface any capture warnings when remux falls back.

### Android Auto (DHU)

Launch Google's Desktop Head Unit beside Live when the Android Auto DHU toolchain is installed. Andy checks readiness, starts/stops the DHU session, and shows console output — the DHU runs in its own window.

### Pop-Out Mirror

Open the device mirror in a separate focused window. The pop-out keeps the same input and hardware controls available when you want to watch or drive the device beside the main workspace.

### Apps

Inspect installed packages on the selected device. Launch, stop, clear data, reset permissions, uninstall user apps, and review declared permissions and activities from a split app/details view.

### Logcat

Stream device logs with live pause, clear, search, package filtering, and per-level toggles. The main Logcat screen includes resizable columns, while Live keeps a compact log panel next to the mirror.

Andy can group stack traces and deobfuscate them with a built-in R8/ProGuard `mapping.txt` parser (auto-discovered from the project directory, or pinned in Settings). A Crashes tab lists dropbox crashes and ANRs and can load/export entries for the same retrace path.

### Intents

Build and send Android activity, deep link, service, and broadcast intents. Andy shows the generated `am` command before sending it so you can verify the exact action, component, and data URI.

### Files & Data

Browse device file paths such as `/sdcard`, `/data/local/tmp`, and `/storage/emulated/0`. Navigate directories, inspect mode, size, and modified timestamps, and use the file service for pull, push, and delete workflows.

### Shared Preferences

Inspect `shared_prefs` XML for a selected debuggable package. Browse files and typed keys, edit values in place, and add or delete entries without dropping to `adb shell`.

### App Databases

Browse SQLite databases for a debuggable app. List databases and tables with row counts, inspect and edit cells, run or save SQL queries, and pull a database copy to the host when you need offline analysis.

### Network

Run a debug-app HTTPS proxy backed by mitmproxy. Start and stop capture, configure device proxy routing, install the local CA, inspect request and response headers or bodies, and organize traffic by host and path.

### Proxy Rules

Create ordered network rewrite rules that match URL patterns and optional HTTP methods. Rules can change status codes, set or remove headers, and provide response bodies for debug-app testing.

### Projects

Organize Android, Kotlin, and Compose Multiplatform work into project spaces with a repo directory and optional environment variables. Each project canvas has tabs for chats, workflow tasks, artifacts, automations, kanban, runbook shell actions, a markdown scratchpad, and nested git worktrees. Start agent chats that inherit the project's context, run actions in a docked Terminal, and keep per-project agent profiles (provider, model, autonomy) for workflow stages.

### Project Workflows

Drive Spec → Build ↔ Review ↔ Verification from the project tasks tab. Create or refine specs (optional grill-me pass), open plan snapshots into builds, gate review verdicts, track verification criteria and attempt history, and jump into the related agent runs. Desktop only.

### Artifacts

Browse a per-project Artifacts + Media catalog from the Artifacts tab under Projects. Workflow outputs, uploads, and pinned files land in Media or Documents views; preview text, reveal on disk, pin/unpin, upload new files, and open entries from workflow tasks. An unscoped Agents catalog collects artifacts not tied to a project. Desktop only.

### Automations

Schedule recurring agent work from the Automations tab under Projects. Create paused-until-resume automations with once, hourly, daily, weekday, weekly, interval, or cron schedules; choose standalone, dedicated-thread, or heartbeat mode; set failure policy, max iterations, and notifications. Arm with Resume, run manually, or drive from MCP `automation.*` tools while `andyd` is up. Desktop only.

### Kanban

Track work on a per-project drag-and-drop board from the Kanban tab under Projects. Andy starts you with To-Do, Doing, and Done lanes; add, rename, reorder, or delete lanes; and create cards with a title, description, and tags. Drag cards between lanes or reorder within a lane, assign cards to agent chats, and start a spec from a card. The board persists locally with Andy's agent store. Desktop only — unavailable while Andy is connected to a running `andyd` (quit the daemon and restart Andy to edit the board).

### Agents

Dispatch coding tasks to Claude Code, Codex, Cursor, Antigravity, OpenCode, Pi, Hermes, OpenClaw, Goose, or local Ollama/LM Studio backends (via OpenCode, Pi, or Goose runtimes) from Andy. Compose prompts with images, `@file` mentions, and `/` skills; choose model, autonomy, and provider sandbox/approvals; optionally isolate the run in a git worktree; toggle plan mode; set a persistent `/goal` (Codex and Claude Code); import a vendor thread/session id to resume an existing provider conversation; and attach Andy MCP so the agent can drive devices and emulators. Start temporary chats that never persist (filtered from MCP, web, automations, and kanban) or promote them when ready. Open side chats docked beside a parent task for read-only second opinions without handing off the work. Pin priority chats (working, blocked, unread, queued, or recently failed) at the top of project and agent inboxes. Follow the live transcript (thinking, tools, inline tool images, mermaid diagram previews, cost/tokens), review file diffs when the task finishes, open file links in Andy's code viewer, send or queue follow-ups, archive or mark chats unread, and check provider quota from the inbox. Voice dictation is available when enabled in Settings.

### Controls

Toggle common device state without memorizing `adb shell` commands. Andy includes controls for airplane mode, Wi-Fi, mobile data, Bluetooth, dark mode, font scale, animation scale, show taps, pointer location, layout bounds, TalkBack, don't-keep-activities, and hardware buttons.

On emulators, Controls also injects GPS (including GPX/KML route playback), sensors, GSM calls/SMS/network type, thermal status, and foldable hinge posture. Battery level/charging overrides work on physical devices too (with a prominent Reset). Runtime locale supports `cmd locale`, setprop+restart, per-app locales, and pseudo-locales (`en-XA` / `ar-XB`).

### Performance

Monitor device performance samples over time. Andy displays CPU, memory, frame rendering, battery, thermal status, process metrics, and frame timing bars that make slower-than-60-fps frames stand out. The Memory tab captures heap dumps, shows `dumpsys meminfo` breakdowns, and summarizes batterystats wakelocks/alarms/jobs.

### Tracing

Capture Perfetto traces from Android 9+ devices with quick-start presets for general, battery, thermal, graphics, Chrome, and V8 workloads. Tune duration and buffer size, edit the textproto config, keep a local trace library, and open completed traces in the Perfetto UI through Andy's local viewer.

### Design

Overlay design tools on top of the live device mirror. Use a grid, ruler, zoom controls, configurable overlay colors, a pointer color picker, and an optional imported image overlay to inspect spacing and visual details while interacting with the app.

### Accessibility

Dump and inspect the Android accessibility hierarchy beside the live mirror. Hover or select nodes to highlight bounds, filter to interesting nodes, toggle layout bounds, and review labels, state, geometry, and simple accessibility issues.

### Inspector

Capture the on-screen view hierarchy beside the live mirror: a tree pane, a read-only properties pane (identity, geometry, state, semantics, raw dumpsys attributes), and the mirror with overlay, plus a 2.5D window z-order layer view (tilt/spacing), structural snapshot diffing, and text/id/class search. Andy merges `uiautomator dump` with `dumpsys activity top`'s unmerged view tree by bounds and class, and reads `dumpsys window` for layering — no on-device agent required. Composable names, modifier chains, and recomposition counts are out of scope; those need a JVMTI agent Andy doesn't ship.

### Bug Capture

Capture reproducible bug reports from Live. Andy saves recent actions, live video frames, logcat, device metadata, and notes, then lets you replay, scrub, export, or delete reports from the Bugs screen.

An "Explain…" action sits beside a selected crash, network exchange, hierarchy node, and investigation moment. It opens a confirmation sheet showing the editable prompt and exactly which evidence would be attached — events, time window, size, exclusions, and redactions — before starting a read-only agent chat. Nothing is sent to a provider until you confirm the sheet, and the resulting chat links back to the investigation moment it came from. Desktop only.

### Settings

Customize appearance (accent, background, code and terminal themes), show or hide sidebar pages, and tune agent behavior: orchestration provider defaults per role (Implementation, UI/design, Research, Planning, Audit), immediate vs queued follow-ups, keep sessions alive after quit, transcript expand/collapse, chat retention sweeps, OS notifications and dock badges, and voice dictation setup. Proxy settings cover start-on-launch and corporate TLS trust. The MCP panel enables Andy's local MCP server, lists available tools, and offers client config snippets for Claude Code, Cursor, Codex, Claude Desktop, Antigravity, OpenCode, Pi, Hermes, OpenClaw, Goose, VS Code, and Windsurf. Optional Network Access serves a static web chat PWA for ACP-lane chats from other devices (Tailscale Serve or LAN bind, bearer-token auth, Web Push notifications) — see [docs/ANDYD.md](docs/ANDYD.md#network-access-optional-web-client).

### Updates

Check for desktop app updates and confirm installation from inside Andy. Version metadata is generated at build time and the app can surface a close-and-install prompt when an update is ready. The same Settings area can install or update the CLI runtime bundle (`andy`, `andyd`, managed tmux, status hook, and orchestration skills) without leaving the app.

### Computer File Browsing

Browse the host filesystem from multi-root folders, open files in a syntax-themed editor with an inline find bar (Cmd/Ctrl+F, next/prev), and search across indexed roots without leaving Andy. File changes under watched roots refresh via FSEvents (macOS) or directory watching.

### Andy for web

The browser build at [andy.joetr.com](https://andy.joetr.com) provides a
smaller subset of Andy's functionality. For the complete experience, use the
desktop app. The browser build can connect directly with WebUSB or through
Andy's pinned tracebox distribution. The bridge keeps ADB on loopback and
permits only Perfetto's standard local origins, `https://andy.joetr.com`, and
the computer's detected private IPv4 origin on port `10000`.

```sh
adb start-server
curl -fL https://github.com/j-roskopf/Andy/releases/latest/download/andy-tracebox -o andy-tracebox
chmod +x andy-tracebox
./andy-tracebox
```

The source manifest, checksum verification, launcher, and release packager are
maintained in [`tools/andy-tracebox`](tools/andy-tracebox/README.md).

## Screenshots

The images below are approved macOS visual-test baselines. The full [screenshot scenario matrix](docs/SCREENSHOT_SCENARIO_MATRIX.md) records fixture state; PR CI verifies screenshots on macOS only.

| Devices | Catalog |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-devices-populated.png" alt="Andy devices screen" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-catalog-images.png" alt="Andy system image catalog" width="480"> |
| Live mirror | Apps |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-live-mirror.png" alt="Andy live mirror" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-apps-details.png" alt="Andy app details" width="480"> |
| Logcat | Intents |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-logcat-stream.png" alt="Andy logcat" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-intents-draft.png" alt="Andy intent draft" width="480"> |
| Files | Shared Preferences |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-device-files.png" alt="Andy files" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-shared-preferences.png" alt="Andy shared preferences" width="480"> |
| App Databases | Computer Files |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-app-database.png" alt="Andy app database" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-computer-files.png" alt="Andy computer files" width="480"> |
| Network | Project tasks |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-network-capture.png" alt="Andy network capture" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-projects-workflows.png" alt="Andy project tasks" width="480"> |
| Project runbook | Project sessions |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-projects-runbook.png" alt="Andy project runbook" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-projects-sessions.png" alt="Andy project sessions" width="480"> |
| Project scratchpad | Project scratchpad editor |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-projects-scratchpad.png" alt="Andy project scratchpad" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-projects-scratchpad-editor.png" alt="Andy project scratchpad editor" width="480"> |
| Project new spec | Project new build |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-projects-new-spec.png" alt="Andy project new spec" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-projects-new-build.png" alt="Andy project new build" width="480"> |
| Project profiles | Project kanban |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-projects-profiles.png" alt="Andy project profiles" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-projects-kanban-board.png" alt="Andy project kanban board" width="480"> |
| Agents | Snapshots |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-agents-completed-diff.png" alt="Andy agents" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-snapshots-populated.png" alt="Andy snapshots" width="480"> |
| Controls | Performance |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-controls-hardware.png" alt="Andy controls" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-performance-samples.png" alt="Andy performance" width="480"> |
| Tracing | Design |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-tracing-perfetto.png" alt="Andy Perfetto tracing" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-design-overlay.png" alt="Andy design tools" width="480"> |
| Accessibility | Inspector |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-accessibility-hierarchy.png" alt="Andy accessibility inspector" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-inspector-hierarchy.png" alt="Andy view hierarchy inspector" width="480"> |
| Inspector layers | Bug Capture |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-inspector-layers.png" alt="Andy view hierarchy 2.5D layer view" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-bugs-replay.png" alt="Andy bug replay" width="480"> |
| Recordings export | |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-recordings-export.png" alt="Andy recordings export" width="480"> | |
| Settings | Mirror pop-out |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-settings-mcp.png" alt="Andy settings" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-mirror-pop-out.png" alt="Andy mirror pop-out" width="480"> |

## CLI

Andy ships a Rust CLI (`andy`) for driving agent chats **and** device/network
automation from the terminal on **macOS and Linux only**. The CLI is not
supported on Windows — use the [desktop app](#download) there instead.

The CLI talks to the same control plane as the desktop app: a background daemon
(`andyd`) that owns agent/project state, spawns provider CLIs into Andy-managed
tmux sessions, and serves MCP over `~/.andy/andyd.sock`.

**`andy` auto-starts `andyd` when needed.** Agent sessions use Andy's bundled
tmux at `~/.andy/bin/tmux` (installed by `install-andy.sh`, like bundled
`scrcpy-server` for mirroring). You do not need to install tmux separately.

### Installation

```sh
curl -fsSL https://github.com/j-roskopf/Andy/releases/latest/download/install-andy.sh | bash

# Permanently add ~/.andy/bin to your PATH (pick your shell):
echo 'export PATH="$HOME/.andy/bin:$PATH"' >> ~/.zshrc   # zsh
# echo 'export PATH="$HOME/.andy/bin:$PATH"' >> ~/.bashrc  # bash
```

Restart your shell (or `source` the rc file you edited) so `andy` is on your `PATH`.

Requires **Java 21+** for the `andyd` runtime. The installer places:

| Path | Role |
| --- | --- |
| `~/.andy/bin/andy` | CLI |
| `~/.andy/bin/andyd` | Daemon launcher |
| `~/.andy/andyd/andyd.jar` | Daemon runtime |
| `~/.andy/bin/tmux` | Andy-managed tmux for agent sessions |

From source: `./gradlew installAndyCli installAndyd`

### Quick start

```sh
andy tui # Main entry point into the CLI for chatting with Agents
andy chat list
andy chat start --agent ClaudeCode --directory "$PWD" "Reply with pong"
andy attach <taskId>
andy project list
andy --remote user@host.local chat list   # SSH-tunnel ~/.andy/andyd.sock

# Device / network scripting (same MCP socket)
andy device list
andy emulator start Pixel_7 --wait
andy network rule upsert --url-pattern '*/api/*' --status-code 500
andy device screenshot -o /tmp/screen.png
andy tool list   # full MCP catalog; `andy tool call <name>` for any tool
```

Provider ids: `ClaudeCode`, `Codex`, `Cursor`, `Antigravity`, `OpenCode`, `Pi`,
`Hermes`, `OpenClaw`, `Goose`, `Ollama`, `LMStudio`.

`andy tui` groups chats by project (`n` new chat, `a` / Enter attach). `andy attach`
opens the ACP viewer or a tmux pane by lane — detach with Esc/`q` (ACP) or F12 /
Alt+d / Ctrl-b then d (tmux) without stopping the agent. Chat start also accepts
`--project`, `--title`, image paths, `--pick-image`, and `--no-attach`.

Device targeting: `--serial` on a command, or `ANDY_SERIAL`. Use global `--json`
for machine-readable output. Curated groups cover `device`, `emulator`, `avd`,
`system-image`, `snapshot`, `input`, `app`, `intent`, `file`, and `network`.
Automation MCP tools (`automation.list`, `automation.create`, `automation.run`, …)
and other agent/workflow tools stay under `andy tool call` (beyond the curated
`andy chat` commands).

See [docs/ANDYD.md](docs/ANDYD.md) for the full command reference, TUI
keybindings, remote access, GUI/daemon modes, and launchd packaging.

## Testing

PR CI runs `./gradlew desktopTest` on Linux/macOS/Windows, plus macOS-only
`verifyRoborazziDesktop`. Opt-in suites that need a device, Simulator, or live
agent CLI — and how to run them locally — are documented in
[docs/TESTS.md](docs/TESTS.md).

## Building from source

`./gradlew run` and `./gradlew runDistributable` compile Andy's native terminal
engine with Cargo, so **Rust** (stable) must be installed and on your `PATH`.
Java 21+ is also required.

```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"
./gradlew run
```

Open a new terminal after install if `cargo` is still not found.

## Runtime Requirements

- Android SDK platform tools for device and emulator access.
- Xcode command-line tools (`xcrun simctl`, `xcrun devicectl`, `xcodebuild`) on macOS for iOS Simulator management, catalog/platform downloads, physical-device status, Live mirror, and crash symbolication (`atos`).
- Network capture uses Andy's pinned mitmproxy runtime at `~/.andy/proxy/venv`
  (provisioned automatically; needs Python 3.12+). Optional fallback:
  `brew install mitmproxy`.
- Andy bundles `scrcpy-server` for embedded Android mirroring and installs a
  managed `tmux` at `~/.andy/bin/tmux` for agent sessions (via `install-andy.sh`
  or the desktop app). Override with `ANDY_TMUX` if needed.
- Optional agent CLIs for Projects and Agents: Claude Code (`claude`), Codex (`codex`), Cursor Agent (`cursor-agent`), Antigravity (`agy`), OpenCode (`opencode`), Pi (`pi`), Hermes (`hermes`), OpenClaw (`openclaw`), or Goose (`goose`). Ollama and LM Studio work as OpenAI-compatible backends when a server is running and configured in Settings.

## Icon Attribution
<a href="https://www.flaticon.com/free-icons/robot" title="robot icons">Robot icons created by Smashicons - Flaticon</a>

## Inspiration
A lot of visual and functional inspiration was borrowed, with love, from [Emu](https://emu.marathonlabs.io/)
