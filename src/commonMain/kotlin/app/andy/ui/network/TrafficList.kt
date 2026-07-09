package app.andy.ui.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.domain.*
import app.andy.model.NetworkExchange
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.MonoCell
import app.andy.ui.components.PanelCard
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.Green
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun NetworkTrafficRowItem(
    row: NetworkTrafficRow,
    expanded: Boolean,
    flashing: Boolean,
    trafficWidth: Float,
    statusWidth: Float,
    typeWidth: Float,
    sizeWidth: Float,
    msWidth: Float,
    onToggle: () -> Unit,
    onSelect: (NetworkExchange) -> Unit,
    onFocus: (String) -> Unit,
    onAddRule: (NetworkExchange) -> Unit,
) {
    val latest = row.latest
    val flashColor by animateColorAsState(
        targetValue = if (flashing) Rust.copy(alpha = 0.24f) else AndyColors.Neutral900.copy(alpha = 0.65f),
    )
    val selectedColor = if (row.exchange != null) AndyColors.Neutral800.copy(alpha = 0.9f) else flashColor
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 32.dp)
                .background(selectedColor)
                .border(1.dp, if (flashing) Rust.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.05f))
                .clickable {
                    row.exchange?.let(onSelect) ?: onToggle()
                }
                .pointerInput(row.key) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                if (event.buttons.isSecondaryPressed) {
                                    if (row.exchange == null) {
                                        onFocus(row.key)
                                    } else {
                                        onSelect(row.exchange)
                                        showMenu = true
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.width(trafficWidth.dp).padding(start = (row.depth * 16).dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        row.exchange != null -> "•"
                        row.hasChildren && expanded -> "v"
                        row.hasChildren -> ">"
                        else -> " "
                    },
                    color = if (row.exchange != null) Rust else TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    if (row.exchange != null) "${latest?.method ?: "-"}  ${row.label}" else "${row.label}  (${row.count})",
                    color = if (row.exchange != null) TextPrimary else AndyColors.Neutral100,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val response = row.exchange
            MonoCell(if (response != null) response.statusCode?.toString() ?: "-" else "", statusWidth.dp, if ((response?.statusCode ?: 200) >= 400) Red else TextSecondary)
            MonoCell(if (response != null) response.contentType?.substringBefore(';') ?: "-" else "", typeWidth.dp, TextSecondary)
            MonoCell(if (response != null) response.sizeBytes?.toString() ?: "-" else "", sizeWidth.dp, TextSecondary)
            MonoCell(if (response != null) response.durationMillis?.toString() ?: "-" else "", msWidth.dp, TextSecondary)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    if (response != null) response.matchedRuleId ?: "-" else "",
                    color = if (response?.matchedRuleId != null) Green else TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (row.exchange != null) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = PanelSoft
            ) {
                DropdownMenuItem(
                    text = { Text("Add rule", color = TextPrimary) },
                    onClick = {
                        showMenu = false
                        onAddRule(row.exchange)
                    }
                )
            }
        }
    }
}

@Composable
internal fun SelectedFlowPanel(selected: NetworkExchange?, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    PanelCard(modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Selected flow", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                selected?.let { "${it.method} ${it.statusCode ?: "-"} ${it.url}" } ?: "No flow selected",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilterPill(if (expanded) "Hide" else "Show", expanded, Rust, onToggle)
        }
        AnimatedVisibility(expanded) {
            if (selected == null) {
                EmptyState("Select a network call to inspect headers and body.")
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FlowPreviewScrollable(
                        title = "Request",
                        headers = selected.requestHeaders,
                        body = selected.requestBodyPreview,
                        formatJson = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    FlowPreviewScrollable(
                        title = "Response",
                        headers = selected.responseHeaders,
                        body = selected.responseBodyPreview,
                        formatJson = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowPreviewScrollable(
    title: String,
    headers: Map<String, String>,
    body: String?,
    formatJson: Boolean,
    modifier: Modifier = Modifier,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val bodyValue = body?.takeIf { it.isNotBlank() }
    val jsonBody = remember(body, formatJson) { if (formatJson) parseJsonBodyPreview(body) else null }
    val expandedJsonKeys = remember(body) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(jsonBody) {
        expandedJsonKeys.clear()
        jsonBody?.let { expandedJsonKeys[it.path] = true }
    }
    val headerText = remember(headers) {
        headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "No headers" }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Box(
            Modifier.fillMaxSize()
                .background(AndyColors.Neutral850, RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
                .padding(10.dp)
                .horizontalScroll(horizontal)
                .verticalScroll(vertical),
        ) {
            SelectionContainer {
                Column {
                    Text("Headers", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    Text(headerText, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Body", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    if (jsonBody != null) {
                        JsonTreeView(
                            node = jsonBody,
                            expandedKeys = expandedJsonKeys,
                        )
                    } else {
                        Text(
                            bodyValue ?: "No body preview",
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonTreeView(
    node: JsonPreviewNode,
    expandedKeys: MutableMap<String, Boolean>,
) {
    val rows = remember(node, expandedKeys.toMap()) { flattenJsonPreview(node, expandedKeys) }
    Column {
        rows.forEach { row ->
            JsonTreeRow(
                row = row,
                expanded = expandedKeys[row.node.path] == true,
                onToggle = {
                    if (row.node.isContainer) {
                        expandedKeys[row.node.path] = expandedKeys[row.node.path] != true
                    }
                },
            )
        }
    }
}

@Composable
private fun JsonTreeRow(row: JsonPreviewRow, expanded: Boolean, onToggle: () -> Unit) {
    val node = row.node
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.clickable(enabled = node.isContainer) { onToggle() },
    ) {
        Text(
            text = when {
                node.isContainer && expanded -> "v"
                node.isContainer -> ">"
                else -> " "
            },
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = row.text,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(start = (row.depth * 14).dp),
        )
    }
}
