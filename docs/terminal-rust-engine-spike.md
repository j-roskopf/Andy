# Terminal Rust engine spike (Phase 0)

**Date:** 2026-08-13  
**Branch:** `terminal-rust-engine-spike` (off `terminal-perf-iter1`)  
**Goal:** Prove the hard parts of replacing BossTerm’s VT/grid engine with `alacritty_terminal` behind a JVM FFI boundary, while keeping Compose/Skia as the renderer. Production terminal path untouched.

## Verdict

**Go for Phase 1 (production integration design + first backend behind a feature flag).**

The hard questions cleared:

| Question | Result |
|----------|--------|
| Does `alacritty_terminal` expose feed-bytes + grid/cell/attr/cursor + resize + alt-screen? | **Yes** — wrapped in `native/andy-terminal-engine` |
| DEC 2026 synchronized updates? | **Yes** — via `vte` 0.15 (`CSI ? 2026 h/l`), with buffering before grid mutation |
| JVM boundary tech? | **Hand-rolled JNI (`jni-rs`)** — see decision below |
| E2E Kotlin → Rust → grid assert? | **Passing** (`RustTerminalEngineTest`) |
| Gradle cargo build + resource bundling like existing JNI dylibs? | **Yes** (`buildAndyTerminalEngineNative` → `desktopProcessResources`) |
| macOS notarization? | **Not fully verified end-to-end**; path is clear and matches existing dylibs (see below) |

No hard blocker found. JNI grid snapshot transfer is cheap enough that it is not a red flag for a coalesced repaint model.

## What was built (isolated)

```
native/andy-terminal-engine/          # Rust cdylib + rlib
  src/engine.rs                       # alacritty_terminal wrapper + DEC2026 tests
  src/ffi_jni.rs                      # JNI exports for engine + round-trip probe
  src/ffi_uniffi.rs                   # UniFFI trivial round-trip probe only
src/desktopMain/.../terminal/rust/    # Kotlin loaders / engine handle (unused by BossTerm)
src/desktopTest/.../terminal/rust/    # E2E + transfer microbench
src/desktopTestMacOs/...              # UniFFI vs JNI round-trip (macOS only)
```

Production files (`BossTermBackend`, `AndyTerminalView`, `TerminalFrameLimiter`, surfaces) were **not** modified. BossTerm dependency remains.

### Repro

```sh
# Rust unit tests (API + DEC 2026)
(cd native/andy-terminal-engine && cargo test)

# Gradle: cargo → dylib → resources → Kotlin tests
./gradlew desktopTest --tests "app.andy.terminal.rust.*"

# Optional JNI transfer microbench
./gradlew desktopTest --tests "app.andy.terminal.rust.RustTerminalJniTransferBench" \
  -Dandy.rust.term.bench=1
# → build/rust-terminal-jni-transfer-bench.txt
```

## alacritty_terminal API confirmation

Crate: `alacritty_terminal` **0.26.0** (depends on `vte` 0.15 with `ansi` feature).

| Capability | How |
|------------|-----|
| Feed raw bytes | `vte::ansi::Processor::advance(&mut Term, bytes)` |
| Grid / cells / attrs | `Term::grid()` → `Cell { c, fg, bg, flags }` |
| Cursor | `grid.cursor.point` |
| Resize | `Term::resize(Dimensions)` |
| Alternate screen | DECSET/DECRST `1049` → `TermMode::ALT_SCREEN` |
| Scrollback | `Config.scrolling_history` (spike sets 10_000) |

Andy keeps owning PTY spawn via existing `pty4j` (unchanged decision). The Rust side is headless parse/state only.

## DEC 2026 synchronized-update status

**Supported.**

- `vte` maps private mode **2026** → `NamedPrivateMode::SyncUpdate`.
- While sync is active, `Processor` buffers bytes and does **not** mutate the grid until `CSI ? 2026 l` (or `stop_sync` / buffer overflow / timeout).
- `Term`’s `set_private_mode(SyncUpdate)` is intentionally a no-op; buffering lives in the parser (same design as Alacritty’s event loop).
- Spike tests cover:
  - Rust: `dec_2026_buffers_until_end_of_sync`, `dec_2026_stop_sync_flushes_on_timeout_path`
  - JNI E2E: `RustTerminalEngineTest.dec2026BuffersUntilEndAcrossJni`

