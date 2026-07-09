package app.andy.ui.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.model.NetworkDiagnosis
import app.andy.model.NetworkExchange
import app.andy.model.NetworkRouteDiagnostics
import app.andy.model.ProxyWarning
import app.andy.service.ProxyService

internal class NetworkScreenState(
    val proxy: ProxyService,
) {
    var status by mutableStateOf("Proxy stopped")
    var proxyStatus by mutableStateOf("Proxy stopped")
    var engineStatus by mutableStateOf("Checking mitmdump")
    var engineReady by mutableStateOf(false)
    var caPath by mutableStateOf("")
    var proxyHost by mutableStateOf("")
    var exchanges by mutableStateOf<List<NetworkExchange>>(emptyList())
    var warnings by mutableStateOf<List<ProxyWarning>>(emptyList())
    var clientConnectionCount by mutableStateOf(0)
    var diagnoses by mutableStateOf<List<NetworkDiagnosis>>(emptyList())
    var selectedFlowId by mutableStateOf<String?>(null)
    var setupExpanded by mutableStateOf(false)
    var setupManuallyToggled by mutableStateOf(false)
    var setupDefaultApplied by mutableStateOf(false)
    var selectedExpanded by mutableStateOf(true)
    var seenFlowIds by mutableStateOf<Set<String>>(emptySet())
    val expandedTrafficKeys = mutableStateMapOf<String, Boolean>()
    val flashingTrafficKeys = mutableStateMapOf<String, Long>()
    var ruleName by mutableStateOf("")
    var rulePattern by mutableStateOf("")
    var ruleMethod by mutableStateOf("")
    var ruleStatus by mutableStateOf("")
    var ruleSetHeaders by mutableStateOf("")
    var ruleRemoveHeaders by mutableStateOf("")
    var ruleBody by mutableStateOf("")
    var editingRuleId by mutableStateOf<String?>(null)
    var trafficWidth by mutableStateOf(480f)
    var statusWidth by mutableStateOf(72f)
    var typeWidth by mutableStateOf(150f)
    var sizeWidth by mutableStateOf(80f)
    var msWidth by mutableStateOf(70f)
    var focusedPath by mutableStateOf<String?>(null)
    var engineChecked by mutableStateOf(false)
    var deviceReadinessChecked by mutableStateOf(false)
    var caInstalled by mutableStateOf(false)
    var proxyConfigured by mutableStateOf(false)
    var routeDiagnostics by mutableStateOf<NetworkRouteDiagnostics?>(null)
    var userCaVerifiedByTrafficForDevice by mutableStateOf(false)
    var proxyTrafficObservedForDevice by mutableStateOf(false)
    var trafficEvidenceSerial by mutableStateOf<String?>(null)
    var flowIdsAtSerialChange by mutableStateOf(emptySet<String>())
    var sslInsecure by mutableStateOf(false)
    var upstreamTrustedCaPath by mutableStateOf("")

    fun clearRuleEditor() {
        ruleName = ""
        rulePattern = ""
        ruleMethod = ""
        ruleStatus = ""
        ruleSetHeaders = ""
        ruleRemoveHeaders = ""
        ruleBody = ""
        editingRuleId = null
    }

    fun loadRuleEditor(
        name: String,
        pattern: String,
        method: String,
        statusCode: String,
        setHeaders: String,
        removeHeaders: String,
        body: String,
        ruleId: String?,
    ) {
        ruleName = name
        rulePattern = pattern
        ruleMethod = method
        ruleStatus = statusCode
        ruleSetHeaders = setHeaders
        ruleRemoveHeaders = removeHeaders
        ruleBody = body
        editingRuleId = ruleId
    }
}
