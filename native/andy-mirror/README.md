# Andy Mirror native component

## scrcpy-server (device side)

Andy bundles the official Apache-2.0 `scrcpy-server-v4.0` release binary at
`src/commonMain/resources/scrcpy/scrcpy-server` (see `SOURCE_PIN.json` for the
SHA-256). That JAR/DEX runs on the Android device to capture H.264 and inject
input. Andy does **not** ship or launch the scrcpy desktop client, SDL UI, or
any of the upstream C host sources.

`verifyScrcpyServer` hash-checks that pinned binary before desktop resources are
processed. Updating scrcpy is replacing the binary + pin + `LICENSE.scrcpy` —
there is no vendored client tree.

## In-process Metal (macOS)

`buildAndyMirrorJniMacArm64` / `MacX64` build the JVM library that presents
VideoToolbox-decoded frames through a borderless AppKit surface letterboxed over
the Live Canvas. Compose Desktop cannot reliably composite an in-process
`CAMetalLayer`, so this overlay is the supported inline GPU path. Auto falls
back to FFmpeg/Swing CPU presentation when native init fails.

## In-process Vulkan (Linux x86_64)

`buildAndyMirrorJniLinuxX64` builds `libandy-mirror-jni.so` with the same
`GpuMirrorJni` hub as macOS. H.264 is decoded with NVDEC (`h264_nvdec` /
`h264_cuvid`) when CUDA is available, otherwise software libavcodec, then
uploaded as NV12 into Vulkan textures. Presentation is an input-transparent
override-redirect X11 window (XWayland on typical Wayland desktops) stacked
over the Live Canvas.

Requires system `libvulkan`, `libX11`, `libXfixes`, `libavcodec`, and
`libavutil`. Overlay shaders are compiled at build time with `glslangValidator`
or `glslc`. Native Wayland (no X11) is out of scope.
