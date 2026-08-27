package app.andy.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuService
import app.andy.service.DhuSession

@Composable
internal actual fun DhuSurface(
    dhu: DhuService,
    session: DhuSession?,
    frame: DhuCaptureFrame?,
    modifier: Modifier,
    occluded: Boolean,
    onRequestFocus: () -> Unit,
) {
    // Web has no DHU host.
}
