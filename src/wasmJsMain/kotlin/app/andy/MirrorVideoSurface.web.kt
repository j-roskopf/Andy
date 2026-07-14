@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.web.webAdbAttachMirror
import app.andy.web.webAdbDetachMirror
import app.andy.web.webAdbMirrorPoint
import app.andy.web.webAdbMirrorRulerAxis
import app.andy.web.webAdbMirrorSourcePoint
import app.andy.web.webAdbSetMirrorOverlay
import app.andy.web.webAdbSetMirrorPickerPoint
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.browser.document
import kotlinx.coroutines.flow.Flow
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.Event

private external interface WebPointerEvent : kotlin.js.JsAny {
    val clientX: Double
    val clientY: Double
    val pointerId: Int
    fun stopImmediatePropagation()
}

private var mirrorHostCounter = 0
private val mirrorInteractions = mutableMapOf<String, WebMirrorInteraction>()

private data class WebMirrorInteraction(
    val passThroughInput: Boolean,
    val pickerEnabled: Boolean,
    val overlay: MirrorOverlay,
    val onHoverColor: (String) -> Unit,
    val onPickerClick: (String) -> Unit,
    val onDevicePointClick: (Int, Int) -> Unit,
    val onRulerResize: (Float, Float) -> Unit,
)

private data class WebMirrorPoint(val x: Int, val y: Int, val color: String)

private fun webMirrorPoint(hostId: String, event: Event): WebMirrorPoint? {
    val pointer = event.unsafeCast<WebPointerEvent>()
    val raw = webAdbMirrorPoint(hostId, pointer.clientX, pointer.clientY)
    val parts = raw.split(',', limit = 3)
    if (parts.size != 3) return null
    return WebMirrorPoint(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2])
}

private fun webMirrorSourcePoint(hostId: String, event: Event): Pair<Float, Float>? {
    val pointer = event.unsafeCast<WebPointerEvent>()
    val parts = webAdbMirrorSourcePoint(hostId, pointer.clientX, pointer.clientY).split(',', limit = 2)
    if (parts.size != 2) return null
    return (parts[0].toFloatOrNull() ?: return null) to (parts[1].toFloatOrNull() ?: return null)
}

private fun MirrorOverlay.webConfig(): String = buildJsonObject {
    put("highlightBounds", highlightBounds.orEmpty())
    put("sourceWidth", sourceWidth ?: 0)
    put("sourceHeight", sourceHeight ?: 0)
    put("showGrid", showGrid)
    put("gridSize", gridSize)
    put("gridColor", gridColor.toCssColor())
    put("showRuler", showRuler)
    put("rulerColor", rulerColor.toCssColor())
    put("rulerX", rulerX)
    put("rulerY", rulerY)
    put("pickerEnabled", pickerColor != null)
    put("pickerColor", (pickerColor ?: androidx.compose.ui.graphics.Color.Transparent).toCssColor())
    put("pickerHex", pickerHex.orEmpty())
}.toString()

private fun androidx.compose.ui.graphics.Color.toCssColor(): String {
    fun channel(value: Float) = (value * 255f).toInt().coerceIn(0, 255)
    return "rgba(${channel(red)}, ${channel(green)}, ${channel(blue)}, $alpha)"
}

