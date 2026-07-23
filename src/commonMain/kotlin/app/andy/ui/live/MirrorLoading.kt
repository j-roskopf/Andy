package app.andy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorFrame
import app.andy.service.MirrorSession
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary

internal fun hasMirrorPresentation(frame: MirrorFrame?, session: MirrorSession?): Boolean {
    val width = session?.width?.takeIf { it > 1 } ?: frame?.width?.takeIf { it > 1 }
    val height = session?.height?.takeIf { it > 1 } ?: frame?.height?.takeIf { it > 1 }
    return width != null && height != null
}

/** True once pixels are actually being presented, not just when stream metadata is known. */
internal fun hasMirrorRendered(frame: MirrorFrame?, session: MirrorSession?): Boolean {
    if (!hasMirrorPresentation(frame, session)) return false
    if (session?.backend?.isHardwareBacked == true) {
        return session.stats.framesPresented > 0 || session.stats.displayedFps > 0f
    }
    if (session?.backend?.kind == MirrorBackendKind.LegacyCpu) {
        // Compose mirror metadata strips ARGB from state; frame numbers still advance on CPU.
        return (frame?.frameNumber ?: 0L) > 0L || session.stats.framesPresented > 0L
    }
    val hasCpuPixels = frame?.let { candidate ->
        candidate.width > 1 &&
            candidate.height > 1 &&
            candidate.argb.size >= candidate.width * candidate.height
    } == true
    return hasCpuPixels || (session?.stats?.framesPresented ?: 0L) > 0L
}

internal fun isMirrorSurfaceLoading(
    serial: String?,
    frame: MirrorFrame?,
    session: MirrorSession?,
    mirrorStatus: String,
): Boolean {
    if (serial == null) return false
    if (session?.failureReason != null) return false
    if (hasMirrorRendered(frame, session)) return false
    if (mirrorStatus.equals("Disconnected", ignoreCase = true) && session == null) return true
    if (mirrorStatus.contains("disconnect", ignoreCase = true)) return false
    return true
}

@Composable
internal fun MirrorLoadingOverlay(
    status: String,
    modifier: Modifier = Modifier,
) {
    val label = status.trim().takeIf {
        it.isNotEmpty() && !it.equals("disconnected", ignoreCase = true)
    } ?: "Starting mirror…"
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Rust)
            Text(label, color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
        }
    }
}
