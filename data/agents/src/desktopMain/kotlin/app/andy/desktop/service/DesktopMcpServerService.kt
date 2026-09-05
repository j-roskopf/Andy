package app.andy.desktop.service

import app.andy.domain.parseBounds
import app.andy.model.*
import app.andy.service.*
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.sse.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.websocket.WebSockets
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64
import app.andy.service.AgentRunService
import app.andy.service.ProjectWorkflowService
import app.andy.desktop.service.proxy.resolveNetworkAccessHosts
import app.andy.desktop.service.webchat.NetworkAccessSessionStore
import app.andy.desktop.service.webchat.AuthFailureLimiter
import app.andy.desktop.service.webchat.NetworkAccessAuthPlugin
import app.andy.desktop.service.webchat.NetworkAccessWebConfig
import app.andy.desktop.service.webchat.WebPushService
import app.andy.desktop.service.webchat.generateNetworkAccessTokenBytes
import app.andy.desktop.service.webchat.installNetworkAccessSecurityHeaders
import app.andy.desktop.service.webchat.installWebChatRoutes
import app.andy.desktop.service.webchat.remotePeerAddress
import app.andy.desktop.service.webchat.resolveHost
import app.andy.desktop.service.webchat.toNetworkAccessBindConfig

class DesktopMcpServerService(
    private val devices: DeviceService,
    private val emulatorControls: EmulatorControls,
    private val avd: AvdService,
    private val mirror: MirrorEngine,
    private val logcat: LogcatService,
    private val intents: IntentService,
    private val apps: AppService,
    private val files: FileService,
    private val proxy: ProxyService,
    private val accessibility: AccessibilityService,
    private val viewHierarchy: ViewHierarchyService = UnavailableViewHierarchyService,
    private val workspaceStore: WorkspaceStore,
    private val metrics: MetricsService = UnavailableMetricsService,
    private val crashInspector: CrashInspectorService = UnavailableCrashInspectorService,
    private val heapDump: HeapDumpService = UnavailableHeapDumpService,
    private val bugs: BugService = UnavailableBugService,
    private val recordingExport: RecordingExportService = UnavailableRecordingExportService,
    private val webPush: WebPushService = WebPushService(workspaceStore),
    private val actionConfig: ActionConfigStore? = null,
) : McpServerService {
    override val status = MutableStateFlow("stopped")
    override val running = MutableStateFlow(false)

    private var serverEngine: EmbeddedServer<*, *>? = null
    private var runningPort: Int? = null
    private var runningHost: String? = null
    private val httpLock = Any()
    private val networkAccessSessions = NetworkAccessSessionStore()
    private val networkAccessLoginLimiter = AuthFailureLimiter(
        maxFailures = 5,
        windowMillis = 60_000L,
        cooldownMillis = 5 * 60_000L,
        clock = { System.currentTimeMillis() },
    )
    private var lastNetworkAccessMasterToken: String? = null
    private var unixSocketServer: McpUnixSocketServer? = null
    private var agentRuns: AgentRunService? = null
    private var projectWorkflows: ProjectWorkflowService? = null
    private var automations: AutomationService? = null

    /**
     * Test-only: force Network Access peer classification (e.g. `"203.0.113.10"`)
     * so integration tests can exercise the non-loopback auth path over loopback sockets.
     */
    internal var authPeerAddressOverride: String? = null

    /** Wire agent/project services so MCP tools can control chats (daemon / embedded). */
    fun bindAgentServices(
        agents: AgentRunService,
        projects: ProjectWorkflowService,
        automations: AutomationService = UnavailableAutomationService,
    ) {
        agentRuns = agents
        projectWorkflows = projects
        this.automations = automations
        webPush.startWatching(agents)
    }

    override fun suggestNetworkAccessHosts(): List<String> {
        val workspace = runCatching { kotlinx.coroutines.runBlocking { workspaceStore.load() } }
            .getOrElse { WorkspaceState() }
        if (!workspace.networkAccessEnabled || !workspace.networkAccessTailscaleOnly) {
            return resolveNetworkAccessHosts()
        }
        // Tailscale-only mode binds loopback — nothing listens on the Tailscale/LAN
        // interface directly. Remote reach is only via `tailscale serve` (forwards to
        // 127.0.0.1 here), so that's the only host actually reachable from this Mac.
        return listOf("127.0.0.1")
    }

    override fun generateNetworkAccessToken(): String = generateNetworkAccessTokenBytes()

    override fun createNetworkLoginCode(): String {
        if (serverEngine == null) return ""
        return networkAccessSessions.createLoginCode()
    }

    /** Clears chat sessions and login codes (e.g. after master token rotation). */
    override fun invalidateNetworkAccessSessions() {
        networkAccessSessions.clearAll()
    }

    fun startUnixSocketBlocking(socketPath: File): CommandResult {
        return try {
            unixSocketServer?.stopBlocking()
            val server = McpUnixSocketServer(socketPath) { createMcpServer() }
            server.startBlocking()
            unixSocketServer = server
            check(socketPath.exists()) {
                "unix socket missing after start: ${socketPath.absolutePath}"
            }
            if (!running.value) {
                status.value = "running on unix:${socketPath.absolutePath}"
                running.value = true
            } else {
                status.value = "${status.value}; unix:${socketPath.absolutePath}"
            }
            CommandResult.success("Unix MCP socket at ${socketPath.absolutePath}")
        } catch (error: Exception) {
            error.printStackTrace()
            CommandResult.failure("Failed to start unix MCP socket: ${error.message}")
        }
    }

    suspend fun startUnixSocket(socketPath: File): CommandResult =
        withContext(Dispatchers.IO) { startUnixSocketBlocking(socketPath) }

    fun stopUnixSocketBlocking(): CommandResult {
        return try {
            unixSocketServer?.stopBlocking()
            unixSocketServer = null
            CommandResult.success("Unix MCP socket stopped")
        } catch (error: Exception) {
            CommandResult.failure(error.message ?: "stop unix socket failed")
        }
    }

    suspend fun stopUnixSocket(): CommandResult =
        withContext(Dispatchers.IO) { stopUnixSocketBlocking() }

    /**
     * Blocking HTTP bind — safe from [AndydMain] / JavaExec where nested
     * `runBlocking` + [Dispatchers.IO] can hang indefinitely.
     */
    fun startHttpBlocking(port: Int): CommandResult = synchronized(httpLock) {
        val workspace = runCatching { kotlinx.coroutines.runBlocking { workspaceStore.load() } }
            .getOrElse { WorkspaceState() }
        val host = workspace.toNetworkAccessBindConfig().resolveHost()
        val masterToken = workspace.networkAccessToken
        if (masterToken != lastNetworkAccessMasterToken) {
            networkAccessSessions.clearAll()
            lastNetworkAccessMasterToken = masterToken
        }

        if (serverEngine != null) {
            if (runningPort == port && runningHost == host) {
                return CommandResult.success("Already running")
            }
            stopEngine()
        }

        if (!isPortAvailable(port, host)) {
            status.value = "error: port $port already in use"
            running.value = false
            return CommandResult.failure("Port $port is already in use")
        }

        try {
            status.value = "starting..."
            System.err.println("andy-mcp: creating Netty engine on $host:$port")

            val engine = embeddedServer(Netty, host = host, port = port) {
                install(DoubleReceive)
                install(WebSockets)
                installNetworkAccessSecurityHeaders()
                install(NetworkAccessAuthPlugin) {
                    sessionStore = networkAccessSessions
                    tokenProvider = {
                        runCatching { kotlinx.coroutines.runBlocking { workspaceStore.load() } }
                            .getOrElse { WorkspaceState() }
                            .networkAccessToken
                    }
                    networkAccessEnabledProvider = {
                        runCatching { kotlinx.coroutines.runBlocking { workspaceStore.load() } }
                            .getOrElse { WorkspaceState() }
                            .networkAccessEnabled
                    }
                    tailscaleOnlyProvider = {
                        runCatching { kotlinx.coroutines.runBlocking { workspaceStore.load() } }
                            .getOrElse { WorkspaceState() }
                            .networkAccessTailscaleOnly
                    }
                    peerAddressResolver = { call ->
                        authPeerAddressOverride ?: call.remotePeerAddress()
                    }
                }
                mcpStreamableHttp("/mcp-http", enableDnsRebindingProtection = true) {
                    createMcpServer(callerTaskId = call.request.queryParameters["andyTaskId"])
                }
                val webConfig = NetworkAccessWebConfig(
                    sessionStore = networkAccessSessions,
                    loginLimiter = networkAccessLoginLimiter,
                    masterTokenProvider = {
                        runCatching { kotlinx.coroutines.runBlocking { workspaceStore.load() } }
                            .getOrElse { WorkspaceState() }
                            .networkAccessToken
                    },
                )
                installWebChatRoutes(
                    agentRuns = { agentRuns },
                    projectWorkflows = { projectWorkflows },
                    actionConfig = { actionConfig },
                    push = webPush,
                    networkAccess = webConfig,
                )
                routing {
                    mcp("/mcp", enableDnsRebindingProtection = true) {
                        createMcpServer(callerTaskId = call.request.queryParameters["andyTaskId"])
                    }
                }
            }

            serverEngine = engine
            System.err.println("andy-mcp: engine.start(wait=false)…")
            engine.start(wait = false)
            runningPort = port
            runningHost = host

            status.value = "running on $host:$port"
            running.value = true
            System.err.println("andy-mcp: HTTP listening on $host:$port")
            CommandResult.success("Server started on $host:$port")
        } catch (e: Exception) {
            e.printStackTrace()
            status.value = "error: ${e.message ?: "start failed"}"
            running.value = false
            serverEngine = null
            runningPort = null
            runningHost = null
            CommandResult.failure("Failed to start server: ${e.message}")
        }
    }

    override suspend fun start(port: Int): CommandResult =
        withContext(Dispatchers.IO) { startHttpBlocking(port) }

    override suspend fun stop(): CommandResult = withContext(Dispatchers.IO) {
        synchronized(httpLock) {
            stopEngine()
            CommandResult.success("Server stopped")
        }
    }

    private fun stopEngine() {
        val engine = serverEngine
        if (engine != null) {
            try {
                engine.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            serverEngine = null
        }
        runningPort = null
        runningHost = null
        status.value = "stopped"
        running.value = false
    }

    override fun getSnippet(clientName: String, port: Int): String {
        val client = McpClientConfig.ClientType.entries.firstOrNull { it.label == clientName } ?: McpClientConfig.ClientType.ClaudeCode
        val workspace = runCatching { kotlinx.coroutines.runBlocking { workspaceStore.load() } }
            .getOrElse { WorkspaceState() }
        val bearer = workspace.takeIf { it.networkAccessEnabled }
            ?.networkAccessToken?.trim()?.takeIf { it.isNotEmpty() }
        return McpClientConfig.getSnippet(client, port, bearerToken = bearer)
    }

    override fun getClients(): List<String> {
        return McpClientConfig.ClientType.entries.map { it.label }
    }

    override fun isAutoWriteSupported(clientName: String): Boolean {
        val client = McpClientConfig.ClientType.entries.firstOrNull { it.label == clientName } ?: return false
        return client in listOf(
            McpClientConfig.ClientType.ClaudeCode,
            McpClientConfig.ClientType.Cursor,
            McpClientConfig.ClientType.Codex,
            McpClientConfig.ClientType.ClaudeDesktop,
            McpClientConfig.ClientType.Antigravity,
            McpClientConfig.ClientType.OpenCode,
            McpClientConfig.ClientType.Pi,
            McpClientConfig.ClientType.Hermes,
            McpClientConfig.ClientType.OpenClaw,
            McpClientConfig.ClientType.Goose,
        )
    }

    override fun writeConfig(clientName: String, port: Int): Boolean {
        val client = McpClientConfig.ClientType.entries.firstOrNull { it.label == clientName } ?: return false
        val workspace = runCatching { kotlinx.coroutines.runBlocking { workspaceStore.load() } }
            .getOrElse { WorkspaceState() }
        val bearer = workspace.takeIf { it.networkAccessEnabled }
            ?.networkAccessToken?.trim()?.takeIf { it.isNotEmpty() }
        return McpClientConfig.writeConfig(client, port, bearerToken = bearer)
    }

    override fun getToolNames(): List<String> = listOf(
        "list_devices", "shell", "list_avds", "list_system_images",
        "create_avd", "clone_avd", "delete_avd", "start_emulator", "stop_emulator",
        "install_system_image", "tap", "swipe", "input_text", "press_key",
        "screenshot", "ui_dump", "list_apps", "launch_app", "stop_app",
        "clear_app_data", "uninstall_app", "install_app", "list_permissions", "list_activities",
        "send_intent", "file_list_dir", "file_pull", "file_push", "file_delete",
        "start_network_proxy", "list_network_mock_rules", "upsert_network_mock_rule",
        "set_network_mock_rules", "delete_network_mock_rule", "stop_network_proxy",
        "clear_network_requests", "list_network_requests", "get_network_request",
        "configure_device_proxy", "save_snapshot", "load_snapshot", "delete_snapshot",
        "list_snapshots", "logcat_snapshot",
        "list_crashes", "get_crash",
        "capture_heap_dump", "get_memory_breakdown", "get_battery_stats",
        "start_screen_recording", "stop_screen_recording", "export_recording",
        "screenshot_host",
    ) + agentProjectToolNames()

    private fun createMcpServer(callerTaskId: String? = null): Server {
        val mcpServer = Server(
            serverInfo = Implementation("andy", "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        registerTools(mcpServer)
        val agents = agentRuns
        val projects = projectWorkflows
        if (agents != null && projects != null) {
            mcpServer.registerAgentProjectTools(
                agents,
                projects,
                callerTaskId = callerTaskId?.takeIf { it.isNotBlank() },
                automations = automations ?: UnavailableAutomationService,
            )
        }
        return mcpServer
    }

    private suspend fun resolveSerial(explicit: String?): String {
        if (!explicit.isNullOrBlank()) return explicit

        val state = workspaceStore.load()
        val savedSerial = state.selectedDeviceSerial

        val onlineDevices = devices.listDevices().filter { it.state == DeviceConnectionState.Online }

        if (!savedSerial.isNullOrBlank() && onlineDevices.any { it.serial == savedSerial }) {
            return savedSerial
        }

        if (onlineDevices.size == 1) {
            return onlineDevices.first().serial
        }

        val serialsStr = onlineDevices.joinToString(", ") { "${it.serial} (${it.model ?: "unknown"})" }
        if (onlineDevices.isEmpty()) {
            throw IllegalArgumentException("No online Android devices found. Please launch an emulator or connect a physical device.")
        }
        throw IllegalArgumentException("Multiple devices available or no selected device. Please specify 'serial'. Available: [$serialsStr]")
    }

    private fun registerTools(mcpServer: Server) {
        mcpServer.registerTool("list_devices", "List all connected Android emulators and physical devices") { args ->
            val list = devices.listDevices()
            val json = buildJsonArray {
                list.forEach { dev ->
                    add(buildJsonObject {
                        put("serial", dev.serial)
                        put("displayName", dev.displayName)
                        put("kind", dev.kind.name)
                        put("state", dev.state.name)
                        put("apiLevel", dev.apiLevel)
                        put("abi", dev.abi)
                        put("model", dev.model)
                        put("product", dev.product)
                        put("batteryPercent", dev.batteryPercent)
                        put("screenSize", dev.screenSize)
                        put("storageSummary", dev.storageSummary)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "shell",
            "Run a shell command on the specified Android device or emulator",
            mapOf(
                "command" to stringProp("The shell command to run"),
                "serial" to stringProp("Optional serial number of the target device")
            ),
            listOf("command")
        ) { args ->
            val command = args["command"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("command is required")
            val serial = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = devices.shell(serial, listOf(command))
            CallToolResult(
                content = listOf(
                    TextContent(
                        text = "Exit Code: ${result.exitCode}\nStdout:\n${result.stdout}\nStderr:\n${result.stderr}"
                    )
                ),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool("list_avds", "List all configured Android Virtual Devices (AVDs)") { args ->
            val list = avd.listVirtualDevices()
            val json = buildJsonArray {
                list.forEach { item ->
                    add(buildJsonObject {
                        put("name", item.name)
                        put("path", item.path)
                        put("target", item.target)
                        put("abi", item.abi)
                        put("running", item.running)
                        put("apiLevel", item.apiLevel)
                        put("deviceType", item.deviceType.name)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool("list_system_images", "List available and installed emulator system images") { args ->
            val list = avd.listSystemImages()
            val json = buildJsonArray {
                list.forEach { item ->
                    add(buildJsonObject {
                        put("packageId", item.packageId)
                        put("api", item.api)
                        put("variant", item.variant)
                        put("abi", item.abi)
                        put("displayName", item.displayName)
                        put("installed", item.installed)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "create_avd",
            "Create a new Android Virtual Device (AVD)",
            mapOf(
                "name" to stringProp("Name of the AVD"),
                "profileId" to stringProp("Profile ID (e.g. pixel_5)"),
                "systemImagePackage" to stringProp("System image package ID (e.g. system-images;android-34;google_apis;arm64-v8a)")
            ),
            listOf("name", "profileId", "systemImagePackage")
        ) { args ->
            val name = args["name"]?.jsonPrimitive?.content ?: ""
            val profileId = args["profileId"]?.jsonPrimitive?.content ?: ""
            val systemImagePackage = args["systemImagePackage"]?.jsonPrimitive?.content ?: ""
            val result = avd.createVirtualDevice(name, profileId, systemImagePackage)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "clone_avd",
            "Clone an existing Android Virtual Device (AVD)",
            mapOf(
                "sourceName" to stringProp("Name of the source AVD"),
                "newName" to stringProp("Name of the new AVD")
            ),
            listOf("sourceName", "newName")
        ) { args ->
            val sourceName = args["sourceName"]?.jsonPrimitive?.content ?: ""
            val newName = args["newName"]?.jsonPrimitive?.content ?: ""
            val result = avd.cloneVirtualDevice(sourceName, newName)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "delete_avd",
            "Delete an Android Virtual Device (AVD)",
            mapOf("name" to stringProp("Name of the AVD to delete")),
            listOf("name")
        ) { args ->
            val name = args["name"]?.jsonPrimitive?.content ?: ""
            val result = avd.deleteVirtualDevice(name)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "start_emulator",
            "Start a configured emulator by name",
            mapOf("name" to stringProp("Name of the AVD to start")),
            listOf("name")
        ) { args ->
            val name = args["name"]?.jsonPrimitive?.content ?: ""
            val result = avd.startVirtualDevice(name)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "stop_emulator",
            "Stop a running emulator by name",
            mapOf("name" to stringProp("Name of the emulator AVD to stop")),
            listOf("name")
        ) { args ->
            val name = args["name"]?.jsonPrimitive?.content ?: ""
            val result = avd.stopVirtualDevice(name)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "install_system_image",
            "Install a new system image package using sdkmanager",
            mapOf("packageId" to stringProp("Package ID (e.g. system-images;android-34;google_apis;arm64-v8a)")),
            listOf("packageId")
        ) { args ->
            val packageId = args["packageId"]?.jsonPrimitive?.content ?: ""
            val result = avd.installSystemImage(packageId)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "tap",
            "Tap the screen at the specified coordinates",
            mapOf(
                "x" to intProp("X coordinate"),
                "y" to intProp("Y coordinate"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("x", "y")
        ) { args ->
            val x = args["x"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("x is required")
            val y = args["y"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("y is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = devices.shell(resolved, listOf("input", "tap", x.toString(), y.toString()))
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "swipe",
            "Swipe on the screen from start to end coordinates",
            mapOf(
                "startX" to intProp("Start X coordinate"),
                "startY" to intProp("Start Y coordinate"),
                "endX" to intProp("End X coordinate"),
                "endY" to intProp("End Y coordinate"),
                "durationMillis" to intProp("Duration in milliseconds"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("startX", "startY", "endX", "endY", "durationMillis")
        ) { args ->
            val startX = args["startX"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("startX is required")
            val startY = args["startY"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("startY is required")
            val endX = args["endX"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("endX is required")
            val endY = args["endY"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("endY is required")
            val duration = args["durationMillis"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("durationMillis is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = devices.shell(resolved, listOf("input", "swipe", startX.toString(), startY.toString(), endX.toString(), endY.toString(), duration.toString()))
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "input_text",
            "Input text into the active focused element",
            mapOf(
                "text" to stringProp("Text to type (spaces will be automatically replaced with %s)"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("text")
        ) { args ->
            val text = args["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("text is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = devices.shell(resolved, listOf("input", "text", text.replace(" ", "%s")))
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "press_key",
            "Press a key event on the target device (back, home, recents, power, or integer code)",
            mapOf(
                "key" to stringProp("Key name (back, home, recents, power) or an integer keycode (e.g. 26 for power)"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("key")
        ) { args ->
            val key = args["key"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("key is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val code = when (key.lowercase()) {
                "back" -> "4"
                "home" -> "3"
                "recents" -> "187"
                "power" -> "26"
                else -> key.toIntOrNull()?.toString() ?: throw IllegalArgumentException("Invalid key. Expected back, home, recents, power, or an integer keycode.")
            }
            val result = devices.shell(resolved, listOf("input", "keyevent", code))
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "screenshot",
            "Take a screenshot of the specified device (returns base64 PNG)",
            mapOf("serial" to stringProp("Optional target device serial"))
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val bytes = mirror.screenshot(resolved) ?: throw RuntimeException("Screenshot failed")
            val base64 = Base64.getEncoder().encodeToString(bytes)
            CallToolResult(
                content = listOf(ImageContent(data = base64, mimeType = "image/png"))
            )
        }

        mcpServer.registerTool(
            "ui_dump",
            "Dump the accessibility node tree from the active window as JSON",
            mapOf("serial" to stringProp("Optional target device serial"))
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val rootNode = accessibility.dump(resolved)
            if (rootNode == null) {
                CallToolResult(
                    content = listOf(TextContent(text = "No accessibility dump available")),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(TextContent(text = mapNode(rootNode).toString()))
                )
            }
        }

        mcpServer.registerTool(
            "capture_view_hierarchy",
            "Captures the view hierarchy: uiautomator dump merged with dumpsys activity top's " +
                "unmerged view tree (view classes/ids Compose collapses out of the accessibility " +
                "tree), by bounds + class name. Tiers 1-2 only — no composable names, modifier " +
                "chains, or recomposition counts; those need an on-device JVMTI agent Andy does " +
                "not have. Depth/node-capped JSON to avoid blowing up agent context.",
            mapOf(
                "serial" to stringProp("Optional target device serial"),
                "maxDepth" to intProp("Maximum tree depth to return (default 12)"),
                "maxNodes" to intProp("Maximum number of nodes to return, depth-first (default 400)"),
                "includeInvisible" to boolProp("Include nodes not visible to the user (default false)"),
                "unmergedSemantics" to boolProp("Return the raw dumpsys activity top view tree instead of the uiautomator-merged tree (default false)"),
                "compressed" to boolProp("Use uiautomator dump --compressed: faster, drops non-interesting nodes (default false)"),
            ),
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val options = HierarchyOptions(
                includeInvisible = args["includeInvisible"]?.jsonPrimitive?.booleanOrNull ?: false,
                unmergedSemantics = args["unmergedSemantics"]?.jsonPrimitive?.booleanOrNull ?: false,
                compressed = args["compressed"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            val maxDepth = args["maxDepth"]?.jsonPrimitive?.intOrNull ?: 12
            val maxNodes = args["maxNodes"]?.jsonPrimitive?.intOrNull ?: 400
            viewHierarchy.capture(resolved, options).fold(
                onSuccess = { snapshot ->
                    val counter = intArrayOf(0)
                    val json = buildJsonObject {
                        put("source", snapshot.source.name)
                        put("displayWidth", snapshot.displayWidth)
                        put("displayHeight", snapshot.displayHeight)
                        put("capturedAtMillis", snapshot.capturedAtMillis)
                        put("root", mapHierarchyNode(snapshot.root, maxDepth, maxNodes, counter))
                        if (counter[0] >= maxNodes) put("truncated", true)
                    }
                    CallToolResult(content = listOf(TextContent(text = json.toString())))
                },
                onFailure = { error ->
                    CallToolResult(content = listOf(TextContent(text = error.message ?: "Capture failed")), isError = true)
                },
            )
        }

        mcpServer.registerTool(
            "find_node_by_text",
            "Finds view-hierarchy nodes whose text, content-description, or resource-id contains " +
                "a query (case-insensitive). Returns bounds and a tap-center point for the `tap` tool.",
            mapOf(
                "query" to stringProp("Text to search for in text, content-description, or resource-id"),
                "serial" to stringProp("Optional target device serial"),
                "maxResults" to intProp("Maximum number of matches to return (default 10)"),
            ),
            listOf("query"),
        ) { args ->
            val query = args["query"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("query is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val maxResults = args["maxResults"]?.jsonPrimitive?.intOrNull ?: 10
            viewHierarchy.capture(resolved).fold(
                onSuccess = { snapshot ->
                    fun search(node: AccessibilityNode): List<AccessibilityNode> {
                        val haystack = listOfNotNull(node.text, node.contentDescription, node.resourceId).joinToString(" ")
                        val self = if (haystack.contains(query, ignoreCase = true)) listOf(node) else emptyList()
                        return self + node.children.flatMap { search(it) }
                    }
                    val matches = search(snapshot.root).take(maxResults)
                    val json = buildJsonArray {
                        matches.forEach { node ->
                            add(buildJsonObject {
                                put("id", node.id)
                                put("className", node.className)
                                put("resourceId", node.resourceId)
                                put("text", node.text)
                                put("contentDescription", node.contentDescription)
                                put("bounds", node.bounds)
                                val bounds = parseBounds(node.bounds)
                                if (bounds != null) {
                                    put("centerX", (bounds[0] + bounds[2]) / 2)
                                    put("centerY", (bounds[1] + bounds[3]) / 2)
                                }
                            })
                        }
                    }
                    CallToolResult(content = listOf(TextContent(text = json.toString())), isError = matches.isEmpty())
                },
                onFailure = { error ->
                    CallToolResult(content = listOf(TextContent(text = error.message ?: "Capture failed")), isError = true)
                },
            )
        }

        mcpServer.registerTool(
            "get_node_properties",
            "Gets the full read-only property set (identity, geometry, state, semantics, raw " +
                "view-tree attributes) for the first view-hierarchy node matching a resource id " +
                "and/or a text/content-description query.",
            mapOf(
                "resourceId" to stringProp("Resource id to match, e.g. com.example.app:id/title"),
                "query" to stringProp("Text or content-description substring to match (case-insensitive) when resourceId is omitted or ambiguous"),
                "serial" to stringProp("Optional target device serial"),
            ),
        ) { args ->
            val resourceId = args["resourceId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (resourceId == null && query == null) throw IllegalArgumentException("Provide resourceId and/or query")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            viewHierarchy.capture(resolved).fold(
                onSuccess = { snapshot ->
                    fun matches(node: AccessibilityNode): Boolean {
                        val resourceOk = resourceId == null || node.resourceId == resourceId
                        val queryOk = query == null ||
                            listOfNotNull(node.text, node.contentDescription).any { it.contains(query, ignoreCase = true) }
                        return resourceOk && queryOk
                    }
                    fun find(node: AccessibilityNode): AccessibilityNode? {
                        if (matches(node)) return node
                        for (child in node.children) find(child)?.let { return it }
                        return null
                    }
                    val node = find(snapshot.root)
                    if (node == null) {
                        CallToolResult(content = listOf(TextContent(text = "No matching node found")), isError = true)
                    } else {
                        val json = buildJsonObject {
                            put("id", node.id)
                            putJsonObject("identity") {
                                put("className", node.className)
                                put("resourceId", node.resourceId)
                                put("packageName", node.packageName)
                            }
                            putJsonObject("geometry") {
                                put("bounds", node.bounds)
                            }
                            putJsonObject("state") {
                                put("clickable", node.clickable)
                                put("longClickable", node.longClickable)
                                put("focusable", node.focusable)
                                put("focused", node.focused)
                                put("enabled", node.enabled)
                                put("selected", node.selected)
                                put("checkable", node.checkable)
                                put("checked", node.checked)
                                put("scrollable", node.scrollable)
                                put("password", node.password)
                                put("visible", node.visible)
                            }
                            putJsonObject("semantics") {
                                put("text", node.text)
                                put("contentDescription", node.contentDescription)
                                put("hint", node.hint)
                            }
                            if (node.attributes.isNotEmpty()) {
                                putJsonObject("raw") { node.attributes.forEach { (key, value) -> put(key, value) } }
                            }
                        }
                        CallToolResult(content = listOf(TextContent(text = json.toString())))
                    }
                },
                onFailure = { error ->
                    CallToolResult(content = listOf(TextContent(text = error.message ?: "Capture failed")), isError = true)
                },
            )
        }

        mcpServer.registerTool(
            "list_apps",
            "List installed apps on the device",
            mapOf("serial" to stringProp("Optional target device serial"))
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val list = apps.listApps(resolved)
            val json = buildJsonArray {
                list.forEach { app ->
                    add(buildJsonObject {
                        put("packageName", app.packageName)
                        put("label", app.label)
                        put("system", app.system)
                        put("enabled", app.enabled)
                        put("versionName", app.versionName)
                        put("versionCode", app.versionCode)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "launch_app",
            "Launch an installed application by package name",
            mapOf(
                "packageName" to stringProp("Application package name"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("packageName")
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("packageName is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = apps.launch(resolved, packageName)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "stop_app",
            "Force stop a running application by package name",
            mapOf(
                "packageName" to stringProp("Application package name"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("packageName")
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("packageName is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = apps.stop(resolved, packageName)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "clear_app_data",
            "Clear package data and cache for an application",
            mapOf(
                "packageName" to stringProp("Application package name"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("packageName")
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("packageName is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = apps.clearData(resolved, packageName)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "uninstall_app",
            "Uninstall an application from the device",
            mapOf(
                "packageName" to stringProp("Application package name"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("packageName")
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("packageName is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = apps.uninstall(resolved, packageName)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "install_app",
            "Install an APK onto the device",
            mapOf(
                "apkPath" to stringProp("Local path to the APK file"),
                "replace" to boolProp("Replace an existing installation (adb install -r)"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("apkPath")
        ) { args ->
            val apkPath = args["apkPath"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("apkPath is required")
            val replace = args["replace"]?.jsonPrimitive?.booleanOrNull ?: false
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = apps.install(resolved, apkPath, replace)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "list_permissions",
            "List requested permissions and grant status for an application",
            mapOf(
                "packageName" to stringProp("Application package name"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("packageName")
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("packageName is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val list = apps.listPermissions(resolved, packageName)
            val json = buildJsonArray {
                list.forEach { perm ->
                    add(buildJsonObject {
                        put("name", perm.name)
                        put("granted", perm.granted)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "list_activities",
            "List activities defined in an application package",
            mapOf(
                "packageName" to stringProp("Application package name"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("packageName")
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("packageName is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val list = apps.listActivities(resolved, packageName)
            val json = buildJsonArray {
                list.forEach { act ->
                    add(buildJsonObject {
                        put("name", act.name)
                        put("exported", act.exported)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "send_intent",
            "Send an Android intent (start activity, service, or broadcast)",
            mapOf(
                "serial" to stringProp("Optional target device serial"),
                "mode" to stringProp("Intent launch mode: activity, service, broadcast (defaults to deeplink)"),
                "action" to stringProp("Intent action (defaults to android.intent.action.VIEW)"),
                "component" to stringProp("Component package/class (optional, e.g. com.example/.MainActivity)"),
                "dataUri" to stringProp("Data URI (optional, e.g. https://google.com)"),
                "categories" to arrayProp("string", "Intent categories"),
                "flags" to arrayProp("string", "Intent flags"),
                "extras" to arrayObjectProp("Extras to include: list of { key: string, type: string (string|boolean|int|long|float), value: string }")
            )
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val modeStr = args["mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
            val intentMode = when (modeStr) {
                "activity" -> IntentMode.Activity
                "service" -> IntentMode.Service
                "broadcast" -> IntentMode.Broadcast
                else -> IntentMode.DeepLink
            }
            val draft = IntentDraft(
                mode = intentMode,
                action = args["action"]?.jsonPrimitive?.contentOrNull ?: "android.intent.action.VIEW",
                component = args["component"]?.jsonPrimitive?.contentOrNull ?: "",
                dataUri = args["dataUri"]?.jsonPrimitive?.contentOrNull ?: "",
                categories = args["categories"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("android.intent.category.DEFAULT"),
                flags = args["flags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                extras = args["extras"]?.jsonArray?.map { extraObj ->
                    val obj = extraObj.jsonObject
                    val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "string"
                    val type = when (typeStr) {
                        "boolean", "booleanvalue" -> ExtraType.BooleanValue
                        "int", "intvalue" -> ExtraType.IntValue
                        "long", "longvalue" -> ExtraType.LongValue
                        "float", "floatvalue" -> ExtraType.FloatValue
                        else -> ExtraType.StringValue
                    }
                    IntentExtra(
                        key = obj["key"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = type,
                        value = obj["value"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                } ?: emptyList()
            )
            val result = intents.send(resolved, draft)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "file_list_dir",
            "List directory contents on target device",
            mapOf(
                "path" to stringProp("Remote directory path"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("path")
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("path is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val list = files.list(resolved, path)
            val json = buildJsonArray {
                list.forEach { f ->
                    add(buildJsonObject {
                        put("path", f.path)
                        put("name", f.name)
                        put("isDirectory", f.isDirectory)
                        put("sizeBytes", f.sizeBytes)
                        put("permissions", f.permissions)
                        put("modified", f.modified)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "file_pull",
            "Pull a file from target device to local machine",
            mapOf(
                "remotePath" to stringProp("Remote source file path"),
                "localPath" to stringProp("Local destination file path"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("remotePath", "localPath")
        ) { args ->
            val remotePath = args["remotePath"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("remotePath is required")
            val localPath = args["localPath"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("localPath is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = files.pull(resolved, remotePath, localPath)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "file_push",
            "Push a file from local machine to target device",
            mapOf(
                "localPath" to stringProp("Local source file path"),
                "remotePath" to stringProp("Remote destination file path"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("localPath", "remotePath")
        ) { args ->
            val localPath = args["localPath"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("localPath is required")
            val remotePath = args["remotePath"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("remotePath is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = files.push(resolved, localPath, remotePath)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "file_delete",
            "Delete a file or directory from target device",
            mapOf(
                "remotePath" to stringProp("Remote file path to delete"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("remotePath")
        ) { args ->
            val remotePath = args["remotePath"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("remotePath is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = files.delete(resolved, remotePath)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "start_network_proxy",
            "Start the HTTP/HTTPS mitmproxy interceptor on the desktop",
            mapOf(
                "port" to intProp("Local port to run proxy on"),
                "rules" to arrayObjectProp("Optional list of mock rules: list of { id, name, enabled, urlPattern, method, statusCode, setHeaders (map), removeHeaders (array), responseBody }")
            ),
            listOf("port")
        ) { args ->
            val port = args["port"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("port is required")
            val rulesJson = args["rules"]?.jsonArray
            val rules = rulesJson?.map { ruleObj ->
                proxyRuleFromJson(ruleObj.jsonObject, existing = null, requirePatternForNew = false)
            } ?: emptyList()
            val result = proxy.start(port, rules)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "list_network_mock_rules",
            "List persisted network mocking rules used by the Andy proxy",
        ) { args ->
            val state = workspaceStore.load()
            val json = buildJsonArray {
                state.proxyRules.forEach { add(proxyRuleToJson(it)) }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "upsert_network_mock_rule",
            "Add a new network mocking rule or edit an existing rule by id. Existing rules keep omitted fields; new rules require urlPattern.",
            mapOf(
                "id" to stringProp("Optional rule id. Provide an existing id to edit that rule; omit to create a new rule."),
                "name" to stringProp("Human-readable rule name"),
                "enabled" to boolProp("Whether the rule is active"),
                "urlPattern" to stringProp("URL substring or wildcard pattern. Wildcards use * and match the full URL."),
                "method" to stringProp("Optional HTTP method to match, for example GET or POST"),
                "statusCode" to intProp("Optional response status code override"),
                "setHeaders" to objectProp("Optional response headers to set, as a string map"),
                "removeHeaders" to arrayProp("string", "Optional response headers to remove"),
                "responseBody" to stringProp("Optional UTF-8 response body override")
            )
        ) { args ->
            val state = workspaceStore.load()
            val existingId = args["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val existing = existingId?.let { id -> state.proxyRules.firstOrNull { it.id == id } }
            val updatedRule = proxyRuleFromJson(
                obj = JsonObject(args),
                existing = existing,
                requirePatternForNew = existing == null
            )
            val updatedRules = if (existing == null) {
                state.proxyRules + updatedRule
            } else {
                state.proxyRules.map { if (it.id == updatedRule.id) updatedRule else it }
            }
            saveProxyRules(state, updatedRules)
            CallToolResult(content = listOf(TextContent(text = proxyRuleToJson(updatedRule).toString())))
        }

        mcpServer.registerTool(
            "set_network_mock_rules",
            "Replace all persisted network mocking rules used by the Andy proxy",
            mapOf(
                "rules" to arrayObjectProp("Rules: list of { id, name, enabled, urlPattern, method, statusCode, setHeaders, removeHeaders, responseBody }")
            ),
            listOf("rules")
        ) { args ->
            val rules = args["rules"]?.jsonArray?.map { ruleObj ->
                proxyRuleFromJson(ruleObj.jsonObject, existing = null, requirePatternForNew = true)
            } ?: throw IllegalArgumentException("rules is required")
            val state = workspaceStore.load()
            saveProxyRules(state, rules)
            val json = buildJsonObject {
                put("count", rules.size)
                putJsonArray("rules") { rules.forEach { add(proxyRuleToJson(it)) } }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "delete_network_mock_rule",
            "Delete a persisted network mocking rule by id",
            mapOf("id" to stringProp("Rule id to delete")),
            listOf("id")
        ) { args ->
            val id = args["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            val state = workspaceStore.load()
            val updatedRules = state.proxyRules.filterNot { it.id == id }
            if (updatedRules.size == state.proxyRules.size) {
                throw IllegalArgumentException("Rule not found for id: $id")
            }
            saveProxyRules(state, updatedRules)
            CallToolResult(content = listOf(TextContent(text = """{"deleted":"$id","count":${updatedRules.size}}""")))
        }

        mcpServer.registerTool("stop_network_proxy", "Stop the network proxy") { args ->
            val result = proxy.stop()
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool("clear_network_requests", "Clear recorded network requests and history") { args ->
            val result = proxy.clearTraffic()
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "list_network_requests",
            "List recorded HTTP network requests/responses",
            mapOf("limit" to intProp("Maximum requests to return (default 50)"))
        ) { args ->
            val limit = args["limit"]?.jsonPrimitive?.int ?: 50
            val list = proxy.exchanges.first().takeLast(limit)
            val json = buildJsonArray {
                list.forEach { exchange ->
                    add(buildJsonObject {
                        put("id", exchange.id)
                        put("startedAtMillis", exchange.startedAtMillis)
                        put("completedAtMillis", exchange.completedAtMillis)
                        put("method", exchange.method)
                        put("url", exchange.url)
                        put("statusCode", exchange.statusCode)
                        put("contentType", exchange.contentType)
                        put("sizeBytes", exchange.sizeBytes)
                        put("durationMillis", exchange.durationMillis)
                        put("error", exchange.error)
                        put("tlsStatus", exchange.tlsStatus)
                        put("matchedRuleId", exchange.matchedRuleId)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "get_network_request",
            "Retrieve full details of a recorded network request, including headers and payload previews",
            mapOf("id" to stringProp("ID of the request")),
            listOf("id")
        ) { args ->
            val id = args["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            val exchange = proxy.exchanges.first().firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Request not found for ID: $id")
            val json = buildJsonObject {
                put("id", exchange.id)
                put("startedAtMillis", exchange.startedAtMillis)
                put("completedAtMillis", exchange.completedAtMillis)
                put("method", exchange.method)
                put("url", exchange.url)
                put("statusCode", exchange.statusCode)
                put("contentType", exchange.contentType)
                put("sizeBytes", exchange.sizeBytes)
                put("durationMillis", exchange.durationMillis)
                put("requestHeaders", buildJsonObject {
                    exchange.requestHeaders.forEach { (k, v) -> put(k, v) }
                })
                put("responseHeaders", buildJsonObject {
                    exchange.responseHeaders.forEach { (k, v) -> put(k, v) }
                })
                put("requestBodyPreview", exchange.requestBodyPreview)
                put("responseBodyPreview", exchange.responseBodyPreview)
                put("error", exchange.error)
                put("tlsStatus", exchange.tlsStatus)
                put("matchedRuleId", exchange.matchedRuleId)
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "configure_device_proxy",
            "Configure target device to route traffic through the proxy host:port",
            mapOf(
                "host" to stringProp("Proxy host IP address (desktop local IP)"),
                "port" to intProp("Proxy port"),
                "serial" to stringProp("Optional target device serial")
            ),
            listOf("host", "port")
        ) { args ->
            val host = args["host"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("host is required")
            val port = args["port"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("port is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = proxy.configureDeviceProxy(resolved, host, port)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "save_snapshot",
            "Save a named snapshot of a running emulator",
            mapOf(
                "avdName" to stringProp("AVD name"),
                "snapshotName" to stringProp("Snapshot identifier name")
            ),
            listOf("avdName", "snapshotName")
        ) { args ->
            val avdName = args["avdName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("avdName is required")
            val snapshotName = args["snapshotName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("snapshotName is required")
            val result = avd.saveSnapshot(avdName, snapshotName)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "load_snapshot",
            "Restore a named snapshot on an emulator",
            mapOf(
                "avdName" to stringProp("AVD name"),
                "snapshotName" to stringProp("Snapshot identifier name")
            ),
            listOf("avdName", "snapshotName")
        ) { args ->
            val avdName = args["avdName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("avdName is required")
            val snapshotName = args["snapshotName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("snapshotName is required")
            val result = avd.restoreSnapshot(avdName, snapshotName)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "delete_snapshot",
            "Delete a named snapshot of an emulator",
            mapOf(
                "avdName" to stringProp("AVD name"),
                "snapshotName" to stringProp("Snapshot identifier name")
            ),
            listOf("avdName", "snapshotName")
        ) { args ->
            val avdName = args["avdName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("avdName is required")
            val snapshotName = args["snapshotName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("snapshotName is required")
            val result = avd.deleteSnapshot(avdName, snapshotName)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
        }

        mcpServer.registerTool(
            "list_snapshots",
            "List snapshots available for a specific emulator AVD",
            mapOf("avdName" to stringProp("AVD name")),
            listOf("avdName")
        ) { args ->
            val avdName = args["avdName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("avdName is required")
            val list = avd.listSnapshots(avdName)
            val json = buildJsonArray {
                list.forEach { item ->
                    add(buildJsonObject {
                        put("name", item.name)
                        put("avdName", item.avdName)
                        put("source", item.source)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "logcat_snapshot",
            "Retrieve a static list of logcat entries matching filter conditions",
            mapOf(
                "serial" to stringProp("Optional target device serial"),
                "search" to stringProp("Keyword search query"),
                "limit" to intProp("Maximum log entries to return (default 100)"),
                "level" to stringProp("Filter minimum log level (verbose, debug, info, warn, error, fatal, silent)")
            )
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val search = args["search"]?.jsonPrimitive?.contentOrNull ?: ""
            val limit = args["limit"]?.jsonPrimitive?.int ?: 100
            val lvlStr = args["level"]?.jsonPrimitive?.contentOrNull
            val levels = lvlStr?.let { lvl ->
                val matched = LogLevel.entries.firstOrNull { it.name.equals(lvl, ignoreCase = true) }
                if (matched != null) {
                    LogLevel.entries.filter { it.ordinal >= matched.ordinal }.toSet()
                } else null
            } ?: setOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error, LogLevel.Fatal)
            val filter = LogcatFilter(
                search = search,
                levels = levels
            )
            val list = logcat.snapshot(resolved, filter, limit)
            val json = buildJsonArray {
                list.forEach { entry ->
                    add(buildJsonObject {
                        put("time", entry.time)
                        put("pid", entry.pid)
                        put("tid", entry.tid)
                        put("level", entry.level.name)
                        put("tag", entry.tag)
                        put("message", entry.message)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "set_device_location",
            "Inject a GPS geo fix on an emulator (adb emu geo fix; longitude first)",
            mapOf(
                "latitude" to stringProp("Latitude"),
                "longitude" to stringProp("Longitude"),
                "altitude" to stringProp("Optional altitude in meters"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("latitude", "longitude"),
        ) { args ->
            val lat = args["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: throw IllegalArgumentException("latitude is required")
            val lon = args["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: throw IllegalArgumentException("longitude is required")
            val alt = args["altitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = emulatorControls.sendGeoFix(resolved, GeoFix(lat, lon, alt))
            CallToolResult(
                content = listOf(TextContent(text = result.stdout.ifBlank { result.stderr })),
                isError = !result.isSuccess,
            )
        }

        mcpServer.registerTool(
            "set_device_sensor",
            "Set an emulator sensor value (adb emu sensor set). Multi-axis values are colon-separated.",
            mapOf(
                "sensor" to stringProp("Sensor name: acceleration, gyroscope, magnetic-field, orientation, proximity, light, pressure, humidity, temperature"),
                "values" to stringProp("Colon-separated float values, e.g. 0:9.81:0"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("sensor", "values"),
        ) { args ->
            val name = args["sensor"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("sensor is required")
            val sensor = EmulatorSensor.entries.firstOrNull { it.emuName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown sensor: $name")
            val values = args["values"]?.jsonPrimitive?.content?.split(':')?.mapNotNull { it.trim().toFloatOrNull() }
                ?: throw IllegalArgumentException("values is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = emulatorControls.setSensor(resolved, sensor, values)
            CallToolResult(
                content = listOf(TextContent(text = result.stdout.ifBlank { result.stderr })),
                isError = !result.isSuccess,
            )
        }

        mcpServer.registerTool(
            "set_battery_state",
            "Override battery level/charging/health via dumpsys battery",
            mapOf(
                "level" to intProp("Battery level 0-100"),
                "charging" to boolProp("Optional charging state"),
                "health" to stringProp("Optional health: good, overheat, dead, overvoltage, failure, cold, unknown"),
                "serial" to stringProp("Optional target device serial"),
            ),
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val messages = mutableListOf<String>()
            var failed = false
            args["level"]?.jsonPrimitive?.intOrNull?.let { level ->
                val result = emulatorControls.setBatteryLevel(resolved, level)
                messages += result.stdout.ifBlank { result.stderr }
                if (!result.isSuccess) failed = true
            }
            args["charging"]?.jsonPrimitive?.booleanOrNull?.let { charging ->
                val result = emulatorControls.setBatteryCharging(resolved, charging)
                messages += result.stdout.ifBlank { result.stderr }
                if (!result.isSuccess) failed = true
            }
            args["health"]?.jsonPrimitive?.contentOrNull?.let { healthName ->
                val health = BatteryHealth.entries.firstOrNull { it.dumpsysValue.equals(healthName, true) || it.name.equals(healthName, true) }
                    ?: throw IllegalArgumentException("Unknown health: $healthName")
                val result = emulatorControls.setBatteryHealth(resolved, health)
                messages += result.stdout.ifBlank { result.stderr }
                if (!result.isSuccess) failed = true
            }
            if (messages.isEmpty()) throw IllegalArgumentException("Provide level, charging, and/or health")
            CallToolResult(content = listOf(TextContent(text = messages.joinToString("\n"))), isError = failed)
        }

        mcpServer.registerTool(
            "reset_battery_state",
            "Reset dumpsys battery overrides on the device",
            mapOf("serial" to stringProp("Optional target device serial")),
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = emulatorControls.resetBattery(resolved)
            CallToolResult(
                content = listOf(TextContent(text = result.stdout.ifBlank { result.stderr })),
                isError = !result.isSuccess,
            )
        }

        mcpServer.registerTool(
            "set_thermal_status",
            "Override thermal status via cmd thermalservice override-status (0-6, API 29+)",
            mapOf(
                "status" to intProp("Thermal status code 0-6"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("status"),
        ) { args ->
            val statusCode = args["status"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("status is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = emulatorControls.setThermalStatus(resolved, statusCode)
            CallToolResult(
                content = listOf(TextContent(text = result.stdout.ifBlank { result.stderr })),
                isError = !result.isSuccess,
            )
        }

        mcpServer.registerTool(
            "simulate_incoming_call",
            "Simulate an incoming GSM call on an emulator",
            mapOf(
                "number" to stringProp("Phone number"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("number"),
        ) { args ->
            val number = args["number"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("number is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = emulatorControls.simulateIncomingCall(resolved, number)
            CallToolResult(
                content = listOf(TextContent(text = result.stdout.ifBlank { result.stderr })),
                isError = !result.isSuccess,
            )
        }

        mcpServer.registerTool(
            "send_sms",
            "Send a simulated SMS on an emulator (message may contain spaces)",
            mapOf(
                "number" to stringProp("Phone number"),
                "message" to stringProp("SMS body"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("number", "message"),
        ) { args ->
            val number = args["number"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("number is required")
            val message = args["message"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("message is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = emulatorControls.sendSms(resolved, number, message)
            CallToolResult(
                content = listOf(TextContent(text = result.stdout.ifBlank { result.stderr })),
                isError = !result.isSuccess,
            )
        }

        mcpServer.registerTool(
            "set_network_type",
            "Set emulator GSM data network type (gprs, edge, umts, lte, nr)",
            mapOf(
                "type" to stringProp("Network type: gprs, edge, umts, lte, nr"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("type"),
        ) { args ->
            val typeName = args["type"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("type is required")
            val type = GsmDataType.entries.firstOrNull { it.emuValue.equals(typeName, true) || it.name.equals(typeName, true) }
                ?: throw IllegalArgumentException("Unknown network type: $typeName")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = emulatorControls.setNetworkType(resolved, type)
            CallToolResult(
                content = listOf(TextContent(text = result.stdout.ifBlank { result.stderr })),
                isError = !result.isSuccess,
            )
        }

        mcpServer.registerTool(
            "set_device_locale",
            "Set device or focused-app locale (cmd locale / setprop / set-app-locales)",
            mapOf(
                "tag" to stringProp("BCP-47 locale tag, e.g. en-US or en-XA"),
                "allowRestart" to boolProp("Allow setprop + am restart fallback"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("tag"),
        ) { args ->
            val tag = args["tag"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("tag is required")
            val allowRestart = args["allowRestart"]?.jsonPrimitive?.booleanOrNull ?: false
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val change = emulatorControls.setDeviceLocale(resolved, tag, allowFrameworkRestart = allowRestart)
            CallToolResult(
                content = listOf(TextContent(text = "${change.result.stdout.ifBlank { change.result.stderr }} (${change.method.label})")),
                isError = !change.result.isSuccess,
            )
        }

        mcpServer.registerTool(
            "list_crashes",
            "List crash/ANR/watchdog records from dumpsys dropbox, /data/anr, and /data/tombstones",
            mapOf("serial" to stringProp("Optional target device serial")),
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val list = crashInspector.listCrashes(resolved)
            val json = buildJsonArray {
                list.forEach { crash ->
                    add(buildJsonObject {
                        put("id", crash.id)
                        put("kind", crash.kind.name)
                        put("packageName", crash.packageName)
                        put("timestampMillis", crash.timestampMillis)
                        put("summary", crash.summary)
                    })
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "get_crash",
            "Load the full text of a crash/ANR record by id (from list_crashes)",
            mapOf(
                "id" to stringProp("Crash record id from list_crashes"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("id"),
        ) { args ->
            val id = args["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val text = crashInspector.loadCrash(resolved, id)
            CallToolResult(content = listOf(TextContent(text = text)))
        }

        mcpServer.registerTool(
            "capture_heap_dump",
            "Capture an hprof heap dump for a package (am dumpheap -> pull -> hprof-conv)",
            mapOf(
                "packageName" to stringProp("Application package name"),
                "localPath" to stringProp("Optional local destination path; defaults to Andy's heap dump library"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("packageName"),
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("packageName is required")
            val localPath = args["localPath"]?.jsonPrimitive?.contentOrNull ?: ""
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val result = heapDump.capture(resolved, packageName, localPath)
            result.fold(
                onSuccess = { info ->
                    CallToolResult(content = listOf(TextContent(text = "Captured ${info.localPath} (${info.sizeBytes} bytes)")))
                },
                onFailure = { error ->
                    CallToolResult(content = listOf(TextContent(text = error.message ?: "Heap dump failed")), isError = true)
                },
            )
        }

        mcpServer.registerTool(
            "get_memory_breakdown",
            "Get a dumpsys meminfo Java/native/graphics/code/stack breakdown for a package",
            mapOf(
                "packageName" to stringProp("Application package name"),
                "serial" to stringProp("Optional target device serial"),
            ),
            listOf("packageName"),
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("packageName is required")
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val breakdown = metrics.meminfoBreakdown(resolved, packageName)
                ?: return@registerTool CallToolResult(
                    content = listOf(TextContent(text = "No meminfo breakdown available for $packageName")),
                    isError = true,
                )
            val json = buildJsonObject {
                put("packageName", breakdown.packageName)
                put("javaHeapMb", breakdown.javaHeapMb)
                put("nativeHeapMb", breakdown.nativeHeapMb)
                put("codeMb", breakdown.codeMb)
                put("stackMb", breakdown.stackMb)
                put("graphicsMb", breakdown.graphicsMb)
                put("privateOtherMb", breakdown.privateOtherMb)
                put("systemMb", breakdown.systemMb)
                put("totalPssMb", breakdown.totalPssMb)
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "get_battery_stats",
            "Summarize dumpsys batterystats into wakelock/alarm/job tables and estimated drain",
            mapOf(
                "packageName" to stringProp("Optional package name to scope the report (dumpsys batterystats --charged <pkg>)"),
                "serial" to stringProp("Optional target device serial"),
            ),
        ) { args ->
            val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            val summary = metrics.batteryStatsSummary(resolved, packageName)
            val json = buildJsonObject {
                putJsonArray("wakelocks") {
                    summary.wakelocks.forEach { w ->
                        add(buildJsonObject { put("name", w.name); put("packageName", w.packageName); put("heldMillis", w.heldMillis); put("count", w.count) })
                    }
                }
                putJsonArray("alarms") {
                    summary.alarms.forEach { a ->
                        add(buildJsonObject { put("name", a.name); put("packageName", a.packageName); put("count", a.count) })
                    }
                }
                putJsonArray("jobs") {
                    summary.jobs.forEach { j ->
                        add(buildJsonObject { put("name", j.name); put("packageName", j.packageName); put("durationMillis", j.durationMillis); put("count", j.count) })
                    }
                }
                putJsonArray("drain") {
                    summary.drain.forEach { d ->
                        add(buildJsonObject { put("packageName", d.packageName); put("percent", d.percent) })
                    }
                }
            }
            CallToolResult(content = listOf(TextContent(text = json.toString())))
        }

        mcpServer.registerTool(
            "start_screen_recording",
            "Starts a durable screen recording on the target device via Andy's live capture pipeline " +
                "(the same path as the Live toolbar record button). Ends any active rolling bug-capture window.",
            mapOf("serial" to stringProp("Optional target device serial")),
        ) { args ->
            val resolved = resolveSerial(args["serial"]?.jsonPrimitive?.contentOrNull)
            if (mirror.session.first()?.serial != resolved) {
                mirror.connect(resolved)
            }
            bugs.startCapture(resolved, device = null)
            bugs.beginRecording()
            CallToolResult(content = listOf(TextContent(text = "Recording started for $resolved")))
        }

        mcpServer.registerTool(
            "stop_screen_recording",
            "Stops the active screen recording and saves it to Andy's Recordings library " +
                "(capture.mp4 + metadata). Returns the recording id for export_recording.",
        ) { args ->
            val report = bugs.saveRecording(device = null)
            val json = buildJsonObject {
                put("id", report.id)
                put("title", report.title)
                put("videoStartedAtMillis", report.videoStartedAtMillis)
                put("videoEndedAtMillis", report.videoEndedAtMillis)
                put("frameCount", report.videoFrameTimestampsMillis.size)
                put("videoCaptureWarning", report.videoCaptureWarning)
            }
            CallToolResult(
                content = listOf(TextContent(text = json.toString())),
                isError = report.videoCaptureWarning != null,
            )
        }

        mcpServer.registerTool(
            "export_recording",
            "Exports a saved recording (from stop_screen_recording, or any entry in the Recordings " +
                "destination) to a GIF, WebP, PNG sequence, or trimmed MP4 for sharing (e.g. in a PR).",
            mapOf(
                "id" to stringProp("Recording id, e.g. recording-1730000000000"),
                "localPath" to stringProp("Destination file path (a directory for pngSequence)"),
                "format" to stringProp("mp4, gif, webp, or pngSequence (default gif)"),
                "startMillis" to intProp("Trim start (epoch millis); defaults to the recording start"),
                "endMillis" to intProp("Trim end (epoch millis); defaults to the recording end"),
                "scale" to intProp("Output width in pixels, aspect-preserving (default 480)"),
                "fps" to intProp("Output frame rate (default 12; capped at 30 for non-mp4 formats)"),
                "loop" to boolProp("Loop forever for gif/webp (default true)"),
            ),
            listOf("id", "localPath"),
        ) { args ->
            val id = args["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            val localPath = args["localPath"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("localPath is required")
            val report = bugs.loadBug(id) ?: throw IllegalArgumentException("Recording not found: $id")
            val format = when (args["format"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "mp4" -> ClipFormat.Mp4
                "webp" -> ClipFormat.WebP
                "pngsequence", "png_sequence", "png" -> ClipFormat.PngSequence
                else -> ClipFormat.Gif
            }
            val request = RecordingExportRequest(
                id = id,
                startMillis = args["startMillis"]?.jsonPrimitive?.longOrNull
                    ?: report.videoStartedAtMillis ?: report.windowStartedAtMillis,
                endMillis = args["endMillis"]?.jsonPrimitive?.longOrNull
                    ?: report.videoEndedAtMillis ?: report.windowEndedAtMillis,
                format = format,
                scale = args["scale"]?.jsonPrimitive?.intOrNull ?: 480,
                fps = args["fps"]?.jsonPrimitive?.intOrNull ?: 12,
                loop = args["loop"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            recordingExport.export(request, localPath).fold(
                onSuccess = { clip ->
                    val json = buildJsonObject {
                        put("localPath", clip.localPath)
                        put("format", clip.format.name)
                        put("sizeBytes", clip.sizeBytes)
                        put("frameCount", clip.frameCount)
                        put("widthPx", clip.widthPx)
                        put("heightPx", clip.heightPx)
                    }
                    CallToolResult(content = listOf(TextContent(text = json.toString())))
                },
                onFailure = { error ->
                    CallToolResult(content = listOf(TextContent(text = error.message ?: "Export failed")), isError = true)
                },
            )
        }

        mcpServer.registerTool(
            "screenshot_host",
            "Capture the whole host desktop as a PNG (not a device/emulator). " +
                "Disabled by default — enable in Settings → MCP → Allow host screenshots.",
        ) { _ ->
            val enabled = workspaceStore.state?.value?.hostScreenshotEnabled == true
            if (!enabled) {
                CallToolResult(
                    content = listOf(
                        TextContent(
                            text = "screenshot_host is disabled. Enable it in Andy Settings → MCP " +
                                "(Allow host screenshots). This tool captures the full desktop of " +
                                "the machine running Andy and sends it to the agent model provider.",
                        ),
                    ),
                    isError = true,
                )
            } else {
                val bytes = HostScreenshotCapture.capturePngBytes()
                val base64 = Base64.getEncoder().encodeToString(bytes)
                CallToolResult(
                    content = listOf(ImageContent(data = base64, mimeType = "image/png")),
                )
            }
        }

    }

    private fun Server.registerTool(
        name: String,
        description: String,
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        handler: suspend (Map<String, JsonElement>) -> CallToolResult
    ) {
        val propertiesObject = buildJsonObject {
            properties.forEach { (propName, propSchema) ->
                put(propName, propSchema)
            }
        }
        val inputSchema = ToolSchema(
            properties = propertiesObject,
            required = required.takeIf { it.isNotEmpty() }
        )
        this.addTool(name, description, inputSchema) { request ->
            val args = request.arguments ?: emptyMap()
            try {
                handler(args)
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: ${e.message ?: e.toString()}")),
                    isError = true
                )
            }
        }
    }

    private fun stringProp(desc: String) = buildJsonObject {
        put("type", "string")
        put("description", desc)
    }

    private fun intProp(desc: String) = buildJsonObject {
        put("type", "integer")
        put("description", desc)
    }

    private fun arrayProp(itemType: String, desc: String) = buildJsonObject {
        put("type", "array")
        putJsonObject("items") {
            put("type", itemType)
        }
        put("description", desc)
    }

    private fun arrayObjectProp(desc: String) = buildJsonObject {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
        }
        put("description", desc)
    }

    private fun boolProp(desc: String) = buildJsonObject {
        put("type", "boolean")
        put("description", desc)
    }

    private fun objectProp(desc: String) = buildJsonObject {
        put("type", "object")
        put("description", desc)
    }

    private suspend fun saveProxyRules(state: WorkspaceState, rules: List<ProxyRule>) {
        workspaceStore.save(state.copy(proxyRules = rules))
        proxy.updateRules(rules)
    }

    private fun proxyRuleFromJson(
        obj: JsonObject,
        existing: ProxyRule?,
        requirePatternForNew: Boolean,
    ): ProxyRule {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: existing?.id
            ?: java.util.UUID.randomUUID().toString()
        val urlPattern = stringArg(obj, "urlPattern", existing?.urlPattern)
        if (requirePatternForNew && urlPattern.isNullOrBlank()) {
            throw IllegalArgumentException("urlPattern is required for a new network mock rule")
        }

        return ProxyRule(
            id = id,
            name = stringArg(obj, "name", existing?.name) ?: "Mock rule",
            enabled = boolArg(obj, "enabled", existing?.enabled) ?: true,
            urlPattern = urlPattern.orEmpty(),
            method = stringArg(obj, "method", existing?.method)?.takeIf { it.isNotBlank() },
            statusCode = intArg(obj, "statusCode", existing?.statusCode),
            setHeaders = stringMapArg(obj, "setHeaders", existing?.setHeaders) ?: emptyMap(),
            removeHeaders = stringListArg(obj, "removeHeaders", existing?.removeHeaders) ?: emptyList(),
            responseBody = stringArg(obj, "responseBody", existing?.responseBody),
        )
    }

    private fun proxyRuleToJson(rule: ProxyRule): JsonObject {
        return buildJsonObject {
            put("id", rule.id)
            put("name", rule.name)
            put("enabled", rule.enabled)
            put("urlPattern", rule.urlPattern)
            put("method", rule.method)
            put("statusCode", rule.statusCode)
            putJsonObject("setHeaders") {
                rule.setHeaders.forEach { (name, value) -> put(name, value) }
            }
            putJsonArray("removeHeaders") {
                rule.removeHeaders.forEach { add(it) }
            }
            put("responseBody", rule.responseBody)
        }
    }

    private fun stringArg(obj: JsonObject, name: String, fallback: String?): String? {
        if (name !in obj) return fallback
        return obj[name]?.jsonPrimitive?.contentOrNull
    }

    private fun boolArg(obj: JsonObject, name: String, fallback: Boolean?): Boolean? {
        if (name !in obj) return fallback
        return obj[name]?.jsonPrimitive?.booleanOrNull
    }

    private fun intArg(obj: JsonObject, name: String, fallback: Int?): Int? {
        if (name !in obj) return fallback
        return obj[name]?.jsonPrimitive?.intOrNull
    }

    private fun stringListArg(obj: JsonObject, name: String, fallback: List<String>?): List<String>? {
        if (name !in obj) return fallback
        return obj[name]?.jsonArray?.map { it.jsonPrimitive.content }
    }

    private fun stringMapArg(obj: JsonObject, name: String, fallback: Map<String, String>?): Map<String, String>? {
        if (name !in obj) return fallback
        return obj[name]?.jsonObject?.entries?.associate { it.key to it.value.jsonPrimitive.content }
    }

    private fun mapNode(node: AccessibilityNode): JsonObject {
        return buildJsonObject {
            put("id", node.id)
            put("className", node.className)
            put("packageName", node.packageName)
            put("resourceId", node.resourceId)
            put("text", node.text)
            put("contentDescription", node.contentDescription)
            put("bounds", node.bounds)
            put("clickable", node.clickable)
            put("focusable", node.focusable)
            put("enabled", node.enabled)
            put("selected", node.selected)
            put("checked", node.checked)
            put("scrollable", node.scrollable)
            put("visible", node.visible)
            if (node.children.isNotEmpty()) {
                put("children", buildJsonArray {
                    node.children.forEach { add(mapNode(it)) }
                })
            }
        }
    }

    /** Like [mapNode], but caps depth and total node count for `capture_view_hierarchy` (§D.5). */
    private fun mapHierarchyNode(node: AccessibilityNode, maxDepth: Int, maxNodes: Int, counter: IntArray, depth: Int = 0): JsonObject {
        counter[0]++
        return buildJsonObject {
            put("id", node.id)
            put("className", node.className)
            put("resourceId", node.resourceId)
            put("text", node.text)
            put("contentDescription", node.contentDescription)
            put("bounds", node.bounds)
            put("clickable", node.clickable)
            put("focusable", node.focusable)
            put("enabled", node.enabled)
            put("visible", node.visible)
            if (node.attributes.isNotEmpty()) {
                putJsonObject("attributes") { node.attributes.forEach { (key, value) -> put(key, value) } }
            }
            when {
                node.children.isEmpty() -> Unit
                depth >= maxDepth -> put("childrenOmittedDepth", node.children.size)
                counter[0] >= maxNodes -> put("childrenOmittedCap", node.children.size)
                else -> putJsonArray("children") {
                    for (child in node.children) {
                        if (counter[0] >= maxNodes) break
                        add(mapHierarchyNode(child, maxDepth, maxNodes, counter, depth + 1))
                    }
                }
            }
        }
    }

    private fun isPortAvailable(port: Int, host: String = "127.0.0.1"): Boolean {
        return try {
            java.net.ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(host, port))
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
