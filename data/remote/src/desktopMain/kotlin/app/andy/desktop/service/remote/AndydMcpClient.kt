package app.andy.desktop.service.remote

import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.SdkDiscovery
import app.andy.service.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-level MCP JSON-RPC client for a local `andyd.sock` (including SSH-tunneled remotes).
 */
class AndydMcpClient(
    private val socketPath: File,
    private val clientName: String = "andy-gui",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val idSeq = AtomicLong(1)

    suspend fun callToolText(
        name: String,
        arguments: Map<String, JsonElement> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        callToolRaw(name, arguments).text
    }

    suspend fun callToolImage(
        name: String,
        arguments: Map<String, JsonElement> = emptyMap(),
    ): ByteArray? = withContext(Dispatchers.IO) {
        val raw = callToolRaw(name, arguments)
        raw.imageBase64?.let { Base64.getDecoder().decode(it) }
    }

    suspend fun listDevices(): List<AndroidDevice> {
        val text = callToolText("list_devices")
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val serial = obj["serial"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val kind = runCatching {
                DeviceKind.valueOf(obj["kind"]?.jsonPrimitive?.contentOrNull ?: "Unknown")
            }.getOrDefault(DeviceKind.Unknown)
            val state = runCatching {
                DeviceConnectionState.valueOf(obj["state"]?.jsonPrimitive?.contentOrNull ?: "Unknown")
            }.getOrDefault(DeviceConnectionState.Unknown)
            AndroidDevice(
                serial = serial,
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: serial,
                kind = kind,
                state = state,
                apiLevel = obj["apiLevel"]?.jsonPrimitive?.contentOrNull,
                abi = obj["abi"]?.jsonPrimitive?.contentOrNull,
                model = obj["model"]?.jsonPrimitive?.contentOrNull,
                product = obj["product"]?.jsonPrimitive?.contentOrNull,
                batteryPercent = obj["batteryPercent"]?.jsonPrimitive?.intOrNull,
                screenSize = obj["screenSize"]?.jsonPrimitive?.contentOrNull,
                storageSummary = obj["storageSummary"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    suspend fun shell(serial: String, command: List<String>): CommandResult {
        val text = callToolText(
            "shell",
            mapOf(
                "serial" to JsonPrimitive(serial),
                "command" to JsonPrimitive(command.joinToString(" ")),
            ),
        )
        return parseShellToolResult(text)
    }

    fun serialArg(serial: String?): Map<String, JsonElement> =
        serial?.takeIf { it.isNotBlank() }?.let { mapOf("serial" to JsonPrimitive(it)) } ?: emptyMap()

    private data class ToolRawResult(val text: String, val imageBase64: String?)

    private fun callToolRaw(name: String, arguments: Map<String, JsonElement>): ToolRawResult {
        if (!socketPath.exists()) error("andyd socket missing: ${socketPath.absolutePath}")
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath.toPath()))
            val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
            val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))

            val initId = idSeq.getAndIncrement()
            writer.write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", initId)
                    put("method", "initialize")
                    put(
                        "params",
                        buildJsonObject {
                            put("protocolVersion", "2024-11-05")
                            put("capabilities", buildJsonObject {})
                            put(
                                "clientInfo",
                                buildJsonObject {
                                    put("name", clientName)
                                    put("version", "1.0.0")
                                },
                            )
                        },
                    )
                }.toString(),
            )
            writer.write("\n")
            writer.flush()
            reader.readLine()
            writer.write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                }.toString(),
            )
            writer.write("\n")
            writer.flush()

            val callId = idSeq.getAndIncrement()
            writer.write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", callId)
                    put("method", "tools/call")
                    put(
                        "params",
                        buildJsonObject {
                            put("name", name)
                            put("arguments", JsonObject(arguments))
                        },
                    )
                }.toString(),
            )
            writer.write("\n")
            writer.flush()

            val line = reader.readLine() ?: error("no response for $name")
            val root = json.parseToJsonElement(line).jsonObject
            val error = root["error"]?.jsonObject
            if (error != null) {
                error(error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString())
            }
            val result = root["result"]?.jsonObject ?: return ToolRawResult("", null)
            if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
                val errText = result["content"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: "$name failed"
                error(errText)
            }
            val content = result["content"]?.jsonArray ?: return ToolRawResult(result.toString(), null)
            var text: String? = null
            var imageBase64: String? = null
            for (item in content) {
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "image" -> imageBase64 = obj["data"]?.jsonPrimitive?.contentOrNull
                    else -> text = obj["text"]?.jsonPrimitive?.contentOrNull ?: text
                }
            }
            return ToolRawResult(text ?: content.toString(), imageBase64)
        }
    }

    companion object {
        fun parseShellToolResult(text: String): CommandResult {
            val exitMatch = Regex("""Exit Code:\s*(-?\d+)""").find(text)
            val exitCode = exitMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val stdout = text.substringAfter("Stdout:\n", "")
                .substringBefore("\nStderr:")
                .trimEnd()
            val stderr = text.substringAfter("Stderr:\n", "").trim()
            return CommandResult(exitCode, stdout, stderr)
        }

        fun parseCommandToolResult(text: String): CommandResult {
            val exitMatch = Regex("""Result:\s*(-?\d+)""").find(text)
            val exitCode = exitMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val stdout = text.substringAfter("Stdout: ", "")
                .substringBefore("\nStderr:")
                .trimEnd()
            val stderr = text.substringAfter("Stderr: ", "").trim()
            return CommandResult(exitCode, stdout, stderr)
        }

        val remoteSdkDiscovery = SdkDiscovery(
            sdkPath = null,
            adbPath = "remote",
            emulatorPath = null,
            sdkManagerPath = null,
            avdManagerPath = null,
            issues = listOf("Using remote host ADB via andyd"),
        )
    }
}
