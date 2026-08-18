package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.agent_antigravity
import app.andy.andy.generated.resources.agent_claude
import app.andy.andy.generated.resources.agent_codex
import app.andy.andy.generated.resources.agent_cursor
import app.andy.andy.generated.resources.agent_opencode
import app.andy.andy.generated.resources.agent_pi
import app.andy.andy.generated.resources.agent_goose
import app.andy.andy.generated.resources.agent_hermes
import app.andy.andy.generated.resources.agent_openclaw
import app.andy.andy.generated.resources.agent_ollama
import app.andy.andy.generated.resources.agent_lmstudio
import app.andy.currentTimeMillis
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.ui.components.ThinkingOrb
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.round

private fun agentIconResource(kind: AgentKind): DrawableResource = when (kind) {
    AgentKind.ClaudeCode -> Res.drawable.agent_claude
    AgentKind.Codex -> Res.drawable.agent_codex
    AgentKind.Cursor -> Res.drawable.agent_cursor
    AgentKind.Antigravity -> Res.drawable.agent_antigravity
    AgentKind.OpenCode -> Res.drawable.agent_opencode
    AgentKind.Pi -> Res.drawable.agent_pi
    AgentKind.Hermes -> Res.drawable.agent_hermes
    AgentKind.OpenClaw -> Res.drawable.agent_openclaw
    AgentKind.Goose -> Res.drawable.agent_goose
    AgentKind.Ollama -> Res.drawable.agent_ollama
    AgentKind.LMStudio -> Res.drawable.agent_lmstudio
}

private val PiViolet = Color(0xFFA78BFA)

internal fun agentColor(kind: AgentKind): Color = when (kind) {
    AgentKind.ClaudeCode -> Rust
    AgentKind.Codex -> Cyan
    AgentKind.Cursor -> Green
    AgentKind.Antigravity -> Red
    AgentKind.OpenCode -> Yellow
    AgentKind.Pi -> PiViolet
    AgentKind.Hermes -> Color(0xFF32C7B5)
    AgentKind.OpenClaw -> Color(0xFFCB3434)
    AgentKind.Goose -> Color(0xFFE5E5E5)
    AgentKind.Ollama -> Color(0xFF14B8A6)
    AgentKind.LMStudio -> Color(0xFF818CF8)
}

internal fun agentStatusColor(status: AgentStatus?): Color = when (status) {
    AgentStatus.Working -> Green
    AgentStatus.Blocked -> Rust
    AgentStatus.Done -> Cyan
    AgentStatus.Error -> Red
    null -> Cyan
}

/**
 * True when a turn finished while still waiting on a plan — either Andy/ACP plan mode
 * is active, or the latest transcript PlanUpdate still has pending entries (Cursor
 * Create Plan can end_turn without flipping mode).
 */
internal fun isAwaitingPlanConfirmation(
    task: AgentTask,
    planModeActive: Boolean = task.planMode,
    hasPendingPlanEntries: Boolean = false,
): Boolean = task.status == AgentStatus.Done &&
    !task.needsInput &&
    (planModeActive || hasPendingPlanEntries)

internal fun agentStatusColor(
    task: AgentTask,
    planModeActive: Boolean = task.planMode,
    hasPendingPlanEntries: Boolean = false,
): Color = if (isAwaitingPlanConfirmation(task, planModeActive, hasPendingPlanEntries)) {
    Green
} else {
    agentStatusColor(task.status)
}

internal fun agentStatusLabel(
    task: AgentTask,
    planModeActive: Boolean = task.planMode,
    hasPendingPlanEntries: Boolean = false,
): String = when {
    isAwaitingPlanConfirmation(task, planModeActive, hasPendingPlanEntries) -> "plan ready"
    // Prefer the lifecycle status when present so thin/partial clients cannot show
    // "queued" for a Done/Working chat that merely omitted startedAtMillis.
    task.status != null -> task.status.name.lowercase()
    isChatRelaunching(task) -> "launching"
    task.isQueued -> "queued"
    else -> "queued"
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
            .background(AndyColors.Neutral900.copy(alpha = AndyOverlay.Medium), RoundedCornerShape(AndyRadius.Control))
            .border(1.dp, agentColor(kind).copy(alpha = 0.4f), RoundedCornerShape(AndyRadius.Control))
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
 * Sidebar / session-row activity marker.
 *
 * Animated via the shared [app.andy.ui.components.ThinkingOrbClock] rather than a
 * per-row phase timer — see ThinkingOrb for why a per-instance timer forced
 * full-window Skiko redraws whenever multiple sessions were working at once.
 */
@Composable
internal fun ProjectActivityIndicator(size: Dp = 16.dp) {
    ThinkingOrb(
        size = size,
        color = Cyan,
        animate = true,
        contentDescription = "Working",
    )
}

@Composable
internal fun ChatSessionSidebarRow(
    task: AgentTask,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val working = isSessionWorking(task)
    val prompt = remember(task.prompt, task.title) {
        task.prompt.lineSequence().firstOrNull()?.trim().orEmpty()
            .ifBlank { task.title }
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AndyRadius.Row))
            .background(if (selected) AndyColors.SurfaceSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentPillIcon(task.agent, Modifier.size(14.dp))
        Text(
            prompt,
            color = when {
                selected || task.unread -> TextPrimary
                else -> TextSecondary
            },
            fontFamily = DisplayFont,
            fontWeight = when {
                task.unread && !selected -> FontWeight.Medium
                selected -> FontWeight.Medium
                else -> FontWeight.Normal
            },
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            working -> ProjectActivityIndicator(12.dp)
            trailing != null -> trailing()
        }
        if (task.unread && !selected) {
            UnreadDot()
        }
    }
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
 *
 * [interactive] should track [isChatTerminalInteractive], not viewer attach state —
 * delaying on attach briefly shows the composer and steals terminal height (a layout flash).
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
