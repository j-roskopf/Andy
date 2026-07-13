package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.agent_antigravity
import app.andy.andy.generated.resources.agent_claude
import app.andy.andy.generated.resources.agent_codex
import app.andy.andy.generated.resources.agent_cursor
import app.andy.model.AgentKind
import app.andy.model.AgentTaskStatus
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary
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

internal fun agentStatusColor(status: AgentTaskStatus): Color = when (status) {
    AgentTaskStatus.Running -> Green
    AgentTaskStatus.Queued -> Cyan
    AgentTaskStatus.Completed -> Cyan
    AgentTaskStatus.Failed -> Red
    AgentTaskStatus.Stopped -> Rust
    AgentTaskStatus.Unknown -> TextSecondary
}

internal fun agentStatusLabel(status: AgentTaskStatus): String = when (status) {
    AgentTaskStatus.Unknown -> "interrupted"
    else -> status.name.lowercase()
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
