# Desktop screenshot scenario matrix

The desktop visual-regression suite captures Andy at a fixed 1365×900 viewport.
Each capture uses `ScreenshotServices`, a no-I/O fixture: it does not invoke ADB,
the emulator, mitmproxy, host files, terminals, agent CLIs, or the updater.

Baselines are renderer-specific and live under
`src/screenshotTest/roborazzi/{macos,windows,linux}/`. Do not copy an image from
one operating system into another directory. Capture it on that platform instead.

| Scenario | Owner | Fixture state | Filename | README |
| --- | --- | --- | --- | --- |
| Devices populated | Devices | online emulator, USB phone, offline Wi-Fi tablet | `desktop-devices-populated.png` | yes |
| Catalog images | Catalog | installed and available phone/TV images | `desktop-catalog-images.png` | yes |
| Live mirror | Live | connected Pixel 8, Compose mirror representation, compact logcat | `desktop-live-mirror.png` | yes |
| Apps details | Apps | Garden package, permissions, activities | `desktop-apps-details.png` | yes |
| Logcat stream | Logcat | mixed-level filtered rows | `desktop-logcat-stream.png` | yes |
| Intent draft | Intents | deep-link command draft | `desktop-intents-draft.png` | yes |
| Device files | Files & data | `/sdcard` folders and APK candidates | `desktop-device-files.png` | yes |
| Computer files | Computer Files | indexed source root and manifest editor document | `desktop-computer-files.png` | yes |
| Network capture | Network | listening proxy, exchange, CA warning, rules | `desktop-network-capture.png` | yes |
| Projects populated | Projects | Garden project, action, note, completed chat | `desktop-projects-populated.png` | yes |
| Project actions | Projects | Garden runbook with two seeded actions | `desktop-projects-actions.png` | yes |
| Project notes | Projects | Garden working notes with one open note | `desktop-projects-notes.png` | yes |
| Agents completed diff | Agents | completed Codex transcript and deterministic diff | `desktop-agents-completed-diff.png` | yes |
| Snapshots populated | Snapshots | boot and manual AVD snapshots | `desktop-snapshots-populated.png` | yes |
| Controls hardware | Controls | selected Pixel 8 control surface | `desktop-controls-hardware.png` | yes |
| Performance samples | Performance | CPU, memory, process, and frame timing data | `desktop-performance-samples.png` | yes |
| Design overlay | Design | mirror with deterministic design tooling data | `desktop-design-overlay.png` | yes |
| Accessibility hierarchy | Accessibility | selected hierarchy with bounds metadata | `desktop-accessibility-hierarchy.png` | yes |
| Bug replay | Bugs | captured checkout report, actions, frames, and log | `desktop-bugs-replay.png` | yes |
| Settings MCP | Settings | configured local SDK, proxy, and MCP service | `desktop-settings-mcp.png` | yes |
| Mirror pop-out | Pop-out mirror | focused mirror with hardware controls | `desktop-mirror-pop-out.png` | yes |

## Local workflow

Record only the current operating system's baselines after reviewing each image:

```sh
./gradlew recordRoborazziDesktop
./gradlew verifyRoborazziDesktop
./gradlew desktopTest
```

When a UI change intentionally affects a visual surface, re-record and commit only
the affected platform directory. The manual `Record desktop screenshot baselines`
workflow produces a platform-specific artifact for Linux, macOS, or Windows; use
that runner to regenerate its matching directory.

For local recording, use either of these equivalent actions:

- Codex: `AGENTS.md` defines the screenshot action as
  `./gradlew recordRoborazziDesktop`. Add the same command to the generated
  local-environment action list to expose it as a clickable button.
- VS Code: **Tasks: Run Task** → **Andy: Record Desktop Screenshots**.
- Andy: on a fresh Actions setup, open the seeded **Andy** project and run
  **Record screenshots**. Existing personal Actions configurations are never
  replaced; add the same `./gradlew recordRoborazziDesktop` command manually.
