package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextButton
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon

@Composable
internal fun EditedFilesCard(
    snapshot: AgentThreadChangeSnapshot,
    showAllFiles: Boolean,
    onShowAllFilesChange: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onReview: () -> Unit,
    canUndo: Boolean,
    modifier: Modifier = Modifier,
) {
    val summary = snapshot.summary
    val files = summary.files
    if (files.isEmpty()) return
    val displayedFiles = if (showAllFiles) files else files.take(3)
    val remaining = files.size - displayedFiles.size

    PanelCard(
        modifier = modifier.fillMaxWidth().testTag("edited-files-card"),
        background = AndyColors.SurfaceRaised,
        borderColor = PaneDividerTint,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(AndyRadius.Control))
                    .background(AndyColors.Neutral850)
                    .border(1.dp, PaneDividerTint, RoundedCornerShape(AndyRadius.Control)),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(Lucide.Plus, TextSecondary, Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Edited ${files.size} ${if (files.size == 1) "file" else "files"}",
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("+${summary.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
                    Text("-${summary.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
            if (canUndo) {
                TextButton(
                    onClick = onUndo,
                    modifier = Modifier.testTag("edited-files-undo"),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Undo", fontSize = 11.sp)
                        Text("↺", fontSize = 12.sp)
                    }
                }
            }
            OutlinedButton(
                onClick = onReview,
                modifier = Modifier.height(28.dp).testTag("edited-files-review"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            ) {
                Text("Review", fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(PaneDividerTint))

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            displayedFiles.forEach { file ->
                EditedFileRow(file)
            }
            if (remaining > 0 || showAllFiles) {
                Row(
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onShowAllFilesChange(!showAllFiles) }
                        .padding(vertical = 2.dp)
                        .testTag("edited-files-expand"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (showAllFiles) "Show fewer files" else "Show $remaining more files",
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                    Text(if (showAllFiles) "∧" else "∨", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun EditedFileRow(file: AgentFileChange) {
    val separator = file.path.lastIndexOf('/').takeIf { it >= 0 }
    val directory = separator?.let { file.path.take(it + 1) }.orEmpty()
    val name = separator?.let { file.path.drop(it + 1) } ?: file.path
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            buildAnnotatedString {
                if (directory.isNotEmpty()) {
                    withStyle(SpanStyle(color = TextSecondary)) { append(directory) }
                }
                withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Medium)) { append(name) }
            },
            fontFamily = MonoFont,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("+${file.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
        Text("-${file.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
    }
}