@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package app.andy.web

import app.andy.formatDecimal
import app.andy.currentTimeMillis
import app.andy.model.*
import app.andy.service.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.JsString

private val WebJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class WebDeviceDto(
    val serial: String,
    val state: String = "device",
    val model: String = "Android device",
    val product: String = "",
    val device: String = "",
    val abi: String = "",
    val api: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val transport: String = "none",
) {
    fun toModel() = AndroidDevice(
        serial = serial,
        displayName = model.ifBlank { product.ifBlank { serial } },
        kind = if (serial.startsWith("emulator-")) DeviceKind.Emulator else DeviceKind.Physical,
        state = if (state == "device") DeviceConnectionState.Online else DeviceConnectionState.Offline,
        transport = if (transport == "webusb") DeviceTransport.Usb else DeviceTransport.Unknown,
        apiLevel = api.toString(),
        abi = abi,
        model = model,
        product = product,
        hardwareId = device,
        screenSize = if (width > 0 && height > 0) "${width}x$height" else null,
    )
}

@Serializable
private data class WebDeviceEnvelope(
    val ok: Boolean = true,
    val transport: String = "none",
    val devices: List<WebDeviceDto> = emptyList(),
    val cancelled: Boolean = false,
)

@Serializable
private data class WebCommandDto(
    val exitCode: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
) {
    fun toResult() = CommandResult(exitCode, stdout, stderr)
}

@Serializable
private data class WebMirrorStatsDto(
    val connected: Boolean = false,
    val displayedFps: Float = 0f,
    val decodedFps: Float = 0f,
    val framesRendered: Long = 0,
    val framesSkipped: Long = 0,
    val width: Int = 720,
    val height: Int = 1280,
    val renderer: String = "WebCodecs",
    val decoder: String = "WebCodecs",
    val hardwareBacked: Boolean = false,
    val fallbackReason: String? = null,
    val p95InputToPresentMillis: Float? = null,
    val error: String? = null,
)

@Serializable
private data class WebMirrorStartDto(
    val renderer: String = "browser decoder",
    val decoder: String = "browser decoder",
    val hardwareBacked: Boolean = false,
    val fallbackReason: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sessionId: String = "",
)

