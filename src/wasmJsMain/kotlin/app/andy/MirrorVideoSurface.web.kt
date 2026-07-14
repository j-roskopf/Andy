package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.web.webAdbAttachMirror
import app.andy.web.webAdbDetachMirror
import app.andy.web.webAdbSetMirrorHighlight
import kotlinx.browser.document
import kotlinx.coroutines.flow.Flow
import org.w3c.dom.HTMLDivElement

private var mirrorHostCounter = 0

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WebMirrorHost(modifier: Modifier, overlay: MirrorOverlay) {
    HtmlElementView(
        factory = {
            (document.createElement("div") as HTMLDivElement).apply {
                id = "andy-web-mirror-${mirrorHostCounter++}"
                style.width = "100%"
                style.height = "100%"
                style.backgroundColor = "#000"
                style.setProperty("overflow", "hidden")
                style.setProperty("position", "relative")
            }
        },
        modifier = modifier,
        update = {
            webAdbAttachMirror(it.id)
            webAdbSetMirrorHighlight(
                hostId = it.id,
                bounds = overlay.highlightBounds.orEmpty(),
                sourceWidth = overlay.sourceWidth ?: 0,
                sourceHeight = overlay.sourceHeight ?: 0,
            )
        },
        onRelease = { webAdbDetachMirror(it.id) },
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
) = WebMirrorHost(modifier, overlay)

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
) = WebMirrorHost(modifier, overlay)
