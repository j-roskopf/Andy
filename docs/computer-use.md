# Computer Use

Letting an Andy agent navigate and drive the host desktop.

This document is the design produced from a full decision review, then revised
against a measurement spike run on macOS 26.6.2 (Apple Silicon). Every
"Decision" below was chosen explicitly; the rationale and the rejected
alternatives are recorded so the tradeoffs survive the implementation.

**The three assumptions the design rested on have now been measured** — see §0.
Two were confirmed, one changed the design.

---

## 0. Spike results

Probes were Swift CLI binaries against live apps. Sources are reproduced in
`scratch/axspike/` and are cheap to re-run when macOS or the target apps update.

### 0.1 TCC attribution — CONFIRMED, with hard evidence

`kTCCServiceAccessibility` in `/Library/Application Support/com.apple.TCC/TCC.db`
lists `com.joetr.andy | 2` (allowed). The decisive test was running **the same
unsigned binary** under two different parents:

| Launch context | `AXIsProcessTrusted()` |
| --- | --- |
| Child of `Andy.app` (`Andy.app → npm → node → zsh → probe`) | **`true`** |
| Reparented to `launchd` via `launchctl submit` | **`false`** |

Same binary, same user, same machine. This is exactly the responsible-process
inheritance model §2 assumed, now proven rather than argued:

- A helper or child process **spawned by `Andy.app` inherits Andy's grant**,
  even unsigned, even from `/tmp`.
- `andyd` launched by `launchd` or `scripts/andyd-launcher.sh` is **not
  trusted**, and would fail with no useful error.

This validates the §2 decision (execute in the UI process) and means no separate
signed helper is needed — a child of the app bundle is sufficient.

### 0.2 Accessibility tree size — CONFIRMED TRACTABLE, but only with viewport clipping

Raw scoped trees can be large. Viewport clipping is what makes them usable:

| App / content | All nodes | In viewport | Visible+labeled controls | Visible text | **Agent payload** |
| --- | --- | --- | --- | --- | --- |
| Chrome — heavy Wikipedia table | **11,105** | 584 | 161 (10 KB) | 206 (6 KB) | **17 KB** |
| Cursor — Electron IDE | 814 | 770 | 131 (10 KB) | 191 (4 KB) | **14 KB** |
| Finder — list view | 564 | 412 | 19 (1 KB) | 106 (3 KB) | **4 KB** |

Chrome on a normal page (GitHub PR list) was 890 nodes / 118 KB raw / 0.12 s.
The heavy Wikipedia page was 11,105 nodes / 1.4 MB raw / 1.41 s.

**A naive full dump of the heavy page is 1.4 MB — unusable. The same page after
scoping, viewport clipping, and pruning is 17 KB (~4–5k tokens).** An 82×
reduction, and the design's central risk is retired. Full-tree walks ran
0.03–1.41 s, fast enough for an interactive loop.

This closes what §16 previously listed as the main open item: the pruning
strategy is now specified, not speculative (§4.1).

### 0.3 `AXPress` — CONFIRMED WORKING, but error codes lie

Functional presses, each verified by observing a real side effect:

| Target | Result |
| --- | --- |
| Chromium `<button>` | ✅ `document.title` changed |
| Chromium `<div role="button">` | ✅ `document.title` changed |
| Chromium `<input type=checkbox>` | ✅ `document.title` changed |
| Finder `AXRadioButton` (native Cocoa) | ✅ view switched list → column |

**Critical caveat found by accident.** `AXUIElementPerformAction` on Finder's
radio button returned **`-25205` (`kAXErrorAttributeUnsupported`) — and the
press still worked.** The view demonstrably changed. A second press on a sibling
returned `0`. **The AXError return code is not a reliable success signal.** The
actuation layer must verify by read-back, never by return code. This is now a
hard requirement in §5.

### 0.4 Label and role quality — WORSE THAN ASSUMED for Electron and Compose

Measured over pressable nodes, counting only genuinely identifying labels
(`AXTitle`, `AXDescription`, `AXHelp`, `AXValue`, `AXPlaceholderValue` — the
generic `AXRoleDescription` is excluded, since "button" identifies nothing):

| App | Pressable | Labeled | **Sane control role** |
| --- | --- | --- | --- |
| Google Chrome | 67 | 94% | **100%** |
| ChatGPT (Electron) | 161 | 98% | **100%** |
| Activity Monitor | 20 | 85% | 95% |
| Finder | 29 | 65% | 96% |
| Cursor (Electron IDE) | 172 | 76% | **31%** |
| Andy (Compose/JVM) | 118 | 98% | **21%** |
| Terminal | 3 | 33% | 100% |

