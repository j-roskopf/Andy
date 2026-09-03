package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.andy.ui.components.Avatar
import app.andy.ui.components.AvatarSize
import app.andy.ui.components.StatusDotVariant
import app.andy.ui.components.StatusDot as ComponentStatusDot
import app.andy.ui.components.ThinkingOrb
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
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
    AgentKind.OpenCode -> Res.drawable.agent_opencode
    AgentKind.Pi -> Res.drawable.agent_pi
    AgentKind.Hermes -> Res.drawable.agent_hermes
    AgentKind.OpenClaw -> Res.drawable.agent_openclaw
    AgentKind.Goose -> Res.drawable.agent_goose
    AgentKind.Ollama -> Res.drawable.agent_ollama
    AgentKind.LMStudio -> Res.drawable.agent_lmstudio
}

private val PiViolet = Color(0xFFA78BFA)

fun agentColor(kind: AgentKind): Color = when (kind) {
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

fun agentStatusColor(status: AgentStatus?): Color = when (status) {
    AgentStatus.Working -> Green
    AgentStatus.Blocked -> Rust
    AgentStatus.Done -> Cyan
    AgentStatus.Error -> Red
    null -> Cyan
}

fun agentStatusVariant(
    task: AgentTask,
    planModeActive: Boolean = task.planMode,
    hasPendingPlanEntries: Boolean = false,
): StatusDotVariant = when {
    isAwaitingPlanConfirmation(task, planModeActive, hasPendingPlanEntries) -> StatusDotVariant.Success
    task.status == AgentStatus.Working -> StatusDotVariant.Info
    task.status == AgentStatus.Done -> StatusDotVariant.Success
    task.status == AgentStatus.Error -> StatusDotVariant.Error
    task.status == AgentStatus.Blocked -> StatusDotVariant.Warning
    else -> StatusDotVariant.Neutral
}

/**
 * True when a turn finished while still waiting on a plan — either Andy/ACP plan mode
 * is active, or the latest transcript PlanUpdate still has pending entries (Cursor
 * Create Plan can end_turn without flipping mode).
 */
fun isAwaitingPlanConfirmation(
    task: AgentTask,
    planModeActive: Boolean = task.planMode,
    hasPendingPlanEntries: Boolean = false,
): Boolean = task.status == AgentStatus.Done &&
    !task.needsInput &&
    (planModeActive || hasPendingPlanEntries)

fun agentStatusColor(
    task: AgentTask,
    planModeActive: Boolean = task.planMode,
    hasPendingPlanEntries: Boolean = false,
): Color = if (isAwaitingPlanConfirmation(task, planModeActive, hasPendingPlanEntries)) {
    Green
} else {
    agentStatusColor(task.status)
}

fun agentStatusLabel(
    task: AgentTask,
    planModeActive: Boolean = task.planMode,
    hasPendingPlanEntries: Boolean = false,
): String {
    val status = task.status
    return when {
    isAwaitingPlanConfirmation(task, planModeActive, hasPendingPlanEntries) -> "plan ready"
    // Prefer the lifecycle status when present so thin/partial clients cannot show
    // "queued" for a Done/Working chat that merely omitted startedAtMillis.
    status != null -> status.name.lowercase()
    isChatRelaunching(task) -> "launching"
    task.isQueued -> "queued"
    else -> "queued"
    }
}

@Composable
fun AgentStatusDot(
    task: AgentTask,
    planModeActive: Boolean = task.planMode,
    hasPendingPlanEntries: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val variant = agentStatusVariant(task, planModeActive, hasPendingPlanEntries)
    val pulsing = isSessionWorking(task)
    ComponentStatusDot(
        modifier = modifier,
        variant = variant,
        pulsing = pulsing,
    )
}

@Composable
fun AgentBadge(kind: AgentKind, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(2.dp, agentColor(kind).copy(alpha = 0.4f), CircleShape)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Avatar(size = AvatarSize.Sm, name = kind.label) {
            Image(
                painter = painterResource(agentIconResource(kind)),
                contentDescription = kind.label,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun AgentMark(kind: AgentKind, modifier: Modifier = Modifier) {
    Avatar(modifier = modifier, size = AvatarSize.Md, name = kind.label) {
        Image(
            painter = painterResource(agentIconResource(kind)),
            contentDescription = kind.label,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun AgentPillIcon(kind: AgentKind, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(agentIconResource(kind)),
        contentDescription = null,
        modifier = modifier.size(16.dp),
    )
}

@Composable
fun UnreadDot(modifier: Modifier = Modifier) {
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
fun ProjectActivityIndicator(size: Dp = 16.dp) {
    ThinkingOrb(
        size = size,
        color = Cyan,
        animate = true,
        contentDescription = "Working",
    )
}

@Composable
fun ChatInboxSectionLabel(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        label.uppercase(),
        color = TextSecondary.copy(alpha = 0.72f),
        fontFamily = MonoFont,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
fun ChatSessionSidebarRow(
    task: AgentTask,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showAgentIcon: Boolean = true,
    showRelativeAge: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Drawn over the trailing edge of the title/age without taking layout width.
     * Receives the row's hovered state so callers can swap idle/hover chrome
     * without a second hoverable (which would fight this row's background).
     * Suppressed while the session is working so the activity indicator stays clear.
     */
    endOverlay: (@Composable (hovered: Boolean) -> Unit)? = null,
) {
    val working = isSessionWorking(task) || isChatLaunching(task) || isChatRelaunching(task)
    val prompt = remember(task.prompt, task.title) {
        task.prompt.lineSequence().firstOrNull()?.trim().orEmpty()
            .ifBlank { task.title }
    }
    val interactionSource = remember(task.id) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val rowBackground = when {
        selected -> AndyColors.SurfaceSelected
        hovered -> AndyColors.SurfaceHover
        else -> Color.Transparent
    }
    // Relative ages are minute-granular; tick so "now" advances without waiting on task updates.
    var nowMillis by remember(task.id) { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(showRelativeAge, task.id) {
        if (!showRelativeAge) return@LaunchedEffect
        while (true) {
            nowMillis = currentTimeMillis()
            delay(30_000)
        }
    }
    val contentPaddingVertical = if (showRelativeAge) 7.dp else 5.dp
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AndyRadius.Row))
            .background(rowBackground)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = contentPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showAgentIcon) {
                Avatar(
                    size = AvatarSize.Xsm,
                    name = task.agent.label,
                ) {
                    Image(
                        painter = painterResource(agentIconResource(task.agent)),
                        contentDescription = task.agent.label,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Marked in every rail, not just the Agents Temporary section — a project chat list
            // shows temporary chats inline, and closing one is unrecoverable.
            if (task.temporary) {
                Text(
                    "temp",
                    color = Yellow,
                    fontFamily = MonoFont,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
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
            if (showRelativeAge) {
                Text(
                    formatChatAge(task.lastActivityMillis(), nowMillis),
                    color = TextSecondary.copy(alpha = 0.76f),
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            when {
                working -> ProjectActivityIndicator(12.dp)
                trailing != null -> trailing()
            }
            if (task.unread && !selected) {
                UnreadDot()
            }
        }
        if (endOverlay != null && !working) {
            // matchParentSize so overlay chrome cannot widen/tall-en the row on hover.
            Box(Modifier.matchParentSize()) {
                val endPad = if (task.unread && !selected) {
                    // Keep the unread dot clear: row end pad + gap + dot.
                    10.dp + 8.dp + 6.dp
                } else {
                    10.dp
                }
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = endPad),
                ) {
                    endOverlay(hovered)
                }
            }
        }
    }
}

private fun AgentTask.lastActivityMillis(): Long = maxOf(
    createdAtMillis,
    startedAtMillis ?: 0L,
    finishedAtMillis ?: 0L,
)

fun formatChatAge(timestampMillis: Long, nowMillis: Long = currentTimeMillis()): String {
    val elapsed = (nowMillis - timestampMillis).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    val month = 30 * day
    val year = 365 * day
    return when {
        elapsed < minute -> "now"
        elapsed < hour -> "${elapsed / minute}m"
        elapsed < day -> "${elapsed / hour}h"
        elapsed < month -> "${elapsed / day}d"
        elapsed < year -> "${elapsed / month}mo"
        else -> "${elapsed / year}y"
    }
}

fun formatElapsed(startMillis: Long?, endMillis: Long?, nowMillis: Long): String? {
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

/** Compact clock used in the post-turn "Worked for X:XX" line. */
fun formatWorkedClock(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

fun workedHeadline(durationMs: Long, success: Boolean): String {
    val clock = formatWorkedClock(durationMs)
    return if (success) "Worked for $clock" else "Failed after $clock"
}

/** True when Andy should show post-turn chrome (duration / edited-files) instead of a live timer. */
fun showsCompletedTurnChrome(task: AgentTask): Boolean {
    if (isElapsedLive(task) || isChatLaunching(task) || isChatRelaunching(task)) return false
    return task.status == AgentStatus.Done ||
        task.status == AgentStatus.Error ||
        task.finishedAtMillis != null
}

/**
 * True when the chat pane should host the live, typeable CLI rather than a read-only
 * scrollback replay.
 *
 * Interactive while the run is active, still launching, or Andy still owns a live
 * tmux/PTY session for this chat ([terminalLive]). A finished turn keeps the CLI
 * typeable at its prompt; read-only replay is only for chats with no live session.
 */
fun isChatTerminalInteractive(task: AgentTask, terminalLive: Boolean): Boolean {
    // Stop must leave the waiting overlay even if tmux/PTY teardown is still in flight.
    if (task.stoppedByUser && !task.isActive) return false
    return task.isActive || isChatLaunching(task) || isChatRelaunching(task) || terminalLive
}

/** Queued for launch, or relaunching for a resume/retry — the terminal is on its way. */
fun isChatLaunching(task: AgentTask): Boolean =
    task.status == null && task.finishedAtMillis == null

/** Resume/retry after a finished turn — status cleared but [AgentTask.startedAtMillis] remains. */
fun isChatRelaunching(task: AgentTask): Boolean =
    task.status == null && task.startedAtMillis != null && task.finishedAtMillis == null

/**
 * True when Andy's own composer belongs under the terminal: always in read-only mode
 * (it is the only way to type), and while a live CLI holds staged images that must
 * ship with a composed message rather than be typed into the PTY.
 *
 * [interactive] should track [isChatTerminalInteractive], not viewer attach state —
 * delaying on attach briefly shows the composer and steals terminal height (a layout flash).
 */
fun showsChatFollowUpComposer(interactive: Boolean, hasStagedImages: Boolean): Boolean =
    !interactive || hasStagedImages

/** True while the card timer should keep ticking with wall clock. */
fun isElapsedLive(task: AgentTask): Boolean =
    task.isActive && task.status == AgentStatus.Working

/** True while the session sidebar/header should show a live activity spinner. */
fun isSessionWorking(task: AgentTask): Boolean =
    task.isActive && task.status == AgentStatus.Working

/**
 * End timestamp for [formatElapsed]. Keeps counting only while [isElapsedLive];
 * otherwise freezes at [finishedAtMillis] or the first non-live observation.
 */
@Composable
fun rememberElapsedEndMillis(
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

fun formatCost(costUsd: Double?, estimated: Boolean = false): String? {
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

fun formatTokens(input: Long?, output: Long?): String? {
    if (input == null && output == null) return null
    fun Long.compact(): String = when {
        this >= 1_000_000 -> "${this / 1_000_000}.${(this % 1_000_000) / 100_000}M"
        this >= 1_000 -> "${this / 1_000}.${(this % 1_000) / 100}k"
        else -> toString()
    }
    return "${input?.compact() ?: "-"} in / ${output?.compact() ?: "-"} out"
}
