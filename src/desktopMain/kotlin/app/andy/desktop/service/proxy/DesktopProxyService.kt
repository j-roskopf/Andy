package app.andy.desktop.service.proxy

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.model.DeviceKind
import app.andy.model.NetworkExchange
import app.andy.model.NetworkRouteDiagnostics
import app.andy.model.ProxyRule
import app.andy.model.ProxyStartOptions
import app.andy.model.ProxyWarning
import app.andy.model.ProxyWarningKind
import app.andy.model.isClientTlsRejectionError
import app.andy.model.isUpstreamTlsVerificationError
import app.andy.model.isWirelessAdbSerial
import app.andy.service.CommandResult
import app.andy.service.ProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DesktopProxyService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
    private val mitmdumpExecutable: () -> String? = { findMitmdumpExecutable() },
    private val processStarter: (List<String>, File, Map<String, String>) -> ProxyProcess = { command, directory, environment ->
        RealProxyProcess(command, directory, environment)
    },
    private val certificateSubjectHash: suspend (File) -> String? = { certificate -> defaultCertificateSubjectHash(runner, certificate) },
    private val certificateSpkiFingerprint: suspend (File) -> String? = { certificate -> defaultCertificateSpkiFingerprint(runner, certificate) },
    private val hostOsName: () -> String = { System.getProperty("os.name") },
) : ProxyService {
    override val exchanges = MutableStateFlow<List<NetworkExchange>>(emptyList())
    override val status = MutableStateFlow("Proxy stopped")
    override val warnings = MutableStateFlow<List<ProxyWarning>>(emptyList())
    override val clientConnectionCount = MutableStateFlow(0)
    private val proxyDir = File(System.getProperty("user.home"), ".andy/proxy")
    private val rulesFile = File(proxyDir, "rules.json")
    private val addonFile = File(proxyDir, "andy_mitm_addon.py")
    private var process: ProxyProcess? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    override suspend fun detectMitmproxy(): CommandResult = withContext(Dispatchers.IO) {
        val executable = mitmdumpExecutable()
        if (executable == null) {
            CommandResult.failure("mitmdump not found. Install mitmproxy with `brew install mitmproxy`.")
        } else {
            CommandResult.success(executable)
        }
    }

    override suspend fun ensureCertificateAuthority(): CommandResult = withContext(Dispatchers.IO) {
        proxyDir.mkdirs()
        writeAddon()
        CommandResult.success("mitmproxy CA will be generated at ${certificateAuthorityPath()}")
    }

    override suspend fun certificateAuthorityPath(): String = withContext(Dispatchers.IO) {
        File(proxyDir, "mitmproxy-ca-cert.cer").absolutePath
    }

    override suspend fun start(port: Int, rules: List<ProxyRule>, options: ProxyStartOptions): CommandResult = withContext(Dispatchers.IO) {
        val executable = mitmdumpExecutable() ?: return@withContext CommandResult.failure("mitmdump not found. Install mitmproxy with `brew install mitmproxy`.")
        stopCurrentProcess(updateStatus = false)
        killOrphanedProxies()
        proxyDir.mkdirs()
        writeAddon()
        writeRules(rules)
        warnings.value = emptyList()
        clientConnectionCount.value = 0
        val hostProxy = detectHostProxyState()
        val trustedCa = options.upstreamTrustedCaPath?.trim()?.takeIf { it.isNotBlank() }?.let(::File)
        if (trustedCa != null && !trustedCa.isFile) {
            return@withContext CommandResult.failure("Corporate root CA not found at ${trustedCa.absolutePath}")
        }
        val command = buildList {
            add(executable)
            hostProxy.upstreamProxy?.let { upstream ->
                add("--mode")
                add("upstream:$upstream")
            }
            addAll(
                listOf(
                    "--listen-host", "0.0.0.0",
                    "--listen-port", port.toString(),
                    "--set", "confdir=${proxyDir.absolutePath}",
                    "-s", addonFile.absolutePath,
                    "--set", "termlog_verbosity=warn",
                ),
            )
            if (options.sslInsecure) {
                add("--ssl-insecure")
            }
            if (trustedCa != null) {
                add("--set")
                add("ssl_verify_upstream_trusted_ca=${trustedCa.absolutePath}")
            }
        }
        runCatching {
            process = processStarter(command, proxyDir, mapOf("ANDY_RULES_PATH" to rulesFile.absolutePath))
            status.value = buildString {
                append("mitmdump listening on 0.0.0.0:$port")
                hostProxy.upstreamProxy?.let { append(" via Mac proxy $it") }
                if (options.sslInsecure) append(" (insecure upstream)")
                if (trustedCa != null) append(" (corp CA ${trustedCa.name})")
            }
            pumpProcess(process!!)
            CommandResult.success(status.value)
        }.getOrElse { error ->
            status.value = "Proxy failed: ${error.message ?: error::class.simpleName}"
            CommandResult.failure(status.value)
        }
    }

    override suspend fun updateRules(rules: List<ProxyRule>): CommandResult = withContext(Dispatchers.IO) {
        proxyDir.mkdirs()
        writeRules(rules)
        CommandResult.success("Updated ${rules.size} proxy rules")
    }

    override suspend fun clearTraffic(): CommandResult = withContext(Dispatchers.IO) {
        exchanges.value = emptyList()
        warnings.value = emptyList()
        clientConnectionCount.value = 0
        CommandResult.success("Cleared network traffic")
    }

    override suspend fun stop(): CommandResult = withContext(Dispatchers.IO) {
        stopCurrentProcess(updateStatus = true)
        CommandResult.success("Proxy stopped")
    }

    private fun stopCurrentProcess(updateStatus: Boolean) {
        stdoutJob?.cancel()
        stderrJob?.cancel()
        stdoutJob = null
        stderrJob = null
        process?.destroy()
        process = null
        if (updateStatus) status.value = "Proxy stopped"
    }

    override suspend fun resolveDeviceProxyHost(serial: String): String {
        val online = devices.listDevices().firstOrNull { it.serial == serial }
        return if (online?.kind == DeviceKind.Emulator || serial.startsWith("emulator-")) "10.0.2.2" else resolveLanIp()
    }

    override suspend fun configureDeviceProxy(serial: String, host: String, port: Int): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        activatePersistedCertificateAuthority(adb, serial)
        val commands = listOf(
            listOf("settings", "put", "global", "http_proxy", "$host:$port"),
            listOf("settings", "put", "global", "global_http_proxy_host", host),
            listOf("settings", "put", "global", "global_http_proxy_port", port.toString()),
            listOf("settings", "delete", "global", "global_http_proxy_exclusion_list"),
            listOf("settings", "delete", "global", "global_proxy_pac_url"),
        )
        val proxyConfigured = runAdbShellSequence(adb, serial, commands, "Device proxy configured at $host:$port")
        if (!proxyConfigured.isSuccess) return proxyConfigured
        val restart = restartDeviceInternet(adb, serial)
        return CommandResult.success(
            listOf(proxyConfigured.stdout, restart.stdout.ifBlank { restart.stderr })
                .filter { it.isNotBlank() }
                .joinToString(". "),
        )
    }

    override suspend fun clearDeviceProxy(serial: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        val commands = listOf(
            listOf("settings", "put", "global", "http_proxy", ":0"),
            listOf("settings", "delete", "global", "global_http_proxy_host"),
            listOf("settings", "delete", "global", "global_http_proxy_port"),
            listOf("settings", "delete", "global", "global_http_proxy_exclusion_list"),
            listOf("settings", "delete", "global", "global_proxy_pac_url"),
        )
        val proxyCleared = runAdbShellSequence(adb, serial, commands, "Device proxy cleared")
        if (!proxyCleared.isSuccess) return proxyCleared
        val restart = restartDeviceInternet(adb, serial)
        return CommandResult.success(
            listOf(proxyCleared.stdout, restart.stdout.ifBlank { restart.stderr })
                .filter { it.isNotBlank() }
                .joinToString(". "),
        )
    }

    override suspend fun diagnoseDeviceProxyRoute(serial: String, host: String, port: Int): NetworkRouteDiagnostics = withContext(Dispatchers.IO) {
        val adb = devices.adbPath()
            ?: return@withContext NetworkRouteDiagnostics(
                expectedProxy = "$host:$port",
                configuredProxy = null,
                proxyConfigured = false,
                vpnActive = false,
                issues = listOf("ADB not found"),
            )
        val expectedProxy = "$host:$port"
        val configuredProxy = readConfiguredProxy(adb, serial)
        val proxyConfigured = configuredProxy == expectedProxy
        val connectivity = runner.run(listOf(adb, "-s", serial, "shell", "dumpsys", "connectivity"), 10)
        val vpn = parseVpnRouteState(connectivity.stdout)
        val route = runner.run(listOf(adb, "-s", serial, "shell", "ip", "route", "get", host), 10)
        val routeSummary = route.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val routeUsesVpn = routeSummary?.contains(Regex("""\b(tun|ppp|wg|vpn)\w*""", RegexOption.IGNORE_CASE)) == true
        val hostProxy = detectHostProxyState()
        val hostVpn = detectHostVpnState()
        val issues = buildList {
            if (!proxyConfigured) add("Android global proxy is ${configuredProxy ?: "not set"}; expected $expectedProxy.")
            if (vpn.active) add("A VPN is active${vpn.name?.let { " ($it)" }.orEmpty()}; it can bypass Android's global HTTP proxy or route Andy's proxy endpoint into the tunnel.")
            if (routeUsesVpn) add("The route to Andy's proxy host appears to use a VPN interface: $routeSummary")
            if (hostProxy.active && hostProxy.upstreamProxy != null) {
                if (isLocalHostProxy(hostProxy.upstreamProxy)) {
                    add(
                        "Mac system proxy points at a local debug proxy (${hostProxy.upstreamProxy}) — often Proxyman/Charles. " +
                            "Andy chains through it; if emulator traffic disappears, add localhost/127.0.0.1/10.0.2.2 to that tool's bypass list or quit it.",
                    )
                } else {
                    add(
                        "Mac system proxy detected (${hostProxy.upstreamProxy}). Andy chains mitmproxy through it; " +
                            "if upstream TLS verification fails, add the corporate root CA or enable insecure-upstream.",
                    )
                }
            }
            if (hostProxy.active && !hostProxy.bypassLooksSafe) add("Mac proxy bypass rules do not clearly include localhost/127.0.0.1/10.0.2.2; add them if emulator traffic still disappears.")
            if (hostVpn.active) add("Mac VPN-like interfaces are active (${hostVpn.summary}); emulator traffic may be routed by the host tunnel.")
        }
        NetworkRouteDiagnostics(
            expectedProxy = expectedProxy,
            configuredProxy = configuredProxy,
            proxyConfigured = proxyConfigured,
            vpnActive = vpn.active,
            vpnName = vpn.name,
            routeUsesVpn = routeUsesVpn,
            routeSummary = routeSummary,
            hostProxyActive = hostProxy.active,
            hostProxySummary = hostProxy.summary,
            hostUpstreamProxy = hostProxy.upstreamProxy,
            hostProxyBypassLooksSafe = hostProxy.bypassLooksSafe,
            hostVpnActive = hostVpn.active,
            hostVpnSummary = hostVpn.summary,
            issues = issues,
        )
    }

    override suspend fun openVpnSettings(serial: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell", "am", "start", "-a", "android.settings.VPN_SETTINGS"), 10)
    }

    override suspend fun prepareUserCertificateInstall(serial: String): CommandResult = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        val certificate = usableCertificateAuthority()
            ?: return@withContext CommandResult.failure(
                "Could not find a valid mitmproxy CA. Start the proxy once so mitmproxy can generate ${File(proxyDir, "mitmproxy-ca-cert.cer").absolutePath}.",
            )
        val remotePath = "/sdcard/Download/andy-mitmproxy-ca-cert.cer"
        val push = runner.run(listOf(adb, "-s", serial, "push", certificate.absolutePath, remotePath), 60)
        if (!push.isSuccess) return@withContext push

        val settings = runner.run(listOf(adb, "-s", serial, "shell", "am", "start", "-a", "android.settings.SECURITY_SETTINGS"), 10)
        val settingsNote = if (settings.isSuccess) {
            "Opened Security settings."
        } else {
            "Could not open Security settings automatically. ${settings.combinedOutput()}".trim()
        }
        CommandResult.success(
            "Copied Andy CA to $remotePath. $settingsNote On the device, install it from Settings > Security > Encryption & credentials > Install a certificate > CA certificate.",
        )
    }

    override suspend fun installSystemCertificateAuthority(serial: String): CommandResult = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        val certificate = usableCertificateAuthority()
        if (certificate == null) {
            return@withContext CommandResult.failure(
                "Could not find a valid mitmproxy CA. Start the proxy once so mitmproxy can generate ${File(proxyDir, "mitmproxy-ca-cert.cer").absolutePath}.",
            )
        }

        val root = runner.run(listOf(adb, "-s", serial, "root"), 30)
        val rootOutput = "${root.stdout}\n${root.stderr}".trim()
        if (!root.isSuccess || rootOutput.contains("cannot run as root", ignoreCase = true)) {
            return@withContext CommandResult.failure(
                "ADB root is not available for this device. Physical devices can only use Android's manual user-credential install unless they are rooted. For emulator system trust, use a non-Google-Play emulator image. $rootOutput".trim(),
            )
        }
        runner.run(listOf(adb, "-s", serial, "wait-for-device"), 60)

        val hash = certificateSubjectHash(certificate)
            ?: return@withContext CommandResult.failure("Could not compute Android CA subject hash for ${certificate.absolutePath}. Install OpenSSL or verify the mitmproxy CA file.")
        val androidCert = File(proxyDir, "$hash.0")
        certificate.copyTo(androidCert, overwrite = true)

        val persistentInstall = installPersistentCertificateAuthority(adb, serial, androidCert, certificate)
        if (persistentInstall.isSuccess) return@withContext persistentInstall

        val runtimeInjection = installRuntimeCertificateAuthority(adb, serial, androidCert, certificate, persistent = false)
        if (runtimeInjection.isSuccess) return@withContext runtimeInjection

        CommandResult.failure(
            "Could not install Andy CA persistently or inject it into the runtime trust store. " +
                "${persistentInstall.combinedOutput()} ${runtimeInjection.combinedOutput()}".trim(),
        )
    }

    override suspend fun activatePersistedCertificateAuthority(serial: String): CommandResult = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        activatePersistedCertificateAuthority(adb, serial)
    }

    override suspend fun isCertificateInstalled(serial: String): Boolean = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext false
        val certificate = usableCertificateAuthority() ?: return@withContext false
        val hash = certificateSubjectHash(certificate) ?: return@withContext false
        val result = runner.run(
            listOf(adb, "-s", serial, "shell", "test -f /system/etc/security/cacerts/$hash.0 || test -f /apex/com.android.conscrypt/cacerts/$hash.0"),
            10
        )
        result.isSuccess
    }

    override suspend fun isDeviceProxyConfigured(serial: String, host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext false
        readConfiguredProxy(adb, serial) == "$host:$port"
    }

    private suspend fun readConfiguredProxy(adb: String, serial: String): String? {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "settings", "get", "global", "http_proxy"), 10)
        if (!result.isSuccess) return null
        return result.stdout.trim().takeIf { it.isNotBlank() && it != "null" && it != ":0" }
    }

    private suspend fun detectHostProxyState(): HostProxyState {
        if (!isMacHost()) return HostProxyState(active = false)
        val result = runner.run(listOf("/usr/sbin/scutil", "--proxy"), 5)
        if (!result.isSuccess) return HostProxyState(active = false)
        return parseMacProxyState(result.stdout)
    }

    private suspend fun detectHostVpnState(): HostVpnState {
        if (!isMacHost()) return HostVpnState(active = false)
        val result = runner.run(listOf("/sbin/ifconfig"), 5)
        if (!result.isSuccess) return HostVpnState(active = false)
        val activeInterfaces = Regex("""^([a-z]+[0-9]+): flags=.*\bUP\b""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .findAll(result.stdout)
            .mapNotNull { match ->
                val name = match.groupValues.getOrNull(1).orEmpty()
                name.takeIf { it.startsWith("utun") || it.startsWith("ppp") || it.startsWith("ipsec") || it.startsWith("wg") }
            }
            .toList()
        return HostVpnState(
            active = activeInterfaces.isNotEmpty(),
            summary = activeInterfaces.distinct().joinToString(", ").takeIf { it.isNotBlank() },
        )
    }

    private fun isMacHost(): Boolean = hostOsName().contains("Mac", ignoreCase = true)

    private suspend fun installPersistentCertificateAuthority(adb: String, serial: String, androidCert: File, originalCertificate: File): CommandResult {
        val remount = remountWritableSystem(adb, serial)
        if (!remount.isSuccess) return remount

        val persistentDir = "/system/etc/andy/cacerts"
        val persistentCert = "$persistentDir/${androidCert.name}"
        val persistentScript = "/system/etc/andy/andy-ca-injector.sh"
        val initScript = "/system/etc/init/andy-ca.rc"
        val systemCert = "/system/etc/security/cacerts/${androidCert.name}"
        val localPersistentScript = File(proxyDir, "andy-persistent-ca-injector.sh")
        val localInitScript = File(proxyDir, "andy-ca.rc")

        localPersistentScript.writeText(androidCaInjectionScript(persistentCert))
        localInitScript.writeText(androidCaInitScript(persistentScript))

        val setupDir = runner.run(listOf(adb, "-s", serial, "shell", "mkdir", "-p", persistentDir), 30)
        if (!setupDir.isSuccess) return setupDir
        val pushPersistentCert = runner.run(listOf(adb, "-s", serial, "push", androidCert.absolutePath, persistentCert), 60)
        if (!pushPersistentCert.isSuccess) return pushPersistentCert
        val pushSystemCert = runner.run(listOf(adb, "-s", serial, "push", androidCert.absolutePath, systemCert), 60)
        if (!pushSystemCert.isSuccess) return pushSystemCert
        val pushScript = runner.run(listOf(adb, "-s", serial, "push", localPersistentScript.absolutePath, persistentScript), 60)
        if (!pushScript.isSuccess) return pushScript
        val pushInit = runner.run(listOf(adb, "-s", serial, "push", localInitScript.absolutePath, initScript), 60)
        if (!pushInit.isSuccess) return pushInit

        val permissions = runAdbShellSequence(
            adb,
            serial,
            listOf(
                listOf("chmod", "644", persistentCert),
                listOf("chmod", "644", systemCert),
                listOf("chmod", "755", persistentScript),
                listOf("chmod", "644", initScript),
                listOf("chown", "root:root", persistentCert, systemCert, persistentScript, initScript),
                listOf("chcon", "u:object_r:system_file:s0", persistentCert, systemCert, persistentScript, initScript),
            ),
            "Persistent CA files installed",
        )
        if (!permissions.isSuccess) return permissions

        val runtimeInjection = installRuntimeCertificateAuthority(adb, serial, androidCert, originalCertificate, persistent = true)
        if (!runtimeInjection.isSuccess) return runtimeInjection

        return CommandResult.success(
            "Installed Andy CA persistently on the writable emulator system image and activated it for this boot. " +
                "After emulator restart, Configure device will reactivate the persisted CA for Android's runtime trust namespace.",
        )
    }

    private suspend fun installRuntimeCertificateAuthority(adb: String, serial: String, androidCert: File, originalCertificate: File, persistent: Boolean): CommandResult {
        val remoteCert = if (persistent) "/system/etc/andy/cacerts/${androidCert.name}" else "/data/local/tmp/${androidCert.name}"
        val remoteInjectionScript = "/data/local/tmp/andy-inject-ca.sh"
        val remoteChromeScript = "/data/local/tmp/andy-chrome-proxy-flags.sh"
        val injectionScript = File(proxyDir, "andy-inject-ca.sh")
        val chromeScript = File(proxyDir, "andy-chrome-proxy-flags.sh")
        injectionScript.writeText(androidCaInjectionScript(remoteCert))
        val spkiFingerprint = certificateSpkiFingerprint(originalCertificate)
        chromeScript.writeText(chromeProxyFlagsScript(spkiFingerprint))

        if (!persistent) {
            val pushCert = runner.run(listOf(adb, "-s", serial, "push", androidCert.absolutePath, remoteCert), 60)
            if (!pushCert.isSuccess) return pushCert
        }
        val pushInjectionScript = runner.run(listOf(adb, "-s", serial, "push", injectionScript.absolutePath, remoteInjectionScript), 60)
        if (!pushInjectionScript.isSuccess) return pushInjectionScript
        val runInjection = runner.run(listOf(adb, "-s", serial, "shell", "sh", remoteInjectionScript), 60)
        if (!runInjection.isSuccess) return CommandResult.failure("Runtime CA injection failed. ${runInjection.combinedOutput()}")

        if (spkiFingerprint != null) {
            val pushChromeScript = runner.run(listOf(adb, "-s", serial, "push", chromeScript.absolutePath, remoteChromeScript), 60)
            if (!pushChromeScript.isSuccess) return pushChromeScript
            val runChromeScript = runner.run(listOf(adb, "-s", serial, "shell", "sh", remoteChromeScript), 30)
            if (!runChromeScript.isSuccess) return CommandResult.failure("Installed runtime CA, but Chrome/WebView proxy flags failed. ${runChromeScript.combinedOutput()}")
            runner.run(listOf(adb, "-s", serial, "shell", "am", "force-stop", "com.android.chrome"), 10)
        }

        return CommandResult.success(
            "Injected Andy CA into the emulator runtime trust store. This avoids a writable-system remount and lasts until emulator reboot. " +
                if (spkiFingerprint != null) {
                    "Chrome/WebView SPKI flags were applied; reopen Chrome and restart the debug app to pick up the CA."
                } else {
                    "Could not compute Chrome/WebView SPKI flags, so Chrome may still need app-side CA trust or a restart."
                },
        )
    }

    private suspend fun activatePersistedCertificateAuthority(adb: String, serial: String): CommandResult {
        val marker = runner.run(listOf(adb, "-s", serial, "shell", "ls", "/system/etc/andy/cacerts/*.0"), 10)
        if (!marker.isSuccess) return CommandResult.success()
        val root = runner.run(listOf(adb, "-s", serial, "root"), 30)
        if (!root.isSuccess || root.combinedOutput().contains("cannot run as root", ignoreCase = true)) return CommandResult.failure(root.combinedOutput())
        runner.run(listOf(adb, "-s", serial, "wait-for-device"), 60)
        val script = "/system/etc/andy/andy-ca-injector.sh"
        val scriptExists = runner.run(listOf(adb, "-s", serial, "shell", "test", "-f", script), 10)
        if (!scriptExists.isSuccess) return CommandResult.failure("Persisted Andy CA marker exists, but $script is missing.")
        val activation = runner.run(listOf(adb, "-s", serial, "shell", "sh", script), 60)
        return if (activation.isSuccess) CommandResult.success("Activated persisted Andy CA for this emulator boot.") else activation
    }

    private fun androidCaInjectionScript(remoteCert: String): String = """
        |#!/system/bin/sh
        |set -u
        |
        |CERT_FILE="$remoteCert"
        |TMP_COPY="/data/local/tmp/andy-ca-copy"
        |TARGET="/system/etc/security/cacerts"
        |
        |if [ -d "/apex/com.android.conscrypt/cacerts" ]; then
        |    CERT_SOURCE="/apex/com.android.conscrypt/cacerts"
        |elif [ -d "/system/etc/security/cacerts" ]; then
        |    CERT_SOURCE="/system/etc/security/cacerts"
        |elif [ -d "/system/etc/certificates" ]; then
        |    CERT_SOURCE="/system/etc/certificates"
        |elif [ -d "/etc/security/cacerts" ]; then
        |    CERT_SOURCE="/etc/security/cacerts"
        |elif [ -d "/data/misc/keychain/cacerts-added" ]; then
        |    CERT_SOURCE="/data/misc/keychain/cacerts-added"
        |elif [ -d "/system/ca-certificates/files" ]; then
        |    CERT_SOURCE="/system/ca-certificates/files"
        |else
        |    echo "Could not find Android certificate directory"
        |    exit 1
        |fi
        |
        |if [ ! -f "${'$'}CERT_FILE" ]; then
        |    echo "Missing certificate at ${'$'}CERT_FILE"
        |    exit 1
        |fi
        |
        |rm -rf "${'$'}TMP_COPY"
        |mkdir -p -m 700 "${'$'}TMP_COPY"
        |cp "${'$'}CERT_SOURCE"/* "${'$'}TMP_COPY"/ 2>/dev/null || true
        |mkdir -p "${'$'}TARGET"
        |mount -t tmpfs tmpfs "${'$'}TARGET" 2>/dev/null || true
        |cp "${'$'}TMP_COPY"/* "${'$'}TARGET"/ 2>/dev/null || true
        |cp "${'$'}CERT_FILE" "${'$'}TARGET"/
        |chown root:root "${'$'}TARGET"/* 2>/dev/null || true
        |chmod 644 "${'$'}TARGET"/* 2>/dev/null || true
        |chcon u:object_r:system_file:s0 "${'$'}TARGET"/* 2>/dev/null || true
        |
        |ZYGOTE_PIDS="$(pidof zygote zygote64 2>/dev/null || true)"
        |for Z_PID in ${'$'}ZYGOTE_PIDS; do
        |    nsenter --mount=/proc/${'$'}Z_PID/ns/mnt -- /bin/mount --bind "${'$'}TARGET" "${'$'}CERT_SOURCE" 2>/dev/null || true
        |done
        |
        |APP_PIDS="$(
        |    for Z_PID in ${'$'}ZYGOTE_PIDS; do
        |        ps -o PID -P "${'$'}Z_PID" 2>/dev/null | grep -v PID || true
        |    done
        |)"
        |for PID in ${'$'}APP_PIDS; do
        |    nsenter --mount=/proc/${'$'}PID/ns/mnt -- /bin/mount --bind "${'$'}TARGET" "${'$'}CERT_SOURCE" 2>/dev/null &
        |done
        |wait
        |
        |echo "Injected CA into ${'$'}CERT_SOURCE"
        |""".trimMargin()

    private fun androidCaInitScript(persistentScript: String): String = """
        |service andy_ca_injector /system/bin/sh $persistentScript
        |    class late_start
        |    user root
        |    group root
        |    oneshot
        |    disabled
        |
        |on property:sys.boot_completed=1
        |    start andy_ca_injector
        |""".trimMargin()

    private fun chromeProxyFlagsScript(spkiFingerprint: String?): String {
        val flags = if (spkiFingerprint == null) {
            "chrome"
        } else {
            "chrome --ignore-certificate-errors-spki-list=$spkiFingerprint"
        }
        return """
            |#!/system/bin/sh
            |set -u
            |FLAGS=${shellQuote(flags)}
            |
            |for variant in chrome android-webview webview content-shell; do
            |    for base_path in /data/local /data/local/tmp; do
            |        FLAGS_PATH="${'$'}base_path/${'$'}variant-command-line"
            |        echo "${'$'}FLAGS" > "${'$'}FLAGS_PATH"
            |        chmod 744 "${'$'}FLAGS_PATH" 2>/dev/null || true
            |        chcon "u:object_r:shell_data_file:s0" "${'$'}FLAGS_PATH" 2>/dev/null || true
            |    done
            |done
            |""".trimMargin()
    }

    private suspend fun remountWritableSystem(adb: String, serial: String): CommandResult {
        val first = runner.run(listOf(adb, "-s", serial, "remount"), 60)
        if (first.isSuccess) return first
        val firstOutput = first.combinedOutput()
        if (firstOutput.contains("bootloader", ignoreCase = true) && firstOutput.contains("unlock", ignoreCase = true)) {
            return CommandResult.failure(
                "Could not remount emulator system partition because the emulator bootloader is locked. " +
                    "Use a non-Google-Play emulator with an unlocked bootloader and writable system image. " +
                    "Andy will not auto-unlock because unlocking can wipe emulator data. A manual path is: " +
                    "`adb -s $serial reboot bootloader`, `fastboot flashing unlock`, then restart the AVD with `-writable-system` and retry. " +
                    firstOutput,
            )
        }
        if (!firstOutput.contains("verity", ignoreCase = true)) {
            return CommandResult.failure("Could not remount emulator system partition. Use a writable non-Google-Play emulator image. $firstOutput")
        }

        val disableVerity = runner.run(listOf(adb, "-s", serial, "disable-verity"), 60)
        if (!disableVerity.isSuccess) {
            return CommandResult.failure("Could not disable Android verified boot for remount. ${disableVerity.combinedOutput()}")
        }
        runner.run(listOf(adb, "-s", serial, "reboot"), 15)
        runner.run(listOf(adb, "-s", serial, "wait-for-device"), 120)
        val root = runner.run(listOf(adb, "-s", serial, "root"), 30)
        if (!root.isSuccess) {
            return CommandResult.failure("ADB root was unavailable after disabling verity. ${root.combinedOutput()}")
        }
        runner.run(listOf(adb, "-s", serial, "wait-for-device"), 60)
        val second = runner.run(listOf(adb, "-s", serial, "remount"), 60)
        return if (second.isSuccess) {
            second
        } else {
            CommandResult.failure("Could not remount emulator system partition after disabling verity. ${second.combinedOutput()}")
        }
    }

    private fun CommandResult.combinedOutput(): String =
        listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim()

    private suspend fun usableCertificateAuthority(): File? =
        listOf(
            File(proxyDir, "mitmproxy-ca-cert.cer"),
            File(proxyDir, "mitmproxy-ca-cert.pem"),
        ).firstOrNull { file ->
            file.exists() && certificateSubjectHash(file) != null
        }

    private suspend fun runAdbShellSequence(adb: String, serial: String, shellCommands: List<List<String>>, successMessage: String): CommandResult {
        shellCommands.forEach { shellCommand ->
            val result = runner.run(listOf(adb, "-s", serial, "shell") + shellCommand)
            if (!result.isSuccess) return result
        }
        return CommandResult.success(successMessage)
    }

    private suspend fun restartDeviceInternet(adb: String, serial: String): CommandResult {
        // Disabling Wi-Fi on an ADB-over-Wi-Fi session drops the transport before re-enable can run.
        if (isWirelessAdbSerial(serial)) {
            return CommandResult.success("Skipped Wi-Fi restart for wireless ADB device to avoid dropping the connection")
        }
        val wifiWasEnabled = readWifiEnabled(adb, serial) != false
        val disableWifi = runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "set-wifi-enabled", "disabled"), 10)
            .takeIf { it.isSuccess }
            ?: runner.run(listOf(adb, "-s", serial, "shell", "svc", "wifi", "disable"), 10)
        val wifiDisabled = waitForWifiEnabled(adb, serial, enabled = false, attempts = 8)

        val enableWifi = runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "set-wifi-enabled", "enabled"), 10)
            .takeIf { it.isSuccess }
            ?: runner.run(listOf(adb, "-s", serial, "shell", "svc", "wifi", "enable"), 10)
        val wifiEnabled = waitForWifiEnabled(adb, serial, enabled = true, attempts = 12)
        runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "reconnect"), 10)
        runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "start-scan"), 10)

        runner.run(listOf(adb, "-s", serial, "shell", "svc", "data", "disable"), 10)
        delay(1000)
        runner.run(listOf(adb, "-s", serial, "shell", "svc", "data", "enable"), 10)

        val wifiMessage = when {
            !disableWifi.isSuccess -> "Wi-Fi restart was requested, but Android rejected the disable command: ${disableWifi.combinedOutput()}"
            !enableWifi.isSuccess -> "Wi-Fi restart was requested, but Android rejected the enable command: ${enableWifi.combinedOutput()}"
            wifiDisabled && wifiEnabled -> "Device Wi-Fi restarted and mobile data bounced"
            !wifiWasEnabled && wifiEnabled -> "Device Wi-Fi was off; Andy enabled it and bounced mobile data"
            else -> "Device mobile data bounced, but Android did not report a Wi-Fi off/on transition"
        }
        return CommandResult.success(wifiMessage)
    }

    private suspend fun waitForWifiEnabled(adb: String, serial: String, enabled: Boolean, attempts: Int): Boolean {
        repeat(attempts) {
            if (readWifiEnabled(adb, serial) == enabled) return true
            delay(500)
        }
        return false
    }

    private suspend fun readWifiEnabled(adb: String, serial: String): Boolean? {
        val status = runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "status"), 10)
        val output = if (status.isSuccess) {
            status.stdout.lowercase()
        } else {
            val fallback = runner.run(listOf(adb, "-s", serial, "shell", "settings", "get", "global", "wifi_on"), 10)
            if (!fallback.isSuccess) return null
            fallback.stdout.trim()
        }
        return when {
            output.contains("wifi is enabled") || output.contains("wi-fi is enabled") || output == "1" -> true
            output.contains("wifi is disabled") || output.contains("wi-fi is disabled") || output == "0" -> false
            else -> null
        }
    }

    private fun pumpProcess(proxyProcess: ProxyProcess) {
        stdoutJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            proxyProcess.stdout.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    when (val event = parseMitmproxyEvent(line)) {
                        is MitmproxyEvent.Exchange -> {
                            val exchange = event.exchange
                            exchanges.update { current ->
                                val index = current.indexOfFirst { it.id == exchange.id }
                                if (index >= 0) {
                                    current.toMutableList().also { it[index] = exchange }
                                } else {
                                    (current + exchange).takeLast(MaxNetworkExchanges)
                                }
                            }
                            if (isClientTlsRejectionError(exchange.error) || exchange.method == "TLS") {
                                pushWarning(
                                    ProxyWarningKind.ClientTlsFailure,
                                    exchange.error ?: "Client TLS handshake failed",
                                    sni = exchange.url.removePrefix("https://").substringBefore('/').takeIf { it.isNotBlank() },
                                )
                            } else if (isUpstreamTlsVerificationError(exchange.error)) {
                                pushWarning(
                                    ProxyWarningKind.UpstreamTlsFailure,
                                    exchange.error ?: "Upstream TLS verification failed",
                                )
                            }
                        }
                        is MitmproxyEvent.ClientConnected -> {
                            clientConnectionCount.update { it + 1 }
                        }
                        is MitmproxyEvent.ClientDisconnected -> Unit
                        null -> Unit
                    }
                }
            }
            if (process === proxyProcess && !proxyProcess.isAlive()) status.value = "mitmdump exited"
        }
        stderrJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            proxyProcess.stderr.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    System.err.println("[Proxy stderr] $line")
                    if (process === proxyProcess && line.isNotBlank()) {
                        classifyStderrWarning(line)?.let { (kind, message) ->
                            pushWarning(kind, message)
                        }
                        if (!proxyProcess.isAlive()) status.value = line.take(220)
                    }
                }
            }
        }
    }

    private fun classifyStderrWarning(line: String): Pair<ProxyWarningKind, String>? {
        return when {
            isUpstreamTlsVerificationError(line) -> ProxyWarningKind.UpstreamTlsFailure to line.trim()
            isClientTlsRejectionError(line) ||
                line.contains("client TLS handshake failed", ignoreCase = true) ||
                line.contains("Client TLS handshake failed", ignoreCase = false) ->
                ProxyWarningKind.ClientTlsFailure to line.trim()
            line.contains("TLS", ignoreCase = true) && line.contains("fail", ignoreCase = true) ->
                ProxyWarningKind.Other to line.trim()
            else -> null
        }
    }

    private fun pushWarning(kind: ProxyWarningKind, message: String, sni: String? = null) {
        val warning = ProxyWarning(
            id = UUID.randomUUID().toString(),
            atMillis = System.currentTimeMillis(),
            kind = kind,
            message = message.take(400),
            sni = sni,
        )
        warnings.update { (it + warning).takeLast(50) }
    }

    private fun writeRules(rules: List<ProxyRule>) {
        rulesFile.writeText(ProxyRuleJson.writeRules(rules))
    }

    private suspend fun killOrphanedProxies() {
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("windows")) {
            runner.run(listOf("taskkill", "/F", "/IM", "mitmdump.exe"), 5)
        } else {
            val psResult = runner.run(listOf("ps", "ax", "-o", "pid,command"), 10)
            if (psResult.isSuccess) {
                psResult.stdout.lineSequence()
                    .map { it.trim() }
                    .filter { it.contains("mitmdump") && it.contains("andy_mitm_addon.py") }
                    .forEach { line ->
                        val pid = line.substringBefore(' ').trim().toIntOrNull()
                        if (pid != null) {
                            runner.run(listOf("kill", "-9", pid.toString()), 5)
                        }
                    }
            }
        }
    }

    private fun writeAddon() {
        addonFile.writeBytes(MitmAddon.getAddonSource())
    }
}

