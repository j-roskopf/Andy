# Andy Mirror native component

## scrcpy-server (device side)

Andy bundles the official Apache-2.0 `scrcpy-server-v4.0` release binary at
`src/commonMain/resources/scrcpy/scrcpy-server` (see `SOURCE_PIN.json` for the
SHA-256). That JAR/DEX runs on the Android device to capture H.264 and inject
input. Andy does **not** ship or launch the scrcpy desktop client, SDL UI, or
any of the upstream C host sources.

`packageAndyMirrorScrcpyServer` copies and hash-checks that pinned binary into
desktop resources. Updating scrcpy is replacing the binary + pin +
`LICENSE.scrcpy` — there is no vendored client tree.

## In-process Metal (macOS)

`buildAndyMirrorJniMacArm64` / `MacX64` build the JVM library that presents
VideoToolbox-decoded frames through a borderless AppKit surface letterboxed over
the Live Canvas. Compose Desktop cannot reliably composite an in-process
`CAMetalLayer`, so this overlay is the supported inline GPU path. Auto falls
back to FFmpeg/Swing CPU presentation when native init fails.

## Optional sidecar

An Andy release may also bundle a product-owned `andy-mirror` executable for
platforms that cannot host an in-process surface (e.g. Wayland pop-out). This
checkout contains the protocol contract; it does not fabricate signed sidecars.
The executable uses one JSON object per line on stdin/stdout:

- commands: `start`, `attach`, `resize`, `overlay`, `inspect`, `input`, `stop`
- events: `ready`, `stats`, `failure`, `stopped`

`ready` must include a decoder, renderer, `decoderHardwareBacked`,
`rendererHardwareBacked`, and `hardwareBacked`. The aggregate `hardwareBacked`
flag is true only when both component flags are true.

Staged sidecar paths before desktop packaging:

- `dist/macos-arm64/andy-mirror`
- `dist/macos-x86_64/andy-mirror`
- `dist/windows-x86_64/andy-mirror.exe`
- `dist/linux-x86_64/andy-mirror`
- `dist/linux-arm64/andy-mirror`

`./gradlew verifyAndyMirrorReleaseInputs` verifies staged sidecars when present.
