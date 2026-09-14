import Foundation
import ApplicationServices
import AppKit
func attr(_ e:AXUIElement,_ a:String)->Any?{var v:CFTypeRef?;guard AXUIElementCopyAttributeValue(e,a as CFString,&v) == .success else{return nil};return v}
func str(_ e:AXUIElement,_ a:String)->String?{ attr(e,a) as? String }
func kids(_ e:AXUIElement)->[AXUIElement]{ (attr(e,kAXChildrenAttribute as String) as? [AXUIElement]) ?? [] }
func acts(_ e:AXUIElement)->[String]{var v:CFArray?;guard AXUIElementCopyActionNames(e,&v) == .success,let a=v as? [String] else{return []};return a}
let appName=CommandLine.arguments[1], want=CommandLine.arguments[2]
let app=NSWorkspace.shared.runningApplications.first{($0.localizedName ?? "")==appName}!
let ax=AXUIElementCreateApplication(app.processIdentifier);AXUIElementSetMessagingTimeout(ax,10)
var v:CFTypeRef?;AXUIElementCopyAttributeValue(ax,kAXWindowsAttribute as CFString,&v)
let wins=(v as? [AXUIElement]) ?? []
var siblings:[(String,AXUIElement)]=[]
func w(_ e:AXUIElement,_ d:Int=0){
 if d>40{return}
 let t=str(e,kAXTitleAttribute as String) ?? str(e,kAXDescriptionAttribute as String) ?? ""
 if acts(e).contains("AXPress") && str(e,kAXRoleAttribute as String)=="AXRadioButton" && !t.isEmpty { siblings.append((t,e)) }
 for c in kids(e){w(c,d+1)}
}
for win in wins { w(win) }
func state()->String{ siblings.map{ "\($0.0)=\((attr($0.1,kAXValueAttribute as String) as? NSNumber)?.intValue ?? -1)" }.joined(separator:" ") }
print("  before: \(state())")
guard let target=siblings.first(where:{$0.0==want})?.1 else{print("  target not found");exit(1)}
let err=AXUIElementPerformAction(target,kAXPressAction as CFString)
print("  AXPress(\"\(want)\") -> AXError \(err.rawValue)")
Thread.sleep(forTimeInterval:1.0)
print("  after:  \(state())")