This matters because agent CLIs emit DEC 2026; BossTerm’s interaction with Andy’s frame limiter was already a known complexity. With Andy owning the engine, DEC 2026 becomes “don’t snapshot/paint while `sync_buffered_bytes > 0`” — Andy’s choice, not a closed-source redraw storm.

## JVM boundary decision

Environment constraint: **JDK 21** (Corretto 21.0.11). Panama FFM is stable only from JDK 22; on 21 it is preview/incubating. **FFM was not spiked** for that reason.

Spiked with working round-trips:

| Tech | Round-trip | Notes |
|------|------------|-------|
| Hand-rolled JNI (`jni-rs` 0.21) | `JniRoundTrip.nativeAdd` ✅ | Matches existing andy-mirror / notifications / voice packaging (`System.load` of packaged `.dylib`) |
| UniFFI 0.32 → Kotlin/JNA | `uniffiRoundTripAdd` ✅ | Generated ~1k-line Kotlin helper; loads via JNA `Native.register`; desktop JVM works, but Android-first ergonomics + Gobley needed for true KMP |

### Decision: **JNI for the engine API**

Rationale:

1. **Existing Andy pattern** — macOS native helpers already ship as resource dylibs extracted to `~/.andy/...` and `System.load`’d. No new packaging paradigm.
2. **No JNA indirection on the hot path** — UniFFI’s Kotlin backend is JNA; we already pay JNI once. For frequent grid snapshots, fewer moving parts matter.
3. **UniFFI cost without multi-language payoff** — Andy’s consumer is JVM/Compose desktop only for this engine. Generated scaffolding + checksum contracts are overhead for a single host language.
4. **Grid snapshot shape** — hand-rolled JNI can evolve toward packed primitive arrays / direct buffers without fighting UniFFI’s type model.
5. **UniFFI still proven** — kept as a macOS-only probe (`desktopTestMacOs` + Gradle `generateAndyTerminalEngineUniffi`) so the comparison is evidence-based, not docs-based.

Panama/FFM remains attractive **if/when** the bundled JDK moves to 22+, especially for bulk grid transfers via `MemorySegment`. Revisit then; not a Phase-1 prerequisite.

## E2E proof

`RustTerminalEngineTest.ansiSequenceProducesExpectedGridState`:

1. Kotlin feeds `\u001B[1;31mOK\u001B[0m hi` through JNI into Rust.
2. Asserts viewport text `"OK hi"`, cursor `(0,5)`, grid chars, and bold attrs on `OK` only.

Also covered across JNI: DEC 2026 buffering and alt-screen 1049 round-trip.

## Packaging / notarization

### What works now

- Gradle `buildAndyTerminalEngineNative` runs `cargo build --release --features uniffi-cli`.
- Stages `build/native/andy-terminal-engine/macos-{arm64,x86_64}/libandy_terminal_engine.dylib`.
- `desktopProcessResources` copies it under classpath `andy-terminal-engine/...` (same pattern as andy-mirror).
- Kotlin loader: `RustTerminalNative` → extract to `~/.andy/terminal-engine/...` → `System.load`.
- Release dylib size on this machine: **~777 KB** (arm64). Linker marks it **adhoc / linker-signed**.

### Notarization — verified vs not

| Item | Status |
|------|--------|
| Dylib builds and loads under dev (adhoc signature) | ✅ Verified |
| Bundling path analogous to andy-mirror / andy-voice | ✅ Wired |
| App entitlements include `disable-library-validation` | ✅ Already true (`packaging/macos/entitlements.plist`) — same escape hatch existing JNI dylibs rely on when extracted outside the app bundle |
| Developer ID Application identity present on this machine | ✅ Seen via `security find-identity` |
| Re-sign dylib with Developer ID + hardened runtime inside release app | ⚠️ **Not executed** this spike (no full `packageReleaseDmg` / `notarizeReleaseDmg` run) |
| Apple notary service acceptance of the new dylib | ⚠️ **Not verified** |

