package app.andy.model

data class ProxyRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val urlPattern: String,
    val method: String? = null,
    val statusCode: Int? = null,
    val setHeaders: Map<String, String> = emptyMap(),
    val removeHeaders: List<String> = emptyList(),
    val responseBody: String? = null,
)

fun ProxyRule.matches(method: String, url: String): Boolean {
    if (!enabled) return false
    if (urlPattern.isNotBlank()) {
        if ("*" in urlPattern) {
            val regex = Regex.escape(urlPattern).replace("\\*", ".*")
            if (!Regex(regex, RegexOption.IGNORE_CASE).matches(url)) return false
        } else {
            if (!url.contains(urlPattern, ignoreCase = true)) return false
        }
    }
    if (!this.method.isNullOrBlank() && !this.method.equals(method, ignoreCase = true)) return false
    return true
}

data class ProxyStartOptions(
    val sslInsecure: Boolean = false,
    val upstreamTrustedCaPath: String? = null,
)

data class ProxyWarning(
    val id: String,
    val atMillis: Long,
    val kind: ProxyWarningKind,
    val message: String,
    val sni: String? = null,
)

enum class ProxyWarningKind {
    ClientTlsFailure,
    UpstreamTlsFailure,
    Other,
}

data class NetworkExchange(
    val id: String,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val contentType: String?,
    val sizeBytes: Long?,
    val durationMillis: Long?,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val requestBodyPreview: String?,
    val responseBodyPreview: String?,
    val error: String?,
    val tlsStatus: String?,
    val matchedRuleId: String?,
    val flowId: String,
)

enum class NetworkFailureMode {
    NotRouted,
    ClientRejectedCa,
    CorporateTlsInspection,
    CaNotInstalled,
    UnsupportedProtocol,
}

enum class NetworkDiagnosisSeverity {
    Green,
    Amber,
    Red,
}

data class NetworkDiagnosis(
    val mode: NetworkFailureMode?,
    val severity: NetworkDiagnosisSeverity,
    val title: String,
    val detail: String,
    val fix: String,
)

data class NetworkRouteDiagnostics(
    val expectedProxy: String,
    val configuredProxy: String?,
    val proxyConfigured: Boolean,
    val vpnActive: Boolean,
    val vpnName: String? = null,
    val routeUsesVpn: Boolean = false,
    val routeSummary: String? = null,
    val hostProxyActive: Boolean = false,
    val hostProxySummary: String? = null,
    val hostUpstreamProxy: String? = null,
    val hostProxyBypassLooksSafe: Boolean = true,
    val hostVpnActive: Boolean = false,
    val hostVpnSummary: String? = null,
    val hostRouteSummary: String? = null,
    val issues: List<String> = emptyList(),
) {
    val hasBlockingIssue: Boolean get() = issues.isNotEmpty()
}

fun isUpstreamTlsVerificationError(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val lower = message.lowercase()
    return listOf(
        "certificate verify failed",
        "sslcertverificationerror",
        "unable to get local issuer certificate",
        "self signed certificate in certificate chain",
        "upstream server tls",
        "tls handshake failed with server",
        "certificate_verify_failed",
    ).any { it in lower }
}

fun isClientTlsRejectionError(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val lower = message.lowercase()
    return listOf(
        "client rejected andy's ca",
        "client tls handshake failed",
        "does not trust the proxy",
        "unknown ca",
        "bad certificate",
        "certificate unknown",
        "tlsv1 alert unknown ca",
        "sslv3 alert bad certificate",
    ).any { it in lower }
}

