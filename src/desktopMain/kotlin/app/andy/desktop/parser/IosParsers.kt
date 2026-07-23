package app.andy.desktop.parser

import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.IosTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object IosParsers {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseSimctlDevices(output: String): List<IosTarget> {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return emptyList()
        val devices = root["devices"]?.jsonObject ?: return emptyList()
        return devices.values.flatMap { runtimeDevices ->
            runtimeDevices.jsonArray.mapNotNull { element ->
                val device = element.jsonObject
                val udid = device.string("udid") ?: return@mapNotNull null
                val name = device.string("name") ?: udid
                val runtime = device.string("deviceTypeIdentifier")?.substringAfterLast('.') ?: "iOS"
                val available = device.boolean("isAvailable") ?: true
                val state = when {
                    !available -> IosTargetState.Unavailable
                    device.string("state") == "Booted" -> IosTargetState.Booted
                    device.string("state") == "Shutdown" -> IosTargetState.Shutdown
                    else -> IosTargetState.Unknown
                }
                IosTarget(
                    udid = udid,
                    displayName = name,
                    kind = IosTargetKind.Simulator,
                    state = state,
                    runtime = runtime,
                    model = name,
                    transport = IosTransport.Unknown,
                )
            }
        }
    }

    fun parseDevicectlDevices(output: String): List<IosTarget> {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return emptyList()
        val devices = root["result"]?.jsonObject?.get("devices")?.jsonArray ?: return emptyList()
        return devices.mapNotNull { element ->
            val device = element.jsonObject
            val hardware = device["hardwareProperties"]?.jsonObject
            val connection = device["connectionProperties"]?.jsonObject
            val udid = hardware?.string("udid") ?: return@mapNotNull null
            val name = device["deviceProperties"]?.jsonObject?.string("name") ?: udid
            val coreDeviceIdentifier = device.string("identifier")
            val model = hardware.string("marketingName") ?: hardware.string("deviceType")
            val transport = when (connection?.string("transportType")) {
                "wired", "usb" -> IosTransport.Usb
                "localNetwork", "network" -> IosTransport.Network
                else -> IosTransport.Unknown
            }
            val paired = connection?.string("pairingState") == "paired"
            val state = if (paired) IosTargetState.Unknown else IosTargetState.Unavailable
            IosTarget(
                udid = udid,
                displayName = name,
                kind = IosTargetKind.Physical,
                state = state,
                runtime = device["deviceProperties"]?.jsonObject?.string("osVersionNumber"),
                model = model,
                transport = transport,
                coreDeviceIdentifier = coreDeviceIdentifier,
            )
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
}
