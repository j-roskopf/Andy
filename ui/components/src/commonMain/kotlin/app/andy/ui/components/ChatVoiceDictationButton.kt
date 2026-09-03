package app.andy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import app.andy.andy.generated.resources.Res
import app.andy.currentTimeMillis
import app.andy.service.UnavailableVoiceDictationService
import app.andy.service.VoiceDictationService
import app.andy.service.VoiceSetupState
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

enum class VoiceDictationButtonStyle {
    /** Bordered pill with background — settings and legacy composer chrome. */
    Pill,
    /** Icon-only — Astryx full-featured composer trailing control. */
    Bare,
}


/**
 * Desktop [androidx.compose.ui.window.Window] preview-key hook binds the focused chat
 * composer's controller so the configurable shortcut works even when focus is outside
 * the composer card (sidebar, transcript, menus).
 */
object ActiveVoiceDictationShortcut {
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
 * Hoisted toggle-to-talk state so both the mic button and an external trigger
 * (e.g. a keyboard shortcut) can drive the same recording session.
 *
 * [active] must be true only for the visible composer. Retained inactive destinations
 * stay composed, so they pass false to avoid stealing the global shortcut slot.
 */
@Composable
fun rememberVoiceDictationController(
    voice: VoiceDictationService,
    onText: (String) -> Unit,
    onError: (String) -> Unit = {},
    active: Boolean = true,
): VoiceDictationController {
    val scope = rememberCoroutineScope()
    val currentOnText by rememberUpdatedState(onText)
    val currentOnError by rememberUpdatedState(onError)
    val controller = remember(voice) {
        VoiceDictationController(voice, scope, { currentOnText(it) }, { currentOnError(it) })
    }
    DisposableEffect(controller, active) {
        if (active) {
            ActiveVoiceDictationShortcut.bind(controller)
        } else {
            controller.discardActiveCapture()
        }
        onDispose {
            ActiveVoiceDictationShortcut.unbind(controller)
            controller.discardActiveCapture()
        }
    }
    return controller
}

class VoiceDictationController(
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
        // Prefer the sync cancel path so UI-scope cancellation cannot leave the mic open.
        voice.cancelRecording()
    }

    /**
     * Stops capture without relying on the composition [scope]. Used when the owning
     * composer is disposed or becomes inactive while a shortcut-started session is live.
     */
    fun discardActiveCapture() {
        if (!recording) return
        recording = false
        voice.cancelRecording()
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

/** Button widths per state; recording widens into a pill to fit the live waveform + timer. */
private val IdleWidth = 40.dp
private val RecordingWidth = 128.dp
private val TranscribingWidth = 116.dp

@Composable
fun ChatVoiceDictationButton(
    controller: VoiceDictationController,
    modifier: Modifier = Modifier,
    style: VoiceDictationButtonStyle = VoiceDictationButtonStyle.Pill,
) {
    if (controller.voice is UnavailableVoiceDictationService) return

    val setupState by controller.voice.setup.state.collectAsState()
    val ready = setupState is VoiceSetupState.Ready
    val recording = controller.recording
    val transcribing = controller.transcribing
    val interactive = !transcribing
    val level by controller.voice.audioLevel.collectAsState()

    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        if (!recording) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val startedAt = currentTimeMillis()
        while (true) {
            elapsedMs = currentTimeMillis() - startedAt
            delay(100)
        }
    }

    if (style == VoiceDictationButtonStyle.Bare) {
        BareVoiceDictationButton(
            controller = controller,
            modifier = modifier,
            ready = ready,
            recording = recording,
            transcribing = transcribing,
            interactive = interactive,
            level = level,
            elapsedMs = elapsedMs,
        )
        return
    }

    val targetWidth = when {
        recording -> RecordingWidth
        transcribing -> TranscribingWidth
        else -> IdleWidth
    }
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "voiceButtonWidth",
    )

    Box(
        modifier = modifier
            .width(width)
            .height(AndyLayout.ControlHeightMd)
            .clip(RoundedCornerShape(AndyRadius.Control))
            .border(1.dp, if (recording) Rust.copy(alpha = 0.6f) else PaneDividerTint, RoundedCornerShape(AndyRadius.Control))
            .background(AndyColors.Neutral800.copy(alpha = if (ready) 0.55f else 0.28f))
            .alpha(if (interactive) 1f else 0.7f)
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                detectTapGestures(onTap = { controller.toggle() })
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        VoiceDictationIndicator(
            recording = recording,
            transcribing = transcribing,
            ready = ready,
            level = level,
            elapsedMs = elapsedMs,
        )
    }
}

