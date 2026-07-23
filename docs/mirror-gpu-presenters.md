# Mirror GPU presenters

## Problem

Andy’s macOS GPU mirror path uses a **single global** VideoToolbox decoder and **one**
borderless Metal overlay window. Live, pop-outs, Android, and iOS all compete for that
singleton. Promoting/demoting presentation in Kotlin is fragile and cannot reliably show
Android + iPhone pop-outs at the same time.

## Goals

1. **Multiple simultaneous GPU surfaces** — each Live pane or pop-out window owns a presenter.
2. **Multiple simultaneous decode pipelines** — Android scrcpy and iOS SimulatorKit can run together.
3. **Optional fan-out** — one decode stream can drive multiple presenters (same device, Live + pop-out).
4. **No presentation stealing** — geometry changes on one window never move another window’s Metal overlay.

## Non-goals (v1)

- Windows/Linux GPU (macOS arm64/x64 only).
- More than one scrcpy session to the **same** Android device (OS limitation); fan-out uses one decode.
- Per-presenter VideoToolbox decoders for the same H.264 stream (one decoder, N presenters).

## Architecture

```
┌─────────────────┐     H.264      ┌──────────────────┐   CVPixelBuffer   ┌─────────────────┐
│ DesktopMirror   │ ─────────────► │ GpuMirrorDecoder │ ────────────────► │ GpuMirrorPresenter │──► NSWindow + Metal
│ Engine (scrcpy) │                │  (per session)   │    fan-out        │  (per Canvas)        │
└─────────────────┘                └──────────────────┘                   └─────────────────┘
                                              ▲
┌─────────────────┐     BGRA                   │
│ DesktopIosMirror│ ───────────────────────────┘
│ Engine (sim)    │
└─────────────────┘
```

### GpuMirrorDecoder

- Owns VideoToolbox H.264 decode **or** accepts iOS BGRA frames directly.
- Retains `latest_pixels` for overlay repaint and bug capture.
- Holds latency/stats state for that stream.
- Not tied to any UI surface.

### GpuMirrorPresenter

- Owns one borderless AppKit child window + `CAMetalLayer` / `MTKView`.
- Letterboxes decoded frames into the Swing `Canvas` host (or fills host for pop-outs).
- Owns overlay uniforms (grid, ruler, picker) for that surface.
- Subscribes to exactly one decoder; many presenters may share one decoder.

### Shared process resources

- One `MTLDevice`, command queue, render pipeline, and `CVMetalTextureCache` for the process.
- Decoders and presenters are cheap relative to decode sessions.

## Kotlin API

```kotlin
// One per MirrorEngine session (scrcpy or iOS capture).
class GpuMirrorPipeline {
    val decoderId: Long
    fun createPresenter(): GpuMirrorPresenter
    fun consumeH264(packet: ByteArray): Boolean
    fun presentPixelBuffer(/* iOS */): Unit  // future / internal
    fun destroy()
}

// One per Swing Canvas that shows GPU video.
class GpuMirrorPresenter {
    val presenterId: Long
    fun openOverlay(host: Component): Boolean
    fun updateGeometry(host: Component)
    fun setVisible(visible: Boolean)
    fun setContentSize(width: Int, height: Int)
    fun setFillHost(fill: Boolean)
    fun repaint()
    fun destroy()
}
```

`GpuMirrorHostRegistry` maps each realized `Canvas` → `GpuMirrorPresenter` (replaces
`NativeMirrorHostRegistry` promotion logic).

## Lifecycle

| Event | Action |
|-------|--------|
| Mirror `connect()` | `GpuMirrorPipeline.create()` |
| Canvas `addNotify()` | `presenter.openOverlay(canvas)`, register host |
| Canvas resize/move | `presenter.updateGeometry(canvas)` |
| H.264 packet / iOS frame | `pipeline` → decoder → fan-out to all presenters |
| Canvas `removeNotify()` | unregister, `presenter.destroy()` (decoder stays if other presenters exist) |
| Mirror `disconnect()` | destroy pipeline (decoder + all its presenters) |
| Pop-out window closes | destroy that presenter; decoder stays if Live still connected |

## Pop-out policy (simplified)

- Each pop-out `MirrorEngine` gets its **own** `GpuMirrorPipeline` (own scrcpy or iOS capture).
- Each pop-out window gets its **own** `GpuMirrorPresenter`.
- **Remove** GPU demotion / CPU pool fallback for pop-outs on macOS GPU-capable builds.
- Keep `DesktopPopOutMirrorPool` only as fallback when `GpuMirrorPipeline.create()` fails.

## Native JNI

New methods on `NativeMirrorJni` (legacy methods delegate to default pipeline until removed):

| Method | Purpose |
|--------|---------|
| `nativeCreateGpuDecoder()` | Returns decoder handle |
| `nativeDestroyGpuDecoder(id)` | Tears down VT session |
| `nativeCreateGpuPresenter(decoderId)` | Returns presenter handle |
| `nativeDestroyGpuPresenter(id)` | Closes overlay window |
| `nativeOpenGpuPresenterOverlay(id)` | Creates Metal overlay |
| `nativeUpdateGpuPresenterGeometry(id, …)` | Positions overlay |
| `nativeConsumeH264ForDecoder(id, packet)` | Feed scrcpy |
| `nativePresentPixelBufferForDecoder(id, …)` | iOS BGRA path |
| `nativeRepaintGpuPresenter(id)` | Overlay-only refresh |

Implementation lives in `native/andy-mirror/jni/andy_mirror_hub.m`.

## Migration phases

1. **Hub + JNI + Kotlin types** (this change) — parallel to legacy singleton.
2. **Wire `DesktopMirrorEngine` / `DesktopIosMirrorEngine`** — each session uses a pipeline.
3. **Wire pop-outs** — drop promote/demote; one pipeline per pop-out engine.
4. **Remove legacy singleton** — delete `presentationOwner`, CPU demotion, shared overlay globals.
5. **Fan-out** — Live + pop-out same Android device share one decoder (one scrcpy).

## Testing

- Unit: presenter registry, pipeline create/destroy, decoder fan-out to two presenters (macOS arm64).
- Smoke: Android pop-out + iOS pop-out simultaneously.
- Regression: existing `NativeMirrorPresentationLifecycleTest` adapted to presenter ids.
