package app.andy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import kotlinx.coroutines.flow.Flow

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
    onContentPan: (Float, Float) -> Unit,
    overlay: MirrorOverlay,
    occluded: Boolean,
    deferNativePresentation: Boolean,
    nativePresentation: Boolean,
    nativePresentationFillHost: Boolean,
    gpuMirrorStreamKey: Any?,
) {
    if (occluded) return
    Box(modifier.background(Color.Black))
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
    onContentPan: (Float, Float) -> Unit,
    overlay: MirrorOverlay,
    occluded: Boolean,
    deferNativePresentation: Boolean,
    nativePresentation: Boolean,
    nativePresentationFillHost: Boolean,
    gpuMirrorStreamKey: Any?,
) {
    if (occluded) return
    Box(modifier.background(Color.Black))
}
