package app.andy.ui.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentTask
import app.andy.model.ProjectAction
import app.andy.model.WorkingTreeStatus
import app.andy.service.AndyServices
import app.andy.ui.components.AndyDropdownMenuItem
import app.andy.ui.components.Card
import app.andy.ui.components.CardElevation
import app.andy.ui.components.HoverTooltip
import app.andy.ui.components.LocalProjectDirLauncher
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private sealed interface SelectedEnvironmentAction {
    data object Terminal : SelectedEnvironmentAction
    data class Run(val action: ProjectAction) : SelectedEnvironmentAction
}

/** Session memory so switching away from a worktree chat doesn't reset the play target. */
private object EnvironmentSelectedActionMemory {
    private const val TerminalKey = "terminal"
    private val selectedKeys = mutableMapOf<String, String>()

    fun keyFor(task: AgentTask): String = task.id

    fun read(task: AgentTask, actions: List<ProjectAction>): SelectedEnvironmentAction {
        val stored = selectedKeys[keyFor(task)] ?: return SelectedEnvironmentAction.Terminal
        if (stored == TerminalKey) return SelectedEnvironmentAction.Terminal
        val action = actions.firstOrNull { it.id == stored } ?: return SelectedEnvironmentAction.Terminal
        return SelectedEnvironmentAction.Run(action)
    }

    fun write(task: AgentTask, selected: SelectedEnvironmentAction) {
        selectedKeys[keyFor(task)] = when (selected) {
            SelectedEnvironmentAction.Terminal -> TerminalKey
            is SelectedEnvironmentAction.Run -> selected.action.id
        }
    }
}

private val EnvironmentCardWidth = 248.dp

/**
 * True when the floating Environment card would cover chat/terminal text.
 *
 * Centered transcript content ([AndyLayout.ChatContentMaxWidth]) leaves side gutters only when
 * the pane is wider than that column plus the card. Full-bleed panes (live terminal surface)
 * always collide, so they tuck.
 */
internal fun environmentCardWouldEclipseContent(
    paneWidth: Dp,
    contentFullBleed: Boolean,
    cardWidth: Dp = EnvironmentCardWidth,
    endPadding: Dp = AndySpace.Space2,
    contentMaxWidth: Dp = AndyLayout.ChatContentMaxWidth,
    /** Extra clear gap between the content column's right edge and the card's left edge. */
    minClearance: Dp = 16.dp,
    /** Transcript LazyColumn end paddings that shrink the usable text edge. */
    contentEndInset: Dp = 26.dp, // 18 contentPadding + 8 list padding
): Boolean {
    if (paneWidth <= 0.dp) return false
    if (contentFullBleed) return true
    val contentWidth = minOf(paneWidth, contentMaxWidth)
    val contentRight = (paneWidth + contentWidth) / 2 - contentEndInset
    val panelLeft = paneWidth - endPadding - cardWidth
    return panelLeft < contentRight + minClearance
}

/**
 * Floating Environment chip for worktree chats — top-right over the transcript/terminal pane.
 * Tucks into an edge tag whenever the expanded card would cover chat text.
 */
