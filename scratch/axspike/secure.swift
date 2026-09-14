import Foundation
import ApplicationServices
import AppKit
func attr(_ el: AXUIElement,_ a:String)->String?{var v:CFTypeRef?;guard AXUIElementCopyAttributeValue(el,a as CFString,&v) == .success else{return nil};if let s=v as? String{return s};if let n=v as? NSNumber{return n.stringValue};return nil}
func kids(_ el: AXUIElement)->[AXUIElement]{var v:CFTypeRef?;guard AXUIElementCopyAttributeValue(el,kAXChildrenAttribute as CFString,&v) == .success,let a=v as? [AXUIElement] else{return []};return a}
func attrNames(_ el: AXUIElement)->[String]{var v:CFArray?;guard AXUIElementCopyAttributeNames(el,&v) == .success,let a=v as? [String] else{return []};return a}
let name = CommandLine.arguments.count>1 ? CommandLine.arguments[1] : "Google Chrome"
guard let a = NSWorkspace.shared.runningApplications.first(where:{($0.localizedName ?? "")==name}) else{exit(1)}
let ax = AXUIElementCreateApplication(a.processIdentifier); AXUIElementSetMessagingTimeout(ax,10)
var v:CFTypeRef?; AXUIElementCopyAttributeValue(ax,kAXWindowsAttribute as CFString,&v)
let wins=(v as? [AXUIElement]) ?? []
func walk(_ el:AXUIElement,_ d:Int=0){
  if d>40 {return}
  let r = attr(el,kAXRoleAttribute as String) ?? "?"
  if r.contains("TextField") || r.contains("TextArea") || r=="AXComboBox" {
     let sub = attr(el,kAXSubroleAttribute as String) ?? "<none>"
     let desc = attr(el,kAXDescriptionAttribute as String) ?? ""
     let help = attr(el,"AXHelp") ?? ""
     let names = attrNames(el)
     let interesting = names.filter{ $0.contains("Secure")||$0.contains("Protected")||$0.contains("Placeholder")||$0.contains("Role")||$0.contains("Value") }
     print("  role=\(r) subrole=\(sub) desc=\"\(desc)\" help=\"\(help)\"")
     print("     attrs: \(interesting.joined(separator:", "))")
  }
  for c in kids(el){walk(c,d+1)}
}
print("== \(name) text inputs ==")
for w in wins { walk(w) }
