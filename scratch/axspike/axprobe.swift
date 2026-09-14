import Foundation
import ApplicationServices
import AppKit

// ---- Result accumulators -------------------------------------------------
struct Stats {
    var total = 0
    var maxDepth = 0
    var withPress = 0            // advertises kAXPressAction
    var withAnyAction = 0
    var labeled = 0              // has title/desc/value text
    var actionableLabeled = 0    // has an action AND a label -> "useful" node
    var rawBytes = 0             // approx JSON of full tree
    var prunedBytes = 0          // approx JSON of pruned tree
    var t2Count = 0              // visible && action && label  (targetable controls)
    var t2Bytes = 0
    var t3Count = 0              // t2 + visible labeled static text (reading context)
    var t3Bytes = 0
    var truncated = false
    var roleCounts: [String: Int] = [:]
    var pressRoleCounts: [String: Int] = [:]
}

let NODE_CAP = 60000
let WALL_LIMIT: TimeInterval = 45.0
var startTime = Date()
let DEPTH_CAP = 60

func attrString(_ el: AXUIElement, _ attr: String) -> String? {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, attr as CFString, &v) == .success else { return nil }
    if let s = v as? String, !s.isEmpty { return s }
    if let n = v as? NSNumber { return n.stringValue }
    return nil
}

func children(_ el: AXUIElement) -> [AXUIElement] {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, kAXChildrenAttribute as CFString, &v) == .success,
          let arr = v as? [AXUIElement] else { return [] }
    return arr
}

func actions(_ el: AXUIElement) -> [String] {
    var v: CFArray?
    guard AXUIElementCopyActionNames(el, &v) == .success, let a = v as? [String] else { return [] }
    return a
}

func frameOf(_ el: AXUIElement) -> CGRect? {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, "AXFrame" as CFString, &v) == .success else { return nil }
    var r = CGRect.zero
    guard let av = v, CFGetTypeID(av) == AXValueGetTypeID() else { return nil }
    // swiftlint:disable:next force_cast
    AXValueGetValue(av as! AXValue, .cgRect, &r)
    return r
}

func walk(_ el: AXUIElement, depth: Int, _ s: inout Stats) {
    if s.total >= NODE_CAP { s.truncated = true; return }
    if s.total % 512 == 0 && Date().timeIntervalSince(startTime) > WALL_LIMIT { s.truncated = true; return }
    if depth > DEPTH_CAP { s.truncated = true; return }
    s.total += 1
    s.maxDepth = max(s.maxDepth, depth)

    let role = attrString(el, kAXRoleAttribute as String) ?? "?"
    s.roleCounts[role, default: 0] += 1

    let acts = actions(el)
    let hasPress = acts.contains(kAXPressAction as String)
    if hasPress { s.withPress += 1; s.pressRoleCounts[role, default: 0] += 1 }
    if !acts.isEmpty { s.withAnyAction += 1 }

    let title = attrString(el, kAXTitleAttribute as String)
    let desc  = attrString(el, kAXDescriptionAttribute as String)
    let value = attrString(el, kAXValueAttribute as String)
    let label = title ?? desc ?? value
    let hasLabel = (label != nil)
    if hasLabel { s.labeled += 1 }

    let subrole = attrString(el, kAXSubroleAttribute as String)
    let frame = frameOf(el)
    let frameStr = frame.map { "\(Int($0.origin.x)),\(Int($0.origin.y)),\(Int($0.size.width)),\(Int($0.size.height))" } ?? ""

    // approximate serialized size of a full dump line
    let rawLine = "{\"id\":\(s.total),\"role\":\"\(role)\",\"subrole\":\"\(subrole ?? "")\",\"label\":\"\(label ?? "")\",\"actions\":\"\(acts.joined(separator: ","))\",\"frame\":\"\(frameStr)\"}"
    s.rawBytes += rawLine.utf8.count + 1

    // pruned: keep only nodes that an agent could plausibly target
    let visible = (frame?.width ?? 0) > 1 && (frame?.height ?? 0) > 1
    let keep = visible && (!acts.isEmpty || hasLabel)
    if keep {
        if !acts.isEmpty && hasLabel { s.actionableLabeled += 1 }
        let prunedLine = "{\"id\":\(s.total),\"role\":\"\(role)\",\"label\":\"\(label ?? "")\",\"act\":\"\(acts.joined(separator: ","))\",\"frame\":\"\(frameStr)\"}"
        s.prunedBytes += prunedLine.utf8.count + 1

        let isControl = !acts.isEmpty && hasLabel
        let isText = (role == "AXStaticText" || role == "AXHeading") && hasLabel
        if isControl {
            s.t2Count += 1
            let l = "{\"i\":\(s.total),\"r\":\"\(role)\",\"l\":\"\(label!.prefix(80))\",\"b\":\"\(frameStr)\"}"
            s.t2Bytes += l.utf8.count + 1
            s.t3Count += 1; s.t3Bytes += l.utf8.count + 1
        } else if isText {
            s.t3Count += 1
            let l = "{\"i\":\(s.total),\"r\":\"t\",\"l\":\"\(label!.prefix(80))\"}"
            s.t3Bytes += l.utf8.count + 1
        }
    }

    for c in children(el) { walk(c, depth: depth + 1, &s) }
}

