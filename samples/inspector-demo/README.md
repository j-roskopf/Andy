# Inspector demo app

A small Android app for exercising Andy's **Inspector** screen and **Logcat → Crashes** panel:
nested Compose, traditional XML views, overlapping layers, dialogs, invisible nodes, and
deterministic crash/log triggers.

## Build and install

Open `samples/inspector-demo` in **Android Studio** and run the **app** configuration on a device or emulator.

From the Andy repo root:

```sh
./gradlew -p samples/inspector-demo :app:installDebug
adb shell am start -n app.andy.inspectordemo/.MainActivity
```

## Try in Andy — Inspector

1. Install the app on a connected device (`app.andy.inspectordemo`).
2. In Andy, select the device and open **Inspector**.
3. Tap **Capture**, then use **Inspect clicks** on the live mirror to select nodes.
4. Toggle **Include invisible** and **Unmerged view tree** to compare hierarchy sources.
5. Tap **Set snapshot baseline**, change the counter or hide the overlay, capture again, and enable **Diff vs. snapshot**.
6. Open the **Layers** tab after showing the in-app dialog to inspect window z-order.

### Sections in the app

| Section | What to test |
| --- | --- |
| Compose hierarchy | Counter, overlay toggle, list rows, profile card |
| Traditional Android views | Embedded XML in Compose; **Open full XML activity** for pure `dumpsys activity top` tree |
| Window layers & dialogs | Alert dialog + Layers tab |
| Invisible nodes | Alpha-zero and off-screen nodes with **Include invisible** |
| Crash & log diagnostics | See below |

## Try in Andy — Crashes (Logcat tab)

1. Open **Logcat** and switch to the **Crashes** sub-panel (or scroll to Crashes in the layout).
2. Use the demo app buttons:
   - **Log ERROR** — tagged `InspectorDemo` stack trace in the log stream (filter `tag:InspectorDemo`).
   - **Java crash** / **Kotlin crash** — process death; reopen the app, then **Refresh** in Crashes.
   - **Trigger ANR** — sleeps the main thread 20s; tap **Wait** on the system dialog so a trace is written.
3. Crash entries appear as `data_app_crash` in dropbox when the device allows read access (emulators and userdebug builds work best).

Package name `app.andy.inspectordemo` should appear on crash records for easy filtering.