@Composable
internal fun WorktreeEnvironmentPanel(
    services: AndyServices,
    task: AgentTask,
    diffSummary: String?,
    onDiffSummaryChange: (String?) -> Unit,
    onCopyText: (String) -> Unit,
    paneWidth: Dp,
    /** Terminal surface uses the full pane width; ACP transcript is a centered max-width column. */
    contentFullBleed: Boolean,
    modifier: Modifier = Modifier,
) {
    val worktreePath = task.worktreePath ?: return
    val scope = rememberCoroutineScope()
    val launcher = LocalProjectDirLauncher.current
    val worktreeActions = remember(launcher, task.projectId) { launcher.actionsFor(task.projectId) }

    var treeStatus by remember(task.id) { mutableStateOf<WorkingTreeStatus?>(null) }
    var changesExpanded by remember(task.id) { mutableStateOf(false) }
    var overflowOpen by remember(task.id) { mutableStateOf(false) }
    var worktreeMenuOpen by remember(task.id) { mutableStateOf(false) }
    var pinnedOpen by remember(task.id) { mutableStateOf(false) }
    var selectedAction by remember(task.id, worktreeActions) {
        mutableStateOf(EnvironmentSelectedActionMemory.read(task, worktreeActions))
    }

    // Drop a stale Run selection if the action list changes.
    LaunchedEffect(worktreeActions) {
        val current = selectedAction
        if (current is SelectedEnvironmentAction.Run &&
            worktreeActions.none { it.id == current.action.id }
        ) {
            selectedAction = SelectedEnvironmentAction.Terminal
            EnvironmentSelectedActionMemory.write(task, SelectedEnvironmentAction.Terminal)
        }
    }

    val shouldAutoTuck = environmentCardWouldEclipseContent(
        paneWidth = paneWidth,
        contentFullBleed = contentFullBleed,
    )
    LaunchedEffect(shouldAutoTuck) {
        if (!shouldAutoTuck) pinnedOpen = false
    }
    val showExpanded = !shouldAutoTuck || pinnedOpen

    suspend fun refreshStatusAndDiff() {
        treeStatus = services.agentRuns.workingTreeStatus(worktreePath)
        onDiffSummaryChange(services.agentRuns.worktreeDiffSummary(task.id))
    }

    LaunchedEffect(task.id, worktreePath) {
        treeStatus = services.agentRuns.workingTreeStatus(worktreePath)
    }

    fun openTerminal() {
        launcher.openTerminal(task.projectId, worktreePath)
    }

    fun runSelected() {
        when (val action = selectedAction) {
            SelectedEnvironmentAction.Terminal -> openTerminal()
            is SelectedEnvironmentAction.Run ->
                launcher.runAction(task.projectId, action.action, worktreePath)
        }
    }

    val selectedLabel = when (val action = selectedAction) {
        SelectedEnvironmentAction.Terminal -> "Terminal"
        is SelectedEnvironmentAction.Run -> action.action.name
    }

    AnimatedContent(
        targetState = showExpanded,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 3 } + fadeOut())
            }.using(SizeTransform(clip = false))
        },
        label = "environment-dock",
        modifier = modifier,
    ) { expanded ->
        if (expanded) {
            EnvironmentExpandedCard(
                selectedLabel = selectedLabel,
                selectedAction = selectedAction,
                treeStatus = treeStatus,
                changesExpanded = changesExpanded,
                onChangesExpandedChange = { opening ->
                    changesExpanded = opening
                    if (opening) scope.launch { refreshStatusAndDiff() }
                },
                onRefreshDiff = { scope.launch { refreshStatusAndDiff() } },
                diffSummary = diffSummary,
                worktreePath = worktreePath,
                branchName = task.branchName,
                worktreeActions = worktreeActions,
                overflowOpen = overflowOpen,
                onOverflowOpenChange = { overflowOpen = it },
                worktreeMenuOpen = worktreeMenuOpen,
                onWorktreeMenuOpenChange = { worktreeMenuOpen = it },
                onSelectAction = {
                    selectedAction = it
                    EnvironmentSelectedActionMemory.write(task, it)
                },
                onRunSelected = ::runSelected,
                onOpenTerminal = ::openTerminal,
                onCopyText = onCopyText,
                showTuckControl = shouldAutoTuck,
                onTuck = { pinnedOpen = false },
            )
        } else {
            EnvironmentEdgeTag(
                status = treeStatus,
                onClick = { pinnedOpen = true },
            )
        }
    }
}

