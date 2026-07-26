package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.agent_antigravity
import app.andy.andy.generated.resources.agent_claude
import app.andy.andy.generated.resources.agent_codex
import app.andy.andy.generated.resources.agent_cursor
import app.andy.currentTimeMillis
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.round

private fun agentIconResource(kind: AgentKind): DrawableResource = when (kind) {
    AgentKind.ClaudeCode -> Res.drawable.agent_claude
    AgentKind.Codex -> Res.drawable.agent_codex
    AgentKind.Cursor -> Res.drawable.agent_cursor
    AgentKind.Antigravity -> Res.drawable.agent_antigravity
}

internal fun agentColor(kind: AgentKind): Color = when (kind) {
    AgentKind.ClaudeCode -> Rust
    AgentKind.Codex -> Cyan
    AgentKind.Cursor -> Green
    AgentKind.Antigravity -> Red
}

internal fun agentStatusColor(status: AgentStatus?): Color = when (status) {
    AgentStatus.Working -> Green
    AgentStatus.Blocked -> Rust
    AgentStatus.Done -> Cyan
    AgentStatus.Error -> Red
    null -> Cyan
}

internal fun agentStatusLabel(task: AgentTask): String = when {
    task.isQueued -> "queued"
    isChatRelaunching(task) -> "launching"
    else -> task.status?.name?.lowercase() ?: "queued"
}

@Composable
internal fun StatusDot(status: AgentStatus, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(6.dp)
            .background(agentStatusColor(status), CircleShape),
    )
}

@Composable
internal fun AgentBadge(kind: AgentKind, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, agentColor(kind).copy(alpha = 0.4f), RoundedCornerShape(AndyRadius.R2))
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(agentIconResource(kind)),
            contentDescription = kind.label,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun AgentMark(kind: AgentKind, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(agentIconResource(kind)),
        contentDescription = kind.label,
        modifier = modifier.size(32.dp),
    )
}

@Composable
internal fun AgentPillIcon(kind: AgentKind, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(agentIconResource(kind)),
        contentDescription = null,
        modifier = modifier.size(16.dp),
    )
}

@Composable
internal fun UnreadDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(6.dp)
            .background(Cyan, CircleShape),
    )
}

/**
 * Sidebar / session-row activity spinner.
 *
 * Driven by a coarse ~10 Hz timer instead of Material's indeterminate infinite
 * transition: on a high-refresh desktop display that transition invalidates the
 * whole Skiko surface at display rate (e.g. 144 Hz). The Swing terminal
 * repaint throttle cannot help — Compose paints through Skiko, not Swing.
 */
@Composable
internal fun ProjectActivityIndicator(size: Dp = 10.dp) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            phase = (phase + 0.08f) % 1f
        }
    }
    CircularProgressIndicator(
        progress = { phase },
        modifier = Modifier.size(size),
        color = Cyan,
        strokeWidth = 1.5.dp,
    )
}

internal fun formatElapsed(startMillis: Long?, endMillis: Long?, nowMillis: Long): String? {
    val start = startMillis ?: return null
    val elapsed = ((endMillis ?: nowMillis) - start).coerceAtLeast(0) / 1000
    val hours = elapsed / 3600
    val minutes = (elapsed % 3600) / 60
    val seconds = elapsed % 60
    return when {
        hours > 0 -> "${hours}h ${minutes.pad()}m"
        minutes > 0 -> "${minutes}m ${seconds.pad()}s"
        else -> "${seconds}s"
    }
}

/**
 * True when the chat pane should host the live, typeable CLI rather than a read-only
 * scrollback replay.
 *
 * Interactive while the run is active, still launching, or Andy still owns a live
 * tmux/PTY session for this chat ([terminalLive]). A finished turn keeps the CLI
 * typeable at its prompt; read-only replay is only for chats with no live session.
 */
internal fun isChatTerminalInteractive(task: AgentTask, terminalLive: Boolean): Boolean =
    task.isActive || isChatLaunching(task) || isChatRelaunching(task) || terminalLive

/** Queued for launch, or relaunching for a resume/retry — the terminal is on its way. */
internal fun isChatLaunching(task: AgentTask): Boolean =
    task.status == null && task.finishedAtMillis == null

/** Resume/retry after a finished turn — status cleared but [AgentTask.startedAtMillis] remains. */
internal fun isChatRelaunching(task: AgentTask): Boolean =
    task.status == null && task.startedAtMillis != null && task.finishedAtMillis == null

/**
 * True when Andy's own composer belongs under the terminal: always in read-only mode
 * (it is the only way to type), and while a live CLI holds staged images that must
 * ship with a composed message rather than be typed into the PTY.
 */
internal fun showsChatFollowUpComposer(interactive: Boolean, hasStagedImages: Boolean): Boolean =
    !interactive || hasStagedImages

/** True while the card timer should keep ticking with wall clock. */
internal fun isElapsedLive(task: AgentTask): Boolean =
    task.isActive && task.status == AgentStatus.Working

/** True while the session sidebar/header should show a live activity spinner. */
internal fun isSessionWorking(task: AgentTask): Boolean =
    task.isActive && task.status == AgentStatus.Working

/**
 * End timestamp for [formatElapsed]. Keeps counting only while [isElapsedLive];
 * otherwise freezes at [finishedAtMillis] or the first non-live observation.
 */
@Composable
internal fun rememberElapsedEndMillis(
    taskId: String,
    finishedAtMillis: Long?,
    task: AgentTask,
): Long? {
    val live = isElapsedLive(task)
    val shouldFreeze = finishedAtMillis == null && !live
    val frozenAt = remember(taskId, shouldFreeze) {
        if (shouldFreeze) currentTimeMillis() else null
    }
    return finishedAtMillis ?: frozenAt
}

private fun Long.pad(): String = if (this < 10) "0$this" else toString()

internal fun formatCost(costUsd: Double?, estimated: Boolean = false): String? {
    val cost = costUsd ?: return null
    if (!cost.isFinite()) return null
    val microCents = round(cost * 10_000).toLong()
    val absolute = abs(microCents)
    val dollars = absolute / 10_000
    val cents = (absolute % 10_000) / 100
    val fraction = absolute % 100
    val sign = if (microCents < 0) "-" else ""
    return (if (estimated) "~$" else "$") + "$sign$dollars.${cents.toString().padStart(2, '0')}${fraction.toString().padStart(2, '0')}"
}

internal fun formatTokens(input: Long?, output: Long?): String? {
    if (input == null && output == null) return null
    fun Long.compact(): String = when {
        this >= 1_000_000 -> "${this / 1_000_000}.${(this % 1_000_000) / 100_000}M"
        this >= 1_000 -> "${this / 1_000}.${(this % 1_000) / 100}k"
        else -> toString()
    }
    return "${input?.compact() ?: "-"} in / ${output?.compact() ?: "-"} out"
}
