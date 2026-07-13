package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentKind
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaWindow
import app.andy.model.agentUsageOverview
import app.andy.service.AndyServices
import app.andy.ui.components.FilterPill
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** Compact provider-usage entry point that expands into quota and local activity detail. */
@Composable
internal fun AgentQuotaMenu(
    services: AndyServices,
    agent: AgentKind,
    modifier: Modifier = Modifier,
) {
    val tasks by services.agentRuns.tasks.collectAsState()
    val quotas by services.agentRuns.providerQuotas.collectAsState()
    val quotaAccess by services.agentRuns.quotaAccess.collectAsState()
    val now = System.currentTimeMillis()
    val overview = remember(tasks, agent, now / 60_000L) { agentUsageOverview(tasks, agent, now) }
    val quota = quotas[agent]
    var expanded by remember(agent) { mutableStateOf(false) }
    var refreshing by remember(agent) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val liveLabel = quota?.windows?.firstOrNull()?.remainingFraction?.let { "${(it * 100).toInt()}% left" }
        ?: if (quota != null) "limits" else "usage"

    Box(modifier) {
        FilterPill(liveLabel, expanded || quota != null, agentColor(agent)) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 390.dp, max = 440.dp),
        ) {
            ProviderUsagePanel(
                agent = agent,
                overview = overview,
                quota = quota,
                accountAccessEnabled = quotaAccess.allows(agent),
                nowMillis = now,
                refreshing = refreshing,
                onEnableAccountAccess = { services.agentRuns.setQuotaAccess(agent, true) },
                onDisableAccountAccess = { services.agentRuns.setQuotaAccess(agent, false) },
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        services.agentRuns.refreshProviderQuotas()
                        refreshing = false
                    }
                },
            )
        }
    }
}

@Composable
private fun ProviderUsagePanel(
    agent: AgentKind,
    overview: app.andy.model.AgentUsageOverview,
    quota: AgentProviderQuota?,
    accountAccessEnabled: Boolean,
    nowMillis: Long,
    refreshing: Boolean,
    onEnableAccountAccess: () -> Unit,
    onDisableAccountAccess: () -> Unit,
    onRefresh: () -> Unit,
) {
    val windows = quota?.windows.orEmpty()
    Column(
        Modifier
            .background(Panel)
            .padding(AndySpace.S4),
        verticalArrangement = Arrangement.spacedBy(AndySpace.S3),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(AndySpace.S2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentBadge(agent)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(agent.label, color = TextPrimary, fontFamily = MonoFont, fontSize = 14.sp)
                    Text("usage & limits", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                quota?.accountLabel?.let { Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp) }
                FilterPill(if (refreshing) "checking" else "refresh", refreshing, agentColor(agent), onClick = onRefresh)
            }
        }

        HorizontalDivider(color = Border)

        if (!accountAccessEnabled && agent != AgentKind.Codex) {
            AccountAccessPrompt(agent, onEnableAccountAccess)
        } else if (windows.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(PanelSoft, RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                    .padding(AndySpace.S3),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (quota?.source?.name == "ProviderQuery") "provider does not expose quota data" else "live quota not reported yet",
                    color = TextPrimary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
                Text(
                    if (quota?.source?.name == "ProviderQuery") {
                        "${agent.cliName} was queried directly but did not return quota windows for this account."
                    } else if (agent == AgentKind.Antigravity) {
                        "Account access is enabled. Andy will use an already-running local Antigravity session when its quota endpoint is available."
                    } else {
                        "Try refresh to read the hourly, session, weekly, or credit windows available for this account."
                    },
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AndySpace.S2)) {
                Text("live account limits", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                windows.forEach { window -> QuotaWindowRow(window, agentColor(agent), nowMillis) }
            }
        }

        HorizontalDivider(color = Border)

        Text("local activity", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AndySpace.S4)) {
            UsageMetric("24h", overview.runsLast24Hours, overview.tokensLast24Hours, overview.costLast24Hours, Modifier.weight(1f))
            UsageMetric("30d", overview.runsLast30Days, overview.tokensLast30Days, overview.costLast30Days, Modifier.weight(1f))
        }
        UsageHistogram(quota?.providerTokenDays?.takeIf { it.isNotEmpty() } ?: overview.tokenDays, agentColor(agent))
        Text(
            quota?.lifetimeTokens?.let { "account lifetime: ${compactTokens(it)}" }
                ?: overview.topModel?.let { "top model: $it" }
                ?: "no local runs yet",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
        )
        Text(
            if (quota?.source?.name == "ProviderQuery") {
                when (agent) {
                    AgentKind.Codex -> "Refreshed directly through the installed Codex app server."
                    AgentKind.ClaudeCode -> "Refreshed with the Claude OAuth credential you explicitly allowed Andy to use."
                    AgentKind.Cursor -> "Refreshed with Cursor.app's local sign-in credential; browser cookies are never read."
                    AgentKind.Antigravity -> "Refreshed from the running local Antigravity session."
                }
            } else {
                "Local activity comes from Andy task history. Account-limit access is always opt-in per provider."
            },
            color = TextSecondary.copy(alpha = 0.72f),
            fontFamily = MonoFont,
            fontSize = 9.sp,
        )
        if (accountAccessEnabled && agent != AgentKind.Codex) {
            FilterPill("turn off account access", false, agentColor(agent), onClick = onDisableAccountAccess)
        }
    }
}

