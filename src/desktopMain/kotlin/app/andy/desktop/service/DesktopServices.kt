package app.andy.desktop.service

import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.desktop.updates.DesktopAppUpdateService
import app.andy.model.*
import app.andy.service.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

fun createDesktopServices(): AndyServices {
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
    val devices = DesktopDeviceService(runner, locator, store)
    val mirror = DesktopMirrorEngine(runner, devices)
    val logcat = DesktopLogcatService(runner, devices)
    val updatesScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updates = DesktopAppUpdateService(updatesScope)
    val actionConfig = DesktopActionConfigStore()
    val actionRuns = DesktopActionRunService(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    val avd = DesktopAvdService(runner, locator) { store.load().selectedSdkPath }
    val intents = DesktopIntentService(runner, devices)
    val apps = DesktopAppService(runner, devices)
    val files = DesktopFileService(runner, devices)
    val hostFiles = DesktopHostFileService(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val proxy = DesktopProxyService(runner, devices)
    val accessibility = DesktopAccessibilityService(runner, devices)

    val mcp = DesktopMcpServerService(
        devices = devices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        proxy = proxy,
        accessibility = accessibility,
        workspaceStore = store
    )

    return AndyServices(
        devices = devices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        hostFiles = hostFiles,
        proxy = proxy,
        metrics = DesktopMetricsService(runner, devices),
        accessibility = accessibility,
        bugs = DesktopBugService(mirror, logcat, devices = devices, accessibility = accessibility),
        artifacts = DesktopArtifactService(runner, devices, mirror),
        workspaceStore = store,
        updates = updates,
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
    )
}

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

    override suspend fun start(port: Int, rules: List<ProxyRule>): CommandResult = withContext(Dispatchers.IO) {
        val executable = mitmdumpExecutable() ?: return@withContext CommandResult.failure("mitmdump not found. Install mitmproxy with `brew install mitmproxy`.")
        stopCurrentProcess(updateStatus = false)
        killOrphanedProxies()
        proxyDir.mkdirs()
        writeAddon()
        writeRules(rules)
        val hostProxy = detectHostProxyState()
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
        }
        runCatching {
            process = processStarter(command, proxyDir, mapOf("ANDY_RULES_PATH" to rulesFile.absolutePath))
            status.value = "mitmdump listening on 0.0.0.0:$port" + hostProxy.upstreamProxy?.let { " via Mac proxy $it" }.orEmpty()
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
                    parseMitmproxyFlowLine(line)?.let { exchange ->
                        val current = exchanges.value
                        val index = current.indexOfFirst { it.id == exchange.id }
                        if (index >= 0) {
                            val mutable = current.toMutableList()
                            mutable[index] = exchange
                            exchanges.value = mutable
                        } else {
                            exchanges.value = (current + exchange).takeLast(MaxNetworkExchanges)
                        }
                    }
                }
            }
            if (process === proxyProcess && !proxyProcess.isAlive()) status.value = "mitmdump exited"
        }
        stderrJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            proxyProcess.stderr.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    System.err.println("[Proxy stderr] $line")
                    if (process === proxyProcess && line.isNotBlank() && !proxyProcess.isAlive()) status.value = line.take(220)
                }
            }
        }
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
        val bundled = javaClass.classLoader.getResourceAsStream("proxy/andy_mitm_addon.py")
        if (bundled != null) {
            bundled.use { input -> addonFile.outputStream().use { output -> input.copyTo(output) } }
        } else if (!addonFile.exists()) {
            addonFile.writeText(AndyMitmAddonSource)
        }
    }
}

interface ProxyProcess {
    val stdout: InputStream
    val stderr: InputStream
    fun isAlive(): Boolean
    fun destroy()
}

class RealProxyProcess(command: List<String>, directory: File, environment: Map<String, String>) : ProxyProcess {
    private val delegate = ProcessBuilder(command)
        .directory(directory)
        .redirectErrorStream(false)
        .also { builder -> builder.environment().putAll(environment) }
        .start()