fun diagnoseNetworkTraffic(
    proxyStarted: Boolean,
    caInstalled: Boolean,
    proxyConfigured: Boolean,
    routeDiagnostics: NetworkRouteDiagnostics?,
    exchanges: List<NetworkExchange>,
    warnings: List<ProxyWarning>,
    clientConnectionsObserved: Int,
    sslInsecure: Boolean,
    upstreamTrustedCaPath: String?,
): List<NetworkDiagnosis> {
    if (!proxyStarted) {
        return listOf(
            NetworkDiagnosis(
                mode = null,
                severity = NetworkDiagnosisSeverity.Amber,
                title = "Proxy is not running",
                detail = "Andy cannot diagnose missing traffic until mitmdump is listening.",
                fix = "Click Start to launch the proxy.",
            ),
        )
    }

    val successfulHttps = exchanges.any { it.tlsStatus == "tls" && it.error == null && it.statusCode != null }
    val successfulAny = exchanges.any { it.error == null && (it.statusCode != null || it.method != "TLS") }
    if (successfulHttps || (successfulAny && exchanges.none { it.error != null })) {
        val chaining = routeDiagnostics?.hostUpstreamProxy
        return listOf(
            NetworkDiagnosis(
                mode = null,
                severity = NetworkDiagnosisSeverity.Green,
                title = if (chaining != null) "Capturing traffic (chaining through $chaining)" else "Capturing traffic",
                detail = "HTTPS flows are reaching Andy and completing successfully.",
                fix = "",
            ),
        )
    }

    val diagnoses = mutableListOf<NetworkDiagnosis>()
    val tlsFailed = exchanges.filter { it.method == "TLS" || isClientTlsRejectionError(it.error) }
    val upstreamErrors = exchanges.filter { isUpstreamTlsVerificationError(it.error) } +
        warnings.filter { it.kind == ProxyWarningKind.UpstreamTlsFailure }
    val clientWarnings = warnings.filter { it.kind == ProxyWarningKind.ClientTlsFailure }

    if (upstreamErrors.isNotEmpty() || (routeDiagnostics?.hostProxyActive == true && exchanges.any { it.error != null && isUpstreamTlsVerificationError(it.error) })) {
        val proxyLabel = routeDiagnostics?.hostUpstreamProxy ?: routeDiagnostics?.hostProxySummary ?: "corporate proxy"
        val hasMitigation = sslInsecure || !upstreamTrustedCaPath.isNullOrBlank()
        diagnoses += NetworkDiagnosis(
            mode = NetworkFailureMode.CorporateTlsInspection,
            severity = NetworkDiagnosisSeverity.Red,
            title = "Upstream TLS verification failed — corporate TLS inspection",
            detail = "Andy appears to be behind a corporate TLS-inspection proxy ($proxyLabel). mitmproxy cannot verify the real server certificate because it was re-signed by the corporate root.",
            fix = if (hasMitigation) {
                "Corporate CA / insecure-upstream is configured; restart the proxy if traffic still fails."
            } else {
                "Add your corporate root CA path or enable insecure-upstream in Network setup / Settings, then restart the proxy."
            },
        )
    }

    if (tlsFailed.isNotEmpty() || clientWarnings.isNotEmpty()) {
        val host = tlsFailed.firstOrNull()?.url?.removePrefix("https://")?.substringBefore('/')
            ?: clientWarnings.firstOrNull()?.sni
            ?: "the app"
        diagnoses += NetworkDiagnosis(
            mode = NetworkFailureMode.ClientRejectedCa,
            severity = NetworkDiagnosisSeverity.Red,
            title = "$host rejected Andy's CA",
            detail = "The app connected to Andy but rejected the proxy certificate during the TLS handshake. Likely certificate pinning, a custom TrustManager/SSLSocketFactory, or missing user-CA trust.",
            fix = "Disable pinning in your debug build, trust user CAs via network_security_config, or install Andy's System CA.",
        )
    }

    if (!caInstalled && exchanges.none { it.tlsStatus == "tls" && it.error == null }) {
        diagnoses += NetworkDiagnosis(
            mode = NetworkFailureMode.CaNotInstalled,
            severity = NetworkDiagnosisSeverity.Red,
            title = "Andy CA is not installed as a system trust",
            detail = "Without system (or verified user) CA trust, HTTPS clients will reject Andy's MITM certificate and produce no decryptable traffic.",
            fix = "Use System CA (root) on a writable emulator, or Prepare phone CA and finish the install on-device for debug apps that trust user CAs.",
        )
    }

    val routeIssues = routeDiagnostics?.issues.orEmpty()
    val noHttpTraffic = exchanges.none { it.method != "TLS" }
    if (noHttpTraffic && (routeIssues.isNotEmpty() || !proxyConfigured || clientConnectionsObserved == 0)) {
        val detail = when {
            routeIssues.isNotEmpty() -> routeIssues.joinToString(" ")
            !proxyConfigured -> "Android's global HTTP proxy is not pointed at Andy."
            clientConnectionsObserved == 0 -> "No TCP client has connected to mitmdump yet — traffic is not reaching Andy (VPN, proxy override, or QUIC/UDP)."
            else -> "Clients connected but no HTTP request was captured — the app may bypass the proxy or use QUIC/HTTP3."
        }
        diagnoses += NetworkDiagnosis(
            mode = NetworkFailureMode.NotRouted,
            severity = NetworkDiagnosisSeverity.Red,
            title = "No client traffic is reaching Andy",
            detail = detail,
            fix = "Repair proxy route, disable/split-tunnel VPN, remove app-level Proxy.NO_PROXY, and force HTTP/1.1 if the app prefers QUIC.",
        )
    }

    if (noHttpTraffic && clientConnectionsObserved > 0 && tlsFailed.isEmpty() && upstreamErrors.isEmpty()) {
        diagnoses += NetworkDiagnosis(
            mode = NetworkFailureMode.UnsupportedProtocol,
            severity = NetworkDiagnosisSeverity.Amber,
            title = "Connected but no HTTP/HTTPS flows",
            detail = "A client reached Andy without producing decryptable HTTP traffic. QUIC/HTTP3, WebSocket upgrades, or gRPC quirks can look like an empty capture.",
            fix = "Disable Private DNS / QUIC in the debug app or force HTTP/1.1 for the endpoints you need to inspect.",
        )
    }

    if (diagnoses.isEmpty()) {
        diagnoses += NetworkDiagnosis(
            mode = null,
            severity = NetworkDiagnosisSeverity.Amber,
            title = "Waiting for traffic",
            detail = "Proxy is running. Make a request from a debug app that trusts Andy's CA.",
            fix = "Configure the device proxy, install the CA if needed, then exercise the app.",
        )
    }
    return diagnoses.distinctBy { it.mode to it.title }
}
