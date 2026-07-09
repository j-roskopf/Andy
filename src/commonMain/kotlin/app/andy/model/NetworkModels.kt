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
