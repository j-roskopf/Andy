# Desktop opt-in / CI-skipped tests

Default PR CI fans out into parallel jobs (see `.github/workflows/pr-checks.yml`):
`desktopTest` on Linux, macOS, and Windows; `verifyRoborazziDesktop` per OS;
`assemble` per OS; `:agent-store:build`; `:web-launcher:test`; and the Rust
CLI build on macOS. Most unit and fixture tests always run.

A smaller set of tests needs a live device, a logged-in vendor CLI, or a slow
local mitmproxy matrix. Those are gated so a cold laptop / CI image does not
fail them. Gates use JUnit `assumeTrue` via
`src/desktopTest/kotlin/app/andy/desktop/test/OptInGates.kt`, so missing
prerequisites show as **skipped** in the test report (not a silent green pass).

## Always on PR CI

| Suite | Notes |
| --- | --- |
| Ordinary `desktopTest` unit/fixture tests | Mock ADB, golden JSON, diagnosis, etc. |
| `ProxyConformanceTest` | Enabled with `ANDY_PROXY_CONFORMANCE=1` + Python 3.12 (CI provisions Andy’s pinned mitmproxy venv). |
| `MitmAddonHookRegistrationTest` / `MitmRuntimeTest` | Import/version guards; provision or use a supported system `mitmdump`. |
| `IosSimMirrorDeviceSmokeTest` (macOS) | CI boots an available iPhone Simulator and sets `ANDY_IOS_SIM_SMOKE=1` / `ANDY_IOS_SIM_UDID`. The iOS↔Android switch case still skips without an online Android device. |

## Opt-in gates (skipped unless set)

### Proxy conformance — `ANDY_PROXY_CONFORMANCE`

| | |
| --- | --- |
| **Why gated** | Starts a real `mitmdump` + origin servers; slower and needs Python 3.12+ / network on first venv install. |
| **CI** | **On** for every PR desktop job. |
| **Tests** | `app.andy.desktop.service.proxy.ProxyConformanceTest` |

```sh
ANDY_PROXY_CONFORMANCE=1 ./gradlew desktopTest --tests '*ProxyConformanceTest*'
```

### iOS Simulator Live smoke — `ANDY_IOS_SIM_SMOKE` / `ANDY_IOS_SIM_UDID`

| | |
| --- | --- |
| **Why gated** | Needs macOS arm64, SimulatorKit / mirror JNI, and a Booted simulator. |
| **CI** | **On** for the macOS desktop job (boots a sim first and sets the env vars). |
| **Tests** | `app.andy.desktop.service.ios.IosSimMirrorDeviceSmokeTest` |

```sh
# Requires an explicit opt-in (a casually Booted sim alone is not enough):
ANDY_IOS_SIM_SMOKE=1 ./gradlew desktopTest --tests '*IosSimMirrorDeviceSmokeTest*'

# Or pin a UDID:
ANDY_IOS_SIM_SMOKE=1 ANDY_IOS_SIM_UDID=<udid> \
  ./gradlew desktopTest --tests '*IosSimMirrorDeviceSmokeTest*'
```

`liveScreenStaysNonBlackAcrossIosAndroidIosSwitch` also needs an **online Android**
device; it remains skipped on CI and on Mac-only setups.

### Android device smoke — `ANDY_DEVICE_SMOKE`

| | |
| --- | --- |
| **Why gated** | Needs a connected online Android device or emulator (`adb devices`). Not provisioned on PR runners. |
| **CI** | **Off** |
| **Tests** | `DesktopMirrorEngineDeviceSmokeTest`, `DesktopMetricsDeviceSmokeTest`, `DesktopMirrorFixVerification`, `DesktopMirrorThroughputProbe` |

Optional helpers: `ANDY_DEVICE_SERIAL`, `ANDY_DEVICE_SMOKE_MAX_SIZE`.

Emulator-state injection (geo fix, battery override/reset), crash list, and heap-dump capture also belong behind this gate when live-device round-trips are added — keep them skipped by default via `OptInGates`.

```sh
# Emulator or USB device online first
ANDY_DEVICE_SMOKE=1 ./gradlew desktopTest --tests '*DesktopMirrorEngineDeviceSmokeTest*'
ANDY_DEVICE_SMOKE=1 ./gradlew desktopTest --tests '*DesktopMetricsDeviceSmokeTest*'
ANDY_DEVICE_SMOKE=1 ./gradlew desktopTest --tests '*DesktopMirrorFixVerification*'
ANDY_DEVICE_SMOKE=1 ./gradlew desktopTest --tests '*DesktopMirrorThroughputProbe*'
```

