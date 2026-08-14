# Terminal performance investigation (iteration 1)

**Date:** 2026-08-13  
**Branch:** `terminal-perf-iter1`  
**Stack:** `com.risaboss:bossterm-compose/core:1.2.143` via `BossTermBackend` → `EmbeddableTerminal` → `TerminalCanvasRenderer`, gated by `TerminalFrameLimiter`.

> **Supersedes earlier tables on this branch.** Numbers below are from a full re-run
> against the post-`5860c504` harness (every FPS/baseline cell explicitly pins
> `performanceMode=latency` + `detectFilePaths=true`). Pre-pin tables in
> `eab551eb` / early drafts are not trustworthy for baseline rows — those variants
> could silently inherit production throughput / no-hyperlinks defaults.

## Goal

A fully performant terminal (smooth, high-fps-feeling) for chat (non-ACP) and the Actions dock, while keeping **~10–20% process CPU** under sustained streaming.

## Harness

`BossTermPipelineBenchmark` / `BossTermPipelineBenchmarkTest` — real `BossTermBackend` + Compose `EmbeddableTerminal` + live PTY stream (not the historical Swing/`FakeTerminal` stand-in).

```sh
./gradlew desktopTest --tests "app.andy.terminal.BossTermPipelineBenchmarkTest" \
  -Dandy.bench=1 -Dandy.bench.knobs=1 --rerun-tasks
# durable copy → build/bossterm-pipeline-benchmark.txt
```

- Workload: ~20 lines / 50ms with path+URL tokens (≈ 2–3k chars/s; matches prior ~2,181 `requestRedraw`/sec live agent measurement).
- Metric: `OperatingSystemMXBean.processCpuTime / wall × 100` (**absolute** whole-process CPU %, multi-core capable).
- Timing this run: 5s warmup + 15s measure per variant.
- Machine: Apple Silicon, 18 logical CPUs.
- FPS-sweep / baseline cells pin `performanceMode=latency` + `detectFilePaths=true`.
- Knob cells set **both** dimensions explicitly (no production-default leakage).

## Measured table (post-pin re-run)

| variant | cpu% | wall_s | procCpu_s | what |
|--------|-----:|-------:|----------:|------|
| idle-shell | 2.6 | 15.0 | 0.39 | Live `cat` PTY, no output |
| 15fps | 31.7 | 15.0 | 4.75 | Cap 15; latency; hyperlinks on |
| 24fps | 33.8 | 15.0 | 5.08 | Cap 24; latency; hyperlinks on |
| 30fps | 35.1 | 15.0 | 5.26 | Cap 30; latency; hyperlinks on |
| 45fps | 39.3 | 15.0 | 5.90 | Cap 45; latency; hyperlinks on |
| uncapped | 61.1 | 15.0 | 9.17 | Gate off; latency; hyperlinks on |
| 15fps+noHyperlinks | 21.1 | 15.0 | 3.17 | 15fps; latency; `detectFilePaths=false` |
| 15fps+throughput | 20.9 | 15.0 | 3.14 | 15fps; `performanceMode=throughput`; hyperlinks on |
| 15fps+both | 23.6 | 15.0 | 3.54 | 15fps; throughput; no hyperlinks |
| 24fps+both | 28.5 | 15.0 | 4.28 | 24fps; throughput; no hyperlinks |
| 30fps+both | 32.5 | 15.0 | 4.87 | 30fps; throughput; no hyperlinks |
| uncapped+both | 61.4 | 15.0 | 9.20 | Uncapped; throughput; no hyperlinks |

Run-to-run note: a prior post-pin run on the same harness saw `15fps+both` at 20.2% and `uncapped` at 61.5%. Knob cells move a few points; **uncapped absolute process CPU stays ~61%**.

## Empirical FPS ceiling inside 10–20% CPU

