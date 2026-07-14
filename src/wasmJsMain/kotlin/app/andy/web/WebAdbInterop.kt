@file:OptIn(ExperimentalWasmJsInterop::class)
@file:JsModule("./andy-web-adb.mjs")

package app.andy.web

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsModule
import kotlin.js.JsBoolean
import kotlin.js.JsString
import kotlin.js.Promise

external fun webAdbConnectWebSocket(): Promise<JsString>
external fun webAdbRequestWebUsb(): Promise<JsString>
external fun webAdbForgetWebUsbAuthorization(): Promise<JsString>
external fun webAdbListDevices(): Promise<JsString>
external fun webAdbShell(serial: String, argsJson: String): Promise<JsString>
external fun webAdbStartLogcat(serial: String): Promise<JsString>
external fun webAdbDrainLogcat(): JsString
external fun webAdbStopLogcat(): Promise<JsAny?>
external fun webPickFiles(allowMultiple: Boolean): Promise<JsString>
external fun webAdbPushFile(serial: String, reference: String, remotePath: String): Promise<JsString>
external fun webAdbInstallFile(serial: String, reference: String, replace: Boolean): Promise<JsString>
external fun webAdbPullFile(serial: String, remotePath: String, suggestedName: String): Promise<JsString>
external fun webAdbDownloadCommand(serial: String, argsJson: String, suggestedName: String, mimeType: String): Promise<JsString>
external fun webAdbDownloadBugReport(serial: String, suggestedName: String): Promise<JsString>
external fun webAdbAccessibility(serial: String): Promise<JsString>
external fun webAdbStartMirror(serial: String, configJson: String): Promise<JsString>
external fun webAdbStopMirror(): Promise<JsAny?>
external fun webAdbAttachMirror(hostId: String): Boolean
external fun webAdbDetachMirror(hostId: String)
external fun webAdbSetMirrorHighlight(hostId: String, bounds: String, sourceWidth: Int, sourceHeight: Int)
external fun webAdbSendMirrorInput(inputJson: String): Promise<JsString>
external fun webAdbMirrorStats(): JsString
external fun webStorageStatus(): Promise<JsString>
external fun webStorageRequestPersistence(): Promise<JsBoolean>
external fun webStorageClearAll(): Promise<JsString>
external fun webWorkspaceLoad(): Promise<JsString>
external fun webWorkspaceSave(value: String): Promise<JsString>
external fun webBugStart(serial: String): Promise<JsString>
external fun webBugStop(): Promise<JsString>
external fun webBugSave(reportJson: String, logcat: String, accessibilityJson: String): Promise<JsString>
external fun webBugList(): Promise<JsString>
external fun webBugLoad(id: String): Promise<JsString>
external fun webBugLoadLog(id: String): Promise<JsString>
external fun webBugDelete(id: String): Promise<JsBoolean>
external fun webBugExport(id: String): Promise<JsString>
external fun webBugBeginPlayback(id: String, positionMillis: Double, play: Boolean): Promise<JsBoolean>