Label coverage is decent. **Role fidelity is not.** Cursor exposes 36 pressable
`AXGroup`s; Andy's Compose UI reports its buttons as `AXStaticText` (67 of them
advertise `AXPress`). Consequences:

- §9's high-consequence classification **cannot rely on role alone.** A
  destructive control in a Compose or Electron app may present as
  `AXStaticText`. Classification must key on **label first**, treating role as a
  weak secondary signal.
- Including "any node advertising `AXPress`" is mandatory; filtering to a
  control-role allowlist would miss 69% of Cursor's and 79% of Andy's controls.

### 0.5 Secure fields — CONFIRMED DETECTABLE in Chromium

A web `<input type="password">` is exposed as `AXTextField` with subrole
**`AXSecureTextField`**, the same as a native Cocoa secure field. §9's
unconditional block on typing into secure fields is implementable as specified,
and it covers browser login forms — the case that matters most.

### 0.6 Two measurement traps to avoid in the implementation

**No windows ≠ no accessibility.** Chrome, Slack, Obsidian, and TextEdit
initially reported `windows=0` with `AXError` success. They were simply running
with all windows closed. Cross-check `CGWindowListCopyWindowInfo` before
reporting an app as inaccessible, or users will get a wrong diagnosis.

**The menu bar dominates a full-app walk.** Chrome's app-level tree was 691
`AXMenuItem` out of 760 nodes; TextEdit's was 309 of 350. Scoping to
`kAXWindowsAttribute` removes it. Menus are genuinely useful for driving apps,
so expose them through a **separate on-demand tool** rather than inlining them
in every dump (§13).

### 0.7 Not yet measured

- Slack and Obsidian had no windows open during the spike; Electron data comes
  from Cursor and ChatGPT only.
- `AXManualAccessibility` returned `-25205` on Chrome but the tree was already
  populated, so whether it is required for a cold Electron app is unresolved.
  Set it defensively and tolerate the error.
- Windows UIA and Linux AT-SPI are entirely unmeasured; §0 findings are macOS-only.

---

## 1. Scope and shape

**Decision — target: the user's real desktop, not a VM.**
`trycua/cua` was the starting reference. Its value is the sandbox layer (Lume
VMs on Apple Silicon, Docker on Linux, Windows Sandbox) plus a Python agent
loop. Driving the real desktop makes that layer irrelevant, and a Python sidecar
wrapping CGEvent/XTEST is a bad trade for Andy. cua remains the leading
candidate for the deferred Phase 4 sandbox (§10).

**Decision — surface: MCP tools, usable by any agent lane.**
Andy has no direct model-API code. Every lane is a CLI subprocess, and
`andyd` exposes MCP over a unix socket (`DesktopServices.kt:333`) and optionally
HTTP. Computer use therefore ships as MCP tools, which every lane gets for free.

The cost, stated plainly: Claude Code and Codex CLI do **not** expose the
provider-native computer-use tool (`computer_20250124`,
`computer-use-preview`). Those are built-in tool types the model was trained
against. Through generic MCP tools the model loses that affordance and pixel
grounding degrades. §4 is the mitigation, and it is the reason the design is
accessibility-first rather than screenshot-first.

**Known limitation (revised from the original plan).** The first sketch claimed
the tool surface would mirror Anthropic's schema so a native loop could drop in
later. That is not achievable: Anthropic's `computer` tool is purely
coordinate-based and cannot express an element id, an element action, or an
accessibility dump. A future native loop can be derived from the *pixel fallback
subset* only, and would not inherit the a11y advantages.

---

## 2. Where code runs

Andy has three deployment modes (`DesktopServices.kt`):

| Mode | MCP server lives in | Computer use |
| --- | --- | --- |
| Monolithic (default) | GUI process, daemon embedded (line 686, 921) | Direct call — no proxy |
| Daemon-client | standalone `andyd`, GUI attaches (line 399) | Proxied to GUI |
| Headless `andyd` only | `andyd` | **Unavailable** — capability error |

**Decision — injection executes in the Andy desktop UI process.**

Two hard constraints force this:

1. **`andyd` is headless.** `AndydMain.kt:22` and `AndydProcess.kt:133` both set
   `java.awt.headless=true`. A headless JVM cannot construct `java.awt.Robot` —
   it throws on instantiation.
