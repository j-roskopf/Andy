package app.andy.desktop.service.remote

import app.andy.model.AndroidActivity
import app.andy.model.AndroidApp
import app.andy.model.AndroidAppDetails
import app.andy.model.AndroidDevice
import app.andy.model.AndroidPermission
import app.andy.model.AvdCreationConfig
import app.andy.model.VirtualDeviceType
import app.andy.model.AvdProfile
import app.andy.model.DeviceFile
import app.andy.model.EmulatorSnapshot
import app.andy.model.IntentDraft
import app.andy.model.LogLevel
import app.andy.model.LogcatEntry
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.model.SystemImage
import app.andy.model.VirtualDevice
import app.andy.service.AppService
import app.andy.service.AvdService
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.FileService
import app.andy.service.IntentService
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import app.andy.service.MirrorBackend
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorSession
import app.andy.service.MirrorStats
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** [DeviceService] backed by remote andyd's `list_devices` / `shell` MCP tools. */
class RemoteMcpDeviceService(
    private val client: AndydMcpClient,
) : DeviceService {
    override suspend fun discoverSdk(): SdkDiscovery = AndydMcpClient.remoteSdkDiscovery

    override suspend fun listDevices(): List<AndroidDevice> = client.listDevices()

    override suspend fun shell(serial: String, command: List<String>): CommandResult =
        client.shell(serial, command)

    override suspend fun emu(serial: String, command: List<String>): CommandResult {
        if (command.isEmpty()) return CommandResult.failure("Missing emulator console command")
        return shell(serial, listOf("emu") + command)
    }

    override suspend fun applyEmulatorDisplayRotation(serial: String, quarterTurn: Int): CommandResult =
        CommandResult.failure("Emulator display rotation is unavailable over remote MCP")

    override suspend fun pair(host: String, port: Int, code: String): CommandResult =
        CommandResult.failure("Wi‑Fi pairing must be done on the remote host")

    override suspend fun connect(host: String, port: Int): CommandResult =
        CommandResult.failure("Wi‑Fi connect must be done on the remote host")

    override suspend fun disconnect(serial: String): CommandResult =
        CommandResult.failure("Wi‑Fi disconnect must be done on the remote host")

    override suspend fun listMdnsServices(): List<MdnsService> = emptyList()

    override suspend fun mdnsAvailable(): Boolean = false

    override suspend fun generatePairingQr(content: String): ByteArray? = null
}

/**
 * Screenshot-polled mirror for SSH-remoted andyd. Input is forwarded via MCP tap/swipe/key tools.
 */
