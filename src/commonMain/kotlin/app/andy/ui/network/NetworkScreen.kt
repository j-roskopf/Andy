package app.andy.ui.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.HeaderCell
import app.andy.domain.*
import app.andy.model.AndroidDevice
import app.andy.model.NetworkExchange
import app.andy.model.NetworkRouteDiagnostics
import app.andy.model.ProxyRule
import app.andy.model.SdkDiscovery
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.HorizontalPaneDivider
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.live.DeviceLivePanel
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private data class SetupRequirement(
    val label: String,
    val ok: Boolean,
    val readyText: String,
    val missingText: String,
    val installCommand: String? = null,
)


private val DebugNetworkSecurityConfigSnippet = """
res/xml/network_security_config.xml
<network-security-config>
  <debug-overrides>
    <trust-anchors>
      <certificates src="user" />
      <certificates src="system" />
    </trust-anchors>
  </debug-overrides>
</network-security-config>

AndroidManifest.xml
<application android:networkSecurityConfig="@xml/network_security_config" />
""".trimIndent()


private fun manualCertificateSteps(caPath: String, proxyHost: String, port: Int): List<String> = listOf(
    "Start the proxy so Andy creates ${caPath.ifBlank { "~/.andy/proxy/mitmproxy-ca-cert.cer" }}.",
    "Click Prepare phone CA, then finish the CA certificate install on the device from Downloads.",
    "Set the device Wi-Fi proxy to Manual with host ${proxyHost.ifBlank { "this Mac's LAN IP" }} and port $port, or click Configure.",
    "Run a debug build whose network security config trusts user certificates.",
    "Disable Private DNS and retry over HTTP/1.1 if a request is missing; pinned apps and QUIC/HTTP3 will not decrypt.",
)


private fun hostSetupSteps(engineReady: Boolean): List<String> {
    val mitmproxyStep = if (engineReady) {
        "mitmproxy is installed; Andy will start mitmdump automatically for Network."
    } else {
        "Install mitmproxy on the host: brew install mitmproxy. Andy uses mitmdump from that package."
    }
    return listOf(
        "Install Android Studio or Android command-line tools so Andy can find adb, emulator, sdkmanager, and avdmanager.",
        "Embedded mirroring uses Andy's bundled scrcpy-server. For local development only, SCRCPY_SERVER_PATH can point at another scrcpy-server file.",
        mitmproxyStep,
    )
}


