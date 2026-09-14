import Foundation
import ApplicationServices
import AppKit

func attr(_ el: AXUIElement, _ a: String) -> String? {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, a as CFString, &v) == .success else { return nil }
    if let s = v as? String { return s }
    if let n = v as? NSNumber { return n.stringValue }
    return nil
}
func kids(_ el: AXUIElement) -> [AXUIElement] {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, kAXChildrenAttribute as CFString, &v) == .success,
          let a = v as? [AXUIElement] else { return [] }
    return a
}
func acts(_ el: AXUIElement) -> [String] {
    var v: CFArray?
    guard AXUIElementCopyActionNames(el, &v) == .success, let a = v as? [String] else { return [] }
    return a
}
func find(_ root: AXUIElement, _ depth: Int = 0, _ match: (AXUIElement) -> Bool) -> AXUIElement? {
    if depth > 40 { return nil }
    if match(root) { return root }
    for c in kids(root) { if let f = find(c, depth + 1, match) { return f } }
    return nil
}
func app(_ name: String) -> AXUIElement? {
    guard let a = NSWorkspace.shared.runningApplications.first(where: {
        ($0.localizedName ?? "") == name }) else { return nil }
    let e = AXUIElementCreateApplication(a.processIdentifier)
    AXUIElementSetMessagingTimeout(e, 10.0)
    return e
}

let args = CommandLine.arguments
let target = args.count > 1 ? args[1] : "Google Chrome"

guard let ax = app(target) else { print("\(target) not running"); exit(1) }

// Report every element with a label, its role and whether AXPress is offered.
struct Row { let role: String; let label: String; let acts: [String] }
var rows: [Row] = []
func collect(_ el: AXUIElement, _ d: Int = 0) {
    if d > 40 || rows.count > 4000 { return }
    let r = attr(el, kAXRoleAttribute as String) ?? "?"
    let l = attr(el, kAXTitleAttribute as String) ?? attr(el, kAXDescriptionAttribute as String) ?? attr(el, kAXValueAttribute as String) ?? ""
    let a = acts(el)
    if !l.isEmpty || !a.isEmpty { rows.append(Row(role: r, label: String(l.prefix(40)), acts: a)) }
    for c in kids(el) { collect(c, d + 1) }
}
var v: CFTypeRef?
AXUIElementCopyAttributeValue(ax, kAXWindowsAttribute as CFString, &v)
let wins = (v as? [AXUIElement]) ?? []
for w in wins { collect(w) }

print("== \(target): \(rows.count) labeled/actionable nodes in windows ==")
if args.contains("--list") {
    for r in rows.prefix(60) where r.acts.contains("AXPress") {
        print("  [\(r.role)] \"\(r.label)\"  acts=\(r.acts.joined(separator: ","))")
    }
}

// Functional press: find by label, press, report
if let idx = args.firstIndex(of: "--press"), args.count > idx + 1 {
    let want = args[idx + 1]
    var found: AXUIElement?
    for w in wins {
        if let f = find(w, 0, { el in
            let l = attr(el, kAXTitleAttribute as String) ?? attr(el, kAXDescriptionAttribute as String) ?? attr(el, kAXValueAttribute as String) ?? ""
            return l == want && acts(el).contains("AXPress")
        }) { found = f; break }
    }
    guard let el = found else { print("  PRESS: element \"\(want)\" not found or offers no AXPress"); exit(4) }
    let role = attr(el, kAXRoleAttribute as String) ?? "?"
    let err = AXUIElementPerformAction(el, kAXPressAction as CFString)
    print("  PRESS \"\(want)\" role=\(role) -> AXError \(err.rawValue) \(err == .success ? "(success)" : "(FAILED)")")
}

// Secure field detection
for w in wins {
    if let sec = find(w, 0, { attr($0, kAXRoleAttribute as String) == "AXTextField"
        && attr($0, kAXSubroleAttribute as String) == "AXSecureTextField" }) {
        print("  SECURE FIELD FOUND: subrole=\(attr(sec, kAXSubroleAttribute as String) ?? "?")")
    }
}
