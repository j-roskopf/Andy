# Android mobile companion

Andy on Android is a **remote-control companion** for developers who already run
Andy Desktop on a Mac or Linux laptop. It is not a reskin of the desktop shell.

## What it does

1. **Hosts** — save Tailscale MagicDNS / `100.x` addresses, VNC port, Network Access URL, and secrets (encrypted on device).
2. **Screen** — connect directly to the host’s VNC server (default `:5900`) with touchpad gestures + soft keyboard.
3. **Projects** — Network Access parity: token auth, project-grouped chats, transcript, follow-ups, new ACP chat.
4. **Settings** — about this build, and self-update from GitHub Releases (`Andy-*.apk`).

## Smoke path

### On the laptop (Andy Desktop / andyd)

1. Keep **andyd** running (standalone launchd/systemd or GUI left open — see [ANDYD.md](ANDYD.md)).
2. Enable **Network Access** in Settings → MCP. Optionally set a **master password** so phones/browsers can sign in without copying the access token.
3. Prefer **Tailscale only** + Serve:

```sh
tailscale serve --bg 8565
# open the https://…ts.net URL Tailscale prints
```

4. Enable **Screen Sharing / VNC** on `:5900`:
   - **macOS:** System Settings → General → Sharing → **Screen Sharing**.
     For third-party VNC clients also enable **“VNC viewers may control screen with password”** and set a password.
     Andy’s phone client speaks Apple ARD auth (security type 30), which is what modern macOS advertises.
   - **Linux:** run `x11vnc`, `wayvnc`, or similar listening on `5900`.
5. Install [Tailscale](https://tailscale.com/download) on the laptop and phone; same account; phone VPN on.

### On the phone (Andy Android)

1. Build/install: `./gradlew :androidApp:installDebug`
2. **Hosts → Add** — display name + MagicDNS or `100.x` address.
3. Optional: Network Access URL (`https://host.ts.net` or `http://100.x.y.z:8565`) and VNC password
   (macOS username optional — leave blank for VNC password-only).
4. **Screen** — open a live session. See [Screen gestures](#screen-gestures).
5. **Projects** — sign in with master password, access token, or login code; browse chats, open / reply / start new.
   Sessions last 7 days; the phone stores only the session token (not the password).

## Screen gestures

One finger is always the mouse; two fingers are always the view.

| Gesture | Action |
| --- | --- |
| One-finger tap | Left click |
| One-finger hold | Right click |
| One-finger drag | Click-drag (works at any zoom) |
| Two-finger pinch | Zoom up to 20×, anchored on the pinch centroid |
| Two-finger drag, zoomed in | Pan |
| Two-finger drag, fit to screen | Scroll wheel |

Pinch in further (up to 20×) when small toolbar icons are still hard to hit.

The screen opens full-screen with the tab bar and system bars hidden; the
fullscreen button in the viewer header brings them back. The keyboard button opens
a send-buffer text field plus `esc` / `tab` / arrows and a sticky `ctrl` — tap
`ctrl`, then the next key or character is chorded.

Keyboard input uses a password-type IME field on purpose. Autocorrect and composing
regions rewrite whole words behind your back, which sends the host characters you
never typed.

## Screen performance

The RFB client negotiates **ZRLE** first, then zlib, CopyRect and Raw. This is the
difference between usable and unusable: a 6896x2346 desktop is ~64 MB per full frame
as Raw, and typically well under a megabyte as ZRLE.

Three other things keep it responsive, and are worth preserving if this code is
touched:

- **Update requests are pipelined.** The next `FramebufferUpdateRequest` goes out
  before the current update is decoded, so server render, network transfer and
  decode overlap. Exactly one request is outstanding at a time.
- **Frames are sampled down to viewport resolution before drawing.** Handing the
  full desktop bitmap to `drawImage` re-uploads all 64 MB to the GPU whenever any
  pixel changes.
- **Frames, zoom and pan are read in the draw phase only**, never at composable
  scope, so a new frame invalidates drawing without recomposing the viewer.

Input (pointer and keys) goes through a single ordered queue with one consumer.
Do not go back to launching a coroutine per event: delivery order stops matching
input order, which scrambles typing and breaks click-drag outright.

## Security notes

- Tokens and VNC passwords use **EncryptedSharedPreferences** (Android Keystore).
- Do not paste tokens into logs or bug reports.
- Cleartext HTTP is allowed only so LAN / Tailscale IP Network Access (`http://100.x:8565`) works; prefer Tailscale Serve HTTPS when possible.

## Project chat notifications

While signed into Network Access, Andy keeps a Foreground Service that:

1. **Pushes** over `/ws/attention` (host → phone) for Blocked / Done / Error
2. **Pulls** `/api/chats` every ~1.5s as a backup if the socket drops

You will see a low-priority ongoing notification (“Listening for Andy chats · …”)
while the listener is running — required so Android allows background networking.
`listening (pull)` means pull is healthy even if push TLS is down; that is enough for alerts.
Tap **Stop** on that notification (or Sign out in Projects) to tear it down.

Alerts are skipped only while that chat is open **and** the app is in the foreground
(backgrounding with the chat still open still notifies). Tap an alert to open
that chat. Android 13+ must allow notifications (the listening banner can appear
even when alert permission is denied — if the banner says “enable app notifications”,
grant the permission in system settings).

Requires Tailscale/LAN reachability to the host. Restart Andy Desktop / andyd
after updating so the host serves `/ws/attention`.

## Limits (MVP)

- VNC transport is **direct TCP** (Tailscale/LAN). SSH tunneling from the phone is not included yet.
- RFB client is a pure-Kotlin ZRLE / zlib / CopyRect / Raw implementation (no LibVNCClient / GPL).
  No Tight or JPEG, so a busy full-screen video will still be slower than Mac-to-Mac
  Screen Sharing, which uses Apple's own codec.
- Multi-monitor display splitting is inferred from the framebuffer aspect ratio and assumes
  equal-width monitors.
- Desktop remote-screen SSH handoff and webchat are unchanged.

## Settings and self-update

The **Settings** tab shows the installed build (`andy.versionName`) and can check
[GitHub Releases](https://github.com/j-roskopf/Andy/releases) for a newer `Andy-*.apk`.

1. Tap **Check for updates** (the app also checks once on launch).
2. When an update is available, tap **Update** to download the APK and open Android’s installer.
3. If prompted, allow Andy to install unknown apps (Settings → Install unknown apps), then tap **Update** again.

Sideloaded updates require a release build signed with the same key as the installed app.
Debug installs cannot update over a release APK (and vice versa) without uninstalling first.
