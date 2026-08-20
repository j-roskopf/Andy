package app.andy.desktop.parser

import app.andy.model.IosDeveloperModeStatus
import app.andy.model.IosDeviceType
import app.andy.model.IosRuntime
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.IosTransport
import app.andy.model.LogLevel
import app.andy.model.LogcatEntry
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Minimal fields Andy needs from a `.ips` crash report (Phase 3.2). */
data class IosIpsReport(
    val id: String,
    val processName: String,
    val exceptionType: String?,
    val timestampMillis: Long,
    /** Simulator device UDID recovered from an image/executable path, when present. */
    val simulatorUdid: String?,
    val summary: String,
    val rawText: String,
)

object IosParsers {
    private val json = Json { ignoreUnknownKeys = true }
    private val ipsTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS xx", Locale.US)
    private val simulatorPathRegex = Regex("CoreSimulator/Devices/([0-9A-Fa-f-]{36})")

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
            val transportTypeRaw = connection?.string("transportType")
            val transport = when (transportTypeRaw) {
                "wired", "usb" -> IosTransport.Usb
                "localNetwork", "network" -> IosTransport.Network
                else -> IosTransport.Unknown
            }
            // `devicectl list devices` returns *remembered* pairings, not connected devices. A
            // device with no transportType is not attached — map that to Unavailable (Phase 6.2).
            // Do NOT gate on tunnelState: USB screen mirroring only needs transportType=wired and
            // trust. tunnelState ("disconnected"/"unavailable") is about the RemoteXPC/DDI tunnel
            // used by Developer Mode tooling, and a trusted USB phone routinely reports
            // tunnelState=disconnected while still mirrorable.
            val paired = connection?.string("pairingState") == "paired"
            val state = when {
                transportTypeRaw == null -> IosTargetState.Unavailable
                paired -> IosTargetState.Unknown
                else -> IosTargetState.Unavailable
            }
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

    /** Device types from `simctl list devicetypes -j`. */
    fun parseDeviceTypes(output: String): List<IosDeviceType> {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return emptyList()
        val types = root["devicetypes"]?.jsonArray ?: return emptyList()
        return types.mapNotNull { element ->
            val obj = element.jsonObject
            val identifier = obj.string("identifier") ?: return@mapNotNull null
            IosDeviceType(
                identifier = identifier,
                name = obj.string("name") ?: identifier,
                productFamily = obj.string("productFamily"),
            )
        }
    }

    /** Runtimes from `simctl list runtimes -j`. */
    fun parseRuntimes(output: String): List<IosRuntime> {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return emptyList()
        val runtimes = root["runtimes"]?.jsonArray ?: return emptyList()
        return runtimes.mapNotNull { element ->
            val obj = element.jsonObject
            val identifier = obj.string("identifier") ?: return@mapNotNull null
            IosRuntime(
                identifier = identifier,
                name = obj.string("name") ?: identifier,
                version = obj.string("version"),
                isAvailable = obj.boolean("isAvailable") ?: true,
                buildVersion = obj.string("buildversion"),
            )
        }
    }

    /**
     * Physical-device Developer Mode gate from `devicectl device info details --json-output`.
     * With Developer Mode disabled, `info apps`/`info files`/`info processes`/`info displays`
     * all fail with `CoreDeviceError 10005` — this is the cheap probe that detects that ahead of
     * time so Andy can show guidance instead of a silent empty screen (Phase 6.1).
     */
    fun parseDeveloperModeStatus(output: String): IosDeveloperModeStatus? {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return null
        val result = root["result"]?.jsonObject ?: root
        val deviceProperties = result["deviceProperties"]?.jsonObject
        val statusRaw = deviceProperties?.string("developerModeStatus") ?: return null
        val enabled = statusRaw.equals("enabled", ignoreCase = true)
        val ddiAvailable = deviceProperties.boolean("ddiServicesAvailable") ?: false
        val message = if (enabled) {
            "Developer Mode is enabled"
        } else {
            "Developer Mode is disabled — enable it in Settings \u2192 Privacy & Security \u2192 " +
                "Developer Mode, then restart the device"
        }
        return IosDeveloperModeStatus(enabled = enabled, ddiServicesAvailable = ddiAvailable, message = message)
    }

    /**
     * One line of `simctl spawn <udid> log stream --style ndjson --level info` (Phase 3.1).
     * `subsystem`/`category` are a better filter axis than Android's flat tag, so both are
     * folded into [LogcatEntry.tag] as `subsystem:category` rather than dropped.
     */
    fun parseLogStreamLine(line: String): LogcatEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        val message = obj.string("eventMessage") ?: return null
        val level = when (obj.string("messageType")) {
            "Debug" -> LogLevel.Debug
            "Info", "Default" -> LogLevel.Info
            "Error" -> LogLevel.Error
            "Fault" -> LogLevel.Fatal
            else -> LogLevel.Info
        }
        val subsystem = obj.string("subsystem").orEmpty()
        val category = obj.string("category").orEmpty()
        val tag = listOf(subsystem, category).filter { it.isNotBlank() }.joinToString(":")
            .ifBlank { obj.string("processImagePath")?.substringAfterLast('/') ?: "log" }
        return LogcatEntry(
            time = obj.string("timestamp").orEmpty(),
            pid = obj["processID"]?.jsonPrimitive?.content,
            tid = obj["threadID"]?.jsonPrimitive?.content,
            level = level,
            tag = tag,
            message = message,
        )
    }

    /**
     * Parses a macOS `.ips` crash report (§Phase 3.2). Modern reports are two JSON objects
     * separated by a newline: a one-line header, then the full body. Legacy plain-text reports
     * (pre-macOS 12) are not handled — `.ips` is the current format for both simulator and
     * on-device crash logs synced via Xcode/devicectl.
     */
    fun parseIpsReport(id: String, text: String): IosIpsReport? {
        val firstLineEnd = text.indexOf('\n')
        if (firstLineEnd <= 0) return null
        val header = runCatching { json.parseToJsonElement(text.substring(0, firstLineEnd).trim()).jsonObject }.getOrNull()
            ?: return null
        val body = runCatching { json.parseToJsonElement(text.substring(firstLineEnd + 1).trim()).jsonObject }.getOrNull()
        val processName = header.string("app_name") ?: header.string("proc_name") ?: body?.string("procName") ?: id
        val timestampRaw = header.string("timestamp") ?: body?.string("captureTime")
        val timestampMillis = parseIpsTimestamp(timestampRaw) ?: 0L
        val exceptionType = body?.get("exception")?.jsonObject?.string("type")
            ?: body?.get("exception")?.jsonObject?.string("signal")
        val pathHaystack = listOfNotNull(
            body?.string("procPath"),
            body?.string("path"),
            body?.get("binaryImages")?.jsonArray?.firstOrNull()?.jsonObject?.string("path"),
        ).joinToString(" ")
        val simulatorUdid = simulatorPathRegex.find(pathHaystack)?.groupValues?.getOrNull(1)
        val terminationReason = body?.string("terminationDescription")
        val summary = listOfNotNull(exceptionType, terminationReason).joinToString(": ").ifBlank { "$processName crashed" }
        return IosIpsReport(
            id = id,
            processName = processName,
            exceptionType = exceptionType,
            timestampMillis = timestampMillis,
            simulatorUdid = simulatorUdid,
            summary = "$processName: $summary",
            rawText = text,
        )
    }

    private fun parseIpsTimestamp(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw, ipsTimestampFormatter).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
}
