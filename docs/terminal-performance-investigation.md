# Terminal performance investigation (iteration 1)

**Date:** 2026-08-13  
**Branch work:** BossTerm Compose pipeline measurement + bounded knob changes  
**Stack under test:** `com.risaboss:bossterm-compose/core:1.2.143` via `BossTermBackend` → `EmbeddableTerminal` → `TerminalCanvasRenderer`, gated by `TerminalFrameLimiter`.

## Goal

Raise terminal responsiveness above today's 15fps gate **without** exceeding roughly **10–20% process CPU** while a terminal is actively streaming (chat non-ACP providers and Actions dock share this path).

## How we measured

New harness: `BossTermPipelineBenchmark` / `BossTermPipelineBenchmarkTest`.

```sh
./gradlew desktopTest --tests "app.andy.terminal.BossTermPipelineBenchmarkTest" \
  -Dandy.bench=1 -Dandy.bench.knobs=1 --rerun-tasks
```

- Drives a real `BossTermBackend` + Compose `EmbeddableTerminal` window (not the historical Swing/`FakeTerminal` stand-in in `TerminalPipelineBenchmark`).
- Workload: agent-like stream (~20 lines / 50ms with path+URL tokens ≈ 2–3k chars/s), sized from the prior live measurement of ~2,181 `requestRedraw`/sec.
- Metric: `OperatingSystemMXBean.processCpuTime / wall` × 100 (multi-core capable; same units Activity Monitor uses when a process exceeds 100%).
- Timing: 4s warmup + 12s measure per variant (unless overridden).
- Machine: Apple Silicon, 18 logical CPUs. Idle floor with a live `cat` PTY: **~2.6–2.9%**.

The older Swing benchmark remains in-tree as historical only; its numbers are **not** comparable to today's bottleneck.

## Baseline FPS sweep (default agent settings at the time: `performanceMode=latency`, hyperlinks on)

