package app.andy.ui.bugs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.domain.InvestigationFilterGroup
import app.andy.domain.InvestigationTimelineFilters
import app.andy.domain.detailFields
import app.andy.domain.groupActive
import app.andy.domain.shortTag
import app.andy.domain.withGroupToggled
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventSeverity
import app.andy.ui.components.DetailRow
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * The investigation timeline pane (§3): a filterable, clickable event list backed by
 * [app.andy.model.InvestigationTimeline], replacing the legacy action-only STEPS list.
 * Clicking a row selects it and seeks the video via [onSelectEvent]; [activeEventId] highlights
 * whichever event is nearest the current playback position, independent of manual selection.
 */
@Composable
internal fun InvestigationTimelinePane(
    events: List<InvestigationEvent>,
    totalEventCount: Int,
    filters: InvestigationTimelineFilters,
    onFiltersChange: (InvestigationTimelineFilters) -> Unit,
    activeEventId: String?,
    expandedEventIds: Map<String, Boolean>,
    onToggleEvent: (InvestigationEvent) -> Unit,
    referenceMillis: Long,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TIMELINE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(
                "${events.size}/$totalEventCount events",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InvestigationFilterGroup.entries.forEach { group ->
                FilterPill(group.label, filters.groupActive(group), Rust) {
                    onFiltersChange(filters.withGroupToggled(group))
                }
            }
        }
        if (events.isEmpty()) {
            EmptyState("No events match the current filters")
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(events, key = { _, event -> event.id }) { _, event ->
                    val active = event.id == activeEventId
                    val expanded = expandedEventIds[event.id] == true
                    Column(
                        Modifier.fillMaxWidth()
                            .animateContentSize()
                            .background(if (active) Rust.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                            .border(1.dp, if (active) Rust.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                            .clickable { onToggleEvent(event) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (expanded) "v" else ">",
                                color = if (active) Rust else TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.width(10.dp),
                            )
                            Text(
                                event.kind.shortTag(),
                                color = event.severity.tint(active),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp,
                                modifier = Modifier.width(40.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    event.summary,
                                    color = if (active) AndyColors.Neutral100 else TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                relativeSecondsFrom(event.atMillis, referenceMillis),
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                        AnimatedVisibility(visible = expanded) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(AndyRadius.R2))
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                event.detailFields().forEach { (label, value) -> DetailRow(label, value) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun InvestigationEventSeverity.tint(active: Boolean): Color = when (this) {
    InvestigationEventSeverity.Error -> Red
    InvestigationEventSeverity.Warning -> Rust
    InvestigationEventSeverity.Info -> if (active) Rust else TextSecondary
}

private fun relativeSecondsFrom(timestampMillis: Long, referenceMillis: Long): String {
    val seconds = (timestampMillis - referenceMillis) / 1000.0
    return "${app.andy.formatDecimal(seconds, 1)}s"
}
