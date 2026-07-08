package app.andy.desktop.service

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
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64

class DesktopMcpServerService(
    private val devices: DeviceService,
    private val avd: AvdService,
    private val mirror: MirrorEngine,
    private val logcat: LogcatService,
    private val intents: IntentService,
    private val apps: AppService,
    private val files: FileService,
    private val proxy: ProxyService,
    private val accessibility: AccessibilityService,
    private val workspaceStore: WorkspaceStore,
) : McpServerService {
    override val status = MutableStateFlow("stopped")
    override val running = MutableStateFlow(false)

    private var serverEngine: EmbeddedServer<*, *>? = null
    private var runningPort: Int? = null

    override suspend fun start(port: Int): CommandResult = withContext(Dispatchers.IO) {
        if (serverEngine != null) {
            if (runningPort == port) {
                return@withContext CommandResult.success("Already running")
            }
            stop()
        }

        if (!isPortAvailable(port)) {
            status.value = "error: port $port already in use"
            running.value = false
            return@withContext CommandResult.failure("Port $port is already in use")
        }

        try {
            status.value = "starting..."

            val engine = embeddedServer(Netty, host = "127.0.0.1", port = port) {
                install(DoubleReceive)
                mcpStreamableHttp("/mcp-http", enableDnsRebindingProtection = false) {
                    createMcpServer()
                }
                routing {
                    mcp("/mcp", enableDnsRebindingProtection = false) {
                        createMcpServer()
                    }
                }
            }

            serverEngine = engine
            engine.start(wait = false)
            runningPort = port

            status.value = "running on 127.0.0.1:$port"
            running.value = true
            CommandResult.success("Server started on port $port")
        } catch (e: Exception) {
            e.printStackTrace()
            status.value = "error: ${e.message ?: "start failed"}"
            running.value = false
            serverEngine = null
            runningPort = null
            CommandResult.failure("Failed to start server: ${e.message}")
        }
    }

    override suspend fun stop(): CommandResult = withContext(Dispatchers.IO) {
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
        status.value = "stopped"
        running.value = false
        CommandResult.success("Server stopped")
    }

    override fun getSnippet(clientName: String, port: Int): String {
        val client = McpClientConfig.ClientType.entries.firstOrNull { it.label == clientName } ?: McpClientConfig.ClientType.ClaudeCode
        return McpClientConfig.getSnippet(client, port)
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
            McpClientConfig.ClientType.ClaudeDesktop
        )
    }

    override fun writeConfig(clientName: String, port: Int): Boolean {
        val client = McpClientConfig.ClientType.entries.firstOrNull { it.label == clientName } ?: return false
        return McpClientConfig.writeConfig(client, port)
    }

    private fun createMcpServer(): Server {
        val mcpServer = Server(
            serverInfo = Implementation("andy", "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        registerTools(mcpServer)
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
                val obj = ruleObj.jsonObject
                ProxyRule(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: java.util.UUID.randomUUID().toString(),
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "rule",
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    urlPattern = obj["urlPattern"]?.jsonPrimitive?.contentOrNull ?: "",
                    method = obj["method"]?.jsonPrimitive?.contentOrNull,
                    statusCode = obj["statusCode"]?.jsonPrimitive?.intOrNull,
                    setHeaders = obj["setHeaders"]?.jsonObject?.entries?.associate { it.key to it.value.jsonPrimitive.content } ?: emptyMap(),
                    removeHeaders = obj["removeHeaders"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    responseBody = obj["responseBody"]?.jsonPrimitive?.contentOrNull
                )
            } ?: emptyList()
            val result = proxy.start(port, rules)
            CallToolResult(
                content = listOf(TextContent(text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}")),
                isError = !result.isSuccess
            )
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

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            java.net.ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
