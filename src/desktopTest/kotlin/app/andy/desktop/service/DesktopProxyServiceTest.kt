package app.andy.desktop.service

import app.andy.model.ProxyRule
import app.andy.model.WorkspaceState
import app.andy.model.matches
import app.andy.service.CommandResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

class DesktopProxyServiceTest {
    @Test
    fun ruleMatchingUsesEnabledUrlAndOptionalMethod() {
        val rule = ProxyRule(
            id = "rule-1",
            name = "API",
            enabled = true,
            urlPattern = "example.test/api",
            method = "POST",
        )

        assertTrue(rule.matches("POST", "https://example.test/api/users"))
        assertTrue(rule.copy(method = null).matches("GET", "https://example.test/api/users"))
        assertFalse(rule.matches("GET", "https://example.test/api/users"))
        assertFalse(rule.matches("POST", "https://other.test/api/users"))
        assertFalse(rule.copy(enabled = false).matches("POST", "https://example.test/api/users"))
    }

    @Test
    fun rulesSerializeForMitmproxyAddon() {
        val json = ProxyRuleJson.writeRules(
            listOf(
                ProxyRule(
                    id = "rule-1",
                    name = "Override",
                    enabled = true,
                    urlPattern = "example.test",
                    method = "GET",
                    statusCode = 503,
                    setHeaders = mapOf("x-andy" to "yes"),
                    removeHeaders = listOf("etag"),
                    responseBody = "{\"offline\":true}",
                ),
            ),
        )

        assertTrue(json.contains(""""id":"rule-1""""))
        assertTrue(json.contains(""""statusCode":503"""))
        assertTrue(json.contains(""""setHeaders":{"x-andy":"yes"}"""))
        assertTrue(json.contains(""""removeHeaders":["etag"]"""))
        assertTrue(json.contains(""""responseBody":"{\"offline\":true}""""))
    }

    @Test
    fun mitmproxyJsonLineParsesIntoNetworkExchange() {
        val exchange = parseMitmproxyFlowLine(
            """
            {"type":"flow","id":"flow-7","startedAtMillis":10,"completedAtMillis":32,"durationMillis":22,"method":"PUT","url":"https://example.test/items","statusCode":202,"contentType":"application/json","sizeBytes":27,"requestHeaders":{"authorization":"redacted"},"responseHeaders":{"x-andy":"yes"},"requestBodyPreview":"{}","responseBodyPreview":"{\"ok\":true}","error":null,"tlsStatus":"tls","matchedRuleId":"rule-1"}
            """.trimIndent(),
        )

        assertNotNull(exchange)
        assertEquals("flow-7", exchange.flowId)
        assertEquals("PUT", exchange.method)
        assertEquals(202, exchange.statusCode)
        assertEquals(22, exchange.durationMillis)
        assertEquals("redacted", exchange.requestHeaders["authorization"])
        assertEquals("rule-1", exchange.matchedRuleId)
    }

    @Test
    fun workspaceStoreRoundTripsProxyRuleStatusAndHeaders() = runBlocking {
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-workspace-test-home").toFile()
        try {
            System.setProperty("user.home", testHome.absolutePath)
            val store = DesktopWorkspaceStore()
            store.save(
                WorkspaceState(
                    proxyRules = listOf(
                        ProxyRule(
                            id = "rule-404",
                            name = "Missing profile",
                            enabled = true,
                            urlPattern = "https://api.example.com/v1/*/profile",
                            method = "GET",
                            statusCode = 404,
                            setHeaders = mapOf("x-andy" to "missing", "content-type" to "application/json"),
                            removeHeaders = listOf("Server", "etag"),
                            responseBody = """{"error":"missing"}""",
                        ),
                    ),
                ),
            )

            val loadedRule = store.load().proxyRules.single()

            assertEquals(404, loadedRule.statusCode)
            assertEquals(mapOf("x-andy" to "missing", "content-type" to "application/json"), loadedRule.setHeaders)
            assertEquals(listOf("Server", "etag"), loadedRule.removeHeaders)
        } finally {
            System.setProperty("user.home", originalHome)
            testHome.deleteRecursively()
        }
    }

    @Test
    fun serviceReportsMissingMitmdumpAndProcessStartFailure() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val missing = DesktopProxyService(env.runner, env.devices, mitmdumpExecutable = { null })
        val missingDetection = missing.detectMitmproxy()
        val missingResult = missing.start(9099, emptyList())

        val failing = DesktopProxyService(
            env.runner,
            env.devices,
            mitmdumpExecutable = { "/usr/bin/mitmdump" },
            processStarter = { _, _, _ -> error("boom") },
        )
        val failingResult = failing.start(9099, emptyList())

        assertFalse(missingDetection.isSuccess)
        assertTrue(missingDetection.stderr.contains("brew install mitmproxy"))
        assertFalse(missingResult.isSuccess)
        assertTrue(missingResult.stderr.contains("brew install mitmproxy"))
        assertFalse(failingResult.isSuccess)
        assertTrue(failingResult.stderr.contains("boom"))
    }

    @Test
    fun deviceProxyHostUsesEmulatorLoopbackAliasOnlyForEmulators() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        assertEquals("10.0.2.2", services.proxy.resolveDeviceProxyHost("emulator-5554"))
        assertTrue(services.proxy.resolveDeviceProxyHost("R3CXB056ZZB").isNotBlank())
    }

    @Test
    fun systemCaInstallPrefersPersistentInstallWhenSystemIsWritable() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-proxy-test-home").toFile()
        val result = try {
            System.setProperty("user.home", testHome.absolutePath)
            val service = DesktopProxyService(
                env.runner,
                env.devices,
                mitmdumpExecutable = { "/usr/bin/mitmdump" },
                processStarter = { _, _, _ -> MockProxyProcess() },
                certificateSubjectHash = { "abcdef12" },
                certificateSpkiFingerprint = { "spki-fingerprint" },
            )
            service.ensureCertificateAuthority()
            File(testHome, ".andy/proxy/mitmproxy-ca-cert.cer").writeText("CERT")
            env.rootResult = CommandResult.success("adbd is already running as root")

            service.installSystemCertificateAuthority("emulator-5554")
        } finally {
            System.setProperty("user.home", originalHome)
        }

        assertTrue(result.isSuccess)
        assertTrue(result.stdout.contains("persistently"))
        assertTrue(env.ran("remount"))
        assertTrue(env.ran("push", File(testHome, ".andy/proxy/abcdef12.0").absolutePath, "/system/etc/andy/cacerts/abcdef12.0"))
        assertTrue(env.ran("push", File(testHome, ".andy/proxy/abcdef12.0").absolutePath, "/system/etc/security/cacerts/abcdef12.0"))
        assertTrue(env.ran("push", File(testHome, ".andy/proxy/andy-persistent-ca-injector.sh").absolutePath, "/system/etc/andy/andy-ca-injector.sh"))
        assertTrue(env.ran("push", File(testHome, ".andy/proxy/andy-ca.rc").absolutePath, "/system/etc/init/andy-ca.rc"))
        assertTrue(env.ran("shell", "sh", "/data/local/tmp/andy-inject-ca.sh"))
        assertTrue(env.ran("shell", "sh", "/data/local/tmp/andy-chrome-proxy-flags.sh"))
    }

    @Test
    fun systemCaInstallFallsBackToRuntimeInjectionWhenPersistentInstallIsLocked() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-proxy-test-home").toFile()
        val result = try {
            System.setProperty("user.home", testHome.absolutePath)
            val service = DesktopProxyService(
                env.runner,
                env.devices,
                mitmdumpExecutable = { "/usr/bin/mitmdump" },
                processStarter = { _, _, _ -> MockProxyProcess() },
                certificateSubjectHash = { "abcdef12" },
                certificateSpkiFingerprint = { "spki-fingerprint" },
            )
            service.ensureCertificateAuthority()
            File(testHome, ".andy/proxy/mitmproxy-ca-cert.cer").writeText("CERT")
            env.rootResult = CommandResult.success("adbd is already running as root")
            env.remountResult = CommandResult.failure("Device must be bootloader unlocked")

            service.installSystemCertificateAuthority("emulator-5554")
        } finally {
            System.setProperty("user.home", originalHome)
        }

        assertTrue(result.isSuccess)
        assertTrue(result.stdout.contains("runtime trust store"))
        assertTrue(env.ran("remount"))
        assertTrue(env.ran("push", File(testHome, ".andy/proxy/abcdef12.0").absolutePath, "/data/local/tmp/abcdef12.0"))
        assertTrue(env.ran("shell", "sh", "/data/local/tmp/andy-inject-ca.sh"))
    }

