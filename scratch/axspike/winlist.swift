import Foundation
import CoreGraphics
import AppKit
let opts: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
guard let info = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] else { exit(1) }
var byApp: [String: [(String, Int, Int)]] = [:]
for w in info {
    let owner = w[kCGWindowOwnerName as String] as? String ?? "?"
    let name = w[kCGWindowName as String] as? String ?? ""
    let b = w[kCGWindowBounds as String] as? [String: Any] ?? [:]
    let ww = (b["Width"] as? NSNumber)?.intValue ?? 0
    let hh = (b["Height"] as? NSNumber)?.intValue ?? 0
    let layer = (w[kCGWindowLayer as String] as? NSNumber)?.intValue ?? 0
    if layer != 0 { continue }          // normal windows only
    if ww < 100 || hh < 100 { continue }
    byApp[owner, default: []].append((name, ww, hh))
}
for (app, wins) in byApp.sorted(by: { $0.key < $1.key }) {
    print("\(app): \(wins.count) on-screen window(s)")
    for (n, w, h) in wins.prefix(3) { print("   \(w)x\(h)  \"\(n.prefix(60))\"") }
}
