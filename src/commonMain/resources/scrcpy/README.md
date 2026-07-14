Bundled scrcpy server
=====================

Andy embeds `scrcpy-server` so packaged builds can run the Live mirror without
requiring users to install the full scrcpy desktop app.

Source:
- Project: https://github.com/Genymobile/scrcpy
- Version: 4.0
- License: Apache-2.0, copied in `LICENSE.scrcpy`
- SHA-256: 84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a

Update process
--------------

Yes: for Andy's embedded mirror, updating scrcpy is normally just replacing the
`scrcpy-server` payload. Andy does not bundle or launch the desktop `scrcpy`
executable, SDL UI, or scrcpy's host-side wrappers.

1. Install the target scrcpy version locally.
   - Homebrew example: `brew upgrade scrcpy`
   - Or install a specific release however you normally stage local tools.
2. Find the server payload from that install.
   - Homebrew Apple Silicon usually puts it at
     `/opt/homebrew/share/scrcpy/scrcpy-server`.
   - A pinned Cellar version may look like
     `/opt/homebrew/Cellar/scrcpy/<version>/share/scrcpy/scrcpy-server`.
3. Replace this directory's `scrcpy-server` file with that payload.
4. Copy the matching upstream license to `LICENSE.scrcpy` if it changed.
5. Update the `Version` and `SHA-256` lines above:
   `shasum -a 256 src/commonMain/resources/scrcpy/scrcpy-server`
6. Verify at least:
   `./gradlew --no-daemon desktopTest --tests app.andy.desktop.service.DesktopServicesMockDeviceTest`
   `./gradlew --no-daemon compileKotlinDesktop`

Important compatibility note: `DesktopMirrorEngine` passes the scrcpy server
version string in its `app_process` command. If a future scrcpy server rejects
Andy with a protocol/version error, update that server version argument in
`DesktopServices.kt` alongside this binary.
