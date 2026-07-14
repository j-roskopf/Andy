package app.andy.desktop.service.mirror

import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorVideoConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Versioned line-delimited JSON protocol for Andy's packaged native mirror process.
 *
 * stdout contains only [AndyMirrorEvent] lines; diagnostic logs belong on stderr. Keeping the
 * wire format here prevents the desktop UI from acquiring a renderer-specific API and makes a
 * companion process independently testable on every target platform.
 */
@Serializable
internal data class AndyMirrorCommand(
    val type: String,
    val serial: String? = null,
    val config: AndyMirrorConfig? = null,
    val host: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val overlay: JsonObject? = null,
    val input: JsonObject? = null,
)

@Serializable
internal data class AndyMirrorConfig(
    val maxSize: Int,
    val bitRate: Int,
    val maxFps: Int,
    val codec: String,
    val requestedMode: String,
)

@Serializable
internal data class AndyMirrorEvent(
    val type: String,
    val decoder: String? = null,
    val renderer: String? = null,
    /** The decoder itself is using a verified platform hardware path. */
    val decoderHardwareBacked: Boolean? = null,
    /** The presentation surface itself is using a verified GPU hardware path. */
    val rendererHardwareBacked: Boolean? = null,
    /**
     * Aggregate capability for UI display. It is true only when both
     * [decoderHardwareBacked] and [rendererHardwareBacked] are true.
     */
    val hardwareBacked: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val displayedFps: Float? = null,
    val decodedFps: Float? = null,
    val droppedFrames: Long? = null,
    val framesPresented: Long? = null,
    val p95InputToPresentMillis: Float? = null,
    val failureReason: String? = null,
)

/**
 * `hardwareBacked` alone is intentionally not sufficient. For example, upstream SDL may use a
 * GPU renderer while its FFmpeg decoder remains software-only; that is not an accelerated Andy
 * mirror session and must not be advertised as one.
 */
internal fun AndyMirrorEvent.isVerifiedHardwareReady(): Boolean =
    type == "ready" &&
        !decoder.isNullOrBlank() &&
        !renderer.isNullOrBlank() &&
        decoderHardwareBacked == true &&
        rendererHardwareBacked == true &&
        hardwareBacked == true

internal object AndyMirrorProtocol {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun start(serial: String, config: MirrorVideoConfig): String = encode(
        AndyMirrorCommand(
            type = "start",
            serial = serial,
            config = AndyMirrorConfig(
                maxSize = config.maxSize,
                bitRate = config.bitRate,
                maxFps = config.maxFps,
                codec = config.codec,
                requestedMode = config.rendererMode.protocolName,
            ),
        ),
    )

    fun attach(host: String): String = encode(AndyMirrorCommand(type = "attach", host = host))

    fun resize(width: Int, height: Int): String = encode(
        AndyMirrorCommand(type = "resize", width = width.coerceAtLeast(1), height = height.coerceAtLeast(1)),
    )

    fun overlay(value: JsonObject): String = encode(AndyMirrorCommand(type = "overlay", overlay = value))

    fun inspect(): String = encode(AndyMirrorCommand(type = "inspect"))

    fun input(value: MirrorInput): String = encode(AndyMirrorCommand(type = "input", input = value.toProtocolJson()))

    fun stop(): String = encode(AndyMirrorCommand(type = "stop"))

    fun decodeEvent(line: String): AndyMirrorEvent = json.decodeFromString(line)

    private fun encode(command: AndyMirrorCommand): String = json.encodeToString(command)
}

private val MirrorRendererMode.protocolName: String
    get() = when (this) {
        MirrorRendererMode.Auto -> "auto"
        MirrorRendererMode.Accelerated -> "accelerated"
        MirrorRendererMode.Legacy -> "legacy"
    }

private fun MirrorInput.toProtocolJson(): JsonObject = buildJsonObject {
    when (this@toProtocolJson) {
        is MirrorInput.Touch -> {
            put("type", "touch")
            put("action", action.name.lowercase())
            put("x", x)
            put("y", y)
        }
        is MirrorInput.Tap -> {
            put("type", "tap")
            put("x", x)
            put("y", y)
        }
        is MirrorInput.Swipe -> {
            put("type", "swipe")
            put("startX", startX)
            put("startY", startY)
            put("endX", endX)
            put("endY", endY)
            put("durationMillis", durationMillis)
        }
        is MirrorInput.Key -> {
            put("type", "key")
            put("keyCode", keyCode)
        }
        is MirrorInput.Text -> {
            put("type", "text")
            put("value", JsonPrimitive(value))
        }
        MirrorInput.Back -> put("type", "back")
        MirrorInput.Home -> put("type", "home")
        MirrorInput.Recents -> put("type", "recents")
        MirrorInput.Power -> put("type", "power")
    }
}