@Serializable
private data class WebLogcatBatchDto(
    val lines: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class WebLogcatStartDto(
    val sessionId: String = "",
)

private data class WebMetricsCommandOutput(
    val cpu: String,
    val processes: String,
    val battery: String,
    val network: Pair<Long, Long>?,
)

@Serializable
private data class WebStorageDto(
    val persisted: Boolean = false,
    val usageBytes: Long = 0,
    val quotaBytes: Long = 0,
    val resourceOrigins: List<String> = emptyList(),
)

@Serializable
private data class WebBugCaptureDto(
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val durationMillis: Long = 0,
    val sizeBytes: Long = 0,
    val actions: List<BugAction> = emptyList(),
    val logLines: List<String> = emptyList(),
)

@Serializable
private data class WebAccessibilityDto(
    val id: String,
    val className: String? = null,
    val packageName: String? = null,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val bounds: String? = null,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val attributes: Map<String, String> = emptyMap(),
    val children: List<WebAccessibilityDto> = emptyList(),
) {
    fun toModel(): AccessibilityNode = AccessibilityNode(
        id, className, packageName, resourceId, text, contentDescription, hint, bounds,
        clickable, longClickable, focusable, focused, enabled, selected, checkable, checked,
        scrollable, password, visible, attributes, children.map(WebAccessibilityDto::toModel),
    )
}

private class BrowserConnectionService : WebConnectionService {
    override val state = MutableStateFlow(WebConnectionState())
    private val connectionMutex = Mutex()
    private var lastTransport = WebConnectionTransport.WebSocket
    var connectedDevices: List<AndroidDevice> = emptyList()
        private set

    override suspend fun connectWebSocket() = connectionMutex.withLock {
        connectLocked(WebConnectionTransport.WebSocket) {
            webAdbConnectWebSocket().await<JsString>().toString()
        }
    }

    override suspend fun requestWebUsb() = connectionMutex.withLock {
        connectLocked(WebConnectionTransport.WebUsb) {
            webAdbRequestWebUsb().await<JsString>().toString()
        }
    }

    suspend fun autoConnectWebSocket(): CommandResult = connectionMutex.withLock {
        // Recheck after acquiring the lock. A user-requested WebUSB attempt may
        // have completed while this automatic request was waiting in the queue.
        if (!state.value.shouldAutoConnectWebSocket()) {
            return@withLock CommandResult.failure("Automatic WebSocket connection is no longer eligible")
        }
        connectLocked(WebConnectionTransport.WebSocket) {
            webAdbConnectWebSocket().await<JsString>().toString()
        }
    }

    private suspend fun connectLocked(transport: WebConnectionTransport, action: suspend () -> String): CommandResult {
        val previousState = state.value
        lastTransport = transport
        state.value = WebConnectionState(transport, "Connecting…", connecting = true)
        return runCatching {
            val envelope = WebJson.decodeFromString<WebDeviceEnvelope>(action())
            if (envelope.cancelled) {
                state.value = previousState.copy(status = "Device selection cancelled", connecting = false)
                CommandResult.failure("Device selection cancelled")
            } else {
                connectedDevices = envelope.devices.map(WebDeviceDto::toModel)
                val count = envelope.devices.size
                state.value = WebConnectionState(
                    transport = transport,
                    status = if (count == 1) "Connected to ${envelope.devices.first().model}" else "Connected · $count devices",
                    connected = true,
                )
                CommandResult.success("Connected · $count devices")
            }
        }.getOrElse { error ->
            val message = error.message ?: error.toString()
            connectedDevices = emptyList()
            state.value = WebConnectionState(transport, "Connection failed", error = message)
            CommandResult.failure(message)
        }
    }

    override suspend fun retry() = when (lastTransport) {
        WebConnectionTransport.WebUsb -> requestWebUsb()
        else -> connectWebSocket()
    }

    override suspend fun forgetWebUsbAuthorization(): CommandResult = connectionMutex.withLock {
        runCatching {
            webAdbForgetWebUsbAuthorization().await<JsString>()
            connectedDevices = emptyList()
            state.value = WebConnectionState(status = "WebUSB authorization cleared")
            CommandResult.success("WebUSB authorization cleared")
        }.getOrElse { CommandResult.failure(it.message ?: it.toString()) }
    }
}

private class BrowserDeviceService(
    private val connection: BrowserConnectionService,
) : DeviceService {
    override suspend fun discoverSdk() = SdkDiscovery(
        sdkPath = null,
        adbPath = "Browser ADB (${connection.state.value.transport.name})",
        emulatorPath = null,
        sdkManagerPath = null,
        avdManagerPath = null,
        issues = connection.state.value.error?.let(::listOf).orEmpty(),
    )

    override suspend fun listDevices(): List<AndroidDevice> {
        val connectionState = connection.state.value
        if (!connectionState.connected) {
            if (!connectionState.shouldAutoConnectWebSocket()) return emptyList()
            if (!connection.autoConnectWebSocket().isSuccess) return emptyList()
            return connection.connectedDevices
        }
        return runCatching {
            WebJson.decodeFromString<WebDeviceEnvelope>(webAdbListDevices().await<JsString>().toString())
                .devices.map(WebDeviceDto::toModel)
        }.getOrElse { emptyList() }
    }

    override suspend fun shell(serial: String, command: List<String>): CommandResult = try {
        WebJson.decodeFromString<WebCommandDto>(
            webAdbShell(serial, WebJson.encodeToString(command)).await<JsString>().toString(),
        ).toResult()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        CommandResult.failure(error.message ?: error.toString())
    }

    override suspend fun pair(host: String, port: Int, code: String) = CommandResult.failure("Wi-Fi pairing is unavailable in the browser.")
    override suspend fun connect(host: String, port: Int) = CommandResult.failure("Use WebUSB or the local ADB bridge.")
    override suspend fun disconnect(serial: String) = CommandResult.success("Browser connection retained")
    override suspend fun listMdnsServices() = emptyList<MdnsService>()
    override suspend fun mdnsAvailable() = false
    override suspend fun generatePairingQr(content: String): ByteArray? = null
}

private class BrowserAppService(private val devices: BrowserDeviceService) : AppService {
    override suspend fun listApps(serial: String): List<AndroidApp> {
        val packages = devices.shell(serial, listOf("cmd", "package", "list", "packages", "-U", "--show-versioncode")).stdout
            .lineSequence().mapNotNull { line ->
                val name = Regex("""package:([^\s]+)""").find(line)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                name to Regex("""versionCode:([^\s]+)""").find(line)?.groupValues?.getOrNull(1)
            }.toList()
        val system = packageSet(devices.shell(serial, listOf("cmd", "package", "list", "packages", "-s")).stdout)
        val disabled = packageSet(devices.shell(serial, listOf("cmd", "package", "list", "packages", "-d")).stdout)
        return packages.map { (name, version) ->
            AndroidApp(name, name.substringAfterLast('.'), name in system, name !in disabled, versionCode = version)
        }.sortedWith(compareBy<AndroidApp> { it.system }.thenBy { it.packageName })
    }

    private fun packageSet(output: String) = output.lineSequence()
        .mapNotNull { it.substringAfter("package:", "").trim().takeIf(String::isNotBlank) }.toSet()

    override suspend fun launch(serial: String, packageName: String) =
        devices.shell(serial, listOf("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"))
    override suspend fun stop(serial: String, packageName: String) = devices.shell(serial, listOf("am", "force-stop", packageName))
    override suspend fun clearData(serial: String, packageName: String) = devices.shell(serial, listOf("pm", "clear", packageName))
    override suspend fun resetPermissions(serial: String, packageName: String) = devices.shell(serial, listOf("pm", "reset-permissions", packageName))
    override suspend fun uninstall(serial: String, packageName: String) = devices.shell(serial, listOf("pm", "uninstall", packageName))
    override suspend fun install(serial: String, apkPath: String, replace: Boolean) = runCatching {
        WebJson.decodeFromString<WebCommandDto>(webAdbInstallFile(serial, apkPath, replace).await<JsString>().toString()).toResult()
    }.getOrElse { CommandResult.failure(it.message ?: it.toString()) }

    override suspend fun listPermissions(serial: String, packageName: String): List<AndroidPermission> {
        val output = devices.shell(serial, listOf("dumpsys", "package", packageName)).stdout
        val requested = LinkedHashSet<String>()
        val granted = mutableMapOf<String, Boolean?>()
        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("android.permission.")) requested += trimmed.substringBefore(":").substringBefore(" ")
            Regex("""(android\.permission\.[^:\s]+):\s+granted=(true|false)""").find(trimmed)?.let { match ->
                requested += match.groupValues[1]
                granted[match.groupValues[1]] = match.groupValues[2].toBoolean()
            }
        }
        return requested.map { AndroidPermission(it, granted[it]) }
    }

    override suspend fun listActivities(serial: String, packageName: String): List<AndroidActivity> {
        val output = devices.shell(serial, listOf("dumpsys", "package", packageName)).stdout
        val activities = LinkedHashSet<String>()
        output.lineSequence().forEach { line ->
            Regex("""${Regex.escape(packageName)}/[^\s}]+""").findAll(line).forEach { activities += it.value.substringAfter('/') }
        }
        return activities.map { AndroidActivity(it, null) }
    }

    override suspend fun getIcon(serial: String, packageName: String): ByteArray? = null
}

