# Desktop screenshot scenario matrix

The desktop visual-regression suite captures Andy at a fixed 1365×900 viewport.
Each capture uses `ScreenshotServices`, a no-I/O fixture: it does not invoke ADB,
the emulator, mitmproxy, host files, terminals, agent CLIs, or the updater.

Baselines are macOS-only and live under `src/screenshotTest/roborazzi/macos/`.
Record and verify on macOS (local or the `Record desktop screenshot baselines`
Actions workflow). PR CI runs `verifyRoborazziDesktop` on `macos-latest` only.

| Scenario | Owner | Fixture state | Filename | README |
| --- | --- | --- | --- | --- |
| Devices populated | Devices | online emulator, USB phone, offline Wi-Fi tablet | `desktop-devices-populated.png` | yes |
| Catalog images | Catalog | installed and available phone/TV images | `desktop-catalog-images.png` | yes |
| Live mirror | Live | connected Pixel 8, Compose mirror representation, compact logcat | `desktop-live-mirror.png` | yes |
| Apps details | Apps | Garden package, permissions, activities | `desktop-apps-details.png` | yes |
| Logcat stream | Logcat | mixed-level filtered rows | `desktop-logcat-stream.png` | yes |
| Intent draft | Intents | deep-link command draft | `desktop-intents-draft.png` | yes |
| Device files | Files & data | `/sdcard` folders and APK candidates | `desktop-device-files.png` | yes |
| Shared preferences | Shared Preferences | Garden prefs XML with typed keys | `desktop-shared-preferences.png` | yes |
| App database | App Databases | Garden SQLite DB with plants table and saved query | `desktop-app-database.png` | yes |
| Computer files | Computer Files | indexed source root and manifest editor document | `desktop-computer-files.png` | yes |
| Network capture | Network | listening proxy, exchange, CA warning, rules | `desktop-network-capture.png` | yes |
| Project tasks | Projects | Garden workflow list with nested spec/build/review/verify | `desktop-projects-workflows.png` | yes |
| Project runbook | Projects | Garden runbook with two seeded actions | `desktop-projects-runbook.png` | yes |
| Project sessions | Projects | Garden agent session list with completed Codex chat | `desktop-projects-sessions.png` | yes |
| Project scratchpad | Projects | Garden scratchpad preview with release constraints | `desktop-projects-scratchpad.png` | yes |
| Project scratchpad editor | Projects | Garden scratchpad in edit mode | `desktop-projects-scratchpad-editor.png` | yes |
| Project profiles | Projects | Project role profiles dialog on spec defaults | `desktop-projects-profiles.png` | yes |
| Project new build | Projects | New build dialog with linked plan and build profile | `desktop-projects-new-build.png` | yes |
| Project new spec | Projects | New spec dialog filled with title, brief, and scratchpad | `desktop-projects-new-spec.png` | yes |
| Agents completed diff | Agents | completed Codex transcript and deterministic diff | `desktop-agents-completed-diff.png` | yes |
| Snapshots populated | Snapshots | boot and manual AVD snapshots | `desktop-snapshots-populated.png` | yes |
| Controls hardware | Controls | selected Pixel 8 control surface | `desktop-controls-hardware.png` | yes |
| Performance samples | Performance | CPU, memory, process, and frame timing data | `desktop-performance-samples.png` | yes |
| Tracing Perfetto | Tracing | default preset, user config, and local trace library | `desktop-tracing-perfetto.png` | yes |
| Design overlay | Design | mirror with deterministic design tooling data | `desktop-design-overlay.png` | yes |
| Accessibility hierarchy | Accessibility | selected hierarchy with bounds metadata | `desktop-accessibility-hierarchy.png` | yes |
| Inspector hierarchy | Inspector | merged uiautomator/dumpsys tree, properties panel | `desktop-inspector-hierarchy.png` | yes |
| Bug replay | Bugs | captured checkout report, investigation timeline (actions/network/metrics/crash-free hierarchy/screenshot/log), frames, and identity | `desktop-bugs-replay.png` | yes |
| Recordings export | Recordings | recording library with export sheet (trim + format) | `desktop-recordings-export.png` | yes |
| Settings MCP | Settings | configured local SDK, proxy, and MCP service | `desktop-settings-mcp.png` | yes |
| Mirror pop-out | Pop-out mirror | focused mirror with hardware controls | `desktop-mirror-pop-out.png` | yes |

The contextual "Explain…" actions never appear in these baselines: they are gated on a
real `InvestigationEvidenceService`, and the screenshot fixtures use the unavailable one.
Adding a fixture with a live evidence service will change the Bugs, Network, Inspector,
and Logcat surfaces and needs a re-record.

## Local workflow

Record macOS baselines after reviewing each image:

```sh
./gradlew recordRoborazziDesktop
./gradlew verifyRoborazziDesktop
./gradlew desktopTest
```

`desktopTest` excludes the Roborazzi suite; use `verifyRoborazziDesktop` /
`recordRoborazziDesktop` for visual baselines. When a UI change intentionally
affects a visual surface, re-record and commit only the changed files under
`src/screenshotTest/roborazzi/macos/`. The manual `Record desktop screenshot
baselines` workflow produces a macOS artifact when local recording drifts from CI.

For local recording, use either of these equivalent actions:

- Codex: `AGENTS.md` defines the screenshot action as
  `./gradlew recordRoborazziDesktop`. Add the same command to the generated
  local-environment action list to expose it as a clickable button.
- VS Code: **Tasks: Run Task** → **Andy: Record Desktop Screenshots**.
- Andy: on a fresh Actions setup, open the seeded **Andy** project and run
  **Record screenshots**. Existing personal Actions configurations are never
  replaced; add the same `./gradlew recordRoborazziDesktop` command manually.