2. **macOS TCC attribution.** Accessibility and Screen Recording grants attach
   to the responsible process / signing identity. A standalone `andyd` launched
   by `scripts/andyd-launcher.sh` is attributed to the terminal or launchd, so
   the user would grant Accessibility to iTerm and watch Andy keep failing.
   `HostScreenshotCapture.kt:34` already hedges on this ambiguity ("the
   Andy/andyd process") for the read-only case.

Additionally, a remote `andyd` over SSH may have no GUI login session at all.
There is no desktop to drive; that must be a clean capability error.

**Interface:**

```kotlin
interface ComputerUseService {
    suspend fun capabilities(): ComputerUseCapabilities
    suspend fun dump(scope: SessionScope): HostAccessibilityTree
    suspend fun act(request: ComputerAction): ComputerActionResult
}
```

Implementations: `LocalComputerUseService` (GUI process) and
`RemoteComputerUseService` (andyd → GUI RPC). Follow the existing
`Swappable*Service` pattern in `data/remote/.../remote/` for the local↔remote
swap, and `RemoteHostCapabilityScanner` for capability reporting.

Suggested module: `data/computer-use`, with `native/andy-computer-use` for JNI.

---

## 3. Platforms

**Decision — v1 targets macOS, Windows, and Linux/X11. Wayland is detected and
refused with guidance.**

Andy already ships Dmg + Msi + Deb (`composeApp/build.gradle.kts:573`) plus
Flatpak, and `DhuHostKind` (`DhuModels.kt:40`) already enumerates
`MacOs / Windows / LinuxX11 / LinuxWayland / Unsupported`. The existing
`DhuWindowHost` / `MacOsDhuWindowHost` / `WindowsDhuWindowHost` /
`X11DhuWindowHost` trio is the structural template to copy.

| Platform | Input | Capture | A11y | Permission |
| --- | --- | --- | --- | --- |
| macOS | `CGEventPost` | ScreenCaptureKit / `CGWindowListCreateImage` | AX API | **Accessibility + Screen Recording** (TCC) |
| Windows | `SendInput` | `Windows.Graphics.Capture` / BitBlt | UI Automation | **None** |
| Linux X11 | XTEST | XGetImage / XComposite | AT-SPI (D-Bus) | **None** |
| Linux Wayland | — | — | — | Blocked by design |

Notes that matter:

- **macOS is hardest but best-behaved.** There is no programmatic way to
  *request* these grants — only to detect denial (`AXIsProcessTrusted`,
  `CGPreflightScreenCaptureAccess`) and deep-link to System Settings. Historically
  the process must restart after a grant.
- **Windows has zero OS consent.** `SendInput` just works. That is a safety
  problem, not a convenience: Andy's own gating is the entire safety story on
  Windows. Also, UIPI silently drops input into elevated windows, which surfaces
  as a flaky agent rather than a permission error — detect and report it.
- **Wayland genuinely cannot do global input injection.** The escape hatches are
  the XDG RemoteDesktop portal (libei, per-session consent, an entirely separate
  code path) or `ydotool` via `/dev/uinput` (root or a udev rule). Roughly as
  much work as the other three combined. `Main.kt:378` already documents Wayland
  breaking the Compose tray, so there is prior art for detect-and-degrade.

`HostScreenshotCapture` shells out to `screencapture`/`grim`/`scrot`/`import`.
Fine for a one-shot; far too slow for a click→capture→click loop and it gives no
control over coordinate space. Computer use needs native capture.

**Coordinate space.** All coordinates in the tool API are in a single virtual
desktop space in **logical points**, origin top-left of the primary display.
The backend converts to per-display physical pixels, handling HiDPI backing
scale factors and negative origins on multi-monitor layouts. Screenshots carry
their scale factor and origin in the result so the model never has to infer it.

---

## 4. Grounding: accessibility first

**Decision — resolve targets through the host accessibility tree; pixel
coordinates are an explicit fallback.**

Because MCP tools cost us the trained computer-use affordance (§1), pure
screenshot-and-guess produces long off-by-40px retry loops whose failure mode is
not a clean error — the agent clicks something adjacent and continues
confidently.

The a11y tree gives exact bounds, roles, and labels, so the agent addresses
element #47 rather than a coordinate. **The marginal permission cost is near
zero**: macOS AX needs the same Accessibility grant `CGEventPost` already
requires; UIA needs none; AT-SPI is usually already running.

Andy already models this exact shape for Android — `AccessibilityNode`
(`AccessibilityModels.kt:3`) with `bounds`, `clickable`, `text`,
`contentDescription`, `enabled`, `focused` — and the `ui_dump` /
`find_node_by_text` / `get_node_properties` tools are built around it. Reuse the
model and the inspector UI.

Coverage is uneven and the pixel path is not optional: Electron apps with
accessibility off, Java apps, games, canvas-heavy web content, and remote
desktop expose little or nothing. On Linux, some toolkits need
`GTK_MODULES` / `QT_ACCESSIBILITY` set.

### 4.1 The dump pipeline (measured, §0.2)

This is the algorithm that turned a 1.4 MB tree into a 17 KB payload. Order
matters — viewport clipping does the heavy lifting.

1. **Scope.** Root at the target app's `AXUIElement`, then descend only into
   `kAXWindowsAttribute`. This alone strips the menu bar, which is 91% of
   Chrome's app-level node count (§0.6).
2. **Viewport clip.** Take the window's `AXFrame` and keep only nodes whose own
   `AXFrame` intersects it with width and height > 1. **This is the dominant
   reduction: 11,105 → 584 nodes on the heavy page.** Offscreen and
   zero-size nodes are invisible to a user and useless to an agent.
3. **Classify.** A node is a *control* if it advertises `AXPress` (**not** if its
   role is in a control allowlist — see §0.4; that would miss 69% of Cursor's
   controls). A node is *text* if its role is `AXStaticText` or `AXHeading`.
4. **Require a label** for controls, from `AXTitle` → `AXDescription` → `AXHelp`
   → `AXValue` → `AXPlaceholderValue`. **Never `AXRoleDescription`** — it
   returns "button", which identifies nothing.
5. **Emit compactly.** Short keys, labels truncated to ~60–80 chars, bounds as a
   packed `x,y,w,h` string.

Budget from the measurements: 4 KB (Finder) to 17 KB (heavy web page).
Enforce a hard payload cap and report truncation explicitly rather than silently
shipping a partial tree.

Unlabeled controls (6–35% depending on app, §0.4) still need to be reachable —
emit them with their bounds and role so the agent can fall back to coordinates
within a known element, rather than dropping them entirely.

---

## 5. Actuation: element actions first

**Decision — invoke elements directly where possible; synthesize input as
fallback.**

Synthetic events drive the user's one physical cursor. The user cannot touch
their machine during a session, and touching the trackpad mid-task means
fighting the agent for control. This is the loudest complaint about host-based
computer use, and the usual answer is "run it in a VM" — which we ruled out.

Since the a11y backends exist anyway, they also *act*:

- macOS: `AXUIElementPerformAction(el, kAXPressAction)`
- Windows: UIA `InvokePattern.Invoke()`, `ValuePattern.SetValue()`

These press a control **without moving the cursor, without stealing focus, and
on windows that are not frontmost** — which upgrades app-scoping (§6) from
purely advisory to nearly enforced for this path, and largely closes the
frontmost-check race.

**Measured and confirmed (§0.3):** `AXPress` fired correctly on native Cocoa
controls, Chromium `<button>`, Chromium `<div role="button">`, and checkboxes,
each verified by an observed side effect. The primary risk in this decision is
retired.

### 5.1 Mandatory: verify by read-back, never by return code

The spike found that `AXUIElementPerformAction` returned **`-25205`
(`kAXErrorAttributeUnsupported`) on a press that demonstrably succeeded** — the
Finder view switched. A sibling press returned `0`. **AXError is not a reliable
success signal.**

Every actuation must therefore:

1. Capture a cheap pre-state — the element's `AXValue`, its parent's selection,
   or the window title.
2. Perform the action; **record the error code as telemetry only, never as the
   verdict.**
3. Re-read the state after a short settle delay and report success from the
   observed change.
4. Report *unverified* — not failed, not succeeded — when the state is
   unreadable, so the agent knows to take a screenshot rather than blindly retry.

Treating `-25205` as failure would make the agent retry an action that already
happened. For a non-idempotent control, that is the difference between sending
one message and sending two.

Where it breaks, and these are not edge cases:

- No hover, no mousedown/mouseup — hover-revealed menus and drag handles ignore it.
- **Drag is inexpressible.** Reorder, resize, and draw all need synthetic events.
- Canvas, video, games, remote desktop — no elements to press.
- `kAXValueAttribute` / `ValuePattern.SetValue` **frequently fail to notify
  controlled inputs**. React and Electron forms visually update, then submit
  empty. Nasty silent failure — prefer real typing for text in web content, and
  verify the value read-back after setting.

Andy selects the primitive based on whether the target resolved to an element;
the agent may force synthetic events per call.

---

## 6. Session scope

**Decision — app-scoped by default; whole desktop is an explicit opt-in at arm
time. Scope accepts a *set* of apps.**

Full-desktop capture sends Slack DMs, email, password manager windows, and other
customers' code to a model provider — and unlike today's `screenshot_host`
one-shot, a computer-use loop captures continuously and unattended.

Scoping to a target app set does three things at once:

1. Crops capture to those windows — smaller images, fewer tokens, **better
   grounding** because the coordinate space is denser with signal.
2. Roots the a11y tree at that app's `AXUIElement` / UIA element — the tree
   pruning problem largely evaporates.
3. Lets Andy refuse an action when focus has wandered outside the scope.

Scope must be a **set**, not one app: "copy this from the spreadsheet into the
web form" is exactly what people want computer use for.

**This is not a security boundary.** `CGEventPost` sends a click to a screen
coordinate; nothing confines it to a window. Keystrokes go wherever focus is,
and focus can change between Andy's check and the injection — a real TOCTOU gap.
It reduces accidental blast radius and leaked pixels. The HUD and panic hotkey
remain the actual backstop. (The element-action path of §5 is materially
stronger here, since actions are addressed to elements, not coordinates.)

---

## 7. Consent and control

**Decision — master switch + per-session arming + HUD + auto-disarm + panic
hotkey.**

The existing `hostScreenshotEnabled` pattern (one Settings boolean,
`WorkspaceModels.kt:72`, gate at `DesktopMcpServerService.kt:2079`) is
proportionate for a read-only screenshot. Input injection is categorically
different: it takes irreversible actions as the logged-in user, with their
cookies, unlocked keychain, and SSH agent. A permanently-true boolean is not
proportionate, and on Windows there is no OS consent layer behind it.

Per-action approval is the other extreme and is unusable — a real task is 30–60
actions, so prompting each one means the user holds down Enter, which
manufactures the *appearance* of consent and is worse than no prompt.

The layers:

| Layer | Behaviour |
| --- | --- |
| Master switch | Off by default, **per-project**, in Settings beside the host-screenshot panel |
| Arming | Agent requests control; user grants for *this run*; disarms at run end |
| HUD | Always-on-top, live action feed, elapsed time, scope, stop button |
| Auto-disarm | Idle timeout **and** absolute wall-clock ceiling |
| Panic hotkey | Global, instant revoke |

The HUD is non-negotiable. An agent moving your cursor with no visible indicator
is indistinguishable from malware. `VoiceNewThreadOverlay.kt` is the existing
always-on-top overlay precedent.

**Panic hotkey implementation.** `native/andy-voice/jni/andy_voice_jni.m:14`
documents that Carbon `RegisterEventHotKey` works **without** an Accessibility
grant, unlike CGEvent taps and NSEvent global monitors. That is precisely the
property a kill switch needs — it keeps working even if the agent has wedged the
event stream. The JNI bridge already exists and should be reused.

---

## 8. Untrusted screen content

**Decision — structural mitigations only. No pattern scanning.**

Computer use inverts the trust model: every AX label and every OCR'd pixel is
attacker-controllable. A web page, support email, Jira ticket, or PR description
can contain text addressed to the model rather than to the user, and the agent
cannot reliably distinguish content from instructions. This is the primary
attack on every deployed computer-use system.

Two escalation paths are specific to Andy and both are worse than generic
injection:

- **Terminal scope is a total bypass.** Every gate here governs clicks and
  keystrokes. An agent scoped to iTerm types `curl … | sh` and the entire
  consent model is one keystroke deep.
- **Andy scope is self-escalation.** Andy's own UI can re-arm sessions, flip the
  master switch, widen scope, and spawn agents. An agent that can drive Andy
  grants itself everything we withheld.

Mitigations, all enforced below the agent:

1. **Hard, non-overridable scope denylist** — terminal emulators, password
   managers, Andy itself, `andyd`. Not a warning; not user-overridable.
2. **Source-tagged events.** Tag injected events with a private
   `CGEventSource` state id (Windows: `GetMessageExtraInfo`) and have Andy's own
   windows reject any event carrying it. Without this the kill switch is
   killable.
3. **Untrusted-data labelling.** All screen-derived text is wrapped and
   explicitly marked untrusted in tool results.
4. **Re-confirmation** on high-consequence actions (§9).

**Rejected: scanning screen text for injection patterns.** It is a blocklist
against natural language. It fails open on anything novel or obfuscated, and its
main effect is to make the system feel protected. If wanted, it belongs as
telemetry, not a control.

---

## 9. High-consequence actions

**Decision — label-first heuristics, user-extendable; secure fields hard-blocked;
no pixel fallback in unattended runs.**

This is the trigger for two behaviours — re-confirm when attended, abort when
unattended — so it is the weakest link in the chain and needs to be explicit.

**Revised by the spike (§0.4): label first, role as a weak secondary signal.**
The original design weighted role and label equally. Measurement killed that:
Cursor exposes only 31% of its pressable nodes with a sane control role, and
Andy's own Compose UI only 21% — its buttons report as `AXStaticText`. A rule
shaped like "`AXButton` labelled Delete" would **silently fail to fire** on
Electron and Compose apps, i.e. it would look like it worked right up until it
mattered. Match on the label regardless of role; let role adjust confidence, not
gate the match.

Signals from the a11y tree:

- **Label match, any role** — anything advertising `AXPress` whose label matches
  Delete, Send, Pay, Purchase, Approve, Allow, Trust, Move to Trash. Built-in
  list, user-extendable per profile.
- **`AXSheet` / modal detection** — confirmation dialogs are usually the last
  gate before something irreversible.
- **`AXSecureTextField`** — password fields. **Typing into these is
  unconditionally barred, with no override.** There is no legitimate flow where
  an agent needs to type the user's password; anything that appears to need it
  is either a phishing surface or should use a credential the agent holds
  explicitly. **Confirmed working in Chromium (§0.5)** — a web
  `<input type="password">` reports subrole `AXSecureTextField`, so browser
  login forms are covered.

Honest weaknesses, on the record:

- **Label matching is locale-dependent and blind to icon-only controls.** A
  trash-can icon with no label sails through; Spanish "Eliminar" misses an
  English list. This is a real gate, not a complete one.
- **6–35% of pressable controls carry no usable label at all** (§0.4: Finder
  65% labeled, Cursor 76%, Terminal 33%). Those are unclassifiable by
  construction. Attended, the HUD covers it. Unattended, an unlabeled control is
  in the same position as the pixel fallback and should be treated the same way
  — refuse rather than guess.
- **The pixel fallback has no labels to classify.** Andy genuinely cannot tell
  whether a raw coordinate click lands on "Cancel" or "Delete Everything."
  Attended, the human sees the HUD. Unattended, the gate structurally cannot
  fire — so **the pixel fallback is unavailable in unattended mode**. This
  narrows unattended runs to apps with a real a11y tree, which is intended.

**Rejected: agent self-declaration of consequence.** Self-reporting by the
component we already deemed untrustworthy under injection. A page that can
instruct the agent can instruct it to declare "low risk."

---

## 10. Unattended runs

**Decision — allowed, with pre-authorized scope, tighter caps, watchdogs,
fail-closed abort, and mandatory recording.**

Andy already runs agents with nobody watching: `DesktopAutomationService` with
cron and daily/weekly schedules (`AutomationModels.kt`), plus remote starts from
the Android client over Network Access. Unless explicitly refused, computer use
inherits that path.

Every control in §7 assumes a human is present — arming is a prompt someone
answers, the HUD is a feed someone reads, the panic hotkey needs a hand on a
keyboard. Unattended removes all three at once. What replaces them:

| Control | Unattended behaviour |
| --- | --- |
| Scope | Pre-authorized via a named profile (§11); cannot be widened at runtime |
| Wall-clock cap | Hard, tighter than attended |
| Action-rate cap | Hard |
| Loop detection | Repeated identical actions abort the run |
| High-consequence action | **Abort immediately + push notification** |
| Out-of-scope action | **Abort immediately + push notification** |
| Pixel fallback | Unavailable (§9) |
| Recording | **Mandatory** (§12) |

Loop detection matters independently: the most common unattended failure is an
agent retrying the same click 400 times, which no consequence-based gate ever
fires on, because clicking the same button repeatedly is not high-consequence —
it is just broken.

**Deferred: async remote approval.** `native/andy-notifications`,
`PushNotification`, and the Android client's ability to respond to a running
agent mean pause-and-ask-remotely is cheap to build. It is deferred because a
2am notification asking "approve this?" gets tapped by someone half-awake, which
is a weaker control than it looks while manufacturing an audit trail that says a
human consented. Revisit once there are real traces of what actually trips.

---

## 11. Grant profiles and Settings

**Decision — named, reusable grant profiles; listed and revocable in Settings.**

A session is configured by: scoped app set, attended vs unattended, wall-clock
cap, rate cap, and user extensions to the high-consequence label list.
Unattended runs need that bundle authorized before they execute.

A named profile ("Expense filing — Chrome + Preview, unattended, 10 min cap")
gives one row per standing grant, one place to revoke, and one thing an
automation references. Inline per-automation config was rejected because
permissions would accrete quietly across a dozen automation records with no
single view of what the agent may currently do to the machine.

**Settings → Computer Use** contains:

1. Master switch (per-project, off by default).
2. Platform capability + permission status — Accessibility granted?, Screen
   Recording granted?, deep links to System Settings, and a clear
   "restart required after granting" note. Wayland shows the refusal and why.
3. Grant profile list — create, edit, revoke, and "used by N automations".
4. Panic hotkey binding.
5. Recording retention controls (§12).

**Persistence constraint.** `WorkspaceState` is persisted by hand-written,
key-by-key load/save in `DesktopWorkspaceStore.kt` — `hostScreenshotEnabled`
appears at line 75 (load) and line 259 (save), and a field missing from either
silently reverts. A flat `.properties` file does not hold a list of structured
profiles naturally, so profiles need either a serialized JSON blob in one
property or their own store. Decide deliberately; do not discover this when a
profile vanishes on restart.

---

## 12. Audit and recording

**Decision — structured text action log persisted by default; screenshots in
memory only, disk retention opt-in when attended and mandatory when unattended;
unattended recordings live in a separate encrypted local-only store.**

Two facts frame this:

- **Local persistence is not the privacy boundary.** Screenshots reach the model
  provider in context regardless. Local storage governs *additional* exposure —
  Time Machine, backups, disk forensics.
- **Persisted transcripts are remotely readable.** Andy transcripts live in
  `~/.andy/agents.db` and are served to the Android client over Network Access
  (`NetworkAccessApi.kt`). Fine for text; not fine for a stream of desktop
  screenshots, which would let a phone on the network page through images of the
  user's Mac.

Because the a11y tree is primary, the text log is unusually good:
`element #47 role=AXButton label="Send" action=AXPress result=ok` explains most
mis-clicks. Pixels are needed for the fallback path and for cases where the tree
lied about what was on screen — hence available on demand rather than always-on.

**Unattended recordings** are mandatory (without them, "pre-authorized scope and
tighter timeouts" is unfalsifiable), and therefore go to a **separate store
outside `agents.db`**: encrypted at rest, never served over Network Access,
viewable only in the local Andy UI, with a hard age and size cap that
auto-purges. An uncapped screenshot store of a real desktop becomes a liability
that outlives its usefulness within about a week.

---

## 13. Tool surface

**Decision — host twins of Andy's existing device tool vocabulary.**

`DesktopMcpServerService.kt:695–927` registers `tap`, `swipe`, `input_text`,
`press_key`, `screenshot`, `ui_dump`, `find_node_by_text`,
`get_node_properties` for Android. Agents in this ecosystem are already fluent
in that shape, a host session and a device session read the same way in a
transcript, and the HUD can reuse the Android node inspector UI.

| Tool | Purpose |
| --- | --- |
| `computer_capabilities` | Platform, permission status, whether armed, current scope |
| `computer_request_control` | Request arming; returns session id or denial reason |
| `computer_release_control` | Voluntary disarm |
| `computer_ui_dump` | Scoped, viewport-clipped, pruned a11y tree (§4.1). 4–17 KB measured |
| `computer_menu_dump` | Menu bar for the scoped app, **on demand only** — it is 91% of a naive dump (§0.6) |
| `computer_find_element` | Query by label / role / text; returns candidate element ids |
| `computer_screenshot` | Scoped capture; returns scale factor and origin |
| `computer_tap` | `element_id` **or** `{x, y}`; optional `force_synthetic` |
| `computer_input_text` | `element_id` or focused; refuses secure fields |
| `computer_press_key` | Key + modifiers |
| `computer_scroll` | Element or coordinate, delta |
| `computer_drag` | Always synthetic; unavailable unattended |

All tools return a clear, actionable error when: not armed, out of scope, master
switch off, permission missing (with the exact System Settings pane), platform
unsupported (Wayland), or running headless-only.

Tool names are registered in the `DesktopMcpServerService` tool list
(`:458–472`) and gated as a group so they are invisible unless the master switch
is on — matching how `screenshot_host` advertises itself.

**Rejected: overloading device tools with `target: "host"`.** A stray `tap`
resolving to the host instead of an emulator is precisely the accident this
whole design exists to prevent, and it would force arming and denylist logic
into the device tools.

---

## 14. Shipping sequence

**Decision — complete macOS attended slice first, then Windows and X11, then
unattended.**

The three assumptions this gate existed to test have been measured (§0):

| Assumption | Verdict |
| --- | --- |
| Scoped AX tree small enough to be useful | ✅ **Yes**, with viewport clipping — 17 KB worst case (§0.2) |
| `AXPress` works on real apps | ✅ **Yes**, native Cocoa + Chromium + ARIA (§0.3) |
| TCC attribution workable | ✅ **Yes** — children of `Andy.app` inherit the grant (§0.1) |

Two findings changed the design rather than confirming it: **AXError codes are
unreliable** (§5.1) and **role fidelity is too poor to gate on** (§9). Neither
invalidates the architecture; both change specific implementation rules.

**The gate is passed. Phase 1 can start with the spike work already done.**

### Phase 1 — macOS attended slice

Ordered so the riskiest remaining pieces land early.

1. **`native/andy-computer-use` JNI.** AX tree read, `AXUIElementPerformAction`
   with read-back verification (§5.1), `CGEventPost` with a private source state
   id, ScreenCaptureKit capture, `AXIsProcessTrusted` /
   `CGPreflightScreenCaptureAccess` probes. The Swift probes in
   `scratch/axspike/` are working reference implementations of the tree walk,
   press, and secure-field detection — port them rather than restarting.
2. **Dump pipeline (§4.1)** — scope → viewport clip → classify by `AXPress` →
   label resolution → compact emit, with a hard payload cap and explicit
   truncation reporting. Unit-testable against recorded tree fixtures.
3. **`data/computer-use` module + `ComputerUseService`.** Local and proxied
   implementations; capability reporting across all three deployment modes.
   **Must detect the launchd case** — if `AXIsProcessTrusted()` is false while
   the app bundle holds the grant, the process was started outside the app
   chain (§0.1) and the error must say so specifically.
4. **Scope resolution**, window-bounds cropping, coordinate space, and the
   `CGWindowListCopyWindowInfo` cross-check so "no windows open" never reports
   as "app not accessible" (§0.6).
5. **Safety layer.** Scope denylist, source-tagged event rejection,
   untrusted-text labelling, label-first high-consequence classification (§9)
   + re-confirm.
6. **Settings + HUD.** Master switch, grant profiles, permission status with
   deep links; arming flow, always-on-top HUD (pattern:
   `VoiceNewThreadOverlay.kt`), auto-disarm, Carbon panic hotkey (reuse
   `andy_voice_jni.m`).
7. **MCP tools (§13)**, attended only. Text action log.

### Phase 2 — Windows

UIA backend, `SendInput` with `GetMessageExtraInfo` tagging,
`Windows.Graphics.Capture`, UIPI detection and reporting. Note there is no OS
consent layer — Andy's gating is the whole story.

### Phase 3 — Linux X11

AT-SPI over D-Bus, XTEST, XGetImage. Wayland detection and refusal with
guidance. Toolkit env-var requirements documented.

### Phase 4 — unattended

Profile pre-authorization wired into `DesktopAutomationService`, watchdogs
(wall-clock, rate, loop), fail-closed abort with push notification via
`native/andy-notifications`, encrypted local-only recording store with
auto-purge, pixel fallback disabled.

### Phase 5 (deferred) — sandbox

Revisit `trycua/cua` or Lume for VM-backed sessions. This is the correct home
for unattended use long-term, and the natural place to relax the restrictions
Phase 4 needs.

---

## 15. Testing

- **Pure logic, normal unit tests:** coordinate-space conversion, scope
  resolution, denylist matching, high-consequence classification, watchdog
  state machines, profile serialization round-trip (including the
  `DesktopWorkspaceStore` load/save pairing).
- **Fixture-driven:** recorded AX/UIA tree dumps from real apps, replayed
  against the pruning and element-matching code.
- **Device-gated smoke tests** in the style of `IosSimMirrorDeviceSmokeTest` —
  opt-in, macOS-only, requiring real TCC grants; drive a known target app and
  assert the element is pressed.
- **Screenshot tests** (`recordRoborazziDesktop`) for the HUD and the Settings
  panel.
- **Explicitly untestable in CI:** TCC grant flows, real global hotkeys,
  cross-app focus races. These need a manual checklist, documented alongside the
  emulator mirror verification steps.

---

## 16. Open items

Resolved by the spike (§0): AX tree pruning strategy (now §4.1), `AXPress`
viability, TCC attribution.

Still open:

- Encryption key management for the unattended recording store (OS keychain is
  the obvious answer; Andy already uses it for the OpenRouter key).
- Whether `computer_drag` is worth shipping in Phase 1 at all. **Shipped attended-only.**
- Windows UIA and Linux AT-SPI label/role quality — §0.4 found Electron and
  Compose role fidelity poor on macOS; the equivalent measurement has not been
  run on the other platforms, and §9's label-first rule may need per-platform
  tuning.
- Whether `AXManualAccessibility` is required for a cold Electron app (§0.7).
  **Set defensively; errors tolerated.**
- Settle delay for read-back verification (§5.1) — 1 s was used in the spike;
  Phase 1 uses 250 ms default.
- How `AXPress` behaves on menu items, which §0 did not exercise functionally.

Resolved during Phase 1 implementation:

- Grant profiles live in `workspace.properties` as a JSON blob
  (`computerUseProfilesJson`).
- Capture uses `screencapture(1)` on macOS 15+ (CGWindowListCreateImage removed).
- Attended arming blocks on an explicit user approval in the HUD
  (`pendingArm` → `decideArm`); it denies on timeout and no longer auto-accepts.
- Sessions are bound to the requesting run (`ownerTaskId`); other MCP
  connections cannot drive an armed session.
- High-consequence actions expose a real confirmation path (HUD Confirm/Cancel
  and `computer_confirm_action` / `computer_discard_action`).
- Synthetic (coordinate/focus) input validates the focused app against the armed
  scope, the denylist, and secure-field state before injecting.
- Scoped screenshots crop to the authorized app's on-screen windows.
- The HUD is a global always-on-top window, not just the task-detail pane.
- Opt-in screenshot persistence writes under
  `~/.andy/computer-use/screenshots` (capped at 200 files).

