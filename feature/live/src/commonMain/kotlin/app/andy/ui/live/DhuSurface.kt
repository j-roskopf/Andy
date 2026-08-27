package app.andy.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuService
import app.andy.service.DhuSession

/**
 * Embedded DHU capture surface. Desktop hosts capture + input forwarding; web is a no-op stub.
 */
@Composable
internal expect fun DhuSurface(
    dhu: DhuService,
    session: DhuSession?,
    frame: DhuCaptureFrame?,
    modifier: Modifier = Modifier,
    occluded: Boolean = false,
    onRequestFocus: () -> Unit = {},
)