@Composable
private fun BareVoiceDictationButton(
    controller: VoiceDictationController,
    modifier: Modifier = Modifier,
    ready: Boolean,
    recording: Boolean,
    transcribing: Boolean,
    interactive: Boolean,
    level: Float,
    elapsedMs: Long,
) {
    val tint = when {
        recording -> Rust
        ready -> TextPrimary
        else -> TextSecondary
    }
    Box(
        modifier = modifier
            .alpha(if (interactive) 1f else 0.7f)
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                detectTapGestures(onTap = { controller.toggle() })
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            recording -> Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideIcon(
                    Lucide.Mic,
                    Rust,
                    Modifier.size(AndyLayout.IconLg),
                    contentDescription = "Stop recording",
                )
                VoiceWaveform(
                    level = level,
                    color = Rust,
                    modifier = Modifier.width(36.dp).height(18.dp),
                )
                Text(
                    text = formatElapsed(elapsedMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFont),
                    color = TextSecondary,
                )
            }
            transcribing -> Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spinner(spinnerSize = SpinnerSize.Sm, shade = SpinnerShade.Subtle)
                Text(
                    text = "Transcribing…",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            else -> LucideIcon(
                Lucide.Mic,
                tint,
                Modifier.size(AndyLayout.IconLg),
                contentDescription = "Voice input",
            )
        }
    }
}

@Composable
private fun VoiceDictationIndicator(
    recording: Boolean,
    transcribing: Boolean,
    ready: Boolean,
    level: Float,
    elapsedMs: Long,
) {
    when {
        recording -> Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon(Lucide.Mic, Rust, Modifier.size(13.dp))
            VoiceWaveform(
                level = level,
                color = Rust,
                modifier = Modifier.width(42.dp).height(22.dp),
            )
            Text(
                text = formatElapsed(elapsedMs),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFont),
                color = TextSecondary,
            )
        }
        transcribing -> Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(spinnerSize = SpinnerSize.Md, shade = SpinnerShade.Subtle)
            Text(
                text = "Transcribing…",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        else -> LucideIcon(
            Lucide.Mic,
            if (ready) TextPrimary else TextSecondary,
            Modifier.size(15.dp),
        )
    }
}

/** mm:ss elapsed, monospaced so the recording pill doesn't jitter width as digits change. */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * Live multi-bar level meter. Each bar chases the real mic [level] (0f..1f) with a slightly
 * different animation duration so the bars ripple rather than moving in lockstep — the ripple
 * timing is cosmetic, but the target height each bar chases is real captured amplitude.
 */
@Composable
private fun VoiceWaveform(
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 5,
) {
    val minFraction = 0.1f
    val bars = remember { List(barCount) { Animatable(minFraction) } }
    LaunchedEffect(level) {
        val center = (barCount - 1) / 2f
        bars.forEachIndexed { index, bar ->
            val centerWeight = 1f - kotlin.math.abs(index - center) * 0.2f
            val target = (minFraction + level * 1.15f * centerWeight).coerceIn(minFraction, 1f)
            launch {
                // Springy rather than tweened so a loud syllable visibly snaps the bars up
                // instead of just easing toward the target.
                bar.animateTo(
                    target,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f - index * 30f),
                )
            }
        }
    }
    Canvas(modifier) {
        val barWidth = size.width / (barCount * 2f - 1f)
        var x = 0f
        bars.forEach { bar ->
            val barHeight = size.height * bar.value
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
            x += barWidth * 2f
        }
    }
}

/** Toggles [controller]'s recording when [shortcut] is pressed anywhere within this subtree. */
fun Modifier.onVoiceDictationShortcut(
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
fun insertTextAtCursor(current: androidx.compose.ui.text.input.TextFieldValue, insertion: String): androidx.compose.ui.text.input.TextFieldValue {
    val start = current.selection.min
    val end = current.selection.max
    val text = current.text.replaceRange(start, end, insertion)
    val caret = start + insertion.length
    return androidx.compose.ui.text.input.TextFieldValue(
        text = text,
        selection = androidx.compose.ui.text.TextRange(caret),
    )
}
