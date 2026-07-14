package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import kotlinx.coroutines.flow.Flow

@Composable
expect fun MirrorVideoSurface(
    frame: MirrorFrame?,
    modifier: Modifier = Modifier,
    onInput: (MirrorInput) -> Unit = {},
    onHoverColor: (String) -> Unit = {},
    passThroughInput: Boolean = true,
    onPickerClick: (String) -> Unit = {},
    onDevicePointClick: (Int, Int) -> Unit = { _, _ -> },
    onRulerResize: (Float, Float) -> Unit = { _, _ -> },
    overlay: MirrorOverlay = MirrorOverlay(),
    occluded: Boolean = false,
)

@Composable
expect fun MirrorVideoSurface(
    frames: Flow<MirrorFrame>,
    resetKey: Any? = null,
    modifier: Modifier = Modifier,
    onInput: (MirrorInput) -> Unit = {},
    onHoverColor: (String) -> Unit = {},
    passThroughInput: Boolean = true,
    onPickerClick: (String) -> Unit = {},
    onDevicePointClick: (Int, Int) -> Unit = { _, _ -> },
    onRulerResize: (Float, Float) -> Unit = { _, _ -> },
    overlay: MirrorOverlay = MirrorOverlay(),
    occluded: Boolean = false,
)

data class MirrorOverlay(
    val highlightBounds: String? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val showGrid: Boolean = false,
    val gridSize: Float = 16f,
    val gridColor: Color = Color.Transparent,
    val showRuler: Boolean = false,
    val rulerColor: Color = Color.Transparent,
    val rulerWidth: Float = 0.5f,
    val rulerHeight: Float = 0.5f,
    val rulerX: Float = 0.5f,
    val rulerY: Float = 0.5f,
    val pickerColor: Color? = null,
    val pickerHex: String? = null,
    val referenceImagePath: String? = null,
    val referenceImageKey: Long = 0L,
    val referenceImageOpacity: Float = 0.5f,
)
