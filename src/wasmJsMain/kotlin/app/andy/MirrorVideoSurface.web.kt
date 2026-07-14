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
import app.andy.web.webAdbSetMirrorHighlight
import kotlinx.browser.document
import kotlinx.coroutines.flow.Flow
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.Event

private external interface WebPointerEvent : kotlin.js.JsAny {
    val clientX: Double
    val clientY: Double
    fun stopImmediatePropagation()
}

private var mirrorHostCounter = 0
private val mirrorInteractions = mutableMapOf<String, WebMirrorInteraction>()

private data class WebMirrorInteraction(
    val passThroughInput: Boolean,
    val pickerEnabled: Boolean,
    val onHoverColor: (String) -> Unit,
    val onPickerClick: (String) -> Unit,
    val onDevicePointClick: (Int, Int) -> Unit,
)

private data class WebMirrorPoint(val x: Int, val y: Int, val color: String)

private fun webMirrorPoint(hostId: String, event: Event): WebMirrorPoint? {
    val pointer = event.unsafeCast<WebPointerEvent>()
    val raw = webAdbMirrorPoint(hostId, pointer.clientX, pointer.clientY)
    val parts = raw.split(',', limit = 3)
    if (parts.size != 3) return null
    return WebMirrorPoint(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2])
}

private fun installMirrorInteraction(host: HTMLDivElement) {
    host.addEventListener("pointermove", { event ->
        val interaction = mirrorInteractions[host.id]
        if (interaction != null && !interaction.passThroughInput && interaction.pickerEnabled) {
            webMirrorPoint(host.id, event)?.let { interaction.onHoverColor(it.color) }
        }
    }, true)
    host.addEventListener("pointerdown", { event ->
        val interaction = mirrorInteractions[host.id]
        if (interaction != null && !interaction.passThroughInput) {
            webMirrorPoint(host.id, event)?.let { point ->
                event.preventDefault()
                event.unsafeCast<WebPointerEvent>().stopImmediatePropagation()
                if (interaction.pickerEnabled) interaction.onPickerClick(point.color)
                else interaction.onDevicePointClick(point.x, point.y)
            }
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
                onHoverColor = onHoverColor,
                onPickerClick = onPickerClick,
                onDevicePointClick = onDevicePointClick,
            )
            webAdbAttachMirror(it.id)
            webAdbSetMirrorHighlight(
                hostId = it.id,
                bounds = overlay.highlightBounds.orEmpty(),
                sourceWidth = overlay.sourceWidth ?: 0,
                sourceHeight = overlay.sourceHeight ?: 0,
            )
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
) = WebMirrorHost(modifier, overlay, passThroughInput, onHoverColor, onPickerClick, onDevicePointClick)

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
) = WebMirrorHost(modifier, overlay, passThroughInput, onHoverColor, onPickerClick, onDevicePointClick)
