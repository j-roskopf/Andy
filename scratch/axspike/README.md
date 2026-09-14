# AX spike probes

Swift CLI probes used to measure the assumptions in `docs/computer-use.md` §0.
Run on macOS 26.6.2, Apple Silicon.

    swiftc -O -o <name> <name>.swift

**These must be launched as a child of `Andy.app`** (e.g. from Andy's terminal).
Accessibility is inherited from the responsible process — reparented to launchd
they report `AXIsProcessTrusted=false`. That is itself finding §0.1.

| Probe | Measures |
| --- | --- |
| `axprobe <App> [--windows-only]` | Tree size, depth, press/label counts, pruning tiers, walk time |
| `viewport <App>` | Viewport-clipped node counts and final agent payload size (§4.1) |
| `labels <App>` | Label coverage and control-role fidelity over pressable nodes (§0.4) |
| `axpress <App> [--list] [--press <label>]` | Enumerate pressable elements; functional press |
| `verify <App> <radioLabel>` | Press with before/after state read-back (found the §5.1 AXError bug) |
| `secure <App>` | Secure-field subrole detection (§0.5) |
| `axenable <App>` | Whether `AXManualAccessibility` changes window exposure (§0.7) |
| `winlist` | Real on-screen windows via CGWindowList — the §0.6 cross-check |

`press.html` is the Chromium press fixture: native button, ARIA div button,
link, checkbox, text input, password input. Each control sets `document.title`
so a press can be verified out-of-band via AppleScript.
