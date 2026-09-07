package app.andy.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.currentTimeMillis
import app.andy.model.AgentTask
import app.andy.ui.components.IconButton
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private const val ContinueGoalPrompt = "Continue pursuing the goal."

/**
 * Always-on pinned goal chrome above the chat surface (composer or live terminal).
 */
@Composable
internal fun GoalPursuitPinnedSection(
    task: AgentTask,
    goal: String,
    editorOpen: Boolean,
    editorText: String,
    onEditorOpenChange: (Boolean) -> Unit,
    onEditorTextChange: (String) -> Unit,
    onSaveGoal: (String) -> Unit,
    onClearGoal: () -> Unit,
    onPause: () -> Unit,
    onResume: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(
        topStart = AndyRadius.Sheet,
        topEnd = AndyRadius.Sheet,
        bottomStart = 4.dp,
        bottomEnd = 4.dp,
    )
    Column(
        modifier = modifier
            .fillMaxWidth(0.96f)
            .shadow(elevation = 1.dp, shape = shape, clip = false)
            .background(AndyColors.SidebarBg, shape)
            .padding(
                start = AndySpace.Space3,
                end = AndySpace.Space3,
                top = AndySpace.Space2,
                bottom = AndySpace.Space2,
            ),
    ) {
        GoalPursuitBar(
            task = task,
            goal = goal,
            editorOpen = editorOpen,
            editorText = editorText,
            onEditorOpenChange = onEditorOpenChange,
            onEditorTextChange = onEditorTextChange,
            onSaveGoal = onSaveGoal,
            onClearGoal = onClearGoal,
            onPause = onPause,
            onResume = onResume,
        )
    }
}

/**
 * Pinned goal strip — target label, truncated objective, elapsed timer,
 * and edit / pause-play / clear / expand actions.
 */
@Composable
internal fun GoalPursuitBar(
    task: AgentTask,
    goal: String,
    editorOpen: Boolean,
    editorText: String,
    onEditorOpenChange: (Boolean) -> Unit,
    onEditorTextChange: (String) -> Unit,
    onSaveGoal: (String) -> Unit,
    onClearGoal: () -> Unit,
    onPause: () -> Unit,
    onResume: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pursuing = isElapsedLive(task) || task.isActive
    val elapsedEnd = rememberElapsedEndMillis(task.id, task.finishedAtMillis, task)
    var nowMillis by remember { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(task.id, pursuing) {
        if (!pursuing) return@LaunchedEffect
        while (true) {
            nowMillis = currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = formatElapsed(task.startedAtMillis, elapsedEnd, nowMillis)
    val statusLabel = if (pursuing) "Pursuing goal" else "Goal paused"
    val iconTint = TextSecondary.copy(alpha = 0.88f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            LucideIcon(Lucide.Target, iconTint, Modifier.size(AndyLayout.IconMd))
            Text(
                statusLabel,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Text(
                goal,
                color = TextSecondary,
                fontFamily = DisplayFont,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onEditorOpenChange(!editorOpen) },
            )
            if (elapsed != null) {
                Text(
                    elapsed,
                    color = TextSecondary.copy(alpha = 0.75f),
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            GoalPursuitAction(
                path = Lucide.Pencil,
                contentDescription = "Edit goal",
                tint = iconTint,
                onClick = { onEditorOpenChange(true) },
            )
            if (pursuing) {
                GoalPursuitAction(
                    path = Lucide.Pause,
                    contentDescription = "Pause goal",
                    tint = iconTint,
                    onClick = onPause,
                )
            } else {
                GoalPursuitAction(
                    path = Lucide.Play,
                    contentDescription = "Resume goal",
                    tint = iconTint,
                    onClick = { onResume(ContinueGoalPrompt) },
                )
            }
            GoalPursuitAction(
                path = Lucide.Trash2,
                contentDescription = "Clear goal",
                tint = iconTint,
                onClick = onClearGoal,
            )
            GoalPursuitAction(
                path = if (editorOpen) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (editorOpen) "Collapse goal" else "Expand goal",
                tint = iconTint.copy(alpha = 0.7f),
                onClick = { onEditorOpenChange(!editorOpen) },
            )
        }

        AnimatedVisibility(
            visible = editorOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                Text(
                    "Persistent task goal",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
                TextField(
                    editorText,
                    onEditorTextChange,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = TextPrimary,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    ),
                    colors = fieldColors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                    Text(
                        "Save goal",
                        color = if (editorText.isNotBlank()) Cyan else TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(enabled = editorText.isNotBlank()) {
                                onSaveGoal(editorText)
                                onEditorOpenChange(false)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalPursuitAction(
    path: String,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(AndyLayout.ControlHeightSm),
        contentDescription = contentDescription,
    ) {
        LucideIcon(path, tint, Modifier.size(AndyLayout.IconSm))
    }
}
