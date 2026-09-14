import Foundation
import ApplicationServices
import AppKit

// Probe whether an app exposes windows, and whether AXManualAccessibility changes that.
let args = CommandLine.arguments
guard args.count > 1 else { print("usage: axenable <AppName>"); exit(1) }
let appName = args[1]
guard let app = NSWorkspace.shared.runningApplications.first(where: {
    ($0.localizedName ?? "") == appName || ($0.bundleIdentifier ?? "") == appName
}) else { print("\(appName): NOT RUNNING"); exit(3) }

let axApp = AXUIElementCreateApplication(app.processIdentifier)
AXUIElementSetMessagingTimeout(axApp, 10.0)

func windowCount(_ el: AXUIElement) -> (Int, String) {
    var v: CFTypeRef?
    let err = AXUIElementCopyAttributeValue(el, kAXWindowsAttribute as CFString, &v)
    guard err == .success else { return (-1, "err=\(err.rawValue)") }
    guard let ws = v as? [AXUIElement] else { return (-1, "not-array") }
    return (ws.count, "ok")
}

func deepCount(_ el: AXUIElement, _ depth: Int = 0) -> Int {
    if depth > 40 { return 1 }
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, kAXChildrenAttribute as CFString, &v) == .success,
          let arr = v as? [AXUIElement] else { return 1 }
    var n = 1
    for c in arr { n += deepCount(c, depth + 1); if n > 40000 { return n } }
    return n
}

func report(_ tag: String) {
    let (wc, note) = windowCount(axApp)
    var contentNodes = 0
    if wc > 0 {
        var v: CFTypeRef?
        AXUIElementCopyAttributeValue(axApp, kAXWindowsAttribute as CFString, &v)
        if let ws = v as? [AXUIElement] { for w in ws { contentNodes += deepCount(w) } }
    }
    print("\(appName) [\(tag)] windows=\(wc) (\(note)) windowSubtreeNodes=\(contentNodes)")
}

report("before")

// Chromium/Electron lazily build their a11y tree; clients must opt in.
let setErr = AXUIElementSetAttributeValue(axApp, "AXManualAccessibility" as CFString, kCFBooleanTrue)
let setErr2 = AXUIElementSetAttributeValue(axApp, "AXEnhancedUserInterface" as CFString, kCFBooleanTrue)
print("\(appName) set AXManualAccessibility=\(setErr.rawValue) AXEnhancedUserInterface=\(setErr2.rawValue)")

for wait in [0.5, 1.5, 3.0] {
    Thread.sleep(forTimeInterval: wait)
    report("after +\(wait)s")
}
