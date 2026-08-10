package app.andy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.andy.currentTimeMillis
import app.andy.service.UnavailableVoiceDictationService
import app.andy.service.VoiceDictationService
import app.andy.service.VoiceSetupState
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Desktop [androidx.compose.ui.window.Window] preview-key hook binds the focused chat
 * composer's controller so the configurable shortcut works even when focus is outside
 * the composer card (sidebar, transcript, menus).
 */
internal object ActiveVoiceDictationShortcut {
    private var bound: VoiceDictationController? = null

    fun bind(controller: VoiceDictationController) {
        bound = controller
    }

    fun unbind(controller: VoiceDictationController) {
        if (bound === controller) bound = null
    }

    /** @return true when a composer handled the shortcut. */
    fun handle(event: KeyEvent, shortcut: KeyCombo?): Boolean {
        if (shortcut == null) return false
        if (event.type != KeyEventType.KeyDown) return false
        if (!shortcut.matches(event)) return false
        val controller = bound ?: return false
        controller.toggle()
        return true
    }
}

/**
 * Hoisted push-to-talk state so both the mic button's press-hold gesture and an
 * external trigger (e.g. a keyboard shortcut) can drive the same recording session.
 */
@Composable
internal fun rememberVoiceDictationController(
    voice: VoiceDictationService,
    onText: (String) -> Unit,
    onError: (String) -> Unit = {},
): VoiceDictationController {
    val scope = rememberCoroutineScope()
    val currentOnText by rememberUpdatedState(onText)
    val currentOnError by rememberUpdatedState(onError)
    val controller = remember(voice) {
        VoiceDictationController(voice, scope, { currentOnText(it) }, { currentOnError(it) })
    }
    DisposableEffect(controller) {
        ActiveVoiceDictationShortcut.bind(controller)
        onDispose { ActiveVoiceDictationShortcut.unbind(controller) }
    }
    return controller
}

internal class VoiceDictationController(
    val voice: VoiceDictationService,
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    var recording by mutableStateOf(false)
        private set
    var transcribing by mutableStateOf(false)
        private set

    private val ready: Boolean get() = voice.setup.state.value is VoiceSetupState.Ready

    /** Starts a session; returns false (with [onError] fired) if unavailable or already active. */
    suspend fun beginRecording(): Boolean {
        if (recording || transcribing) return false
        if (!ready) {
            onError("Enable voice dictation in Settings")
            return false
        }
        val started = voice.startRecording()
        recording = started
        if (!started) {
            onError(voice.lastError.value ?: "Microphone access denied — check System Settings")
        }
        return started
    }

    fun endRecording() {
        if (!recording) return
        recording = false
        transcribing = true
        scope.launch {
            try {
                val text = voice.finishRecording()
                if (!text.isNullOrBlank()) onText(text) else voice.lastError.value?.let(onError)
            } finally {
                transcribing = false
            }
        }
    }

    fun cancelRecording() {
        if (!recording) return
        recording = false
        scope.launch { runCatching { voice.finishRecording() } }
    }

    private var lastToggleAtMillis = 0L

    /** Toggles recording on/off; used by the configurable keyboard shortcut. */
    fun toggle() {
        if (transcribing) return
        // Key-repeat KeyDown events would otherwise start+stop in one hold.
        val now = currentTimeMillis()
        if (now - lastToggleAtMillis < 350L) return
        lastToggleAtMillis = now
        if (recording) endRecording() else scope.launch { beginRecording() }
    }
}

@Composable
internal fun ChatVoiceDictationButton(
    controller: VoiceDictationController,
    modifier: Modifier = Modifier,
) {
    if (controller.voice is UnavailableVoiceDictationService) return

    val setupState by controller.voice.setup.state.collectAsState()
    val ready = setupState is VoiceSetupState.Ready
    val recording = controller.recording
    val transcribing = controller.transcribing
    val interactive = !transcribing

    Box(
        modifier = modifier
            .height(AndyLayout.ControlHeightMd)
            .clip(RoundedCornerShape(AndyRadius.Control))
            .border(1.dp, if (recording) Rust.copy(alpha = 0.6f) else Border, RoundedCornerShape(AndyRadius.Control))
            .background(AndyColors.Neutral800.copy(alpha = if (ready) 0.55f else 0.28f))
            .alpha(if (interactive) 1f else 0.7f)
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                detectTapGestures(
                    onPress = {
                        var started = false
                        try {
                            started = controller.beginRecording()
                            if (!started) return@detectTapGestures
                            try {
                                awaitRelease()
                            } finally {
                                controller.endRecording()
                            }
                        } catch (_: Throwable) {
                            if (started) controller.cancelRecording()
                        }
                    },
                )
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        VoiceDictationIndicator(recording = recording, transcribing = transcribing, ready = ready)
    }
}