// ---- main ---------------------------------------------------------------
let trusted = AXIsProcessTrusted()
FileHandle.standardError.write("AXIsProcessTrusted=\(trusted)\n".data(using: .utf8)!)
if !trusted {
    print("{\"error\":\"not_trusted\"}")
    exit(2)
}

let args = CommandLine.arguments
guard args.count > 1 else { print("usage: axprobe <AppName> [--windows-only]"); exit(1) }
let appName = args[1]
let windowsOnly = args.contains("--windows-only")

guard let app = NSWorkspace.shared.runningApplications.first(where: {
    ($0.localizedName ?? "") == appName || ($0.bundleIdentifier ?? "") == appName
}) else {
    print("{\"error\":\"app_not_running\",\"app\":\"\(appName)\"}")
    exit(3)
}

let axApp = AXUIElementCreateApplication(app.processIdentifier)
AXUIElementSetMessagingTimeout(axApp, 10.0)

var roots: [AXUIElement] = [axApp]
if windowsOnly {
    var v: CFTypeRef?
    if AXUIElementCopyAttributeValue(axApp, kAXWindowsAttribute as CFString, &v) == .success,
       let ws = v as? [AXUIElement], !ws.isEmpty {
        roots = ws
    }
}

var s = Stats()
let t0 = Date()
startTime = t0
for r in roots { walk(r, depth: 0, &s) }
let elapsed = Date().timeIntervalSince(t0)

func topN(_ d: [String: Int], _ n: Int) -> String {
    d.sorted { $0.value > $1.value }.prefix(n)
        .map { "\"\($0.key)\":\($0.value)" }.joined(separator: ",")
}

print("""
{"app":"\(appName)","pid":\(app.processIdentifier),"windowsOnly":\(windowsOnly),\
"total":\(s.total),"maxDepth":\(s.maxDepth),"truncated":\(s.truncated),\
"withPress":\(s.withPress),"withAnyAction":\(s.withAnyAction),"labeled":\(s.labeled),\
"actionableLabeled":\(s.actionableLabeled),\
"rawKB":\(s.rawBytes/1024),"prunedKB":\(s.prunedBytes/1024),\
"controls":\(s.t2Count),"controlsKB":\(s.t2Bytes/1024),"ctxNodes":\(s.t3Count),"ctxKB":\(s.t3Bytes/1024),\
"seconds":\(String(format: "%.2f", elapsed)),\
"roles":{\(topN(s.roleCounts, 8))},"pressRoles":{\(topN(s.pressRoleCounts, 8))}}
""")