private class BrowserIntentService(private val devices: BrowserDeviceService) : IntentService {
    override fun buildCommand(draft: IntentDraft): List<String> = buildList {
        add("am")
        add(when (draft.mode) {
            IntentMode.Activity, IntentMode.DeepLink -> "start"
            IntentMode.Service -> "startservice"
            IntentMode.Broadcast -> "broadcast"
        })
        if (draft.action.isNotBlank()) addAll(listOf("-a", draft.action))
        if (draft.component.isNotBlank()) addAll(listOf("-n", draft.component))
        draft.categories.filter(String::isNotBlank).forEach { addAll(listOf("-c", it)) }
        draft.flags.filter(String::isNotBlank).forEach { addAll(listOf("-f", it)) }
        draft.extras.forEach { extra ->
            add(when (extra.type) {
                ExtraType.StringValue -> "--es"
                ExtraType.BooleanValue -> "--ez"
                ExtraType.IntValue -> "--ei"
                ExtraType.LongValue -> "--el"
                ExtraType.FloatValue -> "--ef"
            })
            add(extra.key)
            add(extra.value)
        }
        if (draft.dataUri.isNotBlank()) addAll(listOf("-d", draft.dataUri))
    }
    override suspend fun send(serial: String, draft: IntentDraft) = devices.shell(serial, buildCommand(draft))
}

private class BrowserFileService(private val devices: BrowserDeviceService) : FileService {
    override suspend fun list(serial: String, path: String): List<DeviceFile> {
        val listPath = if (path != "/" && !path.endsWith('/')) "$path/" else path
        return parseFileListing(path, devices.shell(serial, listOf("ls", "-la", listPath)).stdout)
    }

    override suspend fun pull(serial: String, remotePath: String, localPath: String) = runCatching {
        webAdbPullFile(serial, remotePath, remotePath.substringAfterLast('/').ifBlank { "android-file" }).await<JsString>()
        CommandResult.success("Download started")
    }.getOrElse { CommandResult.failure(it.message ?: it.toString()) }

    override suspend fun push(serial: String, localPath: String, remotePath: String) = runCatching {
        webAdbPushFile(serial, localPath, remotePath).await<JsString>()
        CommandResult.success("Uploaded ${localPath.substringAfter(':')}")
    }.getOrElse { CommandResult.failure(it.message ?: it.toString()) }

    override suspend fun delete(serial: String, remotePath: String) = devices.shell(serial, listOf("rm", "-rf", remotePath))

    private fun parseFileListing(path: String, output: String) = output.lineSequence().mapNotNull { line ->
        val match = lsLineRegex.matchEntire(line.trim()) ?: return@mapNotNull null
        val name = match.groupValues[4]
        if (name == "." || name == "..") return@mapNotNull null
        DeviceFile(
            path = if (path.endsWith('/')) path + name else "$path/$name",
            name = name,
            isDirectory = match.groupValues[1].startsWith('d'),
            sizeBytes = match.groupValues[2].toLongOrNull(),
            permissions = match.groupValues[1],
            modified = match.groupValues[3],
        )
    }.toList()