@Composable
private fun VoiceDictationIndicator(
    recording: Boolean,
    transcribing: Boolean,
    ready: Boolean,
) {
    val iconSize = 15.dp
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            val transition = rememberInfiniteTransition(label = "voice-ping")
            val pingScale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.9f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100, easing = LinearOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "voice-ping-scale",
            )
            val pingAlpha by transition.animateFloat(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "voice-ping-alpha",
            )
            Box(
                Modifier
                    .size(iconSize + 6.dp)
                    .graphicsLayer { scaleX = pingScale; scaleY = pingScale; alpha = pingAlpha }
                    .background(Rust, CircleShape),
            )
            Box(
                Modifier
                    .size(iconSize + 10.dp)
                    .background(Rust.copy(alpha = 0.16f), CircleShape),
            )
        }
        if (transcribing) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize + 8.dp),
                strokeWidth = 1.5.dp,
                color = TextPrimary,
            )
        }
        MicIcon(
            color = when {
                transcribing -> TextPrimary.copy(alpha = 0.45f)
                recording -> Rust
                ready -> TextPrimary
                else -> TextSecondary
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun MicIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        val capsuleWidth = w * 0.42f
        val capsuleHeight = h * 0.56f
        val capsuleTop = h * 0.02f
        drawRoundRect(
            color = color,
            topLeft = Offset((w - capsuleWidth) / 2f, capsuleTop),
            size = Size(capsuleWidth, capsuleHeight),
            cornerRadius = CornerRadius(capsuleWidth / 2f),
        )

        val standDiameter = w * 0.78f
        val standStroke = w * 0.12f
        val standTop = capsuleTop + capsuleHeight * 0.22f
        drawArc(
            color = color,
            startAngle = 25f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset((w - standDiameter) / 2f, standTop),
            size = Size(standDiameter, standDiameter),
            style = Stroke(width = standStroke, cap = StrokeCap.Round),
        )

        val stemWidth = w * 0.1f
        val stemTop = standTop + standDiameter * 0.64f
        val stemBottom = h * 0.88f
        drawRoundRect(
            color = color,
            topLeft = Offset((w - stemWidth) / 2f, stemTop),
            size = Size(stemWidth, stemBottom - stemTop),
            cornerRadius = CornerRadius(stemWidth / 2f),
        )

        val baseWidth = w * 0.52f
        val baseHeight = h * 0.09f
        drawRoundRect(
            color = color,
            topLeft = Offset((w - baseWidth) / 2f, h - baseHeight - h * 0.02f),
            size = Size(baseWidth, baseHeight),
            cornerRadius = CornerRadius(baseHeight / 2f),
        )
    }
}

/** Toggles [controller]'s recording when [shortcut] is pressed anywhere within this subtree. */
internal fun Modifier.onVoiceDictationShortcut(
    shortcut: KeyCombo?,
    controller: VoiceDictationController,
): Modifier {
    if (shortcut == null) return this
    return onPreviewKeyEvent { event ->
        // Prefer the window-level handler when bound; still handle here as a fallback
        // for tests / platforms without Window onPreviewKeyEvent.
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (!shortcut.matches(event)) return@onPreviewKeyEvent false
        controller.toggle()
        true
    }
}

/** Insert [insertion] at the current cursor (or selection) of a prompt field. */
internal fun insertTextAtCursor(current: androidx.compose.ui.text.input.TextFieldValue, insertion: String): androidx.compose.ui.text.input.TextFieldValue {
    val start = current.selection.min
    val end = current.selection.max
    val text = current.text.replaceRange(start, end, insertion)
    val caret = start + insertion.length
    return androidx.compose.ui.text.input.TextFieldValue(
        text = text,
        selection = androidx.compose.ui.text.TextRange(caret),
    )
}
