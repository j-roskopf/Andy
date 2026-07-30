package app.andy.ui.logcat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.HostCodeEditor
import app.andy.domain.groupStackTraces
import app.andy.formatDisplayDateTime
import app.andy.model.CrashRecord
import app.andy.model.HostSearchMode
import app.andy.model.LogcatEntry
import app.andy.model.StackTraceBlock
import app.andy.model.explainCrashRequest
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.ui.agents.ContextualAiActionHost
import app.andy.ui.agents.ExplainActionButton
import app.andy.ui.agents.contextualAiActionsEnabled
import app.andy.ui.agents.findInvestigationEvent
import app.andy.ui.agents.rememberContextualAiActionState
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val frameFileLineRegex = Regex("""([\w$]+\.(?:kt|java)):(\d+)""")

/** Reassembles the live stream into stack-trace blocks with clickable source frames. */
@Composable
internal fun StackTraceLibraryPanel(
    entries: List<LogcatEntry>,
    modifier: Modifier = Modifier,
    onOpenFile: (fileName: String, line: Int) -> Unit,
) {
    val blocks = remember(entries) { groupStackTraces(entries).asReversed().take(20) }
    if (blocks.isEmpty()) {
        Column(modifier, verticalArrangement = Arrangement.Center) {
            Text("No stack traces detected in the current stream yet.", color = TextSecondary, fontSize = 12.sp)
        }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(blocks, key = { it.startIndex }) { block ->
            StackTraceBlockCard(block = block, onOpenFile = onOpenFile)
        }
    }
}

@Composable
private fun StackTraceBlockCard(
    block: StackTraceBlock,
    onOpenFile: (fileName: String, line: Int) -> Unit,
) {
    val text = remember(block) { (listOf(block.header) + block.frames).joinToString("\n") }
    val copyText = rememberCopyText()

    PanelCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(block.header, color = Red, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { copyText(text) }) { Text("Copy", fontSize = 11.sp) }
        }
        Column(Modifier.padding(top = 4.dp)) {
            text.lines().forEach { line -> ClickableFrameLine(line, onOpenFile) }
        }
    }
}