    private companion object {
        val lsLineRegex = Regex(
            """^([bcdlps-][rwxStTs-]{9})\s+\S+\s+\S+\s+\S+\s+(\d+)\s+((?:\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})|(?:[A-Z][a-z]{2}\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})))\s+(.+)$""",
        )
    }
}

private class BrowserLogcatService(private val devices: BrowserDeviceService) : LogcatService {
    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = kotlinx.coroutines.flow.flow {
        val packagePids = filter.packageName
            ?.let { devices.shell(serial, listOf("pidof", it)).stdout.split(Regex("\\s+")).toSet() }
            .orEmpty()
        while (currentCoroutineContext().isActive) {
            val sessionId = try {
                WebJson.decodeFromString<WebLogcatStartDto>(webAdbStartLogcat(serial).await<JsString>().toString()).sessionId
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                ""
            }
            if (sessionId.isBlank()) {
                delay(1_500)
                continue
            }
            try {
                while (currentCoroutineContext().isActive) {
                    val batch = WebJson.decodeFromString<WebLogcatBatchDto>(webAdbDrainLogcat(sessionId).toString())
                    val fresh = batch.lines.asSequence().mapNotNull(::parseLogcatLine).filter { entry ->
                        entry.level in filter.levels &&
                            (filter.packageName == null || entry.pid in packagePids) &&
                            (filter.search.isBlank() || entry.tag.contains(filter.search, true) || entry.message.contains(filter.search, true))
                    }.toList()
                    if (fresh.isNotEmpty()) emit(fresh)
                    if (batch.error != null) break
                    delay(250)
                }
            } finally {
                withContext(NonCancellable) {
                    runCatching { webAdbStopLogcat(sessionId).await<JsAny?>() }
                }
            }
            delay(750)
        }
    }

    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int): List<LogcatEntry> {
        val result = devices.shell(serial, listOf("logcat", "-d", "-v", "threadtime", "-t", limit.toString()))
        val packagePids = filter.packageName?.let { devices.shell(serial, listOf("pidof", it)).stdout.split(Regex("\\s+")).toSet() }.orEmpty()
        return result.stdout.lineSequence().mapNotNull(::parseLogcatLine).filter { entry ->
            entry.level in filter.levels &&
                (filter.packageName == null || entry.pid in packagePids) &&
                (filter.search.isBlank() || entry.tag.contains(filter.search, true) || entry.message.contains(filter.search, true))
        }.toList()
    }

    override suspend fun clear(serial: String) { devices.shell(serial, listOf("logcat", "-c")) }

    private fun parseLogcatLine(line: String): LogcatEntry? {
        val match = Regex("""^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+([^:]+):\s?(.*)$""").find(line) ?: return null
        val level = when (match.groupValues[4]) {
            "V" -> LogLevel.Verbose; "D" -> LogLevel.Debug; "I" -> LogLevel.Info
            "W" -> LogLevel.Warn; "E" -> LogLevel.Error; "F" -> LogLevel.Fatal
            else -> LogLevel.Silent
        }
        return LogcatEntry(match.groupValues[1], match.groupValues[2], match.groupValues[3], level, match.groupValues[5].trim(), match.groupValues[6])
    }
}

private class BrowserAccessibilityService : AccessibilityService {
    override suspend fun dump(serial: String): AccessibilityNode? = runCatching {
        val json = webAdbAccessibility(serial).await<JsString>().toString()
        if (json == "null") null else WebJson.decodeFromString<WebAccessibilityDto>(json).toModel()
    }.getOrNull()
}

private class BrowserArtifactService : ArtifactService {
    override suspend fun saveScreenshot(serial: String, suggestedName: String) = runCatching {
        webAdbDownloadCommand(serial, WebJson.encodeToString(listOf("screencap", "-p")), suggestedName, "image/png").await<JsString>()
        CommandResult.success("Downloaded $suggestedName")
    }.getOrElse { CommandResult.failure(it.message ?: it.toString()) }

    override suspend fun saveBugReport(serial: String, suggestedName: String) = runCatching {
        webAdbDownloadBugReport(serial, suggestedName).await<JsString>()
        CommandResult.success("Downloaded $suggestedName")
    }.getOrElse { CommandResult.failure(it.message ?: it.toString()) }
}

