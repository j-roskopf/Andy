package app.andy.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.andy.domain.mirrorInputBugText
import app.andy.domain.mirrorSwipeBugText
import app.andy.domain.mirrorTapBugText
import app.andy.service.AndyServices
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.currentTimeMillis
import app.andy.service.MirrorTouchAction
import app.andy.ui.components.ContentScrollBusyRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@Composable
fun rememberMirrorInputSender(
    services: AndyServices,
    serial: String?,
    mirror: MirrorEngine = services.mirror,
    enabled: Boolean = true,
    recordActions: Boolean = true,
): (MirrorInput) -> Unit {
    val currentSerial by rememberUpdatedState(serial)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRecordActions by rememberUpdatedState(recordActions)
    var touchGesture by remember { mutableStateOf<BugTouchGesture?>(null) }
    // A backend call can be slow over SSH (or SimulatorKit HID). Unlimited queues of Touch(Move)
    // events turn network RTT into multi-second gesture lag — coalesce consecutive moves so the
    // device always sees the latest pointer, never a backlog.
    val channel = remember(mirror, serial) { Channel<MirrorInput>(Channel.UNLIMITED) }
    LaunchedEffect(channel, mirror) {
        withContext(Dispatchers.Default) {
            for (first in channel) {
                if (!currentEnabled || currentSerial == null) continue
                for (input in coalesceMirrorInputs(first) { channel.tryReceive().getOrNull() }) {
                    if (!currentEnabled || currentSerial == null) break
                    mirror.sendInput(input)
                }
            }
        }
    }
    DisposableEffect(channel) {
        onDispose { channel.close() }
    }
    return remember(channel) {
        { input ->
            if (currentEnabled && currentSerial != null && currentRecordActions) {
                when (input) {
                    is MirrorInput.Touch -> {
                        val now = currentTimeMillis()
                        when (input.action) {
                            MirrorTouchAction.Down -> {
                                touchGesture = BugTouchGesture(input.x, input.y, input.x, input.y, now)
                            }
                            MirrorTouchAction.Move -> {
                                touchGesture = touchGesture?.copy(lastX = input.x, lastY = input.y, moved = true)
                            }
                            MirrorTouchAction.Up -> {
                                val gesture = touchGesture
                                touchGesture = null
                                if (gesture != null && gesture.isSwipeTo(input.x, input.y)) {
                                    val (label, detail) = mirrorSwipeBugText(
                                        startX = gesture.startX,
                                        startY = gesture.startY,
                                        endX = input.x,
                                        endY = input.y,
                                        durationMillis = (now - gesture.startedAtMillis).toInt().coerceAtLeast(0),
                                    )
                                    services.bugs.recordAction("input", label, detail)
                                } else {
                                    val (tapLabel, tapDetail) = mirrorTapBugText(input.x, input.y, null)
                                    services.bugs.recordAction("input", tapLabel, tapDetail)
                                }
                            }
                        }
                    }
                    is MirrorInput.Tap -> {
                        val (label, detail) = mirrorTapBugText(input.x, input.y, null)
                        services.bugs.recordAction("input", label, detail)
                    }
                    else -> {
                        val (label, detail) = mirrorInputBugText(input, null)
                        services.bugs.recordAction("input", label, detail)
                    }
                }
            }
            if (channel.trySend(input).isFailure) Unit
        }
    }
}

private data class BugTouchGesture(
    val startX: Int,
    val startY: Int,
    val lastX: Int,
    val lastY: Int,
    val startedAtMillis: Long,
    val moved: Boolean = false,
) {
    fun isSwipeTo(endX: Int, endY: Int): Boolean {
        val dx = endX - startX
        val dy = endY - startY
        return moved && dx * dx + dy * dy >= BugTapMaxDistancePx * BugTapMaxDistancePx
    }
}

private const val BugTapMaxDistancePx = 24

/**
 * Collapse runs of [MirrorTouchAction.Move] so a slow control path (SSH-tunneled scrcpy)
 * cannot accumulate a multi-second pointer backlog. Down/Up/keys/taps are never dropped.
 */
internal fun coalesceMirrorInputs(
    first: MirrorInput,
    tryReceive: () -> MirrorInput?,
): List<MirrorInput> {
    val out = ArrayList<MirrorInput>(4)
    var current = first
    while (true) {
        if (current !is MirrorInput.Touch || current.action != MirrorTouchAction.Move) {
            out += current
            val next = tryReceive() ?: break
            current = next
            continue
        }
        // Drain consecutive moves; keep only the latest.
        var latestMove = current
        var next: MirrorInput? = tryReceive()
        while (next is MirrorInput.Touch && next.action == MirrorTouchAction.Move) {
            latestMove = next
            next = tryReceive()
        }
        out += latestMove
        if (next == null) break
        current = next
    }
    return out
}

@Composable
fun MirrorFrameContent(mirror: MirrorEngine, resetKey: Any?, content: @Composable (Flow<MirrorFrame>, MirrorFrame?) -> Unit) {
    // Every Live surface funnels through here, so this is where the engine learns whether anyone
    // can actually see the stream. Sessions stay connected either way; only presentation pauses.
    // While main content (e.g. chat) is scrolling, drop the hold so Metal is not fighting Skia.
    val contentScrollBusy = ContentScrollBusyRegistry.anyBusy
    // Hide the inline overlay immediately; acquire/release still has a handoff grace that would
    // leave Metal painting through the first half-second of a scroll gesture.
    MirrorPresentationVisibilityEffect(visible = !contentScrollBusy, enabled = true)
    DisposableEffect(mirror, contentScrollBusy) {
        if (contentScrollBusy) {
            onDispose { }
        } else {
            mirror.acquirePresentation()
            onDispose { mirror.releasePresentation() }
        }
    }
    var frame by remember(mirror, resetKey) { mutableStateOf<MirrorFrame?>(null) }
    LaunchedEffect(mirror, resetKey) {
        mirror.frames.collectLatest { next ->
            if (next.width <= 1 || next.height <= 1) {
                // connect()/disconnect() push a 1x1 sentinel; clear the frame so the
                // surface releases its last image instead of freezing on it.
                frame = null
                return@collectLatest
            }
            val previous = frame
            if (shouldUpdateMirrorMetadata(previous, next)) {
                // Keep pixels out of Compose state — CPU frames are megabytes and thrash the
                // UI thread when held as snapshot state. The Swing surface reads pixels from
                // the frame Flow directly.
                frame = if (next.argb.isEmpty()) {
                    next
                } else {
                    next.copy(argb = EmptyMirrorArgb)
                }
            }
        }
    }
    content(mirror.frames, frame)
}

internal fun shouldUpdateMirrorMetadata(previous: MirrorFrame?, next: MirrorFrame): Boolean =
    previous == null ||
        previous.width != next.width ||
        previous.height != next.height ||
        next.argb.isEmpty() ||
        next.frameNumber % MirrorMetadataFrameInterval == 0L

private const val MirrorMetadataFrameInterval = 30L
private val EmptyMirrorArgb = IntArray(0)