    private val shutdownHook = Thread {
        try {
            if (delegate.isAlive) {
                delegate.destroy()
                if (!delegate.waitFor(500, TimeUnit.MILLISECONDS)) {
                    delegate.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override val stdout: InputStream get() = delegate.inputStream
    override val stderr: InputStream get() = delegate.errorStream
    override fun isAlive(): Boolean = delegate.isAlive
    override fun destroy() {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        delegate.destroy()
        if (!delegate.waitFor(800, TimeUnit.MILLISECONDS)) delegate.destroyForcibly()
    }
}

private const val MaxNetworkExchanges = 20_000

internal fun findMitmdumpExecutable(): String? {
    val pathCandidates = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "mitmdump") }
    return (pathCandidates + listOf(File("/opt/homebrew/bin/mitmdump"), File("/usr/local/bin/mitmdump")))
        .firstOrNull { it.exists() && it.canExecute() }
        ?.absolutePath
}

private suspend fun defaultCertificateSubjectHash(runner: CommandRunner, certificate: File): String? {
    return listOf("PEM", "DER").firstNotNullOfOrNull { format ->
        val result = runner.run(
            listOf("openssl", "x509", "-inform", format, "-subject_hash_old", "-in", certificate.absolutePath, "-noout"),
            10,
        )
        if (!result.isSuccess) {
            null
        } else {
            result.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.matches(Regex("[0-9a-fA-F]{8}")) }
                ?.lowercase()
        }
    }
}

private suspend fun defaultCertificateSpkiFingerprint(runner: CommandRunner, certificate: File): String? {
    val command = "openssl x509 -in ${shellQuote(certificate.absolutePath)} -pubkey -noout | " +
        "openssl pkey -pubin -outform der | " +
        "openssl dgst -sha256 -binary | " +
        "base64"
    val result = runner.run(listOf("/bin/sh", "-c", command), 10)
    return if (result.isSuccess) result.stdout.trim().takeIf { it.isNotBlank() } else null
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun resolveLanIp(): String {
    return NetworkInterface.getNetworkInterfaces().toList().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.254.") }
        ?.hostAddress
        ?: "127.0.0.1"
}

internal object ProxyRuleJson {
    fun writeRules(rules: List<ProxyRule>): String {
        return rules.joinToString(prefix = "{\"rules\":[", postfix = "]}\n") { rule ->
            buildString {
                append("{")
                appendJsonField("id", rule.id)
                append(",")
                appendJsonField("name", rule.name)
                append(",\"enabled\":${rule.enabled}")
                append(",")
                appendJsonField("urlPattern", rule.urlPattern)
                append(",\"method\":")
                append(rule.method?.let(::quoteJson) ?: "null")
                append(",\"statusCode\":")
                append(rule.statusCode?.toString() ?: "null")
                append(",\"setHeaders\":{")
                append(rule.setHeaders.entries.joinToString(",") { "${quoteJson(it.key)}:${quoteJson(it.value)}" })
                append("},\"removeHeaders\":[")
                append(rule.removeHeaders.joinToString(",") { quoteJson(it) })
                append("],\"responseBody\":")
                append(rule.responseBody?.let(::quoteJson) ?: "null")
                append("}")
            }
        }
    }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append(quoteJson(name))
        append(":")
        append(quoteJson(value))
    }
}

internal fun parseMitmproxyFlowLine(line: String): NetworkExchange? {
    if (!line.trimStart().startsWith("{") || jsonString(line, "type") != "flow") return null
    val id = jsonString(line, "id") ?: return null
    val requestHeaders = jsonObject(line, "requestHeaders")
    val responseHeaders = jsonObject(line, "responseHeaders")
    val started = jsonLong(line, "startedAtMillis") ?: System.currentTimeMillis()
    val completed = jsonLong(line, "completedAtMillis")
    return NetworkExchange(
        id = id,
        flowId = id,
        startedAtMillis = started,
        completedAtMillis = completed,
        method = jsonString(line, "method") ?: "-",
        url = jsonString(line, "url") ?: "-",
        statusCode = jsonInt(line, "statusCode"),
        contentType = jsonString(line, "contentType"),
        sizeBytes = jsonLong(line, "sizeBytes"),
        durationMillis = jsonLong(line, "durationMillis"),
        requestHeaders = requestHeaders,
        responseHeaders = responseHeaders,
        requestBodyPreview = jsonNullableString(line, "requestBodyPreview"),
        responseBodyPreview = jsonNullableString(line, "responseBodyPreview"),
        error = jsonNullableString(line, "error"),
        tlsStatus = jsonNullableString(line, "tlsStatus"),
        matchedRuleId = jsonNullableString(line, "matchedRuleId"),
    )
}

internal data class VpnRouteState(val active: Boolean, val name: String?)

internal fun parseVpnRouteState(dumpsysConnectivity: String): VpnRouteState {
    val lines = dumpsysConnectivity.lines()
    val vpnLine = lines.firstOrNull { line ->
        line.contains("TRANSPORT_VPN") ||
            line.contains("type: VPN", ignoreCase = true) ||
            (line.contains("VPN") && line.contains("CONNECTED", ignoreCase = true))
    } ?: return VpnRouteState(active = false, name = null)
    val ownerLine = lines.firstOrNull { line ->
        line.contains("mOwnerName=", ignoreCase = true) ||
            line.contains("owner=", ignoreCase = true) ||
            (line.contains("vpn", ignoreCase = true) && line.contains("package", ignoreCase = true))
    }
    val name = ownerLine
        ?.let { Regex("""(?:mOwnerName|owner|packageName|package)=([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }
        ?: Regex("""\b([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_-]+){2,})\b""").find(vpnLine)?.groupValues?.getOrNull(1)
    return VpnRouteState(active = true, name = name)
}

internal data class HostProxyState(
    val active: Boolean,
    val summary: String? = null,
    val upstreamProxy: String? = null,
    val bypassLooksSafe: Boolean = true,
)

private data class HostVpnState(val active: Boolean, val summary: String? = null)

internal fun parseMacProxyState(scutilProxy: String): HostProxyState {
    val entries = scutilProxy.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (":" !in trimmed) return@mapNotNull null
            trimmed.substringBefore(":").trim() to trimmed.substringAfter(":").trim()
        }
        .toMap()
    val http = proxyEndpoint(entries, "HTTP")
    val https = proxyEndpoint(entries, "HTTPS")
    val socks = proxyEndpoint(entries, "SOCKS")
    val upstream = https ?: http ?: socks
    val activeParts = listOfNotNull(
        http?.let { "HTTP $it" },
        https?.let { "HTTPS $it" },
        socks?.let { "SOCKS $it" },
        entries["ProxyAutoConfigEnable"]?.takeIf { it == "1" }?.let { entries["ProxyAutoConfigURLString"]?.let { url -> "PAC $url" } ?: "PAC" },
    )
    val active = activeParts.isNotEmpty()
    val exceptions = scutilProxy.lineSequence()
        .map { it.trim() }
        .filter { Regex("""^\d+\s*:""").containsMatchIn(it) }
        .map { it.substringAfter(":").trim() }
        .toList()
    val simpleHostsExcluded = entries["ExcludeSimpleHostnames"] == "1"
    val bypassLooksSafe = !active || simpleHostsExcluded || listOf("localhost", "127.0.0.1", "10.0.2.2").all { expected ->
        exceptions.any { exception -> proxyExceptionCovers(exception, expected) }
    }
    return HostProxyState(
        active = active,
        summary = activeParts.joinToString(", ").takeIf { it.isNotBlank() },
        upstreamProxy = upstream,
        bypassLooksSafe = bypassLooksSafe,
    )
}

private fun proxyEndpoint(entries: Map<String, String>, prefix: String): String? {
    if (entries["${prefix}Enable"] != "1") return null
    val host = entries["${prefix}Proxy"]?.takeIf { it.isNotBlank() } ?: return null
    val port = entries["${prefix}Port"]?.takeIf { it.isNotBlank() } ?: return null
    val scheme = if (prefix == "SOCKS") "socks5" else "http"
    return "$scheme://$host:$port"
}

private fun proxyExceptionCovers(exception: String, expected: String): Boolean {
    val normalized = exception.trim().lowercase()
    val target = expected.lowercase()
    return normalized == target ||
        normalized == "<local>" && target == "localhost" ||
        normalized == "*.local" && target.endsWith(".local") ||
        normalized.endsWith(".*") && target.startsWith(normalized.removeSuffix(".*")) ||
        normalized.endsWith("/8") && normalized.substringBefore("/") == "10.0.0.0" && target.startsWith("10.") ||
        normalized.endsWith("/16") && target.startsWith(normalized.substringBeforeLast('.').removeSuffix(".0")) ||
        normalized.endsWith("/24") && target.startsWith(normalized.substringBeforeLast('.'))
}

private fun quoteJson(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}

private fun jsonString(source: String, key: String): String? = jsonNullableString(source, key)

private fun jsonNullableString(source: String, key: String): String? {
    val start = Regex(""""${Regex.escape(key)}"\s*:\s*""").find(source)?.range?.last?.plus(1) ?: return null
    val trimmed = source.substring(start).trimStart()
    if (trimmed.startsWith("null")) return null
    if (!trimmed.startsWith("\"")) return null
    val builder = StringBuilder()
    var escape = false
    for (char in trimmed.drop(1)) {
        if (escape) {
            builder.append(
                when (char) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> char
                },
            )
            escape = false
        } else if (char == '\\') {
            escape = true
        } else if (char == '"') {
            return builder.toString()
        } else {
            builder.append(char)
        }
    }
    return null
}

private fun jsonInt(source: String, key: String): Int? = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
    .find(source)
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()

private fun jsonLong(source: String, key: String): Long? = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
    .find(source)
    ?.groupValues
    ?.getOrNull(1)
    ?.toLongOrNull()

private fun jsonObject(source: String, key: String): Map<String, String> {
    val start = Regex(""""${Regex.escape(key)}"\s*:\s*\{""").find(source)?.range?.last ?: return emptyMap()
    var depth = 0
    var end = -1
    for (index in start until source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    end = index
                    break
                }
            }
        }
    }
    if (end < 0) return emptyMap()
    val body = source.substring(start + 1, end)
    return Regex(""""((?:\\.|[^"\\])*)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        .findAll(body)
        .associate { match -> unescapeJson(match.groupValues[1]) to unescapeJson(match.groupValues[2]) }
}