private class BrowserMetricsService(private val devices: BrowserDeviceService) : MetricsService {
    override fun stream(serial: String, packageName: String?): Flow<PerformanceSample> = kotlinx.coroutines.flow.flow {
        var previousNetwork: Pair<Long, Long>? = null
        var previousAt = 0L
        while (currentCoroutineContext().isActive) {
            val output = coroutineScope {
                val cpu = async { devices.shell(serial, listOf("dumpsys", "cpuinfo")).stdout }
                val processes = async { devices.shell(serial, listOf("top", "-b", "-n", "1", "-o", "PID,%CPU,RES,ARGS", "-m", "80")).stdout }
                val battery = async { devices.shell(serial, listOf("dumpsys", "battery")).stdout }
                val network = async { parseNetwork(devices.shell(serial, listOf("cat", "/proc/net/dev")).stdout) }
                WebMetricsCommandOutput(cpu.await(), processes.await(), battery.await(), network.await())
            }
            val cpu = output.cpu
            val processOutput = output.processes
            val battery = output.battery
            val network = output.network
            val now = currentTimeMillis()
            val elapsed = (now - previousAt) / 1000f
            val rx = if (network != null && previousNetwork != null && elapsed > 0) ((network.first - previousNetwork.first).coerceAtLeast(0) / 1024f) / elapsed else null
            val tx = if (network != null && previousNetwork != null && elapsed > 0) ((network.second - previousNetwork.second).coerceAtLeast(0) / 1024f) / elapsed else null
            val processes = parseProcesses(processOutput)
            emit(PerformanceSample(
                timestampMillis = now,
                cpuPercent = Regex("""(\d+(?:\.\d+)?)%""").find(cpu)?.groupValues?.getOrNull(1)?.toFloatOrNull(),
                memoryMb = processes.sumOf { it.memoryMb?.toDouble() ?: 0.0 }.toFloat().takeIf { it > 0 },
                fps = null,
                batteryPercent = Regex("""level:\s*(\d+)""").find(battery)?.groupValues?.getOrNull(1)?.toIntOrNull(),
                thermalStatus = null,
                networkRxKbps = rx,
                networkTxKbps = tx,
                processes = processes,
            ))
            if (network != null) { previousNetwork = network; previousAt = now }
            delay(900)
        }
    }

    private fun parseNetwork(output: String): Pair<Long, Long>? {
        var rx = 0L; var tx = 0L; var found = false
        output.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val iface = line.substring(0, separator).trim()
            val values = line.substring(separator + 1).trim().split(Regex("\\s+"))
            if (iface == "lo" || values.size < 9) return@forEach
            rx += values[0].toLongOrNull() ?: return@forEach
            tx += values[8].toLongOrNull() ?: return@forEach
            found = true
        }
        return if (found) rx to tx else null
    }

    private fun parseProcesses(output: String): List<ProcessMetric> = output.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        val pid = parts.firstOrNull()?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
        val cpu = parts.drop(1).firstNotNullOfOrNull { it.removeSuffix("%").toFloatOrNull()?.takeIf { value -> value <= 1000f } }
        val memory = parts.firstOrNull { it.endsWith('K') || it.endsWith('M') || it.endsWith('G') }?.let(::memoryMb)
        val name = parts.lastOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        ProcessMetric(pid, name, cpu, memory)
    }.distinctBy { it.pid }.sortedByDescending { it.cpuPercent ?: -1f }.take(120).toList()

    private fun memoryMb(token: String): Float? {
        val value = token.dropLastWhile(Char::isLetter).toFloatOrNull() ?: return null
        return when (token.lastOrNull()?.uppercaseChar()) { 'G' -> value * 1024; 'M' -> value; 'K' -> value / 1024; else -> value / 1024 }
    }
}