class RemoteMcpMirrorEngine(
    private val client: AndydMcpClient,
    private val scope: CoroutineScope,
) : MirrorEngine {
    private val json = Json { ignoreUnknownKeys = true }
    private val _session = MutableStateFlow<MirrorSession?>(null)
    override val session: StateFlow<MirrorSession?> = _session.asStateFlow()
    private val _frames = MutableSharedFlow<MirrorFrame>(extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames: Flow<MirrorFrame> = _frames.asSharedFlow()
    override val status: Flow<String> = flowOf("Remote mirror via andyd")
    private val _presenting = MutableStateFlow(false)
    override val presenting: StateFlow<Boolean> = _presenting.asStateFlow()

    private var pollJob: Job? = null
    private var presentationHolders = 0
    private var connectedSerial: String? = null
    private var frameNumber = 0L
    private val inputMutex = Mutex()

    override fun acquirePresentation() {
        presentationHolders++
        _presenting.value = true
        ensurePolling()
    }

    override fun releasePresentation() {
        presentationHolders = (presentationHolders - 1).coerceAtLeast(0)
        if (presentationHolders == 0) {
            _presenting.value = false
            stopPolling()
        }
    }

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        connectedSerial = serial
        _session.value = MirrorSession(
            serial = serial,
            requestedMode = MirrorRendererMode.Legacy,
            backend = MirrorBackend(MirrorBackendKind.LegacyCpu, "Remote screenshot", "Compose"),
            stats = MirrorStats(),
            width = 0,
            height = 0,
        )
        ensurePolling()
        return CommandResult.success("Connected to $serial (remote)")
    }

    override suspend fun disconnect(immediate: Boolean) {
        stopPolling()
        connectedSerial = null
        _session.value = null
        presentationHolders = 0
        _presenting.value = false
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult = inputMutex.withLock {
        val serial = connectedSerial ?: return CommandResult.failure("No active remote mirror")
        when (input) {
            is MirrorInput.Tap -> client.callToolText(
                "tap",
                client.serialArg(serial) + mapOf("x" to JsonPrimitive(input.x), "y" to JsonPrimitive(input.y)),
            ).let { AndydMcpClient.parseCommandToolResult(it) }
            is MirrorInput.Swipe -> client.callToolText(
                "swipe",
                client.serialArg(serial) + mapOf(
                    "startX" to JsonPrimitive(input.startX),
                    "startY" to JsonPrimitive(input.startY),
                    "endX" to JsonPrimitive(input.endX),
                    "endY" to JsonPrimitive(input.endY),
                    "durationMillis" to JsonPrimitive(input.durationMillis),
                ),
            ).let { AndydMcpClient.parseCommandToolResult(it) }
            is MirrorInput.Text -> client.callToolText(
                "input_text",
                client.serialArg(serial) + mapOf("text" to JsonPrimitive(input.value)),
            ).let { AndydMcpClient.parseCommandToolResult(it) }
            is MirrorInput.Key -> client.shell(serial, listOf("input", "keyevent", input.keyCode.toString()))
            is MirrorInput.Back -> pressKey(serial, "back")
            is MirrorInput.Home -> pressKey(serial, "home")
            is MirrorInput.Recents -> pressKey(serial, "recents")
            is MirrorInput.Power -> pressKey(serial, "power")
            is MirrorInput.Touch -> {
                when (input.action) {
                    app.andy.service.MirrorTouchAction.Down,
                    app.andy.service.MirrorTouchAction.Move,
                    -> CommandResult.success()
                    app.andy.service.MirrorTouchAction.Up ->
                        client.callToolText(
                            "tap",
                            client.serialArg(serial) + mapOf(
                                "x" to JsonPrimitive(input.x),
                                "y" to JsonPrimitive(input.y),
                            ),
                        ).let { AndydMcpClient.parseCommandToolResult(it) }
                }
            }
        }
    }

    override suspend fun screenshot(serial: String): ByteArray? =
        client.callToolImage("screenshot", client.serialArg(serial))

    private suspend fun pressKey(serial: String, key: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText("press_key", client.serialArg(serial) + mapOf("key" to JsonPrimitive(key))),
        )

    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        val serial = connectedSerial ?: return
        pollJob = scope.launch(Dispatchers.IO) {
            var lastFpsTick = System.nanoTime()
            var framesThisSecond = 0
            while (isActive && connectedSerial == serial) {
                if (!_presenting.value) {
                    delay(200)
                    continue
                }
                val png = runCatching { client.callToolImage("screenshot", client.serialArg(serial)) }.getOrNull()
                if (png != null) {
                    val frame = png.toMirrorFrame(++frameNumber)
                    _frames.tryEmit(frame)
                    _session.value = _session.value?.copy(width = frame.width, height = frame.height)
                    framesThisSecond++
                }
                val now = System.nanoTime()
                if (now - lastFpsTick >= 1_000_000_000L) {
                    val fps = framesThisSecond.toFloat()
                    _session.value = _session.value?.copy(
                        stats = MirrorStats(displayedFps = fps, decodedFps = fps, framesPresented = frameNumber),
                        width = _session.value?.width ?: 0,
                        height = _session.value?.height ?: 0,
                    )
                    framesThisSecond = 0
                    lastFpsTick = now
                }
                delay(100)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun ByteArray.toMirrorFrame(frameNumber: Long): MirrorFrame {
        val image = ImageIO.read(ByteArrayInputStream(this))
            ?: return MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()), frameNumber)
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return MirrorFrame(image.width, image.height, pixels, frameNumber)
    }
}

/** Polls `logcat_snapshot` on the remote andyd host. */
class RemoteMcpLogcatService(
    private val client: AndydMcpClient,
    private val scope: CoroutineScope,
) : LogcatService {
    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> =
        kotlinx.coroutines.flow.channelFlow {
            var lastKeys = emptySet<String>()
            while (isActive) {
                val batch = snapshot(serial, filter, 200)
                val fresh = batch.filter { entryKey(it) !in lastKeys }
                if (fresh.isNotEmpty()) {
                    lastKeys = (lastKeys + fresh.map(::entryKey)).toList().takeLast(2_000).toSet()
                    send(fresh)
                }
                delay(500)
            }
        }

    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int): List<LogcatEntry> {
        val minLevel = filter.levels.minByOrNull { it.ordinal } ?: LogLevel.Debug
        val args = buildJsonObject {
            put("serial", serial)
            put("limit", limit)
            put("search", filter.search)
            put("level", minLevel.name.lowercase())
        }
        val text = client.callToolText("logcat_snapshot", args)
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val level = runCatching {
                LogLevel.valueOf(obj["level"]?.jsonPrimitive?.contentOrNull ?: "Info")
            }.getOrDefault(LogLevel.Info)
            if (level !in filter.levels) return@mapNotNull null
            LogcatEntry(
                time = obj["time"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                pid = obj["pid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                tid = obj["tid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                level = level,
                tag = obj["tag"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    override suspend fun clear(serial: String) {
        client.shell(serial, listOf("logcat", "-c"))
    }

    private fun entryKey(entry: LogcatEntry): String =
        "${entry.time}|${entry.pid}|${entry.tid}|${entry.tag}|${entry.message}"
}

class RemoteMcpAvdService(
    private val client: AndydMcpClient,
) : AvdService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listSystemImages(): List<SystemImage> {
        val text = client.callToolText("list_system_images")
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el.jsonObject
            SystemImage(
                packageId = obj["packageId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                api = obj["api"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                variant = obj["variant"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                abi = obj["abi"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                installed = obj["installed"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    override suspend fun listProfiles(): List<AvdProfile> = emptyList()

    override suspend fun listVirtualDevices(): List<VirtualDevice> {
        val text = client.callToolText("list_avds")
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el.jsonObject
            VirtualDevice(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                path = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                target = obj["target"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                abi = obj["abi"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                running = obj["running"]?.jsonPrimitive?.booleanOrNull ?: false,
                apiLevel = obj["apiLevel"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                deviceType = runCatching {
                    VirtualDeviceType.valueOf(obj["deviceType"]?.jsonPrimitive?.contentOrNull ?: "Unknown")
                }.getOrDefault(VirtualDeviceType.Unknown),
            )
        }
    }

    override suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "create_avd",
                mapOf(
                    "name" to JsonPrimitive(name),
                    "profileId" to JsonPrimitive(profileId),
                    "systemImagePackage" to JsonPrimitive(systemImagePackage),
                ),
            ),
        )

    override suspend fun createVirtualDevice(config: AvdCreationConfig): CommandResult =
        createVirtualDevice(config.name, config.profileId, config.systemImagePackage)

    override suspend fun startVirtualDevice(name: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText("start_emulator", mapOf("name" to JsonPrimitive(name))),
        )

    override suspend fun coldBootVirtualDevice(name: String): CommandResult = startVirtualDevice(name)

    override suspend fun stopVirtualDevice(name: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText("stop_emulator", mapOf("name" to JsonPrimitive(name))),
        )

    override suspend fun wipeVirtualDevice(name: String): CommandResult =
        CommandResult.failure("Wipe AVD is unavailable over remote MCP")

    override suspend fun deleteVirtualDevice(name: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText("delete_avd", mapOf("name" to JsonPrimitive(name))),
        )

    override suspend fun cloneVirtualDevice(sourceName: String, newName: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "clone_avd",
                mapOf("sourceName" to JsonPrimitive(sourceName), "newName" to JsonPrimitive(newName)),
            ),
        )

    override suspend fun installSystemImage(packageId: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText("install_system_image", mapOf("packageId" to JsonPrimitive(packageId))),
        )

    override suspend fun uninstallSystemImage(packageId: String): CommandResult =
        CommandResult.failure("Uninstall system image is unavailable over remote MCP")

    override suspend fun listSnapshots(avdName: String): List<EmulatorSnapshot> {
        val text = client.callToolText("list_snapshots", mapOf("avdName" to JsonPrimitive(avdName)))
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: el.jsonPrimitive.contentOrNull
            name?.let { EmulatorSnapshot(name = it, avdName = avdName) }
        }
    }

    override suspend fun saveSnapshot(avdName: String, snapshotName: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "save_snapshot",
                mapOf("avdName" to JsonPrimitive(avdName), "snapshotName" to JsonPrimitive(snapshotName)),
            ),
        )

    override suspend fun restoreSnapshot(avdName: String, snapshotName: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "load_snapshot",
                mapOf("avdName" to JsonPrimitive(avdName), "snapshotName" to JsonPrimitive(snapshotName)),
            ),
        )

    override suspend fun deleteSnapshot(avdName: String, snapshotName: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "delete_snapshot",
                mapOf("avdName" to JsonPrimitive(avdName), "snapshotName" to JsonPrimitive(snapshotName)),
            ),
        )

    override suspend fun renameSnapshot(avdName: String, oldName: String, newName: String): CommandResult =
        CommandResult.failure("Rename snapshot is unavailable over remote MCP")
}

class RemoteMcpAppService(
    private val client: AndydMcpClient,
) : AppService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listApps(serial: String): List<AndroidApp> {
        val text = client.callToolText("list_apps", client.serialArg(serial))
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el.jsonObject
            AndroidApp(
                packageName = obj["packageName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                system = obj["system"]?.jsonPrimitive?.booleanOrNull ?: false,
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                versionName = obj["versionName"]?.jsonPrimitive?.contentOrNull,
                versionCode = obj["versionCode"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override suspend fun focusedPackage(serial: String): String? = null

    override suspend fun getAppDetails(serial: String, packageName: String): AndroidAppDetails =
        AndroidAppDetails()

    override suspend fun launch(serial: String, packageName: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "launch_app",
                client.serialArg(serial) + mapOf("packageName" to JsonPrimitive(packageName)),
            ),
        )

    override suspend fun launchActivity(serial: String, packageName: String, activityName: String): CommandResult =
        CommandResult.failure("Launch activity is unavailable over remote MCP")

    override suspend fun stop(serial: String, packageName: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "stop_app",
                client.serialArg(serial) + mapOf("packageName" to JsonPrimitive(packageName)),
            ),
        )

    override suspend fun clearData(serial: String, packageName: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "clear_app_data",
                client.serialArg(serial) + mapOf("packageName" to JsonPrimitive(packageName)),
            ),
        )

    override suspend fun resetPermissions(serial: String, packageName: String): CommandResult =
        CommandResult.failure("Reset permissions is unavailable over remote MCP")

    override suspend fun uninstall(serial: String, packageName: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "uninstall_app",
                client.serialArg(serial) + mapOf("packageName" to JsonPrimitive(packageName)),
            ),
        )

    override suspend fun install(serial: String, apkPath: String, replace: Boolean): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "install_app",
                client.serialArg(serial) + mapOf(
                    "apkPath" to JsonPrimitive(apkPath),
                    "replace" to JsonPrimitive(replace),
                ),
            ),
        )

    override suspend fun listPermissions(serial: String, packageName: String): List<AndroidPermission> {
        val text = client.callToolText(
            "list_permissions",
            client.serialArg(serial) + mapOf("packageName" to JsonPrimitive(packageName)),
        )
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el.jsonObject
            AndroidPermission(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                granted = obj["granted"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    override suspend fun listActivities(serial: String, packageName: String): List<AndroidActivity> {
        val text = client.callToolText(
            "list_activities",
            client.serialArg(serial) + mapOf("packageName" to JsonPrimitive(packageName)),
        )
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el.jsonObject
            AndroidActivity(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                exported = obj["exported"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    override suspend fun getIcon(serial: String, packageName: String): ByteArray? = null
}

class RemoteMcpFileService(
    private val client: AndydMcpClient,
) : FileService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun list(serial: String, path: String): List<DeviceFile> {
        val text = client.callToolText(
            "file_list_dir",
            client.serialArg(serial) + mapOf("path" to JsonPrimitive(path)),
        )
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el.jsonObject
            DeviceFile(
                path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                isDirectory = obj["isDirectory"]?.jsonPrimitive?.booleanOrNull ?: false,
                sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                permissions = obj["permissions"]?.jsonPrimitive?.contentOrNull,
                modified = obj["modified"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override suspend fun pull(serial: String, remotePath: String, localPath: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "file_pull",
                client.serialArg(serial) + mapOf(
                    "remotePath" to JsonPrimitive(remotePath),
                    "localPath" to JsonPrimitive(localPath),
                ),
            ),
        )

    override suspend fun push(serial: String, localPath: String, remotePath: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "file_push",
                client.serialArg(serial) + mapOf(
                    "localPath" to JsonPrimitive(localPath),
                    "remotePath" to JsonPrimitive(remotePath),
                ),
            ),
        )

    override suspend fun delete(serial: String, remotePath: String): CommandResult =
        AndydMcpClient.parseCommandToolResult(
            client.callToolText(
                "file_delete",
                client.serialArg(serial) + mapOf("remotePath" to JsonPrimitive(remotePath)),
            ),
        )
}

class RemoteMcpIntentService(
    private val client: AndydMcpClient,
) : IntentService {
    override fun buildCommand(draft: IntentDraft): List<String> = emptyList()

    override suspend fun send(serial: String, draft: IntentDraft): CommandResult {
        val args = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("serial", JsonPrimitive(serial))
            if (draft.action.isNotBlank()) put("action", JsonPrimitive(draft.action))
            if (draft.dataUri.isNotBlank()) put("dataUri", JsonPrimitive(draft.dataUri))
            if (draft.component.isNotBlank()) put("component", JsonPrimitive(draft.component))
            put("mode", JsonPrimitive(draft.mode.name.lowercase()))
        }
        return AndydMcpClient.parseCommandToolResult(client.callToolText("send_intent", args))
    }
}

/** Bundle of MCP-backed Android services for one tunneled andyd socket. */
class RemoteAndyStack(
    val client: AndydMcpClient,
    val devices: RemoteMcpDeviceService,
    val mirror: RemoteMcpMirrorEngine,
    val logcat: RemoteMcpLogcatService,
    val avd: RemoteMcpAvdService,
    val apps: RemoteMcpAppService,
    val files: RemoteMcpFileService,
    val intents: RemoteMcpIntentService,
) {
    companion object {
        fun create(socketPath: java.io.File, scope: CoroutineScope): RemoteAndyStack {
            val client = AndydMcpClient(socketPath)
            return RemoteAndyStack(
                client = client,
                devices = RemoteMcpDeviceService(client),
                mirror = RemoteMcpMirrorEngine(client, scope),
                logcat = RemoteMcpLogcatService(client, scope),
                avd = RemoteMcpAvdService(client),
                apps = RemoteMcpAppService(client),
                files = RemoteMcpFileService(client),
                intents = RemoteMcpIntentService(client),
            )
        }
    }
}
