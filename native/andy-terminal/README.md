# andy-terminal (libghostty)

GPU-native terminal backend for Andy on macOS/Linux.

## Status

The libghostty C API is alpha. This module is intentionally thin:

- JNI bindings mirror the pattern in `native/andy-mirror/`
- Host Ghostty's GPU surface in a native child window layered into the Compose window
  (same problem solved by `GpuMirror*` + Metal overlay)
- All churn stays behind `app.andy.terminal.TerminalSession`

Until a pinned libghostty commit is linked and `LibghosttyBackend.isAvailable()`
returns true, Andy selects `JediTermBackend` on every platform.

## Force backend (dev)

```
-Dandy.terminal.backend=jediterm
-Dandy.terminal.backend=libghostty
```