private class BrowserMirrorEngine(
    private val devices: BrowserDeviceService,
) : MirrorEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableFrames = MutableSharedFlow<MirrorFrame>(replay = 1, extraBufferCapacity = 2)
    private val mutableStatus = MutableStateFlow("Disconnected")
    override val session = MutableStateFlow<MirrorSession?>(null)
    private var serial: String? = null
    private var generation = 0
    private var sessionId: String? = null
    override val frames: Flow<MirrorFrame> = mutableFrames
    override val status: Flow<String> = mutableStatus

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        val token = ++generation
        this.serial = serial
        mutableStatus.value = "Connecting · ${config.rendererMode.name.lowercase()} renderer"
        return runCatching {
            val configJson = """{"maxSize":${config.maxSize},"bitRate":${config.bitRate},"maxFps":${config.maxFps},"codec":${WebJson.encodeToString(config.codec)},"rendererMode":${WebJson.encodeToString(config.rendererMode.name.lowercase())}}"""
            val start = WebJson.decodeFromString<WebMirrorStartDto>(webAdbStartMirror(serial, configJson).await<JsString>().toString())
            sessionId = start.sessionId
            session.value = MirrorSession(
                serial = serial,
                requestedMode = config.rendererMode,
                backend = MirrorBackend(
                    kind = if (start.hardwareBacked) MirrorBackendKind.BrowserHardware else MirrorBackendKind.LegacyCpu,
                    decoder = start.decoder,
                    renderer = start.renderer,
                    fallbackReason = start.fallbackReason,
                ),
                width = start.width,
                height = start.height,
                failureReason = start.fallbackReason,
            )
            mutableFrames.emit(MirrorFrame(start.width.coerceAtLeast(2), start.height.coerceAtLeast(2), IntArray(0), frameNumber = 1))
            mutableStatus.value = "Connected · ${start.width}×${start.height} · measuring displayed fps · ${start.renderer}"
            scope.launch {
                while (isActive && generation == token) {
                    delay(1_000)
                    runCatching {
                        val stats = WebJson.decodeFromString<WebMirrorStatsDto>(webAdbMirrorStats().toString())
                        if (stats.connected) {
                            session.value?.let { active ->
                                session.value = active.copy(
                                    backend = MirrorBackend(
                                        kind = if (stats.hardwareBacked) MirrorBackendKind.BrowserHardware else MirrorBackendKind.LegacyCpu,
                                        decoder = stats.decoder,
                                        renderer = stats.renderer,
                                        fallbackReason = stats.fallbackReason,
                                    ),
                                    stats = MirrorStats(
                                        displayedFps = stats.displayedFps,
                                        decodedFps = stats.decodedFps,
                                        droppedFrames = stats.framesSkipped,
                                        framesPresented = stats.framesRendered,
                                        p95InputToPresentMillis = stats.p95InputToPresentMillis,
                                    ),
                                    width = stats.width,
                                    height = stats.height,
                                    failureReason = stats.error ?: stats.fallbackReason,
                                )
                            }
                            mutableStatus.value = buildString {
                                append("Connected · ")
                                append(formatDecimal(stats.displayedFps, 1))
                                append(" displayed fps · ")
                                append(formatDecimal(stats.decodedFps, 1))
                                append(" decoded fps · ")
                                append(stats.width)
                                append('×')
                                append(stats.height)
                                append(" · ")
                                append(stats.renderer)
                                if (stats.hardwareBacked) append(" · GPU accelerated")
                                stats.fallbackReason?.let { append(" · fallback: $it") }
                                stats.p95InputToPresentMillis?.let { append(" · ${formatDecimal(it, 1)} ms P95") }
                                stats.error?.let { append(" · $it") }
                            }
                            mutableFrames.emit(
                                MirrorFrame(
                                    stats.width.coerceAtLeast(2),
                                    stats.height.coerceAtLeast(2),
                                    IntArray(0),
                                    frameNumber = stats.framesRendered + stats.framesSkipped,
                                    decodedFps = stats.decodedFps,
                                    displayedFps = stats.displayedFps,
                                ),
                            )
                        }
                    }
                }
            }
            CommandResult.success("Connected · ${start.width}×${start.height} · ${start.renderer}")
        }.getOrElse { error ->
            val message = error.message ?: error.toString()
            session.value = MirrorSession(
                serial = serial,
                requestedMode = config.rendererMode,
                backend = MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = message),
                failureReason = message,
            )
            mutableStatus.value = "Connection failed · $message"
            CommandResult.failure(message)
        }
    }

    override suspend fun disconnect() {
        generation++
        sessionId?.let { webAdbStopMirror(it).await<JsAny?>() }
        sessionId = null
        session.value = null
        mutableStatus.value = "Disconnected"
        mutableFrames.emit(MirrorFrame(1, 1, IntArray(1)))
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult = runCatching {
        val payload = when (input) {
            is MirrorInput.Key -> """{"type":"key","keyCode":${input.keyCode}}"""
            is MirrorInput.Text -> """{"type":"text","value":${WebJson.encodeToString(input.value)}}"""
            MirrorInput.Back -> """{"type":"back"}"""
            MirrorInput.Home -> """{"type":"home"}"""
            MirrorInput.Recents -> """{"type":"recents"}"""
            MirrorInput.Power -> """{"type":"power"}"""
            is MirrorInput.Tap -> return devices.shell(serial ?: return CommandResult.failure("Mirror disconnected"), listOf("input", "tap", input.x.toString(), input.y.toString()))
            is MirrorInput.Swipe -> return devices.shell(serial ?: return CommandResult.failure("Mirror disconnected"), listOf("input", "swipe", input.startX.toString(), input.startY.toString(), input.endX.toString(), input.endY.toString(), input.durationMillis.toString()))
            is MirrorInput.Touch -> return CommandResult.success()
        }
        webAdbSendMirrorInput(payload).await<JsString>()
        CommandResult.success()
    }.getOrElse { CommandResult.failure(it.message ?: it.toString()) }

    override suspend fun screenshot(serial: String): ByteArray? = null
}

private class BrowserStorageService : WebStorageService {
    override val state = MutableStateFlow(WebStorageState())

    override suspend fun refresh(): WebStorageState = runCatching {
        val dto = WebJson.decodeFromString<WebStorageDto>(webStorageStatus().await<JsString>().toString())
        WebStorageState(dto.persisted, dto.usageBytes, dto.quotaBytes, dto.resourceOrigins).also { state.value = it }
    }.getOrElse { state.value }

    override suspend fun requestPersistence(): Boolean = runCatching {
        webStorageRequestPersistence().await<JsBoolean>().toBoolean()
    }.getOrDefault(false).also { refresh() }