| Cap + knobs | CPU % (this run) | In 10–20% band? |
|-------------|-----------------:|-----------------|
| 15fps+throughput | 20.9 | Top edge (yes) |
| 15fps+noHyperlinks | 21.1 | Just outside |
| 15fps+both | 23.6 | No (was 20.2% on prior post-pin run — noisy) |
| 24fps+both | 28.5 | No |
| 30fps+both | 32.5 | No |
| 15fps baseline | 31.7 | No |
| uncapped | 61.1 | No |

**Nothing above 15fps fits the band.** At 15fps, throughput alone is the most stable in-band/near-band win; combining knobs helps on average but is noisier than either knob alone.

## Do the knobs help?

**Yes.**

| Knob | Effect at 15fps (this run) |
|------|----------------------------|
| `detectFilePaths=false` | 31.7% → 21.1% (−10.6 pts) |
| `performanceMode=throughput` | 31.7% → 20.9% (−10.8 pts) |
| Both | 31.7% → 23.6% (−8.1 pts; prior post-pin run 20.2%) |

Dead / negative (unchanged findings):

| Setting | Result |
|---------|--------|
| `maxRefreshRate` | No bytecode consumer outside settings UI |
| `gpuAcceleration` | Same — dead on Compose path |
| Host-side PTY re-batching | Andy already forwards `read()` chunks; does not stop per-char `requestRedraw()` |

## Reconciling uncapped ~61% vs `TerminalFrameLimiter` kdoc 47.6%

`TerminalFrameLimiter`’s existing kdoc (unchanged this iteration) says uncapped INTERACTIVE steady state was **47.6% of Andy's total process CPU**, from ~4.8ms/frame × ~38 renders/sec in `TerminalCanvasRenderer.renderTerminal`.

That **47.6% is not the same metric** as this bench’s uncapped row:

| | Kdoc 47.6% | This bench uncapped ~61% |
|--|------------|--------------------------|
| Quantity | **Share of process samples** attributed to `renderTerminal` (profiler fraction) | **Absolute** `processCpuTime/wall` for the whole JVM |
| Scope | Paint cost only | Parse + PTY + Compose + hyperlinks + GC + paint |
| Workload | Live agent CLI in full Andy | Synthetic stream in an isolated Compose test window |
| Implied absolute paint | 38 × 4.8ms ≈ **18% of one core** just for `renderTerminal` | — |

So they do **not** need to numerically agree. Re-measure after the pin fix still shows uncapped absolute process CPU at **~61%** (61.1% this run; 61.5% prior post-pin) — **not** closer to 47.6%. The earlier draft’s 58.6% was the same absolute metric in the same ballpark; it was **not** an artifact of the production-default leak (leak would mainly corrupt capped baseline cells; uncapped+both is also ~61%).

## What we shipped

1. **`DEFAULT_FPS = 15`** kept (only cap that can approach the band). Asserted in `TerminalFrameLimiterTest`.
2. **Both terminal surfaces** (shared `BossTermAppearance` → `BossTermBackend`):
   - `performanceMode = "throughput"`
   - `detectFilePaths = false`
   - Overrides: `-Dandy.terminal.performanceMode=…`, `-Dandy.terminal.detectFilePaths=true|false`
3. Harness pins A/B cells; durable results under `build/bossterm-pipeline-benchmark.txt`.

Expected active streaming CPU with shipped defaults: **~21–24%** (top of / slightly above the aspirational band), down from **~32%** at 15fps+latency+hyperlinks. Smoothness unchanged at 15fps.

## Can BossTerm be “fully performant” inside this CPU budget?

**No — not with a high-fps feel.** Best near-band configs stay at 15fps; 24fps+both is already ~28–29%; uncapped absolute process CPU is ~61%. Closed-source per-character redraw model is the ceiling. Renderer replacement (e.g. xterm.js / WebGL) is the next-iteration decision; **JCEF is not a dependency in this repo today**.

## Repro

```sh
./gradlew desktopTest --tests "app.andy.terminal.BossTermPipelineBenchmarkTest" \
  -Dandy.bench=1 -Dandy.bench.knobs=1 --rerun-tasks
# table → build/bossterm-pipeline-benchmark.txt
```
