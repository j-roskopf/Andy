package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkDiagnosisTest {
    @Test
    fun clientCaRejectionProducesRedTlsFailedDiagnosis() {
        val diagnoses = diagnoseNetworkTraffic(
            proxyStarted = true,
            caInstalled = true,
            proxyConfigured = true,
            routeDiagnostics = null,
            exchanges = listOf(
                NetworkExchange(
                    id = "tls-1",
                    flowId = "tls-1",
                    startedAtMillis = 1,
                    completedAtMillis = 1,
                    method = "TLS",
                    url = "https://api.example.com/",
                    statusCode = null,
                    contentType = null,
                    sizeBytes = null,
                    durationMillis = 0,
                    requestHeaders = emptyMap(),
                    responseHeaders = emptyMap(),
                    requestBodyPreview = null,
                    responseBodyPreview = null,
                    error = "Client rejected Andy's CA for api.example.com: unknown ca",
                    tlsStatus = "tls",
                    matchedRuleId = null,
                ),
            ),
            warnings = emptyList(),
            clientConnectionsObserved = 1,
            sslInsecure = false,
            upstreamTrustedCaPath = null,
        )

        assertTrue(diagnoses.any { it.mode == NetworkFailureMode.ClientRejectedCa && it.severity == NetworkDiagnosisSeverity.Red })
        assertTrue(diagnoses.any { it.title.contains("api.example.com") })
    }

    @Test
    fun corporateUpstreamFailureProducesRedInspectionDiagnosis() {
        val diagnoses = diagnoseNetworkTraffic(
            proxyStarted = true,
            caInstalled = true,
            proxyConfigured = true,
            routeDiagnostics = NetworkRouteDiagnostics(
                expectedProxy = "10.0.2.2:8888",
                configuredProxy = "10.0.2.2:8888",
                proxyConfigured = true,
                vpnActive = false,
                hostProxyActive = true,
                hostUpstreamProxy = "http://proxy.corp:8080",
            ),
            exchanges = listOf(
                NetworkExchange(
                    id = "flow-1",
                    flowId = "flow-1",
                    startedAtMillis = 1,
                    completedAtMillis = 2,
                    method = "GET",
                    url = "https://api.example.com/",
                    statusCode = null,
                    contentType = null,
                    sizeBytes = null,
                    durationMillis = 1,
                    requestHeaders = emptyMap(),
                    responseHeaders = emptyMap(),
                    requestBodyPreview = null,
                    responseBodyPreview = null,
                    error = "Certificate verify failed",
                    tlsStatus = "tls",
                    matchedRuleId = null,
                ),
            ),
            warnings = emptyList(),
            clientConnectionsObserved = 1,
            sslInsecure = false,
            upstreamTrustedCaPath = null,
        )

        val corporate = diagnoses.first { it.mode == NetworkFailureMode.CorporateTlsInspection }
        assertEquals(NetworkDiagnosisSeverity.Red, corporate.severity)
        assertTrue(corporate.detail.contains("http://proxy.corp:8080"))
        assertTrue(corporate.fix.contains("corporate root CA") || corporate.fix.contains("insecure-upstream"))
    }

    @Test
    fun idleTabDoesNotShowPrematureCaOrRoutingErrors() {
        val diagnoses = diagnoseNetworkTraffic(
            proxyStarted = true,
            caInstalled = false,
            proxyConfigured = false,
            routeDiagnostics = NetworkRouteDiagnostics(
                expectedProxy = "10.0.2.2:8888",
                configuredProxy = null,
                proxyConfigured = false,
                vpnActive = true,
                vpnName = "epc.tmobile.com",
                issues = listOf("A VPN is active (epc.tmobile.com); it can bypass Android's global HTTP proxy."),
            ),
            exchanges = emptyList(),
            warnings = emptyList(),
            clientConnectionsObserved = 0,
            sslInsecure = false,
            upstreamTrustedCaPath = null,
        )

        assertTrue(diagnoses.none { it.mode == NetworkFailureMode.NotRouted })
        assertTrue(diagnoses.none { it.mode == NetworkFailureMode.CaNotInstalled })
        assertTrue(diagnoses.any { it.title == "Waiting for traffic" })
    }

    @Test
    fun addonErrorsSurfaceAlongsideHealthyCapture() {
        val diagnoses = diagnoseNetworkTraffic(
            proxyStarted = true,
            caInstalled = true,
            proxyConfigured = true,
            routeDiagnostics = null,
            exchanges = listOf(
                NetworkExchange(
                    id = "flow-ok",
                    flowId = "flow-ok",
                    startedAtMillis = 1,
                    completedAtMillis = 2,
                    method = "GET",
                    url = "https://example.test/",
                    statusCode = 200,
                    contentType = "application/json",
                    sizeBytes = 2,
                    durationMillis = 1,
                    requestHeaders = emptyMap(),
                    responseHeaders = emptyMap(),
                    requestBodyPreview = null,
                    responseBodyPreview = "{}",
                    error = null,
                    tlsStatus = "tls",
                    matchedRuleId = null,
                ),
            ),
            warnings = listOf(
                ProxyWarning(
                    id = "w1",
                    atMillis = 3,
                    kind = ProxyWarningKind.AddonError,
                    message = "response hook: NameError: x",
                ),
                ProxyWarning(
                    id = "w2",
                    atMillis = 4,
                    kind = ProxyWarningKind.CaptureDropped,
                    message = "Network capture dropped 3 event(s) under load; proxied traffic was not blocked.",
                ),
                ProxyWarning(
                    id = "w3",
                    atMillis = 5,
                    kind = ProxyWarningKind.AddonMismatch,
                    message = "Running mitm addon SHA-256 deadbeef does not match Andy's resource abc.",
                ),
            ),
            clientConnectionsObserved = 1,
            sslInsecure = false,
            upstreamTrustedCaPath = null,
        )

        assertTrue(diagnoses.any { it.severity == NetworkDiagnosisSeverity.Green && it.title.contains("Capturing") })
        assertTrue(diagnoses.any { it.title == "mitm addon error" && it.detail.contains("NameError") })
        assertTrue(diagnoses.any { it.title == "Network capture dropped events" })
        assertTrue(diagnoses.any { it.title == "mitm addon version mismatch" })
    }
}
