package app.andy.desktop.service

import app.andy.service.CommandResult
import app.andy.service.IosDeviceService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Curated simulator lifecycle + Controls tools (no Android equivalents). */
internal val IosMcpToolNames: List<String> = listOf(
    "ios_list_device_types",
    "ios_list_runtimes",
    "ios_create_simulator",
    "ios_clone_simulator",
    "ios_erase_simulator",
    "ios_rename_simulator",
    "ios_delete_simulator",
    "ios_boot",
    "ios_shutdown",
    "ios_set_appearance",
    "ios_set_content_size",
    "ios_status_bar_override",
    "ios_status_bar_clear",
    "ios_set_location",
    "ios_privacy",
    "ios_pbcopy",
    "ios_pbpaste",
    "ios_push",
)

internal typealias McpToolRegistrar = (
    name: String,
    description: String,
    properties: Map<String, JsonObject>,
    required: List<String>,
    handler: suspend (Map<String, JsonElement>) -> CallToolResult,
) -> Unit

/** Registers simulator-only MCP tools against [iosDevices]. */
internal fun registerIosMcpTools(
    iosDevices: IosDeviceService,
    stringProp: (String) -> JsonObject,
    register: McpToolRegistrar,
) {
    fun resultOf(result: CommandResult): CallToolResult = CallToolResult(
        content = listOf(
            TextContent(
                text = "Result: ${result.exitCode}\nStdout: ${result.stdout}\nStderr: ${result.stderr}",
            ),
        ),
        isError = !result.isSuccess,
    )

    register(
        "ios_list_device_types",
        "List available iOS Simulator device types (simctl list devicetypes)",
        emptyMap(),
        emptyList(),
    ) {
        val list = iosDevices.listDeviceTypes()
        val json = buildJsonArray {
            list.forEach { item ->
                add(
                    buildJsonObject {
                        put("identifier", item.identifier)
                        put("name", item.name)
                        put("productFamily", item.productFamily)
                    },
                )
            }
        }
        CallToolResult(content = listOf(TextContent(text = json.toString())))
    }

    register(
        "ios_list_runtimes",
        "List available iOS Simulator runtimes (simctl list runtimes)",
        emptyMap(),
        emptyList(),
    ) {
        val list = iosDevices.listRuntimes()
        val json = buildJsonArray {
            list.forEach { item ->
                add(
                    buildJsonObject {
                        put("identifier", item.identifier)
                        put("name", item.name)
                        put("version", item.version)
                        put("isAvailable", item.isAvailable)
                        put("buildVersion", item.buildVersion)
                    },
                )
            }
        }
        CallToolResult(content = listOf(TextContent(text = json.toString())))
    }

    register(
        "ios_create_simulator",
        "Create a new iOS Simulator",
        mapOf(
            "name" to stringProp("Display name for the new simulator"),
            "deviceTypeId" to stringProp("Device type identifier from ios_list_device_types"),
            "runtimeId" to stringProp("Optional runtime identifier from ios_list_runtimes"),
        ),
        listOf("name", "deviceTypeId"),
    ) { args ->
        val name = args["name"]?.jsonPrimitive?.content ?: ""
        val deviceTypeId = args["deviceTypeId"]?.jsonPrimitive?.content ?: ""
        val runtimeId = args["runtimeId"]?.jsonPrimitive?.contentOrNull
        resultOf(iosDevices.createSimulator(name, deviceTypeId, runtimeId))
    }

    register(
        "ios_clone_simulator",
        "Clone an existing iOS Simulator",
        mapOf(
            "udid" to stringProp("Source simulator UDID"),
            "newName" to stringProp("Name for the clone"),
        ),
        listOf("udid", "newName"),
    ) { args ->
        resultOf(
            iosDevices.cloneSimulator(
                args["udid"]?.jsonPrimitive?.content ?: "",
                args["newName"]?.jsonPrimitive?.content ?: "",
            ),
        )
    }

    register(
        "ios_erase_simulator",
        "Erase an iOS Simulator (factory reset)",
        mapOf("udid" to stringProp("Simulator UDID")),
        listOf("udid"),
    ) { args ->
        resultOf(iosDevices.eraseSimulator(args["udid"]?.jsonPrimitive?.content ?: ""))
    }

    register(
        "ios_rename_simulator",
        "Rename an iOS Simulator",
        mapOf(
            "udid" to stringProp("Simulator UDID"),
            "newName" to stringProp("New display name"),
        ),
        listOf("udid", "newName"),
    ) { args ->
        resultOf(
            iosDevices.renameSimulator(
                args["udid"]?.jsonPrimitive?.content ?: "",
                args["newName"]?.jsonPrimitive?.content ?: "",
            ),
        )
    }

    register(
        "ios_delete_simulator",
        "Delete an iOS Simulator",
        mapOf("udid" to stringProp("Simulator UDID")),
        listOf("udid"),
    ) { args ->
        resultOf(iosDevices.deleteSimulator(args["udid"]?.jsonPrimitive?.content ?: ""))
    }

    register(
        "ios_boot",
        "Boot an iOS Simulator",
        mapOf("udid" to stringProp("Simulator UDID")),
        listOf("udid"),
    ) { args ->
        resultOf(iosDevices.boot(args["udid"]?.jsonPrimitive?.content ?: ""))
    }

    register(
        "ios_shutdown",
        "Shut down an iOS Simulator",
        mapOf("udid" to stringProp("Simulator UDID")),
        listOf("udid"),
    ) { args ->
        resultOf(iosDevices.shutdown(args["udid"]?.jsonPrimitive?.content ?: ""))
    }

    register(
        "ios_set_appearance",
        "Set Simulator appearance (light or dark)",
        mapOf(
            "udid" to stringProp("Booted simulator UDID"),
            "appearance" to stringProp("light or dark"),
        ),
        listOf("udid", "appearance"),
    ) { args ->
        val udid = args["udid"]?.jsonPrimitive?.content ?: ""
        val appearance = args["appearance"]?.jsonPrimitive?.content?.lowercase() ?: ""
        if (appearance != "light" && appearance != "dark") {
            throw IllegalArgumentException("appearance must be light or dark")
        }
        resultOf(iosDevices.simctl(listOf("ui", udid, "appearance", appearance)))
    }

    register(
        "ios_set_content_size",
        "Set Simulator Dynamic Type / content size category",
        mapOf(
            "udid" to stringProp("Booted simulator UDID"),
            "size" to stringProp("e.g. medium, xLarge, accessibilityExtraLarge"),
        ),
        listOf("udid", "size"),
    ) { args ->
        val udid = args["udid"]?.jsonPrimitive?.content ?: ""
        val size = args["size"]?.jsonPrimitive?.content ?: ""
        resultOf(iosDevices.simctl(listOf("ui", udid, "content_size", size)))
    }

    register(
        "ios_status_bar_override",
        "Override Simulator status bar for screenshot studio (9:41, full battery, bars)",
        mapOf("udid" to stringProp("Booted simulator UDID")),
        listOf("udid"),
    ) { args ->
        val udid = args["udid"]?.jsonPrimitive?.content ?: ""
        resultOf(
            iosDevices.simctl(
                listOf(
                    "status_bar", udid, "override",
                    "--time", "9:41",
                    "--batteryLevel", "100",
                    "--batteryState", "charged",
                    "--cellularBars", "4",
                    "--wifiBars", "3",
                ),
            ),
        )
    }

    register(
        "ios_status_bar_clear",
        "Clear Simulator status bar overrides",
        mapOf("udid" to stringProp("Booted simulator UDID")),
        listOf("udid"),
    ) { args ->
        val udid = args["udid"]?.jsonPrimitive?.content ?: ""
        resultOf(iosDevices.simctl(listOf("status_bar", udid, "clear")))
    }

    register(
        "ios_set_location",
        "Set Simulator simulated GPS location",
        mapOf(
            "udid" to stringProp("Booted simulator UDID"),
            "latitude" to stringProp("Latitude"),
            "longitude" to stringProp("Longitude"),
        ),
        listOf("udid", "latitude", "longitude"),
    ) { args ->
        val udid = args["udid"]?.jsonPrimitive?.content ?: ""
        val lat = args["latitude"]?.jsonPrimitive?.content ?: ""
        val lon = args["longitude"]?.jsonPrimitive?.content ?: ""
        resultOf(iosDevices.simctl(listOf("location", udid, "set", "$lat,$lon")))
    }

    register(
        "ios_privacy",
        "Grant, revoke, or reset a Simulator privacy permission for a bundle",
        mapOf(
            "udid" to stringProp("Booted simulator UDID"),
            "action" to stringProp("grant, revoke, or reset"),
            "service" to stringProp("e.g. photos, camera, location, all"),
            "bundleId" to stringProp("App bundle identifier"),
        ),
        listOf("udid", "action", "service", "bundleId"),
    ) { args ->
        val udid = args["udid"]?.jsonPrimitive?.content ?: ""
        val action = args["action"]?.jsonPrimitive?.content?.lowercase() ?: ""
        val service = args["service"]?.jsonPrimitive?.content ?: ""
        val bundleId = args["bundleId"]?.jsonPrimitive?.content ?: ""
        if (action !in setOf("grant", "revoke", "reset")) {
            throw IllegalArgumentException("action must be grant, revoke, or reset")
        }
        resultOf(iosDevices.simctl(listOf("privacy", udid, action, service, bundleId)))
    }

    register(
        "ios_pbcopy",
        "Copy text onto the Simulator pasteboard",
        mapOf(
            "udid" to stringProp("Booted simulator UDID"),
            "text" to stringProp("Text to copy"),
        ),
        listOf("udid", "text"),
    ) { args ->
        val udid = args["udid"]?.jsonPrimitive?.content ?: ""
        val text = args["text"]?.jsonPrimitive?.content ?: ""
        resultOf(iosDevices.simctl(listOf("pbcopy", udid, text)))
    }

    register(
        "ios_pbpaste",
        "Read text from the Simulator pasteboard",
        mapOf("udid" to stringProp("Booted simulator UDID")),
        listOf("udid"),
    ) { args ->
        val udid = args["udid"]?.jsonPrimitive?.content ?: ""
        resultOf(iosDevices.simctl(listOf("pbpaste", udid)))
    }

    register(
        "ios_push",
        "Send a simulated push notification payload to a Simulator app",
        mapOf(
            "udid" to stringProp("Booted simulator UDID"),
            "bundleId" to stringProp("App bundle identifier"),
            "payloadJson" to stringProp("APNs JSON payload"),
        ),
        listOf("udid", "bundleId", "payloadJson"),
    ) { args ->
        resultOf(
            iosDevices.push(
                args["udid"]?.jsonPrimitive?.content ?: "",
                args["bundleId"]?.jsonPrimitive?.content ?: "",
                args["payloadJson"]?.jsonPrimitive?.content ?: "",
            ),
        )
    }
}
