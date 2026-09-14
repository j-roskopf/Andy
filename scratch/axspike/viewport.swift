import Foundation
import ApplicationServices
import AppKit
func raw(_ e:AXUIElement,_ a:String)->CFTypeRef?{var v:CFTypeRef?;guard AXUIElementCopyAttributeValue(e,a as CFString,&v) == .success else{return nil};return v}
func str(_ e:AXUIElement,_ a:String)->String?{ let v=raw(e,a); if let s=v as? String{return s}; if let n=v as? NSNumber{return n.stringValue}; return nil }
func kids(_ e:AXUIElement)->[AXUIElement]{ (raw(e,kAXChildrenAttribute as String) as? [AXUIElement]) ?? [] }
func acts(_ e:AXUIElement)->[String]{var v:CFArray?;guard AXUIElementCopyActionNames(e,&v) == .success,let a=v as? [String] else{return []};return a}
func frame(_ e:AXUIElement)->CGRect?{
  guard let v=raw(e,"AXFrame"), CFGetTypeID(v)==AXValueGetTypeID() else {return nil}
  var r=CGRect.zero; AXValueGetValue(v as! AXValue,.cgRect,&r); return r }
let appName=CommandLine.arguments[1]
let app=NSWorkspace.shared.runningApplications.first{($0.localizedName ?? "")==appName}!
let ax=AXUIElementCreateApplication(app.processIdentifier);AXUIElementSetMessagingTimeout(ax,15)
let wins=(raw(ax,kAXWindowsAttribute as String) as? [AXUIElement]) ?? []
var all=0, inView=0, ctrlAll=0, ctrlInView=0, textInView=0
var ctrlBytes=0, textBytes=0
for w in wins {
  guard let wf=frame(w) else {continue}
  func walk(_ e:AXUIElement,_ d:Int=0){
    if d>50||all>200000 {return}
    all += 1
    let f=frame(e)
    let visible = f.map{ $0.intersects(wf) && $0.width>1 && $0.height>1 } ?? false
    if visible { inView += 1 }
    let a=acts(e)
    let role=str(e,kAXRoleAttribute as String) ?? "?"
    let label=[kAXTitleAttribute as String,kAXDescriptionAttribute as String,"AXHelp",kAXValueAttribute as String,"AXPlaceholderValue"].compactMap{str(e,$0)}.first{ !$0.trimmingCharacters(in:.whitespaces).isEmpty }
    if a.contains("AXPress") {
      ctrlAll += 1
      if visible, let l=label {
        ctrlInView += 1
        ctrlBytes += "{\"i\":\(all),\"r\":\"\(role)\",\"l\":\"\(l.prefix(60))\",\"b\":\"\(Int(f!.minX)),\(Int(f!.minY)),\(Int(f!.width)),\(Int(f!.height))\"}".utf8.count+1
      }
    } else if visible, role=="AXStaticText"||role=="AXHeading", let l=label {
      textInView += 1
      textBytes += "{\"i\":\(all),\"t\":\"\(l.prefix(60))\"}".utf8.count+1
    }
    for c in kids(e){walk(c,d+1)}
  }
  walk(w)
}
print("""
  allNodes=\(all)  inViewport=\(inView)
  pressable total=\(ctrlAll)  ->  visible+labeled controls=\(ctrlInView) (\(ctrlBytes/1024)KB)
  visible text nodes=\(textInView) (\(textBytes/1024)KB)
  AGENT PAYLOAD = \((ctrlBytes+textBytes)/1024)KB
""")
