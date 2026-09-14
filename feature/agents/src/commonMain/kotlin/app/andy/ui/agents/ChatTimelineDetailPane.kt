package app.andy.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.formatDisplayDateTime
import app.andy.model.TimelineDetail
import app.andy.model.TimelineTiming
import app.andy.ui.components.ChatMarkdown
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TabBarItem
import app.andy.ui.components.TabBarRow
import app.andy.ui.components.TabListSize
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

private enum class TimelineDetailTab {
    Payload,
    Result,
    Timing,
}

@Composable
fun ChatTimelineDetailPane(
    detail: TimelineDetail,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val availableTabs = remember(detail) {
        buildList {
            if (!detail.payload.isNullOrBlank()) add(TimelineDetailTab.Payload)
            if (!detail.result.isNullOrBlank()) add(TimelineDetailTab.Result)
            if (detail.timing != null) add(TimelineDetailTab.Timing)
        }
    }
    var tab by remember(detail.header, availableTabs) {
        mutableStateOf<TimelineDetailTab?>(null)
    }

    PanelCard(
        modifier = modifier.fillMaxHeight().testTag("chat-timeline-detail"),
        borderColor = Color.Transparent,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                detail.header,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onClose) { Text("Close", fontSize = 11.sp) }
        }
        if (availableTabs.isNotEmpty()) {
            TabBarRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                size = TabListSize.Sm,
                hasDivider = false,
            ) {
                availableTabs.forEach { value ->
                    TabBarItem(
                        label = value.name,
                        selected = tab == value,
                        onClick = { tab = if (tab == value) null else value },
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                null -> {
                    DetailMarkdown(detail.summary)
                    val tokens = buildString {
                        detail.inputTokens?.let { append("**Input tokens:** `").append(it).append('`') }
                        detail.outputTokens?.let {
                            if (isNotEmpty()) append("  ·  ")
                            append("**Output tokens:** `").append(it).append('`')
                        }
                    }
                    if (tokens.isNotBlank()) {
                        DetailMarkdown(tokens)
                    }
                }
                TimelineDetailTab.Payload -> DetailMarkdown(
                    detail.payload?.takeIf { it.isNotBlank() } ?: "—",
                    preserveLineBreaks = true,
                )
                TimelineDetailTab.Result -> DetailMarkdown(
                    detail.result?.takeIf { it.isNotBlank() } ?: "—",
                    preserveLineBreaks = true,
                )
                TimelineDetailTab.Timing -> TimingBody(detail.timing)
            }
        }
    }
}

@Composable
private fun DetailMarkdown(
    text: String,
    preserveLineBreaks: Boolean = false,
) {
    if (text.isBlank() || text == "—") {
        Text("—", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
        return
    }
    ChatMarkdown(
        text = text,
        lineHeight = 18.sp,
        preserveLineBreaks = preserveLineBreaks,
        modifier = Modifier.fillMaxWidth().testTag("chat-timeline-detail-body"),
    )
}

@Composable
private fun TimingBody(timing: TimelineTiming?) {
    if (timing == null) {
        Text("—", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TimingLine("Started", formatTimelineMillis(timing.startedAtMillis))
        TimingLine(
            "Ended",
            formatTimelineMillis(timing.endedAtMillis),
        )
        TimingLine(
            "Duration",
            timing.durationMs?.let { "$it ms" } ?: "—",
        )
        TimingLine("Timing source", timing.sourceLabel)
        if (timing.approximate) {
            Text(
                "Timestamps for this step were approximated from neighboring events.",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = MonoFont,
            )
        }
    }
}

@Composable
private fun TimingLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextSecondary, fontSize = 11.sp, fontFamily = MonoFont)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontFamily = MonoFont)
    }
}

internal fun formatTimelineMillis(millis: Long?): String {
    if (millis == null || millis <= 0L) return "—"
    return formatDisplayDateTime(millis)
}