    override suspend fun clearAll(): CommandResult = runCatching {
        webStorageClearAll().await<JsString>()
        state.value = WebStorageState()
        CommandResult.success("Browser data cleared")
    }.getOrElse { CommandResult.failure(it.message ?: it.toString()) }
}

private class BrowserBugService(
    private val logcat: LogcatService,
) : BugService {
    private val mutableStatus = MutableStateFlow(BugCaptureStatus(message = "Browser bug capture ready"))
    override val status: Flow<BugCaptureStatus> = mutableStatus
    private val actions = mutableListOf<BugAction>()
    private var selectedDevice: AndroidDevice? = null
    private var selectedSerial: String? = null
    private var startedAt = 0L
    private var capture: WebBugCaptureDto? = null

    override suspend fun startCapture(serial: String, device: AndroidDevice?) {
        if (mutableStatus.value.active && selectedSerial == serial) return
        if (mutableStatus.value.active) stopCapture()
        val response = WebJson.decodeFromString<WebBugCaptureDto>(webBugStart(serial).await<JsString>().toString())
        selectedSerial = serial
        selectedDevice = device
        startedAt = response.startedAt.takeIf { it > 0 } ?: currentTimeMillis()
        capture = null
        actions.clear()
        mutableStatus.value = BugCaptureStatus(active = true, deviceSerial = serial, message = "Recording Live canvas to OPFS")
    }

    override suspend fun stopCapture() {
        if (!mutableStatus.value.active) return
        capture = WebJson.decodeFromString(webBugStop().await<JsString>().toString())
        mutableStatus.value = mutableStatus.value.copy(
            active = false,
            actionCount = actions.size + capture?.actions.orEmpty().size,
            logCount = capture?.logLines.orEmpty().size,
            videoFrameCount = frameCount(capture),
            message = "Capture stopped · ready to save",
        )
    }

    override fun recordAction(kind: String, label: String, detail: String?) {
        if (!mutableStatus.value.active) return
        actions += BugAction(
            id = "web-action-${currentTimeMillis()}-${actions.size}",
            timestampMillis = currentTimeMillis(),
            kind = kind,
            label = label,
            detail = detail,
        )
        mutableStatus.value = mutableStatus.value.copy(actionCount = actions.size)
    }

    override suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?): BugReport {
        if (mutableStatus.value.active) stopCapture()
        val video = capture ?: WebJson.decodeFromString<WebBugCaptureDto>(webBugStop().await<JsString>().toString()).also { capture = it }
        val serial = selectedSerial ?: device?.serial ?: error("No Android device is selected for this capture.")
        val now = currentTimeMillis()
        val id = "andy-web-$now"
        val capturedActions = (actions + video.actions)
            .distinctBy(BugAction::id)
            .sortedBy(BugAction::timestampMillis)
        val logText = video.logLines.joinToString("\n").ifBlank {
            val logEntries = runCatching { logcat.snapshot(serial, LogcatFilter(), 2_000) }.getOrDefault(emptyList())
            logEntries.joinToString("\n") { entry ->
                "${entry.time} ${entry.pid.orEmpty()} ${entry.tid.orEmpty()} ${entry.level.name.first()} ${entry.tag}: ${entry.message}"
            }
        }
        val accessibilityJson = runCatching { webAdbAccessibility(serial).await<JsString>().toString() }.getOrDefault("null")
        val effectiveDevice = device ?: selectedDevice
        val videoStart = video.startedAt.takeIf { it > 0 } ?: startedAt
        val videoEnd = video.endedAt.takeIf { it >= videoStart } ?: now
        val timestamps = buildList {
            var value = videoStart
            while (value <= videoEnd && size < 36_000) {
                add(value)
                value += 1_000L / 15L
            }
        }
        val report = BugReport(
            id = id,
            title = draft.title.ifBlank { "Bug $now" },
            notes = draft.notes,
            deviceSerial = serial,
            deviceModel = effectiveDevice?.model ?: effectiveDevice?.displayName,
            apiLevel = effectiveDevice?.apiLevel,
            abi = effectiveDevice?.abi,
            resolution = effectiveDevice?.screenSize,
            capturedAtMillis = now,
            windowStartedAtMillis = startedAt,
            windowEndedAtMillis = videoEnd,
            actions = capturedActions,
            artifacts = buildList {
                if (video.sizeBytes > 0) add(BugArtifact("capture.webm", "bugs/$id/capture.webm", "video/webm", video.sizeBytes))
                add(BugArtifact("logcat.txt", "bugs/$id/logcat.txt", "text/plain", logText.length.toLong()))
                add(BugArtifact("actions.json", "bugs/$id/actions.json", "application/json"))
                add(BugArtifact("accessibility.json", "bugs/$id/accessibility.json", "application/json"))
            },
            videoStartedAtMillis = videoStart,
            videoEndedAtMillis = videoEnd,
            videoFrameRate = 15.0,
            videoFrameTimestampsMillis = timestamps,
        )
        webBugSave(WebJson.encodeToString(report), logText, accessibilityJson).await<JsString>()
        mutableStatus.value = BugCaptureStatus(message = "Saved ${report.title} to browser storage")
        actions.clear()
        capture = null
        return report
    }

    override suspend fun listBugs(): List<BugReport> = runCatching {
        WebJson.decodeFromString<List<BugReport>>(webBugList().await<JsString>().toString())
    }.getOrDefault(emptyList())

    override suspend fun loadBug(id: String): BugReport? = runCatching {
        webBugLoad(id).await<JsString>().toString().takeUnless { it == "null" }?.let { WebJson.decodeFromString<BugReport>(it) }
    }.getOrNull()

    override suspend fun loadBugLog(id: String): String = runCatching { webBugLoadLog(id).await<JsString>().toString() }.getOrDefault("")
    override suspend fun deleteBug(id: String): Boolean = runCatching { webBugDelete(id).await<JsBoolean>().toBoolean() }.getOrDefault(false)
    override suspend fun exportBug(id: String): String? = runCatching {
        webBugExport(id).await<JsString>(); "browser downloads"
    }.getOrNull()

    override fun playbackFrames(id: String, startFrameIndex: Int): Flow<MirrorFrame> = kotlinx.coroutines.flow.flow {
        val report = loadBug(id) ?: return@flow
        val count = report.videoFrameTimestampsMillis.size
        val width = report.resolution?.substringBefore('x')?.toIntOrNull() ?: 720
        val height = report.resolution?.substringAfter('x')?.toIntOrNull() ?: 1280
        val start = startFrameIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
        val position = report.videoFrameTimestampsMillis.getOrNull(start)?.minus(report.videoStartedAtMillis ?: 0L)?.toDouble() ?: 0.0
        webBugBeginPlayback(id, position, true).await<JsBoolean>()
        for (index in start until count) {
            emit(MirrorFrame(width, height, IntArray(0), frameNumber = (index + 1).toLong(), decodedFps = 15f))
            delay(1_000L / 15L)
        }
    }

    override suspend fun bugVideoFrameCount(id: String): Int = loadBug(id)?.videoFrameTimestampsMillis?.size ?: 0

    override suspend fun loadBugVideoFrame(id: String, frameIndex: Int): MirrorFrame? {
        val report = loadBug(id) ?: return null
        val index = frameIndex.coerceIn(0, (report.videoFrameTimestampsMillis.size - 1).coerceAtLeast(0))
        val position = report.videoFrameTimestampsMillis.getOrNull(index)?.minus(report.videoStartedAtMillis ?: 0L)?.toDouble() ?: 0.0
        webBugBeginPlayback(id, position, false).await<JsBoolean>()
        val width = report.resolution?.substringBefore('x')?.toIntOrNull() ?: 720
        val height = report.resolution?.substringAfter('x')?.toIntOrNull() ?: 1280
        return MirrorFrame(width, height, IntArray(0), frameNumber = (index + 1).toLong())
    }

    private fun frameCount(capture: WebBugCaptureDto?): Int = ((capture?.durationMillis ?: 0L) * 15L / 1_000L).toInt()
}