private fun installMirrorInteraction(host: HTMLDivElement) {
    var activeRulerAxis: String? = null
    host.addEventListener("pointermove", { event ->
        val interaction = mirrorInteractions[host.id]
        if (interaction != null) {
            val pointer = event.unsafeCast<WebPointerEvent>()
            val rulerAxis = activeRulerAxis
            if (rulerAxis != null) {
                webMirrorSourcePoint(host.id, event)?.let { (sourceX, sourceY) ->
                    val nextX = if (rulerAxis == "x") sourceX else interaction.overlay.rulerX
                    val nextY = if (rulerAxis == "y") sourceY else interaction.overlay.rulerY
                    interaction.onRulerResize(nextX, nextY)
                }
                event.preventDefault()
                pointer.stopImmediatePropagation()
            } else if (!interaction.passThroughInput && interaction.pickerEnabled) {
                webAdbSetMirrorPickerPoint(host.id, pointer.clientX, pointer.clientY)
                webMirrorPoint(host.id, event)?.let { interaction.onHoverColor(it.color) }
            }
        }
    }, true)
    host.addEventListener("pointerdown", { event ->
        val interaction = mirrorInteractions[host.id]
        if (interaction != null) {
            val pointer = event.unsafeCast<WebPointerEvent>()
            val rulerAxis = webAdbMirrorRulerAxis(host.id, pointer.clientX, pointer.clientY)
            if (rulerAxis.isNotEmpty()) {
                activeRulerAxis = rulerAxis
                host.setPointerCapture(pointer.pointerId)
                event.preventDefault()
                pointer.stopImmediatePropagation()
                return@addEventListener
            }
        }
        if (interaction != null && !interaction.passThroughInput) {
            webMirrorPoint(host.id, event)?.let { point ->
                event.preventDefault()
                event.unsafeCast<WebPointerEvent>().stopImmediatePropagation()
                val pointer = event.unsafeCast<WebPointerEvent>()
                if (interaction.pickerEnabled) webAdbSetMirrorPickerPoint(host.id, pointer.clientX, pointer.clientY)
                if (interaction.pickerEnabled) interaction.onPickerClick(point.color)
                else interaction.onDevicePointClick(point.x, point.y)
            }
        }
    }, true)
    host.addEventListener("pointerup", { event ->
        if (activeRulerAxis != null) {
            activeRulerAxis = null
            host.releasePointerCapture(event.unsafeCast<WebPointerEvent>().pointerId)
            event.preventDefault()
            event.unsafeCast<WebPointerEvent>().stopImmediatePropagation()
        }
    }, true)
    host.addEventListener("pointercancel", { event ->
        if (activeRulerAxis != null) {
            activeRulerAxis = null
            host.releasePointerCapture(event.unsafeCast<WebPointerEvent>().pointerId)
            event.preventDefault()
            event.unsafeCast<WebPointerEvent>().stopImmediatePropagation()
        }
    }, true)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WebMirrorHost(
    modifier: Modifier,
    overlay: MirrorOverlay,
    passThroughInput: Boolean,
    onHoverColor: (String) -> Unit,
    onPickerClick: (String) -> Unit,
    onDevicePointClick: (Int, Int) -> Unit,
    onRulerResize: (Float, Float) -> Unit,
) {
    HtmlElementView(
        factory = {
            (document.createElement("div") as HTMLDivElement).apply {
                id = "andy-web-mirror-${mirrorHostCounter++}"
                style.width = "100%"
                style.height = "100%"
                style.backgroundColor = "#000"
                style.setProperty("overflow", "hidden")
                style.setProperty("position", "relative")
                installMirrorInteraction(this)
            }
        },
        modifier = modifier,
        update = {
            mirrorInteractions[it.id] = WebMirrorInteraction(
                passThroughInput = passThroughInput,
                pickerEnabled = overlay.pickerColor != null,
                overlay = overlay,
                onHoverColor = onHoverColor,
                onPickerClick = onPickerClick,
                onDevicePointClick = onDevicePointClick,
                onRulerResize = onRulerResize,
            )
            webAdbAttachMirror(it.id)
            webAdbSetMirrorOverlay(it.id, overlay.webConfig())
        },
        onRelease = {
            mirrorInteractions.remove(it.id)
            webAdbDetachMirror(it.id)
        },
    )
}

@Composable
actual fun MirrorVideoSurface(
    frame: MirrorFrame?,
    modifier: Modifier,
    onInput: (MirrorInput) -> Unit,
    onHoverColor: (String) -> Unit,
    passThroughInput: Boolean,
    onPickerClick: (String) -> Unit,
    onDevicePointClick: (Int, Int) -> Unit,
    onRulerResize: (Float, Float) -> Unit,
    overlay: MirrorOverlay,
    occluded: Boolean,
) {
    if (occluded) return
    WebMirrorHost(modifier, overlay, passThroughInput, onHoverColor, onPickerClick, onDevicePointClick, onRulerResize)
}

@Composable
actual fun MirrorVideoSurface(
    frames: Flow<MirrorFrame>,
    resetKey: Any?,
    modifier: Modifier,
    onInput: (MirrorInput) -> Unit,
    onHoverColor: (String) -> Unit,
    passThroughInput: Boolean,
    onPickerClick: (String) -> Unit,
    onDevicePointClick: (Int, Int) -> Unit,
    onRulerResize: (Float, Float) -> Unit,
    overlay: MirrorOverlay,
    occluded: Boolean,
) {
    if (occluded) return
    WebMirrorHost(modifier, overlay, passThroughInput, onHoverColor, onPickerClick, onDevicePointClick, onRulerResize)
}
