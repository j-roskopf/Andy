# Terminal Rust engine

**Date:** 2026-08-13  
**Branch:** `terminal-rust-engine-spike`  
**Status:** Rust (`alacritty_terminal` via JNI) is the **only** desktop VT engine. BossTerm has been removed.

## Architecture

| Piece | Role |
|-------|------|
| `native/andy-terminal-engine` | Rust cdylib: parse, grid, DEC 2026, palette, mouse flags, scrollback |
| `AndyPty` | Pty4J spawn/read/write/resize/kill (no BossTerm) |
| `RustTerminalBackend` | Live PTY session + coalesced paint |
| `RustScrollbackReplay` / `RustScrollbackCapture` | History UI + styled transcript derivation |
| `RustTerminalCanvas` | Compose/Skia paint, keys, SGR mouse, selection, local scroll |
| `TerminalPalette` | Andy-owned ANSI 16 + fg/bg/cursor/selection per Settings theme |

## Packaging (macOS / Linux / Windows)

Gradle `buildAndyTerminalEngineNative` builds the **host** library and stages it under:

```
build/native/andy-terminal-engine/
  macos-arm64/libandy_terminal_engine.dylib
  macos-x86_64/libandy_terminal_engine.dylib
  linux-x86_64/libandy_terminal_engine.so
  linux-arm64/libandy_terminal_engine.so
  windows-x86_64/andy_terminal_engine.dll
  windows-arm64/andy_terminal_engine.dll
```

`desktopProcessResources` copies whichever slices are present into the app jar.  
**Release Deb/Msi must be built on Linux/Windows (or a CI job that produces those natives)** so the matching `.so` / `.dll` is packaged. There is no mac→linux/windows cross-compile toolchain in the default developer image.

Override for local experiments:

```
-Dandy.terminal.engine.native=/path/to/libandy_terminal_engine.dylib
```

## Run / test

```sh
./gradlew run
./gradlew desktopTest --tests "app.andy.terminal.rust.*"
(cd native/andy-terminal-engine && cargo test)
```

## Residual risks

- First notarized macOS build with the dylib still needs one real `packageReleaseDmg` / notary pass.
- Linux/Windows packaging quality depends on CI (or local builds) on those hosts.
- History replay for ended chats uses the Rust engine (BossTerm replay path deleted).
