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