@Composable
private fun EnvironmentEdgeTag(
    status: WorkingTreeStatus?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    HoverTooltip(text = "Show Environment") {
        Card(
            modifier = modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics {
                    contentDescription = "Show Environment"
                    role = Role.Button
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .testTag("chat-worktree-environment-tag"),
            elevation = CardElevation.Med,
            shape = AndyShape.Menu,
            backgroundColor = AndyColors.SurfaceRaised,
            contentPadding = PaddingValues(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LucideIcon(Lucide.GitBranch, TextSecondary, Modifier.size(14.dp))
                Text(
                    "Env",
                    color = if (hovered) TextPrimary else TextSecondary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
                val dirty = status?.takeIf { it.additions > 0 || it.deletions > 0 }
                if (dirty != null) {
                    if (dirty.additions > 0) {
                        Text(
                            "+${dirty.additions}",
                            color = Green,
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                        )
                    }
                    if (dirty.deletions > 0) {
                        Text(
                            "-${dirty.deletions}",
                            color = Red,
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentExpandedCard(
    selectedLabel: String,
    selectedAction: SelectedEnvironmentAction,
    treeStatus: WorkingTreeStatus?,
    changesExpanded: Boolean,
    onChangesExpandedChange: (Boolean) -> Unit,
    onRefreshDiff: () -> Unit,
    diffSummary: String?,
    worktreePath: String,
    branchName: String?,
    worktreeActions: List<ProjectAction>,
    overflowOpen: Boolean,
    onOverflowOpenChange: (Boolean) -> Unit,
    worktreeMenuOpen: Boolean,
    onWorktreeMenuOpenChange: (Boolean) -> Unit,
    onSelectAction: (SelectedEnvironmentAction) -> Unit,
    onRunSelected: () -> Unit,
    onOpenTerminal: () -> Unit,
    onCopyText: (String) -> Unit,
    showTuckControl: Boolean,
    onTuck: () -> Unit,
) {
    val shortPath = remember(worktreePath) { worktreePath.trimEnd('/').substringAfterLast('/') }

    Card(
        modifier = Modifier
            .width(EnvironmentCardWidth)
            .heightIn(max = 280.dp)
            .testTag("chat-worktree-environment"),
        elevation = CardElevation.Med,
        shape = AndyShape.Menu,
        backgroundColor = AndyColors.SurfaceRaised,
        contentPadding = PaddingValues(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(EnvironmentHeaderHeight)
                .padding(horizontal = AndySpace.Space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Environment",
                color = TextSecondary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                selectedLabel,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            Box(contentAlignment = Alignment.Center) {
                EnvironmentIconButton(
                    path = Lucide.Ellipsis,
                    contentDescription = "Worktree actions",
                    onClick = { onOverflowOpenChange(true) },
                    modifier = Modifier.testTag("chat-worktree-overflow"),
                )
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { onOverflowOpenChange(false) },
                    modifier = Modifier.widthIn(min = 200.dp),
                ) {
                    AndyDropdownMenuItem(
                        label = "Terminal",
                        onClick = {
                            onSelectAction(SelectedEnvironmentAction.Terminal)
                            onOverflowOpenChange(false)
                        },
                        leading = {
                            LucideIcon(Lucide.SquareTerminal, TextSecondary, Modifier.size(14.dp))
                        },
                        trailing = {
                            if (selectedAction is SelectedEnvironmentAction.Terminal) {
                                LucideIcon(Lucide.Check, TextSecondary, Modifier.size(12.dp))
                            }
                        },
                        modifier = Modifier.testTag("chat-worktree-terminal"),
                    )
                    worktreeActions.forEachIndexed { index, action ->
                        AndyDropdownMenuItem(
                            label = action.name,
                            onClick = {
                                onSelectAction(SelectedEnvironmentAction.Run(action))
                                onOverflowOpenChange(false)
                            },
                            leading = {
                                LucideIcon(Lucide.Play, TextSecondary, Modifier.size(14.dp))
                            },
                            trailing = {
                                val selected = (selectedAction as? SelectedEnvironmentAction.Run)
                                    ?.action?.id == action.id
                                if (selected) {
                                    LucideIcon(Lucide.Check, TextSecondary, Modifier.size(12.dp))
                                }
                            },
                            modifier = if (index == 0) {
                                Modifier.testTag("chat-worktree-run")
                            } else {
                                Modifier
                            },
                        )
                    }
                    AndyDropdownMenuItem(
                        label = "Copy path",
                        onClick = {
                            onOverflowOpenChange(false)
                            onCopyText(worktreePath)
                        },
                        leading = {
                            LucideIcon(Lucide.Copy, TextSecondary, Modifier.size(14.dp))
                        },
                    )
                    AndyDropdownMenuItem(
                        label = "Refresh diff",
                        onClick = {
                            onOverflowOpenChange(false)
                            onRefreshDiff()
                            onChangesExpandedChange(true)
                        },
                        leading = {
                            LucideIcon(Lucide.RefreshCw, TextSecondary, Modifier.size(14.dp))
                        },
                    )
                }
            }
            EnvironmentIconButton(
                path = Lucide.Play,
                contentDescription = "Run $selectedLabel",
                onClick = onRunSelected,
                modifier = Modifier.testTag("chat-worktree-play"),
            )
            if (showTuckControl) {
                EnvironmentIconButton(
                    path = Lucide.ChevronRight,
                    contentDescription = "Hide Environment",
                    onClick = onTuck,
                    modifier = Modifier.testTag("chat-worktree-tuck"),
                )
            }
        }

        EnvironmentRow(
            label = "Changes",
            leading = { LucideIcon(Lucide.FileCode, TextSecondary, Modifier.size(RowIconSize)) },
            onClick = { onChangesExpandedChange(!changesExpanded) },
            trailing = {
                EnvironmentDiffStats(status = treeStatus)
            },
        )

        AnimatedVisibility(
            visible = changesExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                diffSummary ?: "loading diff…",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 88.dp)
                    .padding(start = 28.dp, end = AndySpace.Space1, bottom = AndySpace.Space1)
                    .verticalScroll(rememberScrollState()),
            )
        }

        EnvironmentRow(
            label = "Worktree",
            leading = { LucideIcon(Lucide.Monitor, TextSecondary, Modifier.size(RowIconSize)) },
            onClick = { onWorktreeMenuOpenChange(true) },
            description = shortPath,
            tooltip = worktreePath,
            trailing = {
                Box {
                    LucideIcon(Lucide.ChevronDown, TextSecondary.copy(alpha = 0.55f), Modifier.size(12.dp))
                    DropdownMenu(
                        expanded = worktreeMenuOpen,
                        onDismissRequest = { onWorktreeMenuOpenChange(false) },
                        modifier = Modifier.widthIn(min = 220.dp, max = 360.dp),
                    ) {
                        Text(
                            worktreePath,
                            color = TextSecondary,
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(
                                horizontal = AndySpace.Space2,
                                vertical = AndySpace.Space1,
                            ),
                        )
                        AndyDropdownMenuItem(
                            label = "Copy path",
                            onClick = {
                                onWorktreeMenuOpenChange(false)
                                onCopyText(worktreePath)
                            },
                            leading = { LucideIcon(Lucide.Copy, TextSecondary, Modifier.size(14.dp)) },
                        )
                        AndyDropdownMenuItem(
                            label = "Open terminal",
                            onClick = {
                                onWorktreeMenuOpenChange(false)
                                onOpenTerminal()
                            },
                            leading = {
                                LucideIcon(Lucide.SquareTerminal, TextSecondary, Modifier.size(14.dp))
                            },
                        )
                    }
                }
            },
        )

        EnvironmentRow(
            label = branchName ?: "detached",
            leading = { LucideIcon(Lucide.GitBranch, TextSecondary, Modifier.size(RowIconSize)) },
            onClick = { branchName?.let(onCopyText) },
            marqueeLabel = branchName != null,
            trailing = {
                LucideIcon(Lucide.Copy, TextSecondary.copy(alpha = 0.45f), Modifier.size(12.dp))
            },
        )
    }
}

@Composable
private fun EnvironmentDiffStats(status: WorkingTreeStatus?) {
    if (status != null && (status.additions > 0 || status.deletions > 0 || status.isDirty)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status.additions > 0) {
                Text(
                    "+${status.additions}",
                    color = Green,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (status.deletions > 0) {
                Text(
                    "-${status.deletions}",
                    color = Red,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (status.additions == 0 && status.deletions == 0 && status.isDirty) {
                Text(
                    "${status.dirtyFileCount}",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                )
            }
        }
    } else {
        Text(
            "clean",
            color = TextSecondary.copy(alpha = 0.7f),
            fontFamily = DisplayFont,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EnvironmentRow(
    label: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    tooltip: String? = description,
    marqueeLabel: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    @Composable
    fun RowBody() {
        Row(
            modifier
                .fillMaxWidth()
                .height(EnvironmentRowHeight)
                .clip(AndyShape.Interactive)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = AndySpace.Space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            leading()
            Text(
                label,
                color = if (hovered) TextPrimary else TextPrimary.copy(alpha = 0.92f),
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = !marqueeLabel,
                overflow = if (marqueeLabel) TextOverflow.Visible else TextOverflow.Ellipsis,
                modifier = when {
                    marqueeLabel ->
                        Modifier
                            .weight(1f)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 800,
                                velocity = 28.dp,
                            )
                    description == null -> Modifier.weight(1f)
                    else -> Modifier
                },
            )
            if (description != null) {
                Text(
                    description,
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else if (!marqueeLabel) {
                Spacer(Modifier.weight(1f))
            }
            if (trailing != null) {
                Box(contentAlignment = Alignment.CenterEnd) {
                    trailing()
                }
            }
        }
    }

    if (tooltip != null) {
        HoverTooltip(text = tooltip, modifier = Modifier.fillMaxWidth()) { RowBody() }
    } else {
        RowBody()
    }
}

@Composable
private fun EnvironmentIconButton(
    path: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(28.dp)
            .clip(AndyShape.Interactive)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(path, TextSecondary, Modifier.size(14.dp))
    }
}

private val RowIconSize = 15.dp
private val EnvironmentRowHeight = 32.dp
private val EnvironmentHeaderHeight = 28.dp
