import Foundation
import ApplicationServices
import AppKit
func attr(_ el:AXUIElement,_ a:String)->String?{var v:CFTypeRef?;guard AXUIElementCopyAttributeValue(el,a as CFString,&v) == .success else{return nil};if let s=v as? String{return s};if let n=v as? NSNumber{return n.stringValue};return nil}
func kids(_ el:AXUIElement)->[AXUIElement]{var v:CFTypeRef?;guard AXUIElementCopyAttributeValue(el,kAXChildrenAttribute as CFString,&v) == .success,let a=v as? [AXUIElement] else{return []};return a}
func acts(_ el:AXUIElement)->[String]{var v:CFArray?;guard AXUIElementCopyActionNames(el,&v) == .success,let a=v as? [String] else{return []};return a}
let name = CommandLine.arguments[1]
guard let a = NSWorkspace.shared.runningApplications.first(where:{($0.localizedName ?? "")==name}) else{print("\(name) not running");exit(1)}
let ax=AXUIElementCreateApplication(a.processIdentifier); AXUIElementSetMessagingTimeout(ax,10)
var v:CFTypeRef?; AXUIElementCopyAttributeValue(ax,kAXWindowsAttribute as CFString,&v)
let wins=(v as? [AXUIElement]) ?? []
var pressable=0, labeled=0, roleOK=0
var unlabeledRoles:[String:Int]=[:]
let controlRoles:Set<String> = ["AXButton","AXCheckBox","AXRadioButton","AXPopUpButton","AXMenuButton","AXLink","AXTextField","AXTextArea","AXComboBox","AXSlider","AXDisclosureTriangle","AXTabGroup","AXCell","AXRow"]
func walk(_ el:AXUIElement,_ d:Int=0){
  if d>40 {return}
  if acts(el).contains("AXPress") {
    pressable += 1
    let r = attr(el,kAXRoleAttribute as String) ?? "?"
    // label sources an agent could use, in priority order
    let l = [kAXTitleAttribute as String, kAXDescriptionAttribute as String, "AXHelp", kAXValueAttribute as String, "AXPlaceholderValue"]
      .compactMap{ attr(el,$0) }.first{ !$0.trimmingCharacters(in:.whitespaces).isEmpty }
    if l != nil { labeled += 1 } else { unlabeledRoles[r, default:0] += 1 }
    if controlRoles.contains(r) { roleOK += 1 }
  }
  for c in kids(el){walk(c,d+1)}
}
for w in wins { walk(w) }
let pct = pressable>0 ? Int(Double(labeled)/Double(pressable)*100) : 0
let rpct = pressable>0 ? Int(Double(roleOK)/Double(pressable)*100) : 0
let top = unlabeledRoles.sorted{$0.value>$1.value}.prefix(4).map{"\($0.key):\($0.value)"}.joined(separator:" ")
print(String(format:"%-18@ pressable=%4d  labeled=%4d (%3d%%)  sane-role=%3d%%   unlabeled: %@",
  name as NSString, pressable, labeled, pct, rpct, top as NSString))