@Composable
private fun AccountAccessPrompt(agent: AgentKind, onEnable: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PanelSoft, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(AndySpace.S3),
        verticalArrangement = Arrangement.spacedBy(AndySpace.S2),
    ) {
        Text("account limits are off", color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp)
        Text(
            when (agent) {
                AgentKind.ClaudeCode -> "Allow Andy to use your local Claude OAuth credential to ask Anthropic for session and weekly limits. It never reads browser cookies."
                AgentKind.Cursor -> "Allow Andy to use Cursor.app's local sign-in credential to ask Cursor for plan limits. It never reads browser cookies."
                AgentKind.Antigravity -> "Allow Andy to query an already-running local Antigravity session. It will not open a login flow or read browser cookies."
                AgentKind.Codex -> "Codex limits are read directly from the installed Codex app server."
            },
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
        )
        FilterPill("allow account access", true, agentColor(agent), onClick = onEnable)
    }
}

@Composable
private fun QuotaWindowRow(window: AgentQuotaWindow, accent: Color, nowMillis: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(window.label, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.weight(1f))
            val value = window.remainingFraction?.let { "${(it * 100).toInt()}% left" }
                ?: window.resetAtMillis?.let { "resets ${formatQuotaReset(it, nowMillis)}" }
                ?: window.detail.orEmpty()
            Text(value, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(AndyColors.Neutral600, RoundedCornerShape(AndyRadius.Pill)),
        ) {
            window.remainingFraction?.let { remaining ->
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(remaining.coerceIn(0f, 1f))
                        .background(accent, RoundedCornerShape(AndyRadius.Pill)),
                )
            }
        }
        window.resetAtMillis?.let { reset ->
            Text("resets ${formatQuotaReset(reset, nowMillis)}", color = TextSecondary.copy(alpha = 0.82f), fontFamily = MonoFont, fontSize = 9.sp)
        }
    }
}

@Composable
private fun UsageMetric(label: String, runs: Int, tokens: Long, cost: Double, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        Text("$runs ${if (runs == 1) "run" else "runs"}", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp)
        Text("${compactTokens(tokens)} · ${formatUsageCost(cost)}", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
    }
}

@Composable
private fun UsageHistogram(values: List<Long>, accent: Color) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    Row(
        Modifier.fillMaxWidth().height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEach { value ->
            Box(
                Modifier
                    .weight(1f)
                    .height(((value.toFloat() / max).coerceAtLeast(0.08f) * 30).dp)
                    .background(accent.copy(alpha = if (value > 0) 0.78f else 0.22f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

private fun compactTokens(value: Long): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}.${(value % 1_000_000) / 100_000}M tok"
    value >= 1_000 -> "${value / 1_000}k tok"
    else -> "$value tok"
}

private fun formatUsageCost(value: Double): String = "${if (value > 0) "~" else ""}$${"%.2f".format(value)}"

private fun formatQuotaReset(resetAtMillis: Long, nowMillis: Long): String {
    val minutes = ((resetAtMillis - nowMillis) / 60_000L).coerceAtLeast(0)
    return if (minutes >= 60) "in ${minutes / 60}h ${minutes % 60}m" else "in ${minutes}m"
}
