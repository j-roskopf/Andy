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
    fun notRoutedWhenNoClientConnected() {
        val diagnoses = diagnoseNetworkTraffic(
            proxyStarted = true,
            caInstalled = true,
            proxyConfigured = false,
            routeDiagnostics = NetworkRouteDiagnostics(
                expectedProxy = "10.0.2.2:8888",
                configuredProxy = null,
                proxyConfigured = false,
                vpnActive = false,
                issues = listOf("Android global proxy is not set; expected 10.0.2.2:8888."),
            ),
            exchanges = emptyList(),
            warnings = emptyList(),
            clientConnectionsObserved = 0,
            sslInsecure = false,
            upstreamTrustedCaPath = null,
        )

        assertTrue(diagnoses.any { it.mode == NetworkFailureMode.NotRouted && it.severity == NetworkDiagnosisSeverity.Red })
    }
}
