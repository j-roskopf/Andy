package app.andy.ui.network

import androidx.compose.animation.AnimatedVisibility
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
import app.andy.ui.components.StatusBadge
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.Green
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow

@Composable
internal fun NetworkTrafficRowItem(
    row: NetworkTrafficRow,
    expanded: Boolean,
    flashing: Boolean,
    focused: Boolean,
    pathFocused: Boolean,
    hostFocused: Boolean,
    trafficWidth: Float,
    statusWidth: Float,
    typeWidth: Float,
    sizeWidth: Float,
    msWidth: Float,
    onToggle: () -> Unit,
    onSelect: (NetworkExchange) -> Unit,
    onFocus: (String) -> Unit,
    onToggleFocusPath: (NetworkExchange) -> Unit,
    onToggleFocusHost: (NetworkExchange) -> Unit,
    onAddRule: (NetworkExchange) -> Unit,
) {
    val latest = row.latest
    val selectedColor = when {
        focused -> AndyColors.Orange.copy(alpha = 0.14f)
        flashing -> Rust.copy(alpha = 0.18f)
        row.exchange != null -> AndyColors.SurfaceSelected.copy(alpha = 0.85f)
        else -> Color.Transparent
    }
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 24.dp)
                .background(selectedColor)
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
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.width(trafficWidth.dp).padding(start = (row.depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        row.exchange != null -> "•"
                        row.hasChildren && expanded -> "v"
                        row.hasChildren -> ">"
                        else -> " "
                    },
                    color = if (row.exchange != null) Rust else TextSecondary.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    if (row.exchange != null) "${latest?.method ?: "-"}  ${row.label}" else "${row.label}  (${row.count})",
                    color = if (row.exchange != null) TextPrimary else AndyColors.Neutral100.copy(alpha = 0.9f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val response = row.exchange
            val isPassthrough = response?.tlsStatus == "passthrough" || response?.method == "PASS"
            val statusText = when {
                response == null -> ""
                isPassthrough -> "PASS"
                else -> response.statusCode?.toString() ?: "-"
            }
            val statusColor = when {
                isPassthrough -> Yellow
                (response?.statusCode ?: 200) >= 400 -> Red
                else -> TextSecondary
            }
            Box(Modifier.width(statusWidth.dp), contentAlignment = Alignment.CenterStart) {
                if (statusText.isNotBlank()) {
                    StatusBadge(statusText, statusColor)
                }
            }
            MonoCell(
                when {
                    response == null -> ""
                    isPassthrough -> "Not decrypted — CA not trusted / pinned"
                    else -> response.contentType?.substringBefore(';') ?: "-"
                },
                typeWidth.dp,
                if (isPassthrough) Yellow else TextSecondary,
                compact = true,
            )
            MonoCell(if (response != null) response.sizeBytes?.toString() ?: "-" else "", sizeWidth.dp, TextSecondary, compact = true)
            MonoCell(if (response != null) response.durationMillis?.toString() ?: "-" else "", msWidth.dp, TextSecondary, compact = true)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    if (response != null) response.matchedRuleId ?: "-" else "",
                    color = if (response?.matchedRuleId != null) Green else TextSecondary.copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
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
                    text = {
                        Text(
                            if (pathFocused) "Unfocus path" else "Focus path",
                            color = TextPrimary,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onToggleFocusPath(row.exchange)
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (hostFocused) "Unfocus host" else "Focus host",
                            color = TextPrimary,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onToggleFocusHost(row.exchange)
                    }
                )
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
internal fun SelectedFlowPanel(
    selected: NetworkExchange?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
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
            actions()
            FilterPill(if (expanded) "Hide" else "Show", expanded, Rust, onClick = onToggle)
        }
        AnimatedVisibility(expanded) {
            if (selected == null) {
                EmptyState("Select a network call to inspect headers and body.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    selected.error?.takeIf { it.isNotBlank() }?.let { error ->
                        Text(error, color = Yellow, fontSize = 12.sp, lineHeight = 16.sp)
                    }
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