@Composable
private fun ClickableFrameLine(
    line: String,
    onOpenFile: (fileName: String, line: Int) -> Unit,
    color: androidx.compose.ui.graphics.Color = TextPrimary,
) {
    val match = frameFileLineRegex.find(line)
    if (match == null) {
        Text(line, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        return
    }
    val fileName = match.groupValues[1]
    val lineNumber = match.groupValues[2].toIntOrNull() ?: 0
    val before = line.substring(0, match.range.first)
    val matched = match.value
    val after = line.substring(match.range.last + 1)
    Row {
        if (before.isNotEmpty()) Text(before, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(
            matched,
            color = Cyan,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(enabled = lineNumber > 0) { onOpenFile(fileName, lineNumber) },
        )
        if (after.isNotEmpty()) Text(after, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

/** Crash/ANR/tombstone inspector (§B.2). */
@Composable
internal fun CrashesPanel(
    services: AndyServices,
    serial: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val copyText = rememberCopyText()
    var crashes by remember { mutableStateOf<List<CrashRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var crashText by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    val contextualActions = rememberContextualAiActionState()
    val explainAvailable = contextualAiActionsEnabled(services)

    suspend fun refresh() {
        val target = serial ?: return
        loading = true
        loadError = null
        crashes = runCatching { services.crashInspector.listCrashes(target) }
            .getOrElse { loadError = it.message ?: "Failed to list crashes"; emptyList() }
        loading = false
    }

    LaunchedEffect(serial) { refresh() }

    suspend fun openCrash(id: String) {
        val target = serial ?: return
        selectedId = id
        crashText = services.crashInspector.loadCrash(target, id)
    }

    /** Prefers the saved investigation holding this crash; falls back to a prompt-only action. */
    fun explainCrash(crash: CrashRecord) {
        scope.launch {
            val location = findInvestigationEvent(services.bugs, key = "crashId", value = crash.id)
            contextualActions.open(
                explainCrashRequest(
                    crashId = crash.id,
                    packageName = crash.packageName,
                    summary = crash.summary,
                    crashText = crashText,
                    investigationId = location?.investigationId,
                    eventId = location?.eventId,
                    atMillis = location?.atMillis,
                ),
            )
        }
    }

    Box(modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Crashes, ANRs & tombstones", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = serial != null) {
                    Text(if (loading) "Loading\u2026" else "Refresh")
                }
            }
            loadError?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
            if (!loading && crashes.isEmpty() && loadError == null) {
                Text(
                    "No crash records found. This is normal on production builds without dropbox/ANR read access.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(crashes, key = { it.id }) { crash ->
                    val selected = crash.id == selectedId
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (selected) AndyColors.SurfaceHover else AndyColors.Neutral900.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .clickable { scope.launch { openCrash(crash.id) } }
                            .padding(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(crash.kind.name, color = crashKindColor(crash), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(crash.packageName ?: "", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                        Text(crash.summary, color = TextPrimary, fontSize = 12.sp, maxLines = 2)
                        if (crash.timestampMillis > 0) {
                            Text(formatDisplayDateTime(crash.timestampMillis), color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        PanelCard(Modifier.weight(1f).fillMaxHeight()) {
            if (selectedId == null) {
                Text("Select a crash to view details.", color = TextSecondary, fontSize = 12.sp)
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Details", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { copyText(crashText) }) { Text("Copy", fontSize = 11.sp) }
                        OutlinedButton(onClick = {
                            val id = selectedId ?: return@OutlinedButton
                            val target = serial ?: return@OutlinedButton
                            scope.launch { services.crashInspector.exportCrash(target, id, "") }
                        }) { Text("Export", fontSize = 11.sp) }
                        if (explainAvailable) {
                            ExplainActionButton("Explain crash…", enabled = crashText.isNotBlank()) {
                                crashes.firstOrNull { it.id == selectedId }?.let(::explainCrash)
                            }
                        }
                    }
                    SelectionContainer(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            crashText,
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
    ContextualAiActionHost(services, contextualActions)
    }
}

private fun crashKindColor(crash: CrashRecord) = when (crash.kind) {
    app.andy.model.CrashKind.JavaCrash -> Red
    app.andy.model.CrashKind.NativeCrash -> Red
    app.andy.model.CrashKind.Anr -> Rust
    app.andy.model.CrashKind.SystemAppCrash -> Rust
    app.andy.model.CrashKind.Watchdog -> Green
}

/** Embedded read-only-ish preview of a host source file jumped to from a stack frame. */
@Composable
internal fun CodePreviewPanel(
    state: CodePreviewState,
    onSave: (path: String, text: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.requestedPath, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close", fontSize = 11.sp) }
        }
        when {
            state.loading -> Text("Loading\u2026", color = TextSecondary, fontSize = 12.sp)
            state.error != null -> Text(state.error, color = Red, fontSize = 12.sp)
            state.document != null -> Box(Modifier.fillMaxWidth()) {
                HostCodeEditor(
                    path = state.document.path,
                    text = state.document.content,
                    languageHint = state.document.languageHint,
                    initialLine = state.line,
                    modifier = Modifier.fillMaxWidth(),
                    onTextChange = { _, _ -> },
                    onSave = { path, text -> onSave(path, text) },
                    onClose = onClose,
                )
            }
        }
    }
}

internal suspend fun resolveAndOpenFile(
    services: AndyServices,
    hostFileRoots: List<String>,
    fileName: String,
    line: Int,
): CodePreviewState {
    val roots = hostFileRoots.ifEmpty { listOf(".") }
    val hit = runCatching {
        services.hostFiles.search(fileName, HostSearchMode.FileName, roots, limit = 5)
            .firstOrNull { it.path.substringAfterLast('/').substringAfterLast('\\') == fileName }
    }.getOrNull()
    if (hit == null) {
        return CodePreviewState(requestedPath = fileName, line = line, loading = false, error = "Could not find $fileName under configured host file roots.")
    }
    return runCatching { services.hostFiles.read(hit.path) }.fold(
        onSuccess = { doc -> CodePreviewState(requestedPath = hit.path, line = line, loading = false, document = doc) },
        onFailure = { err -> CodePreviewState(requestedPath = hit.path, line = line, loading = false, error = err.message ?: "Failed to read file") },
    )
}