@Composable
internal fun NetworkScreen(
    services: AndyServices,
    sdk: SdkDiscovery,
    serial: String?,
    device: AndroidDevice?,
    port: Int,
    rules: List<ProxyRule>,
    rulesVisible: Boolean,
    liveVisible: Boolean,
    onPortChange: (Int) -> Unit,
    onRulesChange: (List<ProxyRule>) -> Unit,
    onRulesVisibleChange: (Boolean) -> Unit,
) {
    val proxy = services.proxy
    val scope = rememberCoroutineScope()
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var status by remember { mutableStateOf("Proxy stopped") }
    var proxyStatus by remember { mutableStateOf("Proxy stopped") }
    var engineStatus by remember { mutableStateOf("Checking mitmdump") }
    var engineReady by remember { mutableStateOf(false) }
    var caPath by remember { mutableStateOf("") }
    var proxyHost by remember { mutableStateOf("") }
    var exchanges by remember { mutableStateOf<List<NetworkExchange>>(emptyList()) }
    var selectedFlowId by remember { mutableStateOf<String?>(null) }
    var setupExpanded by remember { mutableStateOf(false) }
    var setupManuallyToggled by remember { mutableStateOf(false) }
    var setupDefaultApplied by remember { mutableStateOf(false) }
    var selectedExpanded by remember { mutableStateOf(true) }
    var seenFlowIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val expandedTrafficKeys = remember { mutableStateMapOf<String, Boolean>() }
    val flashingTrafficKeys = remember { mutableStateMapOf<String, Long>() }
    var ruleName by remember { mutableStateOf("") }
    var rulePattern by remember { mutableStateOf("") }
    var ruleMethod by remember { mutableStateOf("") }
    var ruleStatus by remember { mutableStateOf("") }
    var ruleSetHeaders by remember { mutableStateOf("") }
    var ruleRemoveHeaders by remember { mutableStateOf("") }
    var ruleBody by remember { mutableStateOf("") }
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    val selected = exchanges.firstOrNull { it.flowId == selectedFlowId } ?: exchanges.lastOrNull()
    var trafficWidth by remember { mutableStateOf(260f) }
    var statusWidth by remember { mutableStateOf(72f) }
    var typeWidth by remember { mutableStateOf(150f) }
    var sizeWidth by remember { mutableStateOf(80f) }
    var msWidth by remember { mutableStateOf(70f) }
    var focusedPath by remember { mutableStateOf<String?>(null) }
    var engineChecked by remember { mutableStateOf(false) }
    var deviceReadinessChecked by remember { mutableStateOf(false) }

    val currentPort = portText.toIntOrNull()?.coerceIn(1, 65535) ?: port
    val filteredExchanges = remember(exchanges, focusedPath) {
        if (focusedPath == null) {
            exchanges
        } else {
            exchanges.filter { exchange ->
                focusedPath in networkTrafficAncestorKeys(exchange)
            }
        }
    }
    val trafficTree = remember(filteredExchanges) { buildNetworkTrafficTree(filteredExchanges) }
    val visibleTrafficRows = remember(trafficTree, expandedTrafficKeys.toMap()) {
        flattenNetworkTrafficTree(trafficTree, expandedTrafficKeys)
    }
    var caInstalled by remember { mutableStateOf(false) }
    var proxyConfigured by remember { mutableStateOf(false) }
    var routeDiagnostics by remember { mutableStateOf<NetworkRouteDiagnostics?>(null) }
    var userCaVerifiedByTrafficForDevice by remember { mutableStateOf(false) }
    var proxyTrafficObservedForDevice by remember { mutableStateOf(false) }
    var trafficEvidenceSerial by remember { mutableStateOf<String?>(null) }
    var flowIdsAtSerialChange by remember { mutableStateOf(emptySet<String>()) }
    val latestRules by rememberUpdatedState(rules)

    LaunchedEffect(Unit) {
        caPath = proxy.certificateAuthorityPath()
        val engine = proxy.detectMitmproxy()
        engineReady = engine.isSuccess
        engineStatus = if (engine.isSuccess) {
            "mitmdump: ${engine.stdout.ifBlank { "ready" }}"
        } else {
            engine.stderr
        }
        engineChecked = true
    }
    LaunchedEffect(engineReady, currentPort) {
        if (!engineReady) return@LaunchedEffect
        val currentStatus = try {
            withTimeout(200) { proxy.status.first() }
        } catch (_: Exception) {
            proxyStatus
        }
        if (shouldAutoStartProxy(currentStatus, currentPort)) {
            proxy.ensureCertificateAuthority()
            val result = proxy.start(currentPort, latestRules)
            val message = if (result.isSuccess) result.stdout else result.stderr
            status = message
            if (result.isSuccess) proxyStatus = message
        }
    }
    LaunchedEffect(Unit) {
        proxy.exchanges.collectLatest { exchanges = it }
    }
    LaunchedEffect(Unit) {
        proxy.status.collectLatest {
            proxyStatus = it
            status = it
        }
    }
    LaunchedEffect(serial, exchanges) {
        if (serial != trafficEvidenceSerial) {
            trafficEvidenceSerial = serial
            flowIdsAtSerialChange = exchanges.map { it.flowId }.toSet()
            userCaVerifiedByTrafficForDevice = false
            proxyTrafficObservedForDevice = false
        } else {
            val newExchanges = exchanges.filter { it.flowId !in flowIdsAtSerialChange }
            if (newExchanges.isNotEmpty()) proxyTrafficObservedForDevice = true
            if (newExchanges.any { it.tlsStatus == "tls" && it.error == null }) {
                userCaVerifiedByTrafficForDevice = true
            }
        }
    }
    LaunchedEffect(serial, currentPort) {
        if (serial == null) {
            caInstalled = false
            proxyConfigured = false
            routeDiagnostics = null
            deviceReadinessChecked = true
            return@LaunchedEffect
        }
        deviceReadinessChecked = false
        while (true) {
            val isCaOk = proxy.isCertificateInstalled(serial)
            val host = proxy.resolveDeviceProxyHost(serial)
            val isProxyOk = proxy.isDeviceProxyConfigured(serial, host, currentPort)
            val route = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
            caInstalled = isCaOk
            proxyConfigured = isProxyOk
            routeDiagnostics = route
            deviceReadinessChecked = true
            delay(3000)
        }
    }
    LaunchedEffect(
        engineChecked,
        deviceReadinessChecked,
        sdk.hasAdb,
        engineReady,
        proxyStatus,
        serial,
        caInstalled,
        proxyTrafficObservedForDevice,
        proxyConfigured,
        routeDiagnostics,
    ) {
        if (setupDefaultApplied || setupManuallyToggled || !engineChecked || !deviceReadinessChecked) return@LaunchedEffect
        val redCount = listOf(
            sdk.hasAdb,
            true,
            engineReady,
            proxyStatus.contains("listening on"),
            serial != null && (caInstalled || proxyTrafficObservedForDevice),
            serial != null && proxyConfigured,
            serial != null && routeDiagnostics?.vpnActive != true && routeDiagnostics?.routeUsesVpn != true,
        ).count { !it }
        setupExpanded = redCount > 2
        setupDefaultApplied = true
    }
    LaunchedEffect(serial) {
        proxyHost = serial?.let { selectedSerial ->
            val activation = proxy.activatePersistedCertificateAuthority(selectedSerial)
            if (activation.isSuccess && activation.stdout.isNotBlank()) {
                status = activation.stdout
            }
            proxy.resolveDeviceProxyHost(selectedSerial)
        }.orEmpty()
    }
    LaunchedEffect(rules) {
        proxy.updateRules(rules)
    }
    LaunchedEffect(exchanges.map { it.flowId }) {
        val currentIds = exchanges.map { it.flowId }.toSet()
        val added = exchanges.filter { it.flowId !in seenFlowIds }
        if (seenFlowIds.isNotEmpty() && added.isNotEmpty()) {
            added.flatMap(::networkTrafficAncestorKeys).distinct().forEach { key ->
                if (expandedTrafficKeys[key] != true && key !in flashingTrafficKeys) {
                    val flashToken = System.nanoTime()
                    flashingTrafficKeys[key] = flashToken
                    scope.launch {
                        delay(280)
                        if (flashingTrafficKeys[key] == flashToken) {
                            flashingTrafficKeys.remove(key)
                        }
                    }
                }
            }
        }
        seenFlowIds = currentIds
    }

    fun persistPort() {
        onPortChange(currentPort)
    }

    fun resetRuleForm() {
        ruleName = ""
        rulePattern = ""
        ruleMethod = ""
        ruleStatus = ""
        ruleSetHeaders = ""
        ruleRemoveHeaders = ""
        ruleBody = ""
        editingRuleId = null
    }

    fun addOrSaveRule() {
        val pattern = rulePattern.trim()
        if (pattern.isBlank()) {
            status = "Enter a URL match pattern before adding a rule"
            return
        }
        val editId = editingRuleId
        val editIdx = editId?.let { id -> rules.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        val rule = ProxyRule(
            id = editId ?: "rule-${rules.size + 1}-${pattern.hashCode().toString().replace("-", "n")}",
            name = ruleName.ifBlank { pattern },
            enabled = editIdx?.let { rules[it].enabled } ?: true,
            urlPattern = pattern,
            method = ruleMethod.trim().uppercase().ifBlank { null },
            statusCode = ruleStatus.toIntOrNull(),
            setHeaders = parseHeaderLines(ruleSetHeaders),
            removeHeaders = ruleRemoveHeaders.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() },
            responseBody = ruleBody.takeIf { it.isNotBlank() },
        )
        if (editIdx != null) {
            onRulesChange(rules.mapIndexed { i, existing -> if (i == editIdx) rule else existing })
        } else {
            onRulesChange(rules + rule)
        }
        resetRuleForm()
    }

    fun clearCapturedTraffic() {
        scope.launch {
            val result = proxy.clearTraffic()
            selectedFlowId = null
            seenFlowIds = emptySet()
            flashingTrafficKeys.clear()
            status = if (result.isSuccess) result.stdout else result.stderr
        }
    }

    fun editRule(ruleId: String) {
        val rule = rules.firstOrNull { it.id == ruleId } ?: return
        ruleName = rule.name
        rulePattern = rule.urlPattern
        ruleMethod = rule.method ?: ""
        ruleStatus = rule.statusCode?.toString() ?: ""
        ruleSetHeaders = rule.setHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        ruleRemoveHeaders = rule.removeHeaders.joinToString("\n")
        ruleBody = rule.responseBody ?: ""
        editingRuleId = rule.id
        onRulesVisibleChange(true)
    }

    val networkPageScrollState = rememberScrollState()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .weight(1.45f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(networkPageScrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PanelCard(Modifier.animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Debug-app HTTPS proxy", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextField(
                        portText,
                        {
                            portText = it.filter(Char::isDigit).take(5)
                            it.toIntOrNull()?.takeIf { value -> value in 1..65535 }?.let(onPortChange)
                        },
                        singleLine = true,
                        modifier = Modifier.width(86.dp).height(54.dp),
                        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        colors = fieldColors(),
                    )
                    Button(onClick = {
                        persistPort()
                        scope.launch {
                            proxy.ensureCertificateAuthority()
                            val result = proxy.start(currentPort, rules)
                            val message = if (result.isSuccess) result.stdout else result.stderr
                            status = message
                            if (result.isSuccess) proxyStatus = message
                        }
                    }) { Text("Start") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = proxy.stop()
                            status = result.stdout
                            proxyStatus = result.stdout
                        }
                    }) { Text("Stop") }
                    OutlinedButton(onClick = ::clearCapturedTraffic) { Text("Clear traffic") }
                    Text(
                        if (setupExpanded) "Hide setup" else "Show setup",
                        color = Rust,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            setupManuallyToggled = true
                            setupExpanded = !setupExpanded
                        },
                    )
                }
                val proxyStarted = proxyStatus.contains("listening on")
                val caText = when {
                    serial == null -> "Select a device first"
                    caInstalled -> "System CA installed"
                    userCaVerifiedByTrafficForDevice -> "User CA verified by HTTPS traffic"
                    proxyTrafficObservedForDevice -> "Traffic observed"
                    else -> "Use System CA or Prepare phone CA"
                }
                val configText = when {
                    serial == null -> "Select a device first"
                    proxyConfigured -> "Device is routed"
                    else -> "Click 'Configure' to route"
                }
                val routeText = when {
                    serial == null -> "Select a device first"
                    routeDiagnostics == null -> "Checking route"
                    routeDiagnostics?.hostProxyActive == true && routeDiagnostics?.hostUpstreamProxy != null -> "Mac proxy chained"
                    routeDiagnostics?.hostProxyActive == true -> "Mac proxy active"
                    routeDiagnostics?.vpnActive == true -> "VPN active (may cause issues)"
                    routeDiagnostics?.routeUsesVpn == true -> "Proxy route uses VPN"
                    routeDiagnostics?.proxyConfigured == false -> "Proxy route needs repair"
                    else -> "No VPN route issue detected"
                }
                val setupRequirements = listOf(
                    SetupRequirement(
                        label = "Android SDK platform-tools",
                        ok = sdk.hasAdb,
                        readyText = "ADB is available through Andy's SDK selection",
                        missingText = "Install Android Studio or command-line tools",
                    ),
                    SetupRequirement(
                        label = "scrcpy-server",
                        ok = true,
                        readyText = "Bundled with Andy for embedded mirroring",
                        missingText = "Packaged builds include scrcpy-server",
                    ),
                    SetupRequirement(
                        label = "mitmproxy",
                        ok = engineReady,
                        readyText = engineStatus,
                        missingText = "Required for Network capture and rewrite rules",
                        installCommand = "brew install mitmproxy",
                    ),
                )
                val networkStatusRequirements = listOf(
                    SetupRequirement(
                        label = "Proxy Status",
                        ok = proxyStarted,
                        readyText = "Listening on port $currentPort",
                        missingText = "Click Start to start",
                    ),
                    SetupRequirement(
                        label = "CA Trust",
                        ok = serial != null && (caInstalled || proxyTrafficObservedForDevice),
                        readyText = caText,
                        missingText = caText,
                    ),
                    SetupRequirement(
                        label = "Device Proxy Routing",
                        ok = serial != null && proxyConfigured,
                        readyText = configText,
                        missingText = configText,
                    ),
                    SetupRequirement(
                        label = "VPN / Route",
                        ok = serial != null &&
                            routeDiagnostics?.vpnActive != true &&
                            routeDiagnostics?.routeUsesVpn != true &&
                            routeDiagnostics?.hostProxyBypassLooksSafe != false,
                        readyText = routeText,
                        missingText = routeText,
                    ),
                )
                val showRouteWarning = routeDiagnostics?.hasBlockingIssue == true && proxyStarted && !proxyTrafficObservedForDevice
                AnimatedVisibility(setupExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val host = proxy.resolveDeviceProxyHost(serial)
                                        proxyHost = host
                                        val result = proxy.configureDeviceProxy(serial, host, currentPort)
                                        status = if (result.isSuccess) result.stdout else result.stderr
                                        proxyConfigured = proxy.isDeviceProxyConfigured(serial, host, currentPort)
                                        routeDiagnostics = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
                                    }
                                },
                            ) { Text("Configure device proxy") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val host = proxy.resolveDeviceProxyHost(serial)
                                        proxyHost = host
                                        val configured = proxy.configureDeviceProxy(serial, host, currentPort)
                                        val route = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
                                        routeDiagnostics = route
                                        proxyConfigured = route.proxyConfigured
                                        val restart = if (route.hostProxyActive) proxy.start(currentPort, rules) else null
                                        status = if (route.vpnActive) {
                                            "Proxy route repaired, but VPN is still active. Open VPN settings and disable or split-tunnel the test app."
                                        } else if (restart?.isSuccess == true) {
                                            "Proxy route repaired; Andy restarted mitmproxy through the Mac proxy."
                                        } else if (configured.isSuccess && !route.hasBlockingIssue) {
                                            "Proxy route repaired"
                                        } else {
                                            (route.issues.ifEmpty { listOf(restart?.stderr ?: configured.stderr) }).joinToString(" ")
                                        }
                                    }
                                },
                            ) { Text("Repair proxy route") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val result = proxy.openVpnSettings(serial)
                                        status = if (result.isSuccess) "Opened Android VPN settings" else result.stderr
                                    }
                                },
                            ) { Text("Open VPN settings") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val result = proxy.clearDeviceProxy(serial)
                                        status = if (result.isSuccess) result.stdout else result.stderr
                                        val host = proxy.resolveDeviceProxyHost(serial)
                                        proxyConfigured = proxy.isDeviceProxyConfigured(serial, host, currentPort)
                                    }
                                },
                            ) { Text("Clear proxy") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val result = proxy.installSystemCertificateAuthority(serial)
                                        status = if (result.isSuccess) result.stdout else result.stderr
                                        caInstalled = proxy.isCertificateInstalled(serial)
                                    }
                                },
                            ) { Text("System CA (root)") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        proxy.ensureCertificateAuthority()
                                        val result = proxy.prepareUserCertificateInstall(serial)
                                        status = if (result.isSuccess) result.stdout else result.stderr
                                    }
                                },
                            ) { Text("Prepare phone CA") }
                        }
                        SetupChecklist(setupRequirements)
                        SetupChecklist(networkStatusRequirements)
                        AnimatedVisibility(showRouteWarning) {
                            NetworkRouteWarningCard(routeDiagnostics)
                        }
                        Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusTag(if (engineReady) "mitmproxy ready" else "mitmproxy missing", if (engineReady) Green else Red)
                            Text(
                                text = buildString {
                                    append(engineStatus)
                                    append("  ·  ")
                                    append("Endpoint: ")
                                    append(proxyHost.ifBlank { "select device" })
                                    append(":")
                                    append(currentPort)
                                },
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        InstallStepsCard(
                            title = "Host prerequisites",
                            steps = hostSetupSteps(engineReady),
                        )
                        Text("CA: ${caPath.ifBlank { "~/.andy/proxy/mitmproxy-ca-cert.cer" }}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ManualCertificateSetupCard(
                            steps = manualCertificateSteps(caPath, proxyHost, currentPort),
                        )
                        Text(
                            "Capture scope: physical devices can trust Andy as a user CA only after manual approval, and only debug apps that opt into user CAs will decrypt. Chrome and many third-party apps need system trust on a rooted device or rootable non-Play emulator; pinned apps, QUIC/HTTP3, private DNS, and direct UDP will not appear in v1.",
                            color = Yellow,
                            fontSize = 12.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("Debug app config", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(
                            DebugNetworkSecurityConfigSnippet,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("Use this only in debug builds that trust user CAs. Certificate pinning and arbitrary third-party app bypass are out of scope.", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
            if (focusedPath != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .background(AndyColors.OrangeSubtle, RoundedCornerShape(AndyRadius.R3))
                        .border(1.dp, AndyColors.OrangeBorder, RoundedCornerShape(AndyRadius.R3))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(AndyColors.Orange, RoundedCornerShape(AndyRadius.Pill))
                        )
                        Text(
                            text = "Focus mode: showing only ${focusedPath?.removePrefix("base:")}",
                            color = AndyColors.Neutral100,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = "Exit Focus",
                        color = AndyColors.Orange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { focusedPath = null }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderCell("TRAFFIC", trafficWidth.dp) { trafficWidth = it.coerceIn(120f, 600f) }
                HeaderCell("STATUS", statusWidth.dp) { statusWidth = it.coerceIn(50f, 150f) }
                HeaderCell("TYPE", typeWidth.dp) { typeWidth = it.coerceIn(80f, 250f) }
                HeaderCell("SIZE", sizeWidth.dp) { sizeWidth = it.coerceIn(50f, 150f) }
                HeaderCell("MS", msWidth.dp) { msWidth = it.coerceIn(50f, 150f) }
                Text("RULE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp)) {
                items(visibleTrafficRows, key = { row -> row.key }) { row ->
                    NetworkTrafficRowItem(
                        row = row,
                        expanded = expandedTrafficKeys[row.key] == true,
                        flashing = row.key in flashingTrafficKeys,
                        trafficWidth = trafficWidth,
                        statusWidth = statusWidth,
                        typeWidth = typeWidth,
                        sizeWidth = sizeWidth,
                        msWidth = msWidth,
                        onToggle = {
                            if (row.hasChildren) {
                                expandedTrafficKeys[row.key] = expandedTrafficKeys[row.key] != true
                                flashingTrafficKeys.remove(row.key)
                            }
                        },
                        onSelect = { exchange ->
                            selectedFlowId = exchange.flowId
                        },
                        onFocus = { path ->
                            focusedPath = path
                        },
                        onAddRule = { exchange ->
                            val pathSegment = exchange.url.substringAfterLast("/").substringBefore("?")
                            ruleName = if (pathSegment.isNotBlank()) "Mock $pathSegment" else "Mock response"
                            rulePattern = exchange.url
                            ruleMethod = exchange.method
                            ruleStatus = exchange.statusCode?.toString() ?: "200"
                            val excludedHeaders = setOf(
                                "content-length",
                                "content-encoding",
                                "transfer-encoding",
                                "connection",
                                "keep-alive",
                                "date",
                                "server",
                                "accept-ranges",
                                "content-range",
                                "age"
                            )
                            ruleSetHeaders = exchange.responseHeaders.entries
                                .filter { it.key.lowercase() !in excludedHeaders }
                                .joinToString("\n") { "${it.key}: ${it.value}" }
                            ruleRemoveHeaders = ""
                            ruleBody = exchange.responseBodyPreview ?: ""
                            onRulesVisibleChange(true)
                        }
                    )
                }
                if (visibleTrafficRows.isEmpty()) {
                    item {
                        EmptyState("No traffic yet. Start the proxy, configure a device, then make a request.")
                    }
                }
            }
            }
            SelectedFlowPanel(
                selected = selected,
                expanded = selectedExpanded,
                onToggle = { selectedExpanded = !selectedExpanded },
                modifier = Modifier.fillMaxWidth().then(if (selectedExpanded) Modifier.height(340.dp) else Modifier.heightIn(min = 54.dp)),
            )
        }
        if (rulesVisible || liveVisible) {
            Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (liveVisible && rulesVisible) {
                    var livePanelHeight by remember { mutableStateOf(300f) }
                    DeviceLivePanel(
                        services = services,
                        serial = serial,
                        device = device,
                        modifier = Modifier.fillMaxWidth().height(livePanelHeight.dp),
                    )
                    HorizontalPaneDivider(
                        onDrag = { dragY -> livePanelHeight = (livePanelHeight + dragY).coerceIn(100f, 600f) }
                    )
                    RulesPaneContent(
                        rules = rules,
                        ruleName = ruleName,
                        onRuleNameChange = { ruleName = it },
                        rulePattern = rulePattern,
                        onRulePatternChange = { rulePattern = it },
                        ruleMethod = ruleMethod,
                        onRuleMethodChange = { ruleMethod = it },
                        ruleStatus = ruleStatus,
                        onRuleStatusChange = { ruleStatus = it },
                        ruleSetHeaders = ruleSetHeaders,
                        onRuleSetHeadersChange = { ruleSetHeaders = it },
                        ruleRemoveHeaders = ruleRemoveHeaders,
                        onRuleRemoveHeadersChange = { ruleRemoveHeaders = it },
                        ruleBody = ruleBody,
                        onRuleBodyChange = { ruleBody = it },
                        onRulesChange = onRulesChange,
                        onAddOrSaveRule = ::addOrSaveRule,
                        editingRuleId = editingRuleId,
                        onEditRule = ::editRule,
                        onCancelEdit = ::resetRuleForm,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                } else if (liveVisible) {
                    DeviceLivePanel(
                        services = services,
                        serial = serial,
                        device = device,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else if (rulesVisible) {
                    RulesPaneContent(
                        rules = rules,
                        ruleName = ruleName,
                        onRuleNameChange = { ruleName = it },
                        rulePattern = rulePattern,
                        onRulePatternChange = { rulePattern = it },
                        ruleMethod = ruleMethod,
                        onRuleMethodChange = { ruleMethod = it },
                        ruleStatus = ruleStatus,
                        onRuleStatusChange = { ruleStatus = it },
                        ruleSetHeaders = ruleSetHeaders,
                        onRuleSetHeadersChange = { ruleSetHeaders = it },
                        ruleRemoveHeaders = ruleRemoveHeaders,
                        onRuleRemoveHeadersChange = { ruleRemoveHeaders = it },
                        ruleBody = ruleBody,
                        onRuleBodyChange = { ruleBody = it },
                        onRulesChange = onRulesChange,
                        onAddOrSaveRule = ::addOrSaveRule,
                        editingRuleId = editingRuleId,
                        onEditRule = ::editRule,
                        onCancelEdit = ::resetRuleForm,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun GlowingDot(isGreen: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by if (isGreen) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val color = if (isGreen) Green else Red

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(16.dp)
    ) {
        if (isGreen) {
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = (2f - pulseScale).coerceIn(0f, 1f)
                    }
                    .background(color.copy(alpha = 0.4f), CircleShape)
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
                .border(1.dp, color.copy(alpha = 0.8f), CircleShape)
        )
    }
}

@Composable
private fun NetworkRouteWarningCard(diagnostics: NetworkRouteDiagnostics?, modifier: Modifier = Modifier) {
    val issues = diagnostics?.issues.orEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .background(Yellow.copy(alpha = 0.10f), RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Yellow.copy(alpha = 0.55f), RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Traffic may be bypassing Andy", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        issues.take(3).forEach { issue ->
            Text(issue, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Text(
            "Repair proxy route reapplies Android's global proxy and restarts mitmproxy through the Mac proxy when one is configured. If traffic still disappears, add localhost, 127.0.0.1, and 10.0.2.2 to the Mac proxy bypass list or disable/split-tunnel the VPN.",
            color = Yellow,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun ManualCertificateSetupCard(steps: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Physical device manual CA", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text("${index + 1}.", color = Rust, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(step, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InstallStepsCard(title: String, steps: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text("${index + 1}.", color = Rust, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(step, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SetupChecklist(requirements: List<SetupRequirement>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        requirements.forEach { requirement ->
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlowingDot(requirement.ok, Modifier.padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(requirement.label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (requirement.ok) requirement.readyText else requirement.missingText,
                        color = if (requirement.ok) Green else Red,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!requirement.ok && requirement.installCommand != null) {
                        Text(requirement.installCommand, color = Rust, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesPaneContent(
    rules: List<ProxyRule>,
    ruleName: String,
    onRuleNameChange: (String) -> Unit,
    rulePattern: String,
    onRulePatternChange: (String) -> Unit,
    ruleMethod: String,
    onRuleMethodChange: (String) -> Unit,
    ruleStatus: String,
    onRuleStatusChange: (String) -> Unit,
    ruleSetHeaders: String,
    onRuleSetHeadersChange: (String) -> Unit,
    ruleRemoveHeaders: String,
    onRuleRemoveHeadersChange: (String) -> Unit,
    ruleBody: String,
    onRuleBodyChange: (String) -> Unit,
    onRulesChange: (List<ProxyRule>) -> Unit,
    onAddOrSaveRule: () -> Unit,
    editingRuleId: String?,
    onEditRule: (String) -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEditing = editingRuleId != null
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PanelCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isEditing) "Edit rule" else "Rules", color = TextPrimary, fontWeight = FontWeight.Bold)
                    if (isEditing) {
                        Text(
                            "Cancel",
                            color = Rust,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onCancelEdit() },
                        )
                    }
                }
                LabeledField("Name", ruleName, onRuleNameChange, Modifier.fillMaxWidth(), placeholder = "Mock response")
                LabeledField("URL pattern", rulePattern, onRulePatternChange, Modifier.fillMaxWidth(), placeholder = "https://api.example.com/v1/*/profile")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("Method", ruleMethod, { onRuleMethodChange(it.uppercase().take(8)) }, Modifier.width(110.dp))
                    LabeledField("Status", ruleStatus, { onRuleStatusChange(it.filter(Char::isDigit).take(3)) }, Modifier.width(100.dp), placeholder = "200")
                }
                LabeledField("Set headers", ruleSetHeaders, onRuleSetHeadersChange, Modifier.fillMaxWidth(), singleLine = false, minHeight = 92.dp, placeholder = "content-type: application/json\nx-debug: true")
                LabeledField("Remove headers", ruleRemoveHeaders, onRuleRemoveHeadersChange, Modifier.fillMaxWidth(), singleLine = false, minHeight = 76.dp, placeholder = "Server\netag\nx-powered-by")
                TextField(
                    ruleBody,
                    onRuleBodyChange,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    colors = fieldColors(),
                    placeholder = { Text("{\"andy\":true}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                )
                Button(onClick = onAddOrSaveRule, modifier = Modifier.fillMaxWidth()) { Text(if (isEditing) "Save rule" else "Add rule") }
            }
        }
        itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
            val isBeingEdited = editingRuleId == rule.id
            PanelCard(
                modifier = if (isBeingEdited) Modifier.border(1.dp, Rust, RoundedCornerShape(AndyRadius.R3)) else Modifier
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(rule.enabled, { checked ->
                        onRulesChange(rules.mapIndexed { i, item -> if (i == index) item.copy(enabled = checked) else item })
                    })
                    Column(Modifier.weight(1f)) {
                        Text(rule.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${rule.method ?: "*"} ${rule.urlPattern}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(rule.statusCode?.let { "status $it" }, rule.responseBody?.let { "body" }, rule.setHeaders.takeIf { it.isNotEmpty() }?.let { "${it.size} headers" }).joinToString(" · "), color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEditRule(rule.id) }) { Text(if (isBeingEdited) "Editing" else "Edit") }
                    OutlinedButton(onClick = { if (index > 0) onRulesChange(rules.swapItems(index, index - 1)) }, enabled = index > 0) { Text("Up") }
                    OutlinedButton(onClick = { if (index < rules.lastIndex) onRulesChange(rules.swapItems(index, index + 1)) }, enabled = index < rules.lastIndex) { Text("Down") }
                    OutlinedButton(onClick = { onRulesChange(rules.filterIndexed { i, _ -> i != index }) }) { Text("Remove") }
                }
            }
        }
    }
}

internal fun shouldAutoStartProxy(status: String, port: Int): Boolean {
    val normalized = status.trim()
    if (normalized == "Proxy stopped" || normalized == "mitmdump exited" || normalized.startsWith("Proxy failed")) {
        return true
    }
    val listeningPort = Regex("""listening on \S+:(\d{1,5})""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return normalized.contains("listening on") && listeningPort != null && listeningPort != port
}


private fun parseHeaderLines(value: String): Map<String, String> {
    return value.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && ":" in it }
        .associate { line -> line.substringBefore(':').trim() to line.substringAfter(':').trim() }
}


private fun <T> List<T>.swapItems(first: Int, second: Int): List<T> {
    return toMutableList().also { items ->
        val temp = items[first]
        items[first] = items[second]
        items[second] = temp
    }
}