    @Test
    fun systemCaInstallExplainsLockedBootloaderWhenBothInstallPathsFail() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-proxy-test-home").toFile()
        val result = try {
            System.setProperty("user.home", testHome.absolutePath)
            val service = DesktopProxyService(
                env.runner,
                env.devices,
                mitmdumpExecutable = { "/usr/bin/mitmdump" },
                processStarter = { _, _, _ -> MockProxyProcess() },
                certificateSubjectHash = { "abcdef12" },
                certificateSpkiFingerprint = { "spki-fingerprint" },
            )
            service.ensureCertificateAuthority()
            File(testHome, ".andy/proxy/mitmproxy-ca-cert.cer").writeText("CERT")
            env.rootResult = CommandResult.success("adbd is already running as root")
            env.runtimeCaInjectionResult = CommandResult.failure("nsenter failed")
            env.remountResult = CommandResult.failure("Device must be bootloader unlocked")

            service.installSystemCertificateAuthority("emulator-5554")
        } finally {
            System.setProperty("user.home", originalHome)
        }

        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("Could not install Andy CA"))
        assertTrue(result.stderr.contains("Runtime CA injection failed"))
        assertTrue(result.stderr.contains("bootloader is locked"))
        assertTrue(result.stderr.contains("fastboot flashing unlock"))
    }
}
