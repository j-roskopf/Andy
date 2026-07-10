package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentKind
import app.andy.model.AgentTaskStatus
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary

internal fun agentMonogram(kind: AgentKind): String = when (kind) {
    AgentKind.ClaudeCode -> "CL"
    AgentKind.Codex -> "CX"
    AgentKind.Cursor -> "CU"
    AgentKind.Antigravity -> "AG"
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
    Text(
        agentMonogram(kind),
        color = agentColor(kind),
        fontFamily = MonoFont,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, agentColor(kind).copy(alpha = 0.4f), RoundedCornerShape(AndyRadius.R2))
            .padding(horizontal = 6.dp, vertical = 3.dp),
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
    val cents = (cost * 100).toInt()
    val fraction = ((cost * 100 - cents) * 100).toInt()
    return (if (estimated) "~$" else "$") + "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}${fraction.toString().padStart(2, '0')}"
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