**What Phase 1 needs for notarization confidence:**

1. Ensure release packaging codesigns `libandy_terminal_engine.dylib` with the Developer ID identity and `--options runtime` (either while still inside app resources, or immediately after extract — prefer signing the bundled copy before notarization, like other embedded natives).
2. Run the existing `packageReleaseDmg` → `notarizeReleaseDmg` pipeline once with the dylib included and confirm notary success.
3. Unlike pty4j’s **spawn helper executable** inside a jar (special `hardenMacReleasePty4jSpawnHelper` task), this is a plain dylib resource — closer to andy-mirror than to pty4j. The pty4j landmine is real but **not the same shape**; do not assume the spawn-helper jar rewrite is required here.

Honest residual risk: first notarized build with a new native library sometimes surfaces Gatekeeper / library-validation edge cases. Budget one release-pipeline iteration in Phase 1; not a reason to stop the spike.

## JNI transfer overhead (red-flag check)

Measured on Apple Silicon with `-Dandy.rust.term.bench=1`:

| Snapshot | Grid | Avg / call |
|----------|------|------------|
| `gridChars()` (4800 chars across JNI) | 120×40 | **~34 µs** |
| `viewportText()` | 120×40 | **~31 µs** |

At 60 FPS, ~34 µs is ~0.2% of a 16.7 ms frame — negligible next to Compose/Skia paint. Even a naïve full-grid String snapshot is fine for Phase 1; packed `IntArray`/`ByteBuffer` can wait until profiling says otherwise.

## Phase 1 (in-app) — landed

Opt-in Actions dock path:

| Piece | Location |
|-------|----------|
| Flag | `-Dandy.terminal.engine=rust` (via `-Pandy.terminal.engine=rust` or `ANDY_TERMINAL_ENGINE=rust`) |
| Backend | `RustTerminalBackend` — PTY via BossTerm `DesktopProcessService`, tee, `advance`, coalesced paint @ 60fps |
| Canvas | `RustTerminalCanvas` — Compose/Skia + keyboard encoder |
| Surface | `ProjectTerminalSurface` mounts Rust canvas when the run is rust-backed |
| Factory | `TerminalSessions` DirectPty (non-`agentCli`) selects Rust when enabled + dylib loads |

Agent chat / tmux attach remains BossTerm.

### How to test in the app

```sh
./gradlew run -Pandy.terminal.engine=rust
# or: ANDY_TERMINAL_ENGINE=rust ./gradlew run
```

Open an **Actions** project terminal (shell). Type normally; arrows / Ctrl-C work. If the dylib fails to load, Andy falls back to BossTerm.

```sh
./gradlew desktopTest --tests "app.andy.terminal.rust.*"
```

## Go / no-go recommendation

### Go — dogfood the Actions path; then expand

Next:

1. More input (mouse, bracketed paste) as TUIs need it.
2. Push Andy Settings theme palette into Rust (today: hard-coded One Dark–ish ARGB).
3. Agent chat / `TmuxAttach` behind the same flag once input is enough.
4. Developer ID sign the dylib in the release graph + one notarization pass.
5. Keep BossTerm until Rust covers agent CLIs + Actions.

### Red flags that would flip this to no-go (none observed)

- DEC 2026 unsupported → would break agent CLI rendering assumptions. **Not the case.**
- JNI snapshot ≫ paint cost → would recreate CPU pain. **Not the case (~30 µs).**
- Notarization impossible for cargo cdylibs → would block shipping. **Unlikely** given existing dylib precedent + entitlements; still needs one real notarize pass.
- JDK forced into fragile FFM preview → avoided by choosing JNI.

### Out of scope (later phases)

- Full VT feature parity / selection / find / hyperlinks
- Linux + Windows cdylib CI matrix (spike is macOS arm64)
- Removing BossTerm
- Replacing Compose/Skia with another renderer
