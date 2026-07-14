<p align="center">
  <img src="src/desktopMain/resources/icons/andy.png" alt="Andy logo" width="128">
</p>

# Andy

Andy is a desktop companion for Android developers. The desktop app is the
recommended experience and includes the full feature set. A smaller subset of
Andy is also available on the web at [andy.joetr.com](https://www.andy.joetr.com).

## Download

[Download the latest release](https://github.com/j-roskopf/Andy/releases/latest)

## Features

### Devices

Discover connected Android devices and created emulators in one place. Search and filter by device type or API level, start emulators, jump into a live session, and stop running emulators without leaving Andy.

### Virtual Device Creation

Create new Android Virtual Devices from SDK profiles and system images. Andy can install the selected image, configure orientation, RAM, storage, CPU, GPU, locale, cameras, hardware keyboard, and optionally launch the emulator after creation.

### System Image Catalog

Browse installed and available Android emulator system images. Filter by API, variant, or ABI, download missing images, and remove unused installed images when no AVD depends on them.

### Snapshots

Save, restore, and delete emulator snapshots for any created AVD. This makes it quick to return a test device to a known state before reproducing bugs or validating flows.

### Live Mirror

Stream a selected device or emulator into Andy with an embedded H.264 mirror. Send touch, keyboard, navigation, power, volume, rotation, screenshot, and text input commands directly from the desktop UI.

### Pop-Out Mirror

Open the device mirror in a separate focused window. The pop-out keeps the same input and hardware controls available when you want to watch or drive the device beside the main workspace.

### Apps

Inspect installed packages on the selected device. Launch, stop, clear data, reset permissions, uninstall user apps, and review declared permissions and activities from a split app/details view.

### Logcat

Stream device logs with live pause, clear, search, package filtering, and per-level toggles. The main Logcat screen includes resizable columns, while Live keeps a compact log panel next to the mirror.

### Intents

Build and send Android activity, deep link, service, and broadcast intents. Andy shows the generated `am` command before sending it so you can verify the exact action, component, and data URI.

### Files & Data

Browse device file paths such as `/sdcard`, `/data/local/tmp`, and `/storage/emulated/0`. Navigate directories, inspect mode, size, and modified timestamps, and use the file service for pull, push, and delete workflows.

### Network

Run a debug-app HTTPS proxy backed by mitmproxy. Start and stop capture, configure device proxy routing, install the local CA, inspect request and response headers or bodies, and organize traffic by host and path.

### Proxy Rules

Create ordered network rewrite rules that match URL patterns and optional HTTP methods. Rules can change status codes, set or remove headers, and provide response bodies for debug-app testing.

### Projects

Organize Android work into project spaces with a repo directory and optional environment variables. Save reusable shell actions, keep notes, open a project terminal, and start agent chats that inherit the project's context.

### Agents

Dispatch coding tasks to Claude Code, Codex, Cursor, or Antigravity from Andy. Compose prompts with images and `/` skills, choose model and autonomy, optionally isolate the run in a git worktree, and attach Andy MCP so the agent can drive devices and emulators. Follow the live transcript, review file diffs when the task finishes, and send follow-ups without leaving the desktop UI.

### Controls

Toggle common device state without memorizing `adb shell` commands. Andy includes controls for airplane mode, Wi-Fi, mobile data, Bluetooth, dark mode, font scale, animation scale, show taps, pointer location, layout bounds, and hardware buttons.

### Performance

Monitor device performance samples over time. Andy displays CPU, memory, frame rendering, battery, process metrics, and frame timing bars that make slower-than-60-fps frames stand out.

### Design

Overlay design tools on top of the live device mirror. Use a grid, ruler, zoom controls, configurable overlay colors, and a pointer color picker to inspect spacing and visual details while interacting with the app.

### Accessibility

Dump and inspect the Android accessibility hierarchy beside the live mirror. Hover or select nodes to highlight bounds, filter to interesting nodes, toggle layout bounds, and review labels, state, geometry, and simple accessibility issues.

### Bug Capture

Capture reproducible bug reports from Live. Andy saves recent actions, live video frames, logcat, device metadata, and notes, then lets you replay, scrub, export, or delete reports from the Bugs screen.

### Updates

Check for desktop app updates and confirm installation from inside Andy. Version metadata is generated at build time and the app can surface a close-and-install prompt when an update is ready.

### Computer File Browsing

Very rudimentary file browsing / editing.

### Andy for web

The browser build at [andy.joetr.com](https://www.andy.joetr.com) provides a
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

The images below are approved macOS visual-test baselines. The full [screenshot scenario matrix](docs/SCREENSHOT_SCENARIO_MATRIX.md) records fixture state and the matching Linux/Windows baseline contract.

| Devices | Catalog |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-devices-populated.png" alt="Andy devices screen" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-catalog-images.png" alt="Andy system image catalog" width="480"> |
| Live mirror | Apps |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-live-mirror.png" alt="Andy live mirror" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-apps-details.png" alt="Andy app details" width="480"> |
| Logcat | Intents |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-logcat-stream.png" alt="Andy logcat" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-intents-draft.png" alt="Andy intent draft" width="480"> |
| Files | Computer Files |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-device-files.png" alt="Andy files" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-computer-files.png" alt="Andy computer files" width="480"> |
| Network | Projects |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-network-capture.png" alt="Andy network capture" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-projects-populated.png" alt="Andy projects" width="480"> |
| Project actions | Project notes |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-projects-actions.png" alt="Andy project actions" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-projects-notes.png" alt="Andy project notes" width="480"> |
| Agents | Snapshots |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-agents-completed-diff.png" alt="Andy agents" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-snapshots-populated.png" alt="Andy snapshots" width="480"> |
| Controls | Performance |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-controls-hardware.png" alt="Andy controls" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-performance-samples.png" alt="Andy performance" width="480"> |
| Design | Accessibility |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-design-overlay.png" alt="Andy design tools" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-accessibility-hierarchy.png" alt="Andy accessibility inspector" width="480"> |
| Bug Capture | Settings |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-bugs-replay.png" alt="Andy bug replay" width="480"> | <img src="src/screenshotTest/roborazzi/macos/desktop-settings-mcp.png" alt="Andy settings" width="480"> |
| Mirror pop-out | |
| --- | --- |
| <img src="src/screenshotTest/roborazzi/macos/desktop-mirror-pop-out.png" alt="Andy mirror pop-out" width="480"> | |

### Runtime Requirements

- Android SDK platform tools for device and emulator access.
- mitmproxy for Network capture and rewrite rules: `brew install mitmproxy`.
- scrcpy does not need to be installed separately for embedded mirroring; Andy bundles `scrcpy-server`.
- Optional agent CLIs for Projects and Agents: Claude Code (`claude`), Codex (`codex`), Cursor Agent (`cursor-agent`), or Antigravity (`agy`).

### Icon Attribution
<a href="https://www.flaticon.com/free-icons/robot" title="robot icons">Robot icons created by Smashicons - Flaticon</a>
