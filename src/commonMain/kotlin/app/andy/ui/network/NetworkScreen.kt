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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.andy.rememberCopyText
import app.andy.ui.components.HeaderCell
import app.andy.domain.*
import app.andy.model.AndroidDevice
import app.andy.model.NetworkDiagnosis
import app.andy.model.NetworkDiagnosisSeverity
import app.andy.model.NetworkExchange
import app.andy.model.NetworkRouteDiagnostics
import app.andy.model.ProxyRule
import app.andy.model.ProxyStartOptions
import app.andy.model.SdkDiscovery
import app.andy.model.diagnoseNetworkTraffic
import app.andy.service.AndyServices
import app.andy.currentTimeMillis
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
    sslInsecure: Boolean = false,
    upstreamTrustedCaPath: String = "",
    onPortChange: (Int) -> Unit,
    onRulesChange: (List<ProxyRule>) -> Unit,
    onRulesVisibleChange: (Boolean) -> Unit,
    onSslInsecureChange: (Boolean) -> Unit = {},
    onUpstreamTrustedCaPathChange: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state = remember(services.proxy) { NetworkScreenState(services.proxy) }
    val proxy = state.proxy
    var portText by remember(port) { mutableStateOf(port.toString()) }
    val selected = state.exchanges.firstOrNull { it.flowId == state.selectedFlowId } ?: state.exchanges.lastOrNull()

    val currentPort = portText.toIntOrNull()?.coerceIn(1, 65535) ?: port
    val filteredExchanges = remember(state.exchanges, state.focusedPath) {
        if (state.focusedPath == null) {
            state.exchanges
        } else {
            state.exchanges.filter { exchange ->
                state.focusedPath in networkTrafficAncestorKeys(exchange)
            }
        }
    }
    val trafficTree = remember(filteredExchanges) { buildNetworkTrafficTree(filteredExchanges) }
    val visibleTrafficRows = remember(trafficTree, state.expandedTrafficKeys.toMap()) {
        flattenNetworkTrafficTree(trafficTree, state.expandedTrafficKeys)
    }
    val latestRules by rememberUpdatedState(rules)
    fun currentStartOptions() = ProxyStartOptions(
        sslInsecure = sslInsecure,
        upstreamTrustedCaPath = upstreamTrustedCaPath.trim().takeIf { it.isNotBlank() },
    )

    LaunchedEffect(sslInsecure, upstreamTrustedCaPath) {
        state.sslInsecure = sslInsecure
        state.upstreamTrustedCaPath = upstreamTrustedCaPath
    }

    LaunchedEffect(Unit) {
        state.caPath = proxy.certificateAuthorityPath()
        val engine = proxy.detectMitmproxy()
        state.engineReady = engine.isSuccess
        state.engineStatus = if (engine.isSuccess) {
            "mitmdump: ${engine.stdout.ifBlank { "ready" }}"
        } else {
            engine.stderr
        }
        state.engineChecked = true
    }
    LaunchedEffect(state.engineReady, currentPort, sslInsecure, upstreamTrustedCaPath) {
        if (!state.engineReady) return@LaunchedEffect
        val currentStatus = try {
            withTimeout(200) { proxy.status.first() }
        } catch (_: Exception) {
            state.proxyStatus
        }
        if (shouldAutoStartProxy(currentStatus, currentPort)) {
            proxy.ensureCertificateAuthority()
            val result = proxy.start(currentPort, latestRules, currentStartOptions())
            val message = if (result.isSuccess) result.stdout else result.stderr
            state.status = message
            if (result.isSuccess) state.proxyStatus = message
        }
    }
    LaunchedEffect(Unit) {
        proxy.exchanges.collectLatest { state.exchanges = it }
    }
    LaunchedEffect(Unit) {
        proxy.warnings.collectLatest { state.warnings = it }
    }
    LaunchedEffect(Unit) {
        proxy.clientConnectionCount.collectLatest { state.clientConnectionCount = it }
    }
    LaunchedEffect(Unit) {
        proxy.status.collectLatest {
            state.proxyStatus = it
            state.status = it
        }
    }
    LaunchedEffect(serial, state.exchanges) {
        if (serial != state.trafficEvidenceSerial) {
            state.trafficEvidenceSerial = serial
            state.flowIdsAtSerialChange = state.exchanges.map { it.flowId }.toSet()
            state.userCaVerifiedByTrafficForDevice = false
            state.proxyTrafficObservedForDevice = false
        } else {
            val newExchanges = state.exchanges.filter { it.flowId !in state.flowIdsAtSerialChange }
            if (newExchanges.any { it.method != "TLS" }) state.proxyTrafficObservedForDevice = true
            if (newExchanges.any { it.tlsStatus == "tls" && it.error == null }) {
                state.userCaVerifiedByTrafficForDevice = true
            }
        }
    }
    LaunchedEffect(
        state.proxyStatus,
        state.caInstalled,
        state.proxyConfigured,
        state.routeDiagnostics,
        state.exchanges,
        state.warnings,
        state.clientConnectionCount,
        state.sslInsecure,
        state.upstreamTrustedCaPath,
    ) {
        val proxyStarted = state.proxyStatus.contains("listening on")
        state.diagnoses = diagnoseNetworkTraffic(
            proxyStarted = proxyStarted,
            caInstalled = state.caInstalled || state.userCaVerifiedByTrafficForDevice,
            proxyConfigured = state.proxyConfigured,
            routeDiagnostics = state.routeDiagnostics,
            exchanges = state.exchanges,
            warnings = state.warnings,
            clientConnectionsObserved = state.clientConnectionCount,
            sslInsecure = state.sslInsecure,
            upstreamTrustedCaPath = state.upstreamTrustedCaPath.trim().takeIf { it.isNotBlank() },
        )
    }
    LaunchedEffect(serial, currentPort) {
        if (serial == null) {
            state.caInstalled = false
            state.proxyConfigured = false
            state.routeDiagnostics = null
            state.deviceReadinessChecked = true
            return@LaunchedEffect
        }
        state.deviceReadinessChecked = false
        while (true) {
            val isCaOk = proxy.isCertificateInstalled(serial)
            val host = proxy.resolveDeviceProxyHost(serial)
            val isProxyOk = proxy.isDeviceProxyConfigured(serial, host, currentPort)
            val route = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
            state.caInstalled = isCaOk
            state.proxyConfigured = isProxyOk
            state.routeDiagnostics = route
            state.deviceReadinessChecked = true
            delay(3000)
        }
    }
    LaunchedEffect(
        state.engineChecked,
        state.deviceReadinessChecked,
        sdk.hasAdb,
        state.engineReady,
        state.proxyStatus,
        serial,
        state.caInstalled,
        state.proxyTrafficObservedForDevice,
        state.proxyConfigured,
        state.routeDiagnostics,
    ) {
        if (state.setupDefaultApplied || state.setupManuallyToggled || !state.engineChecked || !state.deviceReadinessChecked) return@LaunchedEffect
        val redCount = listOf(
            sdk.hasAdb,
            true,
            state.engineReady,
            state.proxyStatus.contains("listening on"),
            serial != null && (state.caInstalled || state.proxyTrafficObservedForDevice),
            serial != null && state.proxyConfigured,
            serial != null && state.routeDiagnostics?.vpnActive != true && state.routeDiagnostics?.routeUsesVpn != true,
        ).count { !it }
        state.setupExpanded = redCount > 2
        state.setupDefaultApplied = true
    }
    LaunchedEffect(serial) {
        state.proxyHost = serial?.let { selectedSerial ->
            val activation = proxy.activatePersistedCertificateAuthority(selectedSerial)
            if (activation.isSuccess && activation.stdout.isNotBlank()) {
                state.status = activation.stdout
            }
            proxy.resolveDeviceProxyHost(selectedSerial)
        }.orEmpty()
    }
    LaunchedEffect(rules) {
        proxy.updateRules(rules)
    }
    LaunchedEffect(state.exchanges.map { it.flowId }) {
        val currentIds = state.exchanges.map { it.flowId }.toSet()
        val added = state.exchanges.filter { it.flowId !in state.seenFlowIds }
        if (state.seenFlowIds.isNotEmpty() && added.isNotEmpty()) {
            added.flatMap(::networkTrafficAncestorKeys).distinct().forEach { key ->
                if (state.expandedTrafficKeys[key] != true && key !in state.flashingTrafficKeys) {
                    val flashToken = currentTimeMillis()
                    state.flashingTrafficKeys[key] = flashToken
                    scope.launch {
                        delay(280)
                        if (state.flashingTrafficKeys[key] == flashToken) {
                            state.flashingTrafficKeys.remove(key)
                        }
                    }
                }
            }
        }
        state.seenFlowIds = currentIds
    }

    fun persistPort() {
        onPortChange(currentPort)
    }

    fun resetRuleForm() {
        state.clearRuleEditor()
    }

    fun addOrSaveRule() {
        val pattern = state.rulePattern.trim()
        if (pattern.isBlank()) {
            state.status = "Enter a URL match pattern before adding a rule"
            return
        }
        val editId = state.editingRuleId
        val editIdx = editId?.let { id -> rules.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        val rule = ProxyRule(
            id = editId ?: "rule-${rules.size + 1}-${pattern.hashCode().toString().replace("-", "n")}",
            name = state.ruleName.ifBlank { pattern },
            enabled = editIdx?.let { rules[it].enabled } ?: true,
            urlPattern = pattern,
            method = state.ruleMethod.trim().uppercase().ifBlank { null },
            statusCode = state.ruleStatus.toIntOrNull(),
            setHeaders = parseHeaderLines(state.ruleSetHeaders),
            removeHeaders = state.ruleRemoveHeaders.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() },
            responseBody = state.ruleBody.takeIf { it.isNotBlank() },
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
            state.selectedFlowId = null
            state.seenFlowIds = emptySet()
            state.flashingTrafficKeys.clear()
            state.status = if (result.isSuccess) result.stdout else result.stderr
        }
    }

    fun editRule(ruleId: String) {
        val rule = rules.firstOrNull { it.id == ruleId } ?: return
        state.loadRuleEditor(
            name = rule.name,
            pattern = rule.urlPattern,
            method = rule.method ?: "",
            statusCode = rule.statusCode?.toString() ?: "",
            setHeaders = rule.setHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            removeHeaders = rule.removeHeaders.joinToString("\n"),
            body = rule.responseBody ?: "",
            ruleId = rule.id,
        )
        onRulesVisibleChange(true)
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .weight(1.45f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PanelCard(Modifier.animateContentSize()) {
                val copyText = rememberCopyText()
                var configCopied by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Debug-app HTTPS proxy", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = ::clearCapturedTraffic) { Text("Clear traffic") }
                    Text(
                        if (state.setupExpanded) "Hide setup" else "Show setup",
                        color = Rust,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            state.setupManuallyToggled = true
                            state.setupExpanded = !state.setupExpanded
                        },
                    )
                }
                val proxyStarted = state.proxyStatus.contains("listening on")
                val caText = when {
                    serial == null -> "Select a device first"
                    state.caInstalled -> "System CA installed"
                    state.userCaVerifiedByTrafficForDevice -> "User CA verified by HTTPS traffic"
                    state.proxyTrafficObservedForDevice -> "Traffic observed"
                    else -> "Use System CA or Prepare phone CA"
                }
                val configText = when {
                    serial == null -> "Select a device first"
                    state.proxyConfigured -> "Device is routed"
                    else -> "Click 'Configure' to route"
                }
                val routeText = when {
                    serial == null -> "Select a device first"
                    state.routeDiagnostics == null -> "Checking route"
                    state.routeDiagnostics?.hostProxyActive == true && state.routeDiagnostics?.hostUpstreamProxy != null -> {
                        val upstream = state.routeDiagnostics?.hostUpstreamProxy
                        if (upstream != null && (upstream.contains("127.0.0.1") || upstream.contains("localhost"))) {
                            "Chaining through local Mac proxy $upstream"
                        } else {
                            "Chaining through Mac proxy $upstream"
                        }
                    }
                    state.routeDiagnostics?.hostProxyActive == true -> "Mac proxy active"
                    state.routeDiagnostics?.vpnActive == true -> "VPN active (may cause issues)"
                    state.routeDiagnostics?.routeUsesVpn == true -> "Proxy route uses VPN"
                    state.routeDiagnostics?.proxyConfigured == false -> "Proxy route needs repair"
                    else -> "No VPN route issue detected"
                }
                val routeOk = serial != null &&
                    state.routeDiagnostics?.vpnActive != true &&
                    state.routeDiagnostics?.routeUsesVpn != true &&
                    (
                        state.routeDiagnostics?.hostProxyBypassLooksSafe != false ||
                            state.routeDiagnostics?.hostUpstreamProxy.orEmpty().let { upstream ->
                                upstream.contains("127.0.0.1") || upstream.contains("localhost")
                            }
                    )
                val showRouteWarning = state.routeDiagnostics?.hasBlockingIssue == true && proxyStarted && !state.proxyTrafficObservedForDevice
                val proxyHostDisplay = state.proxyHost.ifBlank { "this Mac's LAN IP" }
                AnimatedVisibility(state.setupExpanded) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SetupStepCard(
                                step = 1,
                                title = "Install mitmproxy",
                                ok = state.engineReady,
                                instruction = if (state.engineReady) {
                                    state.engineStatus
                                } else {
                                    "Required for Network capture and rewrite rules. Install on the host, then Andy will start mitmdump automatically."
                                },
                                modifier = Modifier.widthIn(min = 260.dp).weight(1f),
                            ) {
                                Text("brew install mitmproxy", color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
                            }
                            SetupStepCard(
                                step = 2,
                                title = "Add network security config",
                                ok = null,
                                instruction = "Debug builds must trust user CAs. Add the XML below as res/xml/network_security_config.xml, then reference it in AndroidManifest.xml with android:networkSecurityConfig=\"@xml/network_security_config\".",
                                modifier = Modifier.widthIn(min = 320.dp).weight(1f),
                            ) {
                                SelectionContainer {
                                    Text(
                                        DebugNetworkSecurityConfigSnippet,
                                        color = TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(onClick = {
                                        copyText(DebugNetworkSecurityConfigSnippet)
                                        configCopied = true
                                        state.status = "Network security config copied to clipboard"
                                    }) { Text("Copy") }
                                    if (configCopied) {
                                        Text("Copied", color = Green, fontSize = 11.sp)
                                    }
                                }
                                Text(
                                    "Use this only in debug builds that trust user CAs. Certificate pinning and arbitrary third-party app bypass are out of scope.",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                )
                            }
                            SetupStepCard(
                                step = 3,
                                title = "Start / stop HTTP proxy",
                                ok = proxyStarted,
                                instruction = if (proxyStarted) {
                                    "Listening on port $currentPort"
                                } else {
                                    "Andy runs mitmdump on this port. Click Start to begin capturing HTTPS traffic."
                                },
                                modifier = Modifier.widthIn(min = 260.dp).weight(1f),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
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
                                            val result = proxy.start(currentPort, rules, currentStartOptions())
                                            val message = if (result.isSuccess) result.stdout else result.stderr
                                            state.status = message
                                            if (result.isSuccess) state.proxyStatus = message
                                        }
                                    }) { Text("Start") }
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            val result = proxy.stop()
                                            state.status = result.stdout
                                            state.proxyStatus = result.stdout
                                        }
                                    }) { Text("Stop") }
                                }
                            }
                            SetupStepCard(
                                step = 4,
                                title = "Install cert to device",
                                ok = null,
                                instruction = "One-time step per device (Andy's CA lasts ~10 years unless you wipe the emulator/device or regenerate ~/.andy/proxy). System CA (root) installs into the system trust store and requires adb root (emulator). Prepare phone CA only pushes the cert to Downloads and opens Security settings — you must still manually complete the CA install on the device (Settings > Security > Install a certificate > CA certificate).",
                                modifier = Modifier.widthIn(min = 260.dp).weight(1f),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        enabled = serial != null,
                                        onClick = {
                                            if (serial != null) scope.launch {
                                                val result = proxy.installSystemCertificateAuthority(serial)
                                                state.status = if (result.isSuccess) result.stdout else result.stderr
                                                state.caInstalled = proxy.isCertificateInstalled(serial)
                                            }
                                        },
                                    ) { Text("System CA (root)") }
                                    OutlinedButton(
                                        enabled = serial != null,
                                        onClick = {
                                            if (serial != null) scope.launch {
                                                proxy.ensureCertificateAuthority()
                                                val result = proxy.prepareUserCertificateInstall(serial)
                                                state.status = if (result.isSuccess) result.stdout else result.stderr
                                            }
                                        },
                                    ) { Text("Prepare phone CA") }
                                }
                            }
                            SetupStepCard(
                                step = 5,
                                title = "Configure device proxy",
                                ok = serial != null && state.proxyConfigured,
                                instruction = "$configText. Sets Android's global http_proxy to $proxyHostDisplay:$currentPort so device traffic routes through Andy.",
                                modifier = Modifier.widthIn(min = 260.dp).weight(1f),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        enabled = serial != null,
                                        onClick = {
                                            if (serial != null) scope.launch {
                                                val host = proxy.resolveDeviceProxyHost(serial)
                                                state.proxyHost = host
                                                val result = proxy.configureDeviceProxy(serial, host, currentPort)
                                                state.status = if (result.isSuccess) result.stdout else result.stderr
                                                state.proxyConfigured = proxy.isDeviceProxyConfigured(serial, host, currentPort)
                                                state.routeDiagnostics = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
                                            }
                                        },
                                    ) { Text("Configure device proxy") }
                                    OutlinedButton(
                                        enabled = serial != null,
                                        onClick = {
                                            if (serial != null) scope.launch {
                                                val result = proxy.clearDeviceProxy(serial)
                                                state.status = if (result.isSuccess) result.stdout else result.stderr
                                                val host = proxy.resolveDeviceProxyHost(serial)
                                                state.proxyConfigured = proxy.isDeviceProxyConfigured(serial, host, currentPort)
                                            }
                                        },
                                    ) { Text("Clear proxy") }
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
                                .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            GlowingDot(routeOk, Modifier.padding(top = 2.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("VPN / Route", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(routeText, color = if (routeOk) Green else Red, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val host = proxy.resolveDeviceProxyHost(serial)
                                        state.proxyHost = host
                                        val configured = proxy.configureDeviceProxy(serial, host, currentPort)
                                        val route = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
                                        state.routeDiagnostics = route
                                        state.proxyConfigured = route.proxyConfigured
                                        val restart = if (route.hostProxyActive) {
                                            proxy.start(currentPort, rules, currentStartOptions())
                                        } else {
                                            null
                                        }
                                        state.status = if (route.vpnActive) {
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
                                        state.status = if (result.isSuccess) "Opened Android VPN settings" else result.stderr
                                    }
                                },
                            ) { Text("Open VPN settings") }
                        }
                        CorporateUpstreamSettings(
                            sslInsecure = state.sslInsecure,
                            upstreamTrustedCaPath = state.upstreamTrustedCaPath,
                            hostUpstreamProxy = state.routeDiagnostics?.hostUpstreamProxy,
                            onSslInsecureChange = {
                                onSslInsecureChange(it)
                                state.sslInsecure = it
                            },
                            onUpstreamTrustedCaPathChange = {
                                onUpstreamTrustedCaPathChange(it)
                                state.upstreamTrustedCaPath = it
                            },
                        )
                        AnimatedVisibility(showRouteWarning) {
                            NetworkRouteWarningCard(state.routeDiagnostics)
                        }
                        Text(state.status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusTag(if (state.engineReady) "mitmproxy ready" else "mitmproxy missing", if (state.engineReady) Green else Red)
                            Text(
                                text = buildString {
                                    append(state.engineStatus)
                                    append("  ·  ")
                                    append("Endpoint: ")
                                    append(state.proxyHost.ifBlank { "select device" })
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
                            steps = hostSetupSteps(state.engineReady),
                        )
                        Text("CA: ${state.caPath.ifBlank { "~/.andy/proxy/mitmproxy-ca-cert.cer" }}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ManualCertificateSetupCard(
                            steps = manualCertificateSteps(state.caPath, state.proxyHost, currentPort),
                        )
                        Text(
                            "Capture scope: physical devices can trust Andy as a user CA only after manual approval, and only debug apps that opt into user CAs will decrypt. Chrome and many third-party apps need system trust on a rooted device or rootable non-Play emulator; pinned apps, QUIC/HTTP3, private DNS, and direct UDP will not appear in v1.",
                            color = Yellow,
                            fontSize = 12.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            NetworkDiagnosisStrip(state.diagnoses)
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
            if (state.focusedPath != null) {
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
                            text = "Focus mode: showing only ${state.focusedPath?.removePrefix("base:")}",
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
                            .clickable { state.focusedPath = null }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderCell("TRAFFIC", state.trafficWidth.dp) { state.trafficWidth = it.coerceIn(160f, 2400f) }
                HeaderCell("STATUS", state.statusWidth.dp) { state.statusWidth = it.coerceIn(50f, 150f) }
                HeaderCell("TYPE", state.typeWidth.dp) { state.typeWidth = it.coerceIn(80f, 250f) }
                HeaderCell("SIZE", state.sizeWidth.dp) { state.sizeWidth = it.coerceIn(50f, 150f) }
                HeaderCell("MS", state.msWidth.dp) { state.msWidth = it.coerceIn(50f, 150f) }
                Text("RULE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(visibleTrafficRows, key = { row -> row.key }) { row ->
                    NetworkTrafficRowItem(
                        row = row,
                        expanded = state.expandedTrafficKeys[row.key] == true,
                        flashing = row.key in state.flashingTrafficKeys,
                        trafficWidth = state.trafficWidth,
                        statusWidth = state.statusWidth,
                        typeWidth = state.typeWidth,
                        sizeWidth = state.sizeWidth,
                        msWidth = state.msWidth,
                        onToggle = {
                            if (row.hasChildren) {
                                state.expandedTrafficKeys[row.key] = state.expandedTrafficKeys[row.key] != true
                                state.flashingTrafficKeys.remove(row.key)
                            }
                        },
                        onSelect = { exchange ->
                            state.selectedFlowId = exchange.flowId
                        },
                        onFocus = { path ->
                            state.focusedPath = path
                        },
                        onAddRule = { exchange ->
                            val pathSegment = exchange.url.substringAfterLast("/").substringBefore("?")
                            state.ruleName = if (pathSegment.isNotBlank()) "Mock $pathSegment" else "Mock response"
                            state.rulePattern = exchange.url
                            state.ruleMethod = exchange.method
                            state.ruleStatus = exchange.statusCode?.toString() ?: "200"
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
                            state.ruleSetHeaders = exchange.responseHeaders.entries
                                .filter { it.key.lowercase() !in excludedHeaders }
                                .joinToString("\n") { "${it.key}: ${it.value}" }
                            state.ruleRemoveHeaders = ""
                            state.ruleBody = exchange.responseBodyPreview ?: ""
                            onRulesVisibleChange(true)
                        }
                    )
                }
                if (visibleTrafficRows.isEmpty()) {
                    item {
                        val emptyMessage = state.diagnoses.firstOrNull { it.severity == NetworkDiagnosisSeverity.Red }?.let {
                            "${it.title}. ${it.fix}"
                        } ?: "No traffic yet. Start the proxy, configure a device, then make a request."
                        EmptyState(emptyMessage)
                    }
                }
            }
            }
            SelectedFlowPanel(
                selected = selected,
                expanded = state.selectedExpanded,
                onToggle = { state.selectedExpanded = !state.selectedExpanded },
                modifier = Modifier.fillMaxWidth().then(if (state.selectedExpanded) Modifier.height(340.dp) else Modifier.heightIn(min = 54.dp)),
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
                        ruleName = state.ruleName,
                        onRuleNameChange = { state.ruleName = it },
                        rulePattern = state.rulePattern,
                        onRulePatternChange = { state.rulePattern = it },
                        ruleMethod = state.ruleMethod,
                        onRuleMethodChange = { state.ruleMethod = it },
                        ruleStatus = state.ruleStatus,
                        onRuleStatusChange = { state.ruleStatus = it },
                        ruleSetHeaders = state.ruleSetHeaders,
                        onRuleSetHeadersChange = { state.ruleSetHeaders = it },
                        ruleRemoveHeaders = state.ruleRemoveHeaders,
                        onRuleRemoveHeadersChange = { state.ruleRemoveHeaders = it },
                        ruleBody = state.ruleBody,
                        onRuleBodyChange = { state.ruleBody = it },
                        onRulesChange = onRulesChange,
                        onAddOrSaveRule = ::addOrSaveRule,
                        editingRuleId = state.editingRuleId,
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
                        ruleName = state.ruleName,
                        onRuleNameChange = { state.ruleName = it },
                        rulePattern = state.rulePattern,
                        onRulePatternChange = { state.rulePattern = it },
                        ruleMethod = state.ruleMethod,
                        onRuleMethodChange = { state.ruleMethod = it },
                        ruleStatus = state.ruleStatus,
                        onRuleStatusChange = { state.ruleStatus = it },
                        ruleSetHeaders = state.ruleSetHeaders,
                        onRuleSetHeadersChange = { state.ruleSetHeaders = it },
                        ruleRemoveHeaders = state.ruleRemoveHeaders,
                        onRuleRemoveHeadersChange = { state.ruleRemoveHeaders = it },
                        ruleBody = state.ruleBody,
                        onRuleBodyChange = { state.ruleBody = it },
                        onRulesChange = onRulesChange,
                        onAddOrSaveRule = ::addOrSaveRule,
                        editingRuleId = state.editingRuleId,
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
private fun NetworkDiagnosisStrip(diagnoses: List<NetworkDiagnosis>, modifier: Modifier = Modifier) {
    if (diagnoses.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        diagnoses.forEach { diagnosis ->
            val (bg, border, accent) = when (diagnosis.severity) {
                NetworkDiagnosisSeverity.Red -> Triple(Red.copy(alpha = 0.12f), Red.copy(alpha = 0.55f), Red)
                NetworkDiagnosisSeverity.Amber -> Triple(Yellow.copy(alpha = 0.10f), Yellow.copy(alpha = 0.55f), Yellow)
                NetworkDiagnosisSeverity.Green -> Triple(Green.copy(alpha = 0.10f), Green.copy(alpha = 0.45f), Green)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(bg, RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, border, RoundedCornerShape(AndyRadius.R3))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Text(diagnosis.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text(diagnosis.detail, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                if (diagnosis.fix.isNotBlank()) {
                    Text(diagnosis.fix, color = accent, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun CorporateUpstreamSettings(
    sslInsecure: Boolean,
    upstreamTrustedCaPath: String,
    hostUpstreamProxy: String?,
    onSslInsecureChange: (Boolean) -> Unit,
    onUpstreamTrustedCaPathChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Corporate TLS / upstream trust", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            if (hostUpstreamProxy != null) {
                val local = hostUpstreamProxy.contains("127.0.0.1") || hostUpstreamProxy.contains("localhost")
                if (local) {
                    "Mac system proxy is a local debug tool ($hostUpstreamProxy). Andy chains through it. Quit Proxyman/Charles or fix its bypass list if traffic looks wrong."
                } else {
                    "Chaining through Mac proxy $hostUpstreamProxy. If upstream verify fails (corporate TLS inspection), trust the corporate root or enable insecure-upstream."
                }
            } else {
                "When a Mac proxy re-signs TLS (common with corporate security tools), mitmproxy needs the corporate root CA or insecure-upstream to reach the real server."
            },
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = sslInsecure, onCheckedChange = onSslInsecureChange)
            Text("Insecure upstream (--ssl-insecure)", color = TextPrimary, fontSize = 12.sp)
        }
        LabeledField(
            label = "Corporate root CA path",
            value = upstreamTrustedCaPath,
            onValueChange = onUpstreamTrustedCaPathChange,
            placeholder = "/path/to/corp-root.pem",
            singleLine = true,
        )
        Text("Restart the proxy after changing these options.", color = TextSecondary, fontSize = 11.sp)
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
private fun SetupStepCard(
    step: Int,
    title: String,
    ok: Boolean?,
    instruction: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    Column(
        modifier
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$step.", color = Rust, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (ok != null) {
                GlowingDot(ok)
            }
        }
        Text(instruction, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        action()
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
