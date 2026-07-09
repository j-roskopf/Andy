package app.andy.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.andy.domain.mirrorInputBugText
import app.andy.domain.mirrorSwipeBugText
import app.andy.domain.mirrorTapBugText
import app.andy.model.AccessibilityNode
import app.andy.service.AndyServices
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorTouchAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun rememberMirrorInputSender(
    services: AndyServices,
    serial: String?,
    enabled: Boolean = true,
    recordActions: Boolean = true,
): (MirrorInput) -> Unit {
    val currentSerial by rememberUpdatedState(serial)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRecordActions by rememberUpdatedState(recordActions)
    var latestAccessibilityRoot by remember { mutableStateOf<AccessibilityNode?>(null) }
    val currentAccessibilityRoot by rememberUpdatedState(latestAccessibilityRoot)
    var touchGesture by remember { mutableStateOf<BugTouchGesture?>(null) }
    var tapAccessibilityLookup by remember { mutableStateOf<Deferred<AccessibilityNode?>?>(null) }
    val scope = rememberCoroutineScope()
    val channel = remember(services.mirror) { Channel<MirrorInput>(Channel.UNLIMITED) }
    LaunchedEffect(services.bugs, services.accessibility, serial) {
        services.bugs.status.collectLatest { status ->
            val activeSerial = serial
            if (!status.active || status.deviceSerial != activeSerial || activeSerial == null) {
                latestAccessibilityRoot = null
                return@collectLatest
            }
            while (true) {
                latestAccessibilityRoot = try {
                    services.accessibility.dump(activeSerial)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                delay(BugAccessibilitySnapshotMillis)
            }
        }
    }
    LaunchedEffect(channel, services.mirror) {
        for (input in channel) {
            if (currentEnabled && currentSerial != null) {
                services.mirror.sendInput(input)
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
                        val now = System.currentTimeMillis()
                        when (input.action) {
                            MirrorTouchAction.Down -> {
                                touchGesture = BugTouchGesture(input.x, input.y, input.x, input.y, now)
                                tapAccessibilityLookup?.cancel()
                                tapAccessibilityLookup = scope.async {
                                    val activeSerial = currentSerial ?: return@async null
                                    services.accessibility.dump(activeSerial)
                                }
                            }
                            MirrorTouchAction.Move -> {
                                touchGesture = touchGesture?.copy(lastX = input.x, lastY = input.y, moved = true)
                            }
                            MirrorTouchAction.Up -> {
                                val gesture = touchGesture
                                touchGesture = null
                                val (label, detail) = if (gesture != null && gesture.isSwipeTo(input.x, input.y)) {
                                    tapAccessibilityLookup?.cancel()
                                    tapAccessibilityLookup = null
                                    mirrorSwipeBugText(
                                        startX = gesture.startX,
                                        startY = gesture.startY,
                                        endX = input.x,
                                        endY = input.y,
                                        durationMillis = (now - gesture.startedAtMillis).toInt().coerceAtLeast(0),
                                    )
                                } else {
                                    null to null
                                }
                                if (label != null) {
                                    services.bugs.recordAction("input", label, detail)
                                } else {
                                    val lookup = tapAccessibilityLookup
                                    tapAccessibilityLookup = null
                                    scope.launch {
                                        val root = lookup?.let {
                                            try {
                                                withTimeoutOrNull(BugTapAccessibilityLookupMillis) { it.await() }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                null
                                            }
                                        }
                                            ?: currentAccessibilityRoot
                                        val (tapLabel, tapDetail) = mirrorTapBugText(input.x, input.y, root)
                                        services.bugs.recordAction("input", tapLabel, tapDetail)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val (label, detail) = mirrorInputBugText(input, currentAccessibilityRoot)
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

private const val BugAccessibilitySnapshotMillis = 1_500L
private const val BugTapAccessibilityLookupMillis = 350L
private const val BugTapMaxDistancePx = 24

@Composable
internal fun MirrorFrameContent(mirror: MirrorEngine, resetKey: Any?, content: @Composable (Flow<MirrorFrame>, MirrorFrame?) -> Unit) {
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
            if (
                previous == null ||
                previous.width != next.width ||
                previous.height != next.height ||
                next.frameNumber % MirrorMetadataFrameInterval == 0L
            ) {
                frame = next
            }
        }
    }
    content(mirror.frames, frame)
}

private const val MirrorMetadataFrameInterval = 30L
