package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.domain.SplitDiffPair
import app.andy.domain.buildSplitDiffPairs
import app.andy.model.AgentFileDiff
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

internal enum class DiffViewMode { Unified, Split }

@Composable
internal fun AgentToolDiffSidePane(
    diff: AgentFileDiff,
    viewMode: DiffViewMode,
    onViewModeChange: (DiffViewMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier.fillMaxHeight(),
        borderColor = Color.Transparent,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Code",
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Text(
                    diff.path.substringAfterLast('/').ifBlank { diff.path },
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onClose) { Text("Close", fontSize = 11.sp) }
        }
        AgentFileDiffViewer(
            diff = diff,
            viewMode = viewMode,
            onViewModeChange = onViewModeChange,
            onCollapse = onClose,
            showCollapseControl = false,
            maxHeight = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun AgentFileDiffViewer(
    diff: AgentFileDiff,
    viewMode: DiffViewMode,
    onViewModeChange: (DiffViewMode) -> Unit,
    onCollapse: () -> Unit,
    showCollapseControl: Boolean = true,
    maxHeight: androidx.compose.ui.unit.Dp? = 420.dp,
    modifier: Modifier = Modifier,
) {
    var expandedContextBlocks by remember(diff.path) { mutableStateOf(setOf<Int>()) }
    val unifiedRows = remember(diff.lines, expandedContextBlocks) {
        buildDiffDisplayRows(diff.lines, expandedContextBlocks)
    }
    val splitRows = remember(diff.lines, expandedContextBlocks) {
        buildSplitDiffDisplayRows(buildSplitDiffPairs(diff.lines), expandedContextBlocks)
    }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    PanelCard(
        modifier = modifier.fillMaxWidth(),
        background = AndyColors.Neutral900.copy(alpha = AndyOverlay.Strong),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(AndyColors.Neutral850)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                diff.path,
                color = TextPrimary,
                fontFamily = MonoFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilterPill("unified", viewMode == DiffViewMode.Unified, Cyan) {
                onViewModeChange(DiffViewMode.Unified)
            }
            FilterPill("split", viewMode == DiffViewMode.Split, Cyan) {
                onViewModeChange(DiffViewMode.Split)
            }
            Text("+${diff.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
            Text("-${diff.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
            if (showCollapseControl) {
                Text(
                    "v",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable(onClick = onCollapse).padding(horizontal = 4.dp),
                )
            }
        }
        when {
            diff.isBinary -> {
                Text(
                    "binary file changed",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
            diff.lines.isEmpty() -> {
                Text(
                    "no line changes",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
            else -> {
                val bodyModifier = Modifier
                    .fillMaxWidth()
                    .let { base -> maxHeight?.let { base.heightIn(max = it) } ?: base }
                    .verticalScroll(verticalScroll)
                Column(bodyModifier) {
                    when (viewMode) {
                        DiffViewMode.Unified -> {
                            Column(Modifier.horizontalScroll(horizontalScroll).padding(bottom = 6.dp)) {
                                unifiedRows.forEach { row ->
                                    when (row) {
                                        is DiffDisplayRow.Collapsed -> CollapsedContextBar(
                                            count = row.lines.size,
                                            onToggle = {
                                                expandedContextBlocks = toggleContextBlock(expandedContextBlocks, row.id)
                                            },
                                        )
                                        is DiffDisplayRow.Line -> DiffCodeLine(row.line)
                                    }
                                }
                            }
                        }
                        DiffViewMode.Split -> {
                            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                splitRows.forEach { row ->
                                    when (row) {
                                        is SplitDisplayRow.Collapsed -> CollapsedContextBar(
                                            count = row.pairs.size,
                                            onToggle = {
                                                expandedContextBlocks = toggleContextBlock(expandedContextBlocks, row.id)
                                            },
                                        )
                                        is SplitDisplayRow.Pair -> SplitDiffCodeRow(row.pair)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedContextBar(count: Int, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(AndyColors.Neutral850.copy(alpha = AndyOverlay.Strong))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("^", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        Text(
            "$count unmodified ${if (count == 1) "line" else "lines"}",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        Text("v", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
    }
}

@Composable
private fun DiffCodeLine(line: DiffLine) {
    val background = when (line.kind) {
        DiffLineKind.Addition -> Green.copy(alpha = 0.14f)
        DiffLineKind.Deletion -> Red.copy(alpha = 0.16f)
        DiffLineKind.Context -> Color.Transparent
    }
    val gutter = when (line.kind) {
        DiffLineKind.Addition -> Green
        DiffLineKind.Deletion -> Red
        DiffLineKind.Context -> Color.Transparent
    }
    val textColor = when (line.kind) {
        DiffLineKind.Addition -> AndyColors.GreenSoft
        DiffLineKind.Deletion -> Red.copy(alpha = 0.92f)
        DiffLineKind.Context -> TextSecondary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .background(gutter),
        )
        Text(
            line.oldLineNumber?.toString().orEmpty(),
            color = TextSecondary.copy(alpha = 0.65f),
            fontFamily = MonoFont,
            fontSize = 10.sp,
            modifier = Modifier.width(36.dp).padding(start = 6.dp),
        )
        Text(
            line.newLineNumber?.toString().orEmpty(),
            color = TextSecondary.copy(alpha = 0.65f),
            fontFamily = MonoFont,
            fontSize = 10.sp,
            modifier = Modifier.width(36.dp),
        )
        Text(
            when (line.kind) {
                DiffLineKind.Addition -> "+"
                DiffLineKind.Deletion -> "-"
                DiffLineKind.Context -> " "
            },
            color = textColor,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            modifier = Modifier.width(12.dp),
        )
        Text(
            line.text.ifEmpty { " " },
            color = textColor,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun SplitDiffCodeRow(pair: SplitDiffPair) {
    Row(Modifier.fillMaxWidth()) {
        SplitDiffPane(
            line = pair.old,
            side = DiffSplitSide.Old,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(1.dp)
                .height(18.dp)
                .background(Border),
        )
        SplitDiffPane(
            line = pair.new,
            side = DiffSplitSide.New,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class DiffSplitSide { Old, New }

@Composable
private fun SplitDiffPane(
    line: DiffLine?,
    side: DiffSplitSide,
    modifier: Modifier = Modifier,
) {
    val kind = line?.kind
    val background = when {
        kind == DiffLineKind.Deletion -> Red.copy(alpha = 0.16f)
        kind == DiffLineKind.Addition -> Green.copy(alpha = 0.14f)
        line == null && side == DiffSplitSide.Old -> Green.copy(alpha = 0.06f)
        line == null && side == DiffSplitSide.New -> Red.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val gutter = when (kind) {
        DiffLineKind.Deletion -> Red
        DiffLineKind.Addition -> Green
        else -> Color.Transparent
    }
    val textColor = when (kind) {
        DiffLineKind.Deletion -> Red.copy(alpha = 0.92f)
        DiffLineKind.Addition -> AndyColors.GreenSoft
        DiffLineKind.Context -> TextSecondary
        null -> TextSecondary.copy(alpha = 0.35f)
    }
    val lineNumber = when (side) {
        DiffSplitSide.Old -> line?.oldLineNumber
        DiffSplitSide.New -> line?.newLineNumber
    }
    val marker = when (kind) {
        DiffLineKind.Deletion -> "-"
        DiffLineKind.Addition -> "+"
        DiffLineKind.Context -> " "
        null -> " "
    }
    Row(
        modifier
            .background(background)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .background(gutter),
        )
        Text(
            lineNumber?.toString().orEmpty(),
            color = TextSecondary.copy(alpha = 0.65f),
            fontFamily = MonoFont,
            fontSize = 10.sp,
            modifier = Modifier.width(36.dp).padding(start = 6.dp),
        )
        Text(
            marker,
            color = textColor,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            modifier = Modifier.width(12.dp),
        )
        Text(
            line?.text?.ifEmpty { " " } ?: " ",
            color = textColor,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private sealed class DiffDisplayRow {
    data class Line(val line: DiffLine) : DiffDisplayRow()
    data class Collapsed(val id: Int, val lines: List<DiffLine>) : DiffDisplayRow()
}

private sealed class SplitDisplayRow {
    data class Pair(val pair: SplitDiffPair) : SplitDisplayRow()
    data class Collapsed(val id: Int, val pairs: List<SplitDiffPair>) : SplitDisplayRow()
}

private fun toggleContextBlock(expanded: Set<Int>, id: Int): Set<Int> =
    if (id in expanded) expanded - id else expanded + id

private fun buildDiffDisplayRows(
    lines: List<DiffLine>,
    expandedContextBlocks: Set<Int>,
): List<DiffDisplayRow> {
    if (lines.isEmpty()) return emptyList()
    val rows = mutableListOf<DiffDisplayRow>()
    var index = 0
    var blockId = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.kind != DiffLineKind.Context) {
            rows += DiffDisplayRow.Line(line)
            index += 1
            continue
        }
        val start = index
        while (index < lines.size && lines[index].kind == DiffLineKind.Context) index += 1
        val block = lines.subList(start, index).toList()
        val id = blockId++
        if (id in expandedContextBlocks) {
            block.forEach { rows += DiffDisplayRow.Line(it) }
        } else {
            rows += DiffDisplayRow.Collapsed(id, block)
        }
    }
    return rows
}

private fun buildSplitDiffDisplayRows(
    pairs: List<SplitDiffPair>,
    expandedContextBlocks: Set<Int>,
): List<SplitDisplayRow> {
    if (pairs.isEmpty()) return emptyList()
    val rows = mutableListOf<SplitDisplayRow>()
    var index = 0
    var blockId = 0
    while (index < pairs.size) {
        if (!pairs[index].isContext) {
            rows += SplitDisplayRow.Pair(pairs[index])
            index += 1
            continue
        }
        val start = index
        while (index < pairs.size && pairs[index].isContext) index += 1
        val block = pairs.subList(start, index).toList()
        val id = blockId++
        if (id in expandedContextBlocks) {
            block.forEach { rows += SplitDisplayRow.Pair(it) }
        } else {
            rows += SplitDisplayRow.Collapsed(id, block)
        }
    }
    return rows
}
