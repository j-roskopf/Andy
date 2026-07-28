package app.andy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuService
import app.andy.service.DhuSession
import app.andy.ui.shell.LocalSuppressHeavyweightSurfaces
import app.andy.ui.theme.TextSecondary

/** Embedding disabled — DHU is a separate desktop-head-unit window. */
@Composable
internal actual fun DhuSurface(
    dhu: DhuService,
    session: DhuSession?,
    frame: DhuCaptureFrame?,
    modifier: Modifier,
    occluded: Boolean,
    onRequestFocus: () -> Unit,
) {
    val suppress = LocalSuppressHeavyweightSurfaces.current
    Box(modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        if (!occluded && !suppress) {
            Text(
                session?.message ?: "DHU runs in its own window",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}