### Android native / Metal mirror smoke — `ANDY_DEVICE_NATIVE_SMOKE`

| | |
| --- | --- |
| **Why gated** | Same device requirement, plus GPU / native presenter JNI (typically macOS). |
| **CI** | **Off** |
| **Tests** | `app.andy.desktop.service.mirror.DesktopNativeMirrorDeviceSmokeTest` |

Optional: `ANDY_DEVICE_SERIAL`, `ANDY_REQUIRE_MIRROR_TARGET`,
`ANDY_DEVICE_MIRROR_BIT_RATE`, `ANDY_DEVICE_MIRROR_MAX_FPS`,
`ANDY_DEVICE_MIRROR_LATENCY_TRIGGER`.

```sh
ANDY_DEVICE_NATIVE_SMOKE=1 ./gradlew desktopTest --tests '*DesktopNativeMirrorDeviceSmokeTest*'
```

### Live agent CLI e2e — `ANDY_AGENT_E2E`

| | |
| --- | --- |
| **Why gated** | Talks to real vendor CLIs (Claude Code / Codex / Antigravity / OpenCode / Pi); costs subscription usage and needs each CLI installed + logged in for headless use. |
| **CI** | **Off** (no secrets / no spend on PRs). |
| **Tests** | `app.andy.desktop.service.agents.AgentRunEndToEndTest` |

Agents whose CLI is missing or not logged in are skipped individually.

```sh
ANDY_AGENT_E2E=1 ./gradlew desktopTest --tests '*AgentRunEndToEndTest*'
```

### ACP lane focused coverage

The default ACP lane is covered without starting a real provider process:

```sh
./gradlew desktopTest --tests 'app.andy.desktop.service.agents.acp.AcpLaneTest'
```

The test covers default lane routing, event reduction, JSONL transcript
round-tripping, and confident ACP stop-reason mapping. Real provider ACP
spawn/initialize/resume behavior still belongs to the opt-in live-agent gate.

## Permanently `@Ignore`d

These are not env-gated; they stay ignored until someone deliberately re-enables them.

| Test | Why ignored | Local run |
| --- | --- | --- |
| `PackagedJavaHomeCatalogSmokeTest` | Needs a host JDK plus Android SDK cmdline-tools; simulates a jlink runtime without `bin/java`. Unit coverage lives in `CommandRunnerJavaHomeTest`. | Remove `@Ignore`, then `./gradlew desktopTest --tests '*PackagedJavaHomeCatalogSmokeTest*'` |
| `ProjectWorkflowServiceTest` cases marked “Reported cost is no longer injected…” | Obsolete: headless `TaskResult` no longer injects reported cost. | Leave ignored unless that product path returns. |

## Quick matrix

| Env / annotation | PR CI | Needs |
| --- | --- | --- |
| (default `desktopTest`) | Yes | JDK 21 |
| `ANDY_PROXY_CONFORMANCE=1` | Yes (all OS) | Python 3.12+, pip/network once for `~/.andy/proxy/venv` |
| `ANDY_IOS_SIM_SMOKE=1` (+ Booted sim) | Yes (macOS) | Xcode Simulator, Andy mirror JNI |
| `ANDY_DEVICE_SMOKE=1` | No | Online Android device/emulator |
| `ANDY_DEVICE_NATIVE_SMOKE=1` | No | Online Android + native/GPU presenter |
| `ANDY_AGENT_E2E=1` | No | Vendor CLIs + login (costs usage) |
| `@Ignore` catalog / cost cases | No | Manual un-ignore |

## Proxy knobs (not test gates)

| Env | Default | Purpose |
| --- | --- | --- |
| `ANDY_HAPPY_EYEBALLS_DELAY` | `0.25` (in the mitm addon) | Seconds of IPv6 lead time before racing IPv4 (RFC 8305). mitmproxy itself does not enable Happy Eyeballs; the addon patches `asyncio.open_connection`. Set `0` to disable. |

## Related

- Screenshot baselines (separate from these gates): [SCREENSHOT_SCENARIO_MATRIX.md](SCREENSHOT_SCENARIO_MATRIX.md), `./gradlew recordRoborazziDesktop` / `verifyRoborazziDesktop`
- Shared gate helpers: `src/desktopTest/kotlin/app/andy/desktop/test/OptInGates.kt`
- PR workflow: `.github/workflows/pr-checks.yml`