private class BrowserWorkspaceStore : WorkspaceStore {
    override suspend fun load(): WorkspaceState = runCatching {
        val value = webWorkspaceLoad().await<JsString>().toString()
        if (value.isBlank()) WorkspaceState() else WebJson.decodeFromString<WorkspaceState>(value)
    }.getOrDefault(WorkspaceState())

    override suspend fun save(state: WorkspaceState) {
        webWorkspaceSave(WebJson.encodeToString(state)).await<JsString>()
    }
}

fun createWebServices(): AndyServices {
    val connection = BrowserConnectionService()
    val devices = BrowserDeviceService(connection)
    val apps = BrowserAppService(devices)
    val mirror = BrowserMirrorEngine(devices)
    val storage = BrowserStorageService()
    val logcat = BrowserLogcatService(devices)
    val bugs = BrowserBugService(logcat)
    return AndyServices(
        devices = devices,
        avd = UnavailableAvdService,
        mirror = mirror,
        logcat = logcat,
        intents = BrowserIntentService(devices),
        apps = apps,
        files = BrowserFileService(devices),
        hostFiles = UnavailableHostFileService,
        proxy = UnavailableProxyService,
        metrics = BrowserMetricsService(devices),
        accessibility = BrowserAccessibilityService(),
        bugs = bugs,
        artifacts = BrowserArtifactService(),
        workspaceStore = BrowserWorkspaceStore(),
        updates = UnavailableUpdateService,
        mcp = UnavailableMcpService,
        actionConfig = EmptyActionConfigStore,
        actionRuns = UnavailableActionRunService,
        agentRuns = UnavailableAgentRunService,
        capabilities = PlatformCapabilities.Web,
        web = WebServices(connection, storage),
    )
}
