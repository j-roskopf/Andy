package app.andy.desktop.service

import app.andy.desktop.service.proxy.DesktopProxyService
import app.andy.desktop.service.proxy.ProxyRuleJson
import app.andy.desktop.service.proxy.parseMitmproxyFlowLine
import app.andy.model.ProxyRule
import app.andy.model.ProxyStartOptions
import app.andy.model.ProxyWarningKind
import app.andy.model.WorkspaceState
import app.andy.model.matches
import app.andy.service.CommandResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
                    proxyStartOnLaunch = true,
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
                    pairedWifiDevices = listOf(
                        app.andy.model.PairedWifiDevice(
                            id = "wifi-1",
                            displayName = "adb-VAN10A203710441",
                            mdnsInstanceName = "adb-VAN10A203710441",
                            lastEndpoint = "192.168.86.47:5555",
                            pairedAtMillis = 1_720_000_000_000L,
                        ),
                    ),
                ),
            )

            val loaded = store.load()
            val loadedRule = loaded.proxyRules.single()

            assertTrue(loaded.proxyStartOnLaunch)
            assertEquals(404, loadedRule.statusCode)
            assertEquals(mapOf("x-andy" to "missing", "content-type" to "application/json"), loadedRule.setHeaders)
            assertEquals(listOf("Server", "etag"), loadedRule.removeHeaders)
            val loadedWifi = loaded.pairedWifiDevices.single()
            assertEquals("wifi-1", loadedWifi.id)
            assertEquals("adb-VAN10A203710441", loadedWifi.mdnsInstanceName)
            assertEquals("192.168.86.47:5555", loadedWifi.lastEndpoint)
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
    fun startChainsMitmproxyThroughDetectedMacProxy() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        env.macProxyOutput = """
            <dictionary> {
              HTTPEnable : 1
              HTTPProxy : proxy.example.test
              HTTPPort : 8080
              ExceptionsList : <array> {
                0 : localhost
                1 : 127.0.0.1
                2 : 10.0.2.2
              }
            }
        """.trimIndent()
        val services = env.services()

        val result = services.proxy.start(8888, emptyList())

        assertTrue(result.isSuccess)
        val command = env.proxyCommands.single()
        assertTrue(command.windowed(2).any { it == listOf("--mode", "upstream:http://proxy.example.test:8080") })
        assertTrue(result.stdout.contains("via Mac proxy http://proxy.example.test:8080"))
    }

    @Test
    fun startAppliesCorporateUpstreamTrustOptions() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-proxy-corp-ca").toFile()
        try {
            System.setProperty("user.home", testHome.absolutePath)
            val ca = File(testHome, "corp-root.pem").apply { writeText("CORP-CA") }
            val service = DesktopProxyService(
                env.runner,
                env.devices,
                mitmdumpExecutable = { "/usr/bin/mitmdump" },
                processStarter = { command, _, _ ->
                    env.proxyCommands += command
                    MockProxyProcess()
                },
                hostOsName = { env.hostOsName },
            )

            val result = service.start(
                8888,
                emptyList(),
                ProxyStartOptions(sslInsecure = true, upstreamTrustedCaPath = ca.absolutePath),
            )

            assertTrue(result.isSuccess)
            val command = env.proxyCommands.single()
            assertTrue(command.contains("--ssl-insecure"))
            assertTrue(command.windowed(2).any { it == listOf("--set", "ssl_verify_upstream_trusted_ca=${ca.absolutePath}") })
            assertTrue(result.stdout.contains("insecure upstream"))
            assertTrue(result.stdout.contains("corp CA"))
        } finally {
            System.setProperty("user.home", originalHome)
            testHome.deleteRecursively()
        }
    }

    @Test
    fun liveStderrTlsWarningsArePublishedWhileProcessIsAlive() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val service = DesktopProxyService(
            env.runner,
            env.devices,
            mitmdumpExecutable = { "/usr/bin/mitmdump" },
            processStarter = { _, _, _ ->
                MockProxyProcess(
                    stdoutText = "",
                    stderrText = "Client TLS handshake failed. The client does not trust the proxy's certificate.\n",
                )
            },
            hostOsName = { env.hostOsName },
        )

        assertTrue(service.start(8888, emptyList()).isSuccess)
        val warnings = withTimeout(2_000) {
            service.warnings.first { it.isNotEmpty() }
        }
        assertTrue(warnings.any { it.kind == ProxyWarningKind.ClientTlsFailure })
        service.stop()
        Unit
    }

    @Test
    fun tlsFailedAddonEventsAppearInExchanges() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val service = DesktopProxyService(
            env.runner,
            env.devices,
            mitmdumpExecutable = { "/usr/bin/mitmdump" },
            processStarter = { _, _, _ ->
                MockProxyProcess(
                    stdoutText = """
                        {"type":"client_connected","id":"c1","startedAtMillis":1,"peer":"10.0.2.15:1"}
                        {"type":"tls_failed","id":"tls-failed-1","startedAtMillis":2,"completedAtMillis":2,"durationMillis":0,"method":"TLS","url":"https://pinned.example/","statusCode":null,"contentType":null,"sizeBytes":null,"requestHeaders":{},"responseHeaders":{},"requestBodyPreview":null,"responseBodyPreview":null,"error":"Client rejected Andy's CA for pinned.example: unknown ca","tlsStatus":"tls","matchedRuleId":null,"sni":"pinned.example","peer":"10.0.2.15:1","reason":"unknown ca"}
                    """.trimIndent(),
                )
            },
            hostOsName = { env.hostOsName },
        )

        assertTrue(service.start(8888, emptyList()).isSuccess)
        val exchanges = withTimeout(2_000) {
            service.exchanges.first { it.any { exchange -> exchange.method == "TLS" } }
        }
        assertEquals(1, service.clientConnectionCount.value)
        assertEquals("https://pinned.example/", exchanges.single().url)
        assertTrue(exchanges.single().error!!.contains("rejected Andy's CA"))
        service.stop()
        Unit
    }

    @Test
    fun routeDiagnosticsFlagsDetectedMacCorporateProxy() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()
        env.macProxyOutput = """
            <dictionary> {
              HTTPEnable : 1
              HTTPProxy : proxy.example.test
              HTTPPort : 8080
              ExceptionsList : <array> {
                0 : localhost
                1 : 127.0.0.1
                2 : 10.0.2.2
              }
            }
        """.trimIndent()

        val diagnostics = services.proxy.diagnoseDeviceProxyRoute("emulator-5554", "10.0.2.2", 8888)

        assertTrue(diagnostics.hostProxyActive)
        assertEquals("http://proxy.example.test:8080", diagnostics.hostUpstreamProxy)
        assertTrue(diagnostics.issues.any { it.contains("Mac system proxy detected") })
    }

    @Test
    fun deviceProxyHostUsesEmulatorLoopbackAliasOnlyForEmulators() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        assertEquals("10.0.2.2", services.proxy.resolveDeviceProxyHost("emulator-5554"))
        assertTrue(services.proxy.resolveDeviceProxyHost("R3CXB056ZZB").isNotBlank())
    }

    @Test
    fun routeDiagnosticsDetectsActiveVpnAndProxyMismatch() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()
        env.httpProxyValue = ":0"
        env.connectivityDump = """
            NetworkAgentInfo{ ni{[type: VPN[], state: CONNECTED/CONNECTED, reason: connected]}
              mOwnerName=com.example.vpn
              NetworkCapabilities: TRANSPORT_VPN
            }
        """.trimIndent()
        env.routeToProxyOutput = "10.0.2.2 dev tun0 src 10.8.0.2"

        val diagnostics = services.proxy.diagnoseDeviceProxyRoute("emulator-5554", "10.0.2.2", 8888)

        assertFalse(diagnostics.proxyConfigured)
        assertTrue(diagnostics.vpnActive)
        assertEquals("com.example.vpn", diagnostics.vpnName)
        assertTrue(diagnostics.routeUsesVpn)
        assertTrue(diagnostics.issues.any { it.contains("VPN is active") })
        assertTrue(diagnostics.issues.any { it.contains("expected 10.0.2.2:8888") })
    }

    @Test
    fun routeDiagnosticsReportsMacProxyBypassRisk() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()
        env.macProxyOutput = """
            <dictionary> {
              HTTPEnable : 1
              HTTPProxy : proxy.example.test
              HTTPPort : 8080
              ExceptionsList : <array> {
                0 : localhost
              }
            }
        """.trimIndent()

        val diagnostics = services.proxy.diagnoseDeviceProxyRoute("emulator-5554", "10.0.2.2", 8888)

        assertTrue(diagnostics.hostProxyActive)
        assertEquals("http://proxy.example.test:8080", diagnostics.hostUpstreamProxy)
        assertFalse(diagnostics.hostProxyBypassLooksSafe)
        assertTrue(diagnostics.issues.any { it.contains("bypass rules") })
    }

    @Test
    fun routeDiagnosticsPassesWhenProxyIsConfiguredAndNoVpnIsActive() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        val diagnostics = services.proxy.diagnoseDeviceProxyRoute("emulator-5554", "10.0.2.2", 8888)

        assertTrue(diagnostics.proxyConfigured)
        assertFalse(diagnostics.vpnActive)
        assertFalse(diagnostics.routeUsesVpn)
        assertTrue(diagnostics.issues.isEmpty())
    }

    @Test
    fun routeDiagnosticsDescribesLocalDebugProxyWithoutCorporateLabel() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()
        env.macProxyOutput = """
            <dictionary> {
              HTTPEnable : 1
              HTTPProxy : 127.0.0.1
              HTTPPort : 9090
              HTTPSEnable : 1
              HTTPSProxy : 127.0.0.1
              HTTPSPort : 9090
              ExceptionsList : <array> {
                0 : dns.google
              }
            }
        """.trimIndent()

        val diagnostics = services.proxy.diagnoseDeviceProxyRoute("emulator-5554", "10.0.2.2", 8888)

        assertTrue(diagnostics.hostProxyActive)
        assertEquals("http://127.0.0.1:9090", diagnostics.hostUpstreamProxy)
        assertTrue(diagnostics.issues.any { it.contains("local debug proxy") })
        assertTrue(diagnostics.issues.none { it.contains("corporate", ignoreCase = true) })
    }

    @Test
    fun openVpnSettingsStartsAndroidVpnSettings() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        val result = services.proxy.openVpnSettings("emulator-5554")

        assertTrue(result.isSuccess)
        assertTrue(env.ran("shell", "am", "start", "-a", "android.settings.VPN_SETTINGS"))
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
    fun prepareUserCertificateInstallCopiesCaAndOpensSecuritySettings() = runBlocking {
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

            service.prepareUserCertificateInstall("R3CXB056ZZB")
        } finally {
            System.setProperty("user.home", originalHome)
        }

        assertTrue(result.isSuccess)
        assertTrue(result.stdout.contains("/sdcard/Download/andy-mitmproxy-ca-cert.cer"))
        assertTrue(env.ran("push", File(testHome, ".andy/proxy/mitmproxy-ca-cert.cer").absolutePath, "/sdcard/Download/andy-mitmproxy-ca-cert.cer"))
        assertTrue(env.ran("shell", "am", "start", "-a", "android.settings.SECURITY_SETTINGS"))
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