private fun unescapeJson(value: String): String {
    val builder = StringBuilder()
    var escape = false
    for (char in value) {
        if (escape) {
            builder.append(
                when (char) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> char
                },
            )
            escape = false
        } else if (char == '\\') {
            escape = true
        } else {
            builder.append(char)
        }
    }
    return builder.toString()
}

/** Fallback when the classpath resource is missing. Phase 0 golden-tests the evaluated string. */
internal val AndyMitmAddonSource = """
import json
import os
import time
from mitmproxy import http

RULES_PATH = os.environ.get("ANDY_RULES_PATH")
PREVIEW_LIMIT = 4096

def _load_rules():
    if not RULES_PATH or not os.path.exists(RULES_PATH):
        return []
    try:
        with open(RULES_PATH, "r", encoding="utf-8") as handle:
            return json.load(handle).get("rules", [])
    except Exception:
        return []

def _preview(content):
    if not content:
        return None
    data = content[:PREVIEW_LIMIT]
    try:
        return data.decode("utf-8", errors="replace")
    except Exception:
        return repr(data)

def _message_preview(message):
    if not message or not message.raw_content:
        return None
    try:
        text = message.get_text(strict=False)
        if text is not None:
            return text[:PREVIEW_LIMIT]
    except Exception:
        pass
    return _preview(message.raw_content)

def _headers(headers):
    return {key: value for key, value in headers.items()}

def _remove_header(headers, target):
    target_lower = target.lower()
    for name in list(headers.keys()):
        if name.lower() == target_lower:
            headers.pop(name, None)

def _match(rule, flow):
    if not rule.get("enabled", True):
        return False
    pattern = (rule.get("urlPattern") or "").lower()
    if pattern and pattern not in flow.request.pretty_url.lower():
        return False
    method = rule.get("method")
    if method and method.upper() != flow.request.method.upper():
        return False
    return True

def request(flow: http.HTTPFlow):
    flow.metadata["andy_started_at"] = int(time.time() * 1000)
    _emit(flow, is_request=True)

def response(flow: http.HTTPFlow):
    matched_rule_id = None
    for rule in _load_rules():
        if not _match(rule, flow):
            continue
        matched_rule_id = rule.get("id")
        if rule.get("statusCode") is not None:
            flow.response.status_code = int(rule["statusCode"])
        for header, value in (rule.get("setHeaders") or {}).items():
            flow.response.headers[header] = value
        for header in rule.get("removeHeaders") or []:
            _remove_header(flow.response.headers, header)
        if rule.get("responseBody") is not None:
            flow.response.headers.pop("content-encoding", None)
            flow.response.headers.pop("content-length", None)
            flow.response.text = rule["responseBody"]
        break
    _emit(flow, matched_rule_id, None)

def error(flow: http.HTTPFlow):
    _emit(flow, None, str(flow.error) if flow.error else "proxy error")

def _emit(flow, matched_rule_id=None, error=None, is_request=False):
    response = flow.response if (hasattr(flow, 'response') and flow.response) else None
    started = flow.metadata.get("andy_started_at")
    if started is None:
        started = int(time.time() * 1000)
        flow.metadata["andy_started_at"] = started

    if is_request:
        completed = None
        duration = None
    else:
        completed = int(time.time() * 1000)
        duration = max(0, completed - started)

    payload = {
        "type": "flow",
        "id": flow.id,
        "startedAtMillis": started,
        "completedAtMillis": completed,
        "durationMillis": duration,
        "method": flow.request.method,
        "url": flow.request.pretty_url,
        "statusCode": response.status_code if response else None,
        "contentType": response.headers.get("content-type") if response else None,
        "sizeBytes": len(response.raw_content or b"") if response else None,
        "requestHeaders": _headers(flow.request.headers),
        "responseHeaders": _headers(response.headers) if response else {},
        "requestBodyPreview": _message_preview(flow.request),
        "responseBodyPreview": _message_preview(response) if response else None,
        "error": error,
        "tlsStatus": "tls" if flow.request.scheme == "https" else "plain",
        "matchedRuleId": matched_rule_id,
    }
    print(json.dumps(payload, separators=(",", ":")), flush=True)
""".trimIndent()

