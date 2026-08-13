# Terminal performance investigation (iteration 1)

**Date:** 2026-08-13  
**Branch:** `terminal-perf-iter1`  
**Stack:** `com.risaboss:bossterm-compose/core:1.2.143` via `BossTermBackend` → `EmbeddableTerminal` → `TerminalCanvasRenderer`, gated by `TerminalFrameLimiter`.

## Goal

A fully performant terminal (smooth, high-fps-feeling) for chat (non-ACP) and the Actions dock, while keeping **~10–20% process CPU** under sustained streaming.

## Harness

`BossTermPipelineBenchmark` / `BossTermPipelineBenchmarkTest` — real `BossTermBackend` + Compose `EmbeddableTerminal` + live PTY stream (not the historical Swing/`FakeTerminal` stand-in).

```sh
./gradlew desktopTest --tests "app.andy.terminal.BossTermPipelineBenchmarkTest" \
  -Dandy.bench=1 -Dandy.bench.knobs=1 --rerun-tasks
```

Results are also written to `build/bossterm-pipeline-benchmark.txt` (durable; Gradle has raced away `test-results` XML after green runs).

- Workload: ~20 lines / 50ms with path+URL tokens (≈ 2–3k chars/s; matches prior ~2,181 `requestRedraw`/sec live agent measurement).
- Metric: `processCpuTime / wall × 100` (multi-core capable).
- Timing this run: 5s warmup + 15s measure per variant.
- Machine: Apple Silicon, 18 logical CPUs.

FPS-sweep cells pin `performanceMode=latency` + `detectFilePaths=true` so production knob defaults cannot leak into the baseline.

## Measured table (definitive run, 2026-08-13)

| variant | cpu% | wall_s | procCpu_s | what |
|--------|-----:|-------:|----------:|------|
| idle-shell | 2.8 | 15.0 | 0.42 | Live `cat` PTY, no output |
| 15fps | 28.9 | 15.0 | 4.34 | Cap 15; latency; hyperlinks on |
| 24fps | 33.0 | 15.0 | 4.95 | Cap 24; latency; hyperlinks on |
| 30fps | 32.4 | 15.0 | 4.87 | Cap 30; latency; hyperlinks on |
| 45fps | 41.6 | 15.0 | 6.24 | Cap 45; latency; hyperlinks on |
| uncapped | 61.5 | 15.0 | 9.23 | Gate off; latency; hyperlinks on |
| 15fps+noHyperlinks | 21.3 | 15.0 | 3.20 | 15fps; latency; `detectFilePaths=false` |
| 15fps+throughput | 20.8 | 15.0 | 3.12 | 15fps; `performanceMode=throughput`; hyperlinks on |
| **15fps+both** | **20.2** | 15.0 | 3.03 | **15fps; throughput; no hyperlinks** |
| 24fps+both | 26.0 | 15.0 | 3.90 | 24fps; throughput; no hyperlinks |
| 30fps+both | 30.1 | 15.0 | 4.51 | 30fps; throughput; no hyperlinks |
| uncapped+both | 58.7 | 15.0 | 8.81 | Uncapped; throughput; no hyperlinks |

## Empirical FPS ceiling inside 10–20% CPU

**15fps is the only frame cap that can land in the band**, and only when combined with the knob defaults below (`15fps+both` = **20.2%**).

| Cap + knobs | CPU % | In 10–20% band? |
|-------------|------:|-----------------|
| 15fps+both | 20.2 | Yes (top edge) |
| 24fps+both | 26.0 | No |
| 30fps+both | 30.1 | No |
| 15fps baseline (no knobs) | 28.9 | No |
| uncapped | 61.5 | No |

Raising `DEFAULT_FPS` above 15 exits the budget even with the best knobs.

## Do the knobs help?

**Yes — both meaningfully.**

| Knob | Effect at 15fps |
|------|-----------------|
| `detectFilePaths=false` | 28.9% → 21.3% (−7.6 pts). Paint path skips `detectAllHyperlinks`. |
| `performanceMode=throughput` | 28.9% → 20.8% (−8.1 pts). `BlockingTerminalDataStream` uses `poll(100ms)` when starved. |
| Both | 28.9% → **20.2%** (−8.7 pts). |

Dead / negative:

| Setting | Result |
|---------|--------|
| `maxRefreshRate` | No bytecode consumer outside settings UI |
| `gpuAcceleration` | Same — dead on Compose path |
| Host-side PTY re-batching | Andy already forwards `read()` chunks; does not stop per-char `requestRedraw()` |

## What we shipped

1. **`DEFAULT_FPS = 15`** kept (only in-band choice). Exposed as `internal` + asserted in `TerminalFrameLimiterTest`.
2. **Both terminal surfaces** (chat non-ACP + Actions dock share `BossTermBackend` / `BossTermAppearance`):
   - `performanceMode = "throughput"`
   - `detectFilePaths = false`
   - Overrides: `-Dandy.terminal.performanceMode=…`, `-Dandy.terminal.detectFilePaths=true|false`
3. Harness + durable results file + gradle `-Dandy.bench*` / `-Dandy.terminal.*` forwarding.

Expected active streaming CPU: **~20%** (top of the target band), down from **~29%** at the old 15fps+latency+hyperlinks baseline. Visual smoothness is unchanged at 15fps — knobs buy budget, not fluidity.

## Can BossTerm be “fully performant” inside this CPU budget?

**No — not with a high-fps feel.**

Evidence:

1. Per-character `requestRedraw()` (~2k/s) forces Andy’s DEC 2026 gate; BossTerm’s own `HIGH_VOLUME` backoff never engages; `maxRefreshRate` is dead.
2. Full-grid Skia `renderTerminal` stays expensive per committed frame — uncapped is still ~59–62% even with knobs.
3. **Best measured config is 15fps @ 20.2%.** Anything that feels smoother (24/30/uncapped) is 26–62%.
4. Closed-source / minified jars — no path to fix the redraw model without replacing or forking.

### Next iteration (informed decision, not a guess)

| Option | Verdict |
|--------|---------|
| Stay on BossTerm @ 15fps + knobs (~20% CPU) | Shipped; acceptable if choppy streaming is OK |
| Raise FPS on BossTerm | **Reject for the 10–20% goal** (measured) |
| **Replace renderer** (e.g. xterm.js / WebGL via JCEF or similar) | **Required for smooth high-fps + ≤20% CPU** |

**JCEF status in this repo:** no JCEF / JavaCEF / Compose WebView terminal dependency exists today (search covered `*.kt` / `*.kts` / docs). Adopting xterm.js would be a greenfield embed (JCEF or another WebView) plus PTY/tmux/scrollback/appearance parity — multi-PR. That is the correct next iteration if the product requirement is “feels like a normal terminal” inside the CPU band.

## Repro

```sh
./gradlew desktopTest --tests "app.andy.terminal.BossTermPipelineBenchmarkTest" \
  -Dandy.bench=1 -Dandy.bench.knobs=1 --rerun-tasks
# table → build/bossterm-pipeline-benchmark.txt

# Packaged app (no JFR in jlinked JDK):
# asprof -d 30 -e cpu -o collapsed -f out.collapsed <andy-pid>
```
