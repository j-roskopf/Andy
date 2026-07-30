package app.andy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.rememberCopyText
import app.andy.service.DhuConsoleHistory
import app.andy.service.DhuConsoleState
import app.andy.service.DhuFixedConfig
import app.andy.service.DhuReadiness
import app.andy.service.DhuService
import app.andy.service.DhuSession
import app.andy.service.DhuSessionPhase
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun DhuConsolePanel(
    dhu: DhuService,
    console: DhuConsoleState,
    session: DhuSession?,
    readiness: DhuReadiness,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onStop: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val copy = rememberCopyText()
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val scroll = rememberScrollState()
    LaunchedEffect(console.lines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val shape = RoundedCornerShape(AndyRadius.Control)
    PanelCard(
        modifier = modifier.fillMaxWidth(),
        background = AndyColors.Neutral900,
        contentPadding = PaddingValues(AndySpace.Space3),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "DHU console",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { dhu.openHelp() }) {
                Text("Help", fontSize = 11.sp)
            }
            OutlinedButton(onClick = {
                copy(dhu.copyDiagnostics())
            }) {
                Text("Copy diagnostics", fontSize = 11.sp)
            }
            OutlinedButton(onClick = onRetry) {
                Text("Retry", fontSize = 11.sp)
            }
            OutlinedButton(onClick = onStop) {
                Text("Stop", fontSize = 11.sp)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 160.dp)
                .background(AndyColors.Neutral900, shape)
                .border(1.dp, Border.copy(alpha = 0.6f), shape)
                .padding(8.dp)
                .verticalScroll(scroll),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (console.lines.isEmpty()) {
                    Text("DHU process output will appear here.", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                } else {
                    console.lines.forEach { line ->
                        Text(line, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        BasicTextField(
            value = input,
            onValueChange = {
                input = it
                historyIndex = -1
            },
            singleLine = true,
            textStyle = TextStyle(
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(Green),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    val command = input
                    input = ""
                    historyIndex = -1
                    scope.launch { dhu.sendConsoleCommand(command) }
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .background(AndyColors.Neutral900, shape)
                .border(1.dp, Border, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            val (idx, value) = DhuConsoleHistory.recall(console.history, historyIndex, -1)
                            historyIndex = idx
                            if (value != null) input = value
                            true
                        }
                        Key.DirectionDown -> {
                            val (idx, value) = DhuConsoleHistory.recall(console.history, historyIndex, 1)
                            historyIndex = idx
                            input = value.orEmpty()
                            true
                        }
                        Key.Enter -> {
                            val command = input
                            input = ""
                            historyIndex = -1
                            scope.launch { dhu.sendConsoleCommand(command) }
                            true
                        }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                if (input.isEmpty()) {
                    Text("DHU command (Enter to send)", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                inner()
            },
        )
        val phase = session?.phase
        val statusColor = when (phase) {
            DhuSessionPhase.Running -> Green
            DhuSessionPhase.Failed -> Rust
            else -> TextSecondary
        }
        Text(
            session?.message
                ?: readiness.blocking.firstOrNull()?.let { "${it.label}: ${it.detail}" }
                ?: "Idle",
            color = statusColor,
            fontSize = 11.sp,
        )
        if (phase == DhuSessionPhase.Running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { dhu.openExternalTroubleshooting() }
                }) {
                    Text("Focus DHU window", fontSize = 11.sp)
                }
                Text(
                    "DHU is a separate desktop-head-unit window — interact there.",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        } else if (phase == DhuSessionPhase.Failed) {
            Text(
                "Use Retry or toggle Android Auto again to start a managed DHU session.",
                color = TextSecondary,
                fontSize = 10.sp,
            )
        }
        Text(
            "Docs: ${DhuFixedConfig.HelpUrl}",
            color = TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
internal fun AndroidAutoToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    readyHint: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Checkbox(
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        Column {
            Text("Android Auto", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                readyHint ?: "Launch Desktop Head Unit in its own window",
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 2,
            )
        }
    }
}