| Variant | CPU % | Notes |
|--------|------:|-------|
| idle-shell | 2.6–2.9 | Live PTY, no output |
| **15fps** (today's default) | **30.6–32.4** | `TerminalFrameLimiter` DEFAULT_FPS |
| 24fps | 35.2 | |
| 30fps | 34.7 | Nearly flat vs 24fps |
| 45fps | 40.0 | |
| uncapped (`fps=0`) | 58.6 | BossTerm stays in `RedrawMode.INTERACTIVE` |

### Empirical FPS ceiling for the 10–20% band

**None of the FPS caps alone land in 10–20% under agent-like streaming.**  
15fps is already ~31–32%. Raising the cap moves further from the target (and uncapped is ~59%).

The 15→30fps band is almost flat because most of the capped cost is **per open-gate paint + emulator work**, not a linear fps×cost curve — and at 30fps the default ~25ms render window consumes most of a 33ms cycle, so Compose can still paint multiple display frames per "capped" tick.

## Knob A/B (Andy-side / public BossTerm settings)

Bytecode consumers confirmed before testing:

| Setting | Consumed by paint/stream? | Result |
|---------|---------------------------|--------|
| `TerminalSettings.maxRefreshRate` | **No** (settings UI / data class only) | Dead — confirmed again |
| `gpuAcceleration` | **No** outside settings UI | Dead for Compose path |
| `performanceMode` | **Yes** — `BlockingTerminalDataStream` (`take` / `poll(10ms)` / `poll(100ms)`) | Real |
| `detectFilePaths` | **Yes** — `ProperTerminal` → `RenderingContext` → `detectAllHyperlinks` | Real |

Measured (representative runs; noise ± a few points):

| Variant | CPU % |
|--------|------:|
| 15fps (latency + hyperlinks) | 30.6–32.4 |
| 15fps + `detectFilePaths=false` | 22.7–29.2 (noisy) |
| 15fps + `performanceMode=throughput` | **22.5–23.9** (stable) |
| 15fps + both | 24.1–25.2 |
| 24fps + both | 29.2–29.5 |
| 30fps + both | 30.3–31.2 |
| 24fps + both + `renderWindowMs=12` | 33.7 (no win) |
| 30fps + both + `renderWindowMs=10` | 29.2 (no win) |
| uncapped + no hyperlinks | 57.5–60.2 |

### Negative / weak results (useful for iteration 2)

- **Tightening `renderWindowMs` at 24/30fps** did not buy a CPU win; stale-frame risk remains if the window drops below one display frame + EDT hop.
- **`detectFilePaths=false` alone** helped in some runs (~10 points) but was noisy; still worth defaulting off for agent TUIs (little product value there).
- **Raising `DEFAULT_FPS`** is not a safe win against the 10–20% budget.
- **Output batching before BossTerm:** Andy already forwards PTY `read()` chunks via `TeeingProcessHandle`; further host-side coalescing would not stop BossTerm's per-character `requestRedraw()` once the emulator parses the chunk. Not pursued beyond `performanceMode=throughput`.
- First firehose stream (`andy.bench.stream=heavy`) pegged parse (~150%+ even at 15fps) and is not representative — kept as an opt-in stress mode only.

## Changes shipped this iteration

1. **`BossTermPipelineBenchmark`** — real Compose/BossTerm CPU harness + gradle forwarding for `-Dandy.bench*` / `-Dandy.terminal.*`.
2. **Agent-CLI defaults** in `BossTermAppearance`:
   - `performanceMode = "throughput"` (was `"latency"`)
   - `detectFilePaths = false`
   - Escape hatches: `-Dandy.terminal.performanceMode=…`, `-Dandy.terminal.detectFilePaths=true|false`
3. **`TerminalFrameLimiter`:** optional `-Dandy.terminal.repaint.renderWindowMs=`; **`DEFAULT_FPS` left at 15** (documented why).
4. Tests updated in `BossTermScrollbackTest.appearanceMapsToBossTermSettings`.

Expected production effect under streaming agent CLIs: roughly **~23–25% process CPU** in the bench environment (down from ~31–32%), still slightly above the aspirational 10–20% band but the best measured BossTerm-side cut that does not raise frame cap.

Actions / non-agent DirectPty shells keep `balanced` + default hyperlinks.

## Can BossTerm hit the goal within its architecture?

**Unlikely for "materially smoother than 15fps" inside 10–20% CPU.**

Reasons grounded in this iteration's data + prior decompile work:

1. **Per-character `requestRedraw()`** (~2k/s live) forces Andy to hold DEC 2026 sync closed most of the time; the gate is the only effective rate control (`maxRefreshRate` is dead; BossTerm's own `HIGH_VOLUME` backoff never engages).
2. **Full-grid Skia paint** (`TerminalCanvasRenderer.renderTerminal`) remains expensive per committed frame; uncapped still ~59% even with hyperlinks off.
3. **Best knob combo still ~23–25% at 15fps** under agent-like load — inside/near the top of the band only by staying at 15fps, not by raising responsiveness.
4. Library is **closed-source / minified**; no path to fix the redraw model without replacing or forking.

### Next iteration decision point (do not start until chosen)

| Option | Pros | Cons |
|--------|------|------|
| **A. Stay on BossTerm + accept ~15fps / ~25% CPU** | Smallest risk; knobs already applied | User-visible choppiness remains |
| **B. Replace renderer** (e.g. xterm.js via JCEF/WebView, or another JVM term) | GPU/DOM terminals routinely sustain 60fps cheaply | No JCEF in-tree today; integration + tmux/PTY/scrollback/appearance parity is a multi-PR project |
| **C. Vendor/fork BossTerm** | Could batch redraws properly | Closed/minified jars; legal/maintainability cost |

**Recommendation:** treat **B** as the default plan for iteration 2 if the product requirement is "feels like a normal terminal" *and* ≤20% CPU. Iteration 1 exhausted the public BossTerm knobs that move the needle; further FPS raises inside this architecture regress the budget.

## Repro commands

```sh
# Full FPS + knob matrix
./gradlew desktopTest --tests "app.andy.terminal.BossTermPipelineBenchmarkTest" \
  -Dandy.bench=1 -Dandy.bench.knobs=1 --rerun-tasks

# Single cap
./gradlew desktopTest --tests "app.andy.terminal.BossTermPipelineBenchmarkTest" \
  -Dandy.bench=1 -Dandy.bench.fps=15 -Dandy.bench.measureSec=15 --rerun-tasks

# Packaged-app profile (jlinked JDK has no JFR):
# asprof -d 30 -e cpu -o collapsed -f out.collapsed <andy-pid>
```
