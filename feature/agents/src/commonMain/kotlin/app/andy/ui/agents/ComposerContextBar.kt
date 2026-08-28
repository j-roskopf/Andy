package app.andy.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.composer_git_branch
import app.andy.andy.generated.resources.composer_local
import app.andy.andy.generated.resources.composer_temporary
import app.andy.currentTimeMillis
import app.andy.model.AgentKind
import app.andy.model.GitBranchInfo
import app.andy.model.WorkingTreeStatus
import app.andy.model.agentUsageOverview
import app.andy.service.AndyServices
import app.andy.ui.components.AndyDropdownMenuItem
import app.andy.ui.components.AndyDropdownMenuSectionLabel
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.FieldChromeStyle
import app.andy.ui.components.HoverTooltip
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Always-on new-chat context strip above the input frame:
 * Local/worktree popup · branch popup · Temporary (right-aligned).
 */
@Composable
internal fun ComposerContextBar(
    services: AndyServices,
    agent: AgentKind,
    showGitControls: Boolean,
    useWorktree: Boolean,
    onUseWorktreeChange: (Boolean) -> Unit,
    branch: String?,
    workingTreeStatus: WorkingTreeStatus?,
    branches: List<GitBranchInfo>,
    onRefreshGit: () -> Unit,
    onCheckoutBranch: suspend (String) -> String?,
    onCreateAndCheckoutBranch: suspend (String) -> String?,
    temporary: Boolean,
    onTemporaryChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = AndyLayout.ControlHeightSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1),
        ) {
            if (showGitControls) {
                ComposerContinueInChip(
                    services = services,
                    agent = agent,
                    useWorktree = useWorktree,
                    onUseWorktreeChange = onUseWorktreeChange,
                )
                ComposerBranchChip(
                    branch = branch,
                    workingTreeStatus = workingTreeStatus,
                    branches = branches,
                    onRefreshGit = onRefreshGit,
                    onCheckoutBranch = onCheckoutBranch,
                    onCreateAndCheckoutBranch = onCreateAndCheckoutBranch,
                )
            }
        }
        ComposerTemporaryLabeledToggle(
            temporary = temporary,
            onTemporaryChange = onTemporaryChange,
        )
    }
}

@Composable
private fun ComposerContinueInChip(
    services: AndyServices,
    agent: AgentKind,
    useWorktree: Boolean,
    onUseWorktreeChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var rateLimitsExpanded by remember { mutableStateOf(false) }
    val tasks by services.agentRuns.tasks.collectAsState()
    val quotas by services.agentRuns.providerQuotas.collectAsState()
    val quotaAccess by services.agentRuns.quotaAccess.collectAsState()
    val now = currentTimeMillis()
    val overview = remember(tasks, agent, now / 60_000L) { agentUsageOverview(tasks, agent, now) }
    val quota = quotas[agent]
    var refreshing by remember(agent) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box {
        ComposerContextMenuChip(
            text = if (useWorktree) "Worktree" else "Local",
            expanded = expanded,
            onClick = { expanded = true },
            leading = {
                if (useWorktree) {
                    ComposerGitBranchIcon(TextSecondary, Modifier.size(GitBranchIconSize))
                } else {
                    ComposerLocalIcon(TextSecondary, Modifier.size(16.dp))
                }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                rateLimitsExpanded = false
            },
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp),
        ) {
            Column(Modifier.padding(AndySpace.Space1)) {
                AndyDropdownMenuSectionLabel("Continue in")
                AndyDropdownMenuItem(
                    label = "Local project",
                    onClick = {
                        onUseWorktreeChange(false)
                        expanded = false
                    },
                    leading = { ComposerLocalIcon(TextPrimary, Modifier.size(16.dp)) },
                    trailing = {
                        if (!useWorktree) {
                            Text("✓", color = TextSecondary, fontSize = 12.sp)
                        }
                    },
                )
                AndyDropdownMenuItem(
                    label = "New worktree",
                    onClick = {
                        onUseWorktreeChange(true)
                        expanded = false
                    },
                    leading = { ComposerGitBranchIcon(TextPrimary, Modifier.size(GitBranchIconSize)) },
                    trailing = {
                        if (useWorktree) {
                            Text("✓", color = TextSecondary, fontSize = 12.sp)
                        }
                    },
                )
                AndyHorizontalDivider(
                    modifier = Modifier.padding(vertical = AndySpace.Space1),
                    color = Border.copy(alpha = 0.65f),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(AndyShape.Interactive)
                        .clickable { rateLimitsExpanded = !rateLimitsExpanded }
                        .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                ) {
                    ComposerClockGlyph(TextSecondary, Modifier.size(14.dp))
                    Text(
                        "Rate limits remaining",
                        color = TextPrimary,
                        fontFamily = DisplayFont,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rateLimitsExpanded) {
                    ComposerRateLimitsSummary(
                        agent = agent,
                        overview = overview,
                        quota = quota,
                        accountAccessEnabled = quotaAccess.allows(agent),
                        nowMillis = now,
                        refreshing = refreshing,
                        onEnableAccountAccess = { services.agentRuns.setQuotaAccess(agent, true) },
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                services.agentRuns.refreshProviderQuotas()
                                refreshing = false
                            }
                        },
                    )
                } else {
                    Text(
                        when {
                            quota?.windows?.isNotEmpty() == true -> {
                                val window = quota.windows.first()
                                window.remainingFraction?.let { "${(it * 100).toInt()}% left · ${window.label}" }
                                    ?: window.detail?.takeIf { it.isNotBlank() }
                                    ?: "Limits available"
                            }
                            overview.runsLast24Hours > 0 ->
                                "${overview.runsLast24Hours} local run${if (overview.runsLast24Hours == 1) "" else "s"} in 24h"
                            else -> "No local usage data was found yet."
                        },
                        color = TextSecondary,
                        fontFamily = DisplayFont,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space1),
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerRateLimitsSummary(
    agent: AgentKind,
    overview: app.andy.model.AgentUsageOverview,
    quota: app.andy.model.AgentProviderQuota?,
    accountAccessEnabled: Boolean,
    nowMillis: Long,
    refreshing: Boolean,
    onEnableAccountAccess: () -> Unit,
    onRefresh: () -> Unit,
) {
    val windows = quota?.windows.orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space1),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(agent.label, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
            Text(
                if (refreshing) "checking…" else "refresh",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(AndyShape.Interactive)
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        when {
            !accountAccessEnabled && agent != AgentKind.Codex -> {
                Text(
                    "Account limits are off.",
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                )
                Text(
                    "Allow access",
                    color = Yellow,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(AndyShape.Interactive)
                        .clickable(onClick = onEnableAccountAccess)
                        .padding(vertical = 2.dp),
                )
            }
            windows.isEmpty() -> {
                Text(
                    "No live quota windows yet.",
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                )
            }
            else -> {
                windows.take(3).forEach { window ->
                    val value = window.remainingFraction?.let { "${(it * 100).toInt()}% left" }
                        ?: window.detail.orEmpty()
                    Text(
                        "${window.label}: $value",
                        color = TextPrimary,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Text(
            "${overview.runsLast24Hours} local run${if (overview.runsLast24Hours == 1) "" else "s"} · 24h",
            color = TextSecondary.copy(alpha = 0.8f),
            fontFamily = MonoFont,
            fontSize = 10.sp,
        )
        // Keep nowMillis referenced so callers can refresh relative reset labels later.
        @Suppress("UNUSED_EXPRESSION")
        nowMillis
    }
}

@Composable
private fun ComposerBranchChip(
    branch: String?,
    workingTreeStatus: WorkingTreeStatus?,
    branches: List<GitBranchInfo>,
    onRefreshGit: () -> Unit,
    onCheckoutBranch: suspend (String) -> String?,
    onCreateAndCheckoutBranch: suspend (String) -> String?,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val filtered = remember(branches, query) {
        val q = query.trim()
        if (q.isEmpty()) branches
        else branches.filter { it.name.contains(q, ignoreCase = true) }
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            query = ""
            creating = false
            newBranchName = ""
            error = null
            onRefreshGit()
        }
    }

    Box {
        ComposerContextMenuChip(
            text = branch ?: "detached",
            expanded = expanded,
            onClick = { expanded = true },
            leading = {
                ComposerGitBranchIcon(TextSecondary, Modifier.size(GitBranchIconSize))
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (!busy) expanded = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
        ) {
            Column(Modifier.padding(AndySpace.Space1)) {
                TextField(
                    value = query,
                    onValueChange = {
                        query = it
                        creating = false
                        error = null
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AndySpace.Space1, vertical = AndySpace.Space1),
                    textStyle = LocalTextStyle.current.copy(
                        color = TextPrimary,
                        fontFamily = DisplayFont,
                        fontSize = 13.sp,
                    ),
                    colors = fieldColors(),
                    chromeStyle = FieldChromeStyle.Standard,
                    placeholder = {
                        Text(
                            "Search branches...",
                            color = TextSecondary,
                            fontFamily = DisplayFont,
                            fontSize = 13.sp,
                        )
                    },
                )
                error?.let { message ->
                    Text(
                        message,
                        color = Red,
                        fontFamily = DisplayFont,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space1),
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (filtered.isEmpty()) {
                        Text(
                            if (query.isBlank()) "No local branches" else "No branches matching \"$query\"",
                            color = TextSecondary,
                            fontFamily = DisplayFont,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space3),
                        )
                    } else {
                        filtered.forEach { info ->
                            val interaction = remember { MutableInteractionSource() }
                            val hovered by interaction.collectIsHoveredAsState()
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(AndyShape.Interactive)
                                    .background(
                                        if (hovered) AndyColors.SurfaceHover else Color.Transparent,
                                    )
                                    .clickable(
                                        enabled = !busy && !info.isCurrent,
                                        interactionSource = interaction,
                                        indication = null,
                                    ) {
                                        busy = true
                                        error = null
                                        scope.launch {
                                            val failure = onCheckoutBranch(info.name)
                                            busy = false
                                            if (failure == null) {
                                                expanded = false
                                            } else {
                                                error = failure
                                            }
                                        }
                                    }
                                    .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        info.name,
                                        color = TextPrimary,
                                        fontFamily = DisplayFont,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (info.isCurrent) {
                                        Text(
                                            "current",
                                            color = TextSecondary,
                                            fontFamily = DisplayFont,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                                if (info.isCurrent && workingTreeStatus?.isDirty == true) {
                                    val files = workingTreeStatus.dirtyFileCount
                                    val fileLabel = if (files == 1) "1 file" else "$files files"
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            "Uncommitted: $fileLabel",
                                            color = TextSecondary,
                                            fontFamily = DisplayFont,
                                            fontSize = 11.sp,
                                        )
                                        if (workingTreeStatus.additions > 0 || workingTreeStatus.deletions > 0) {
                                            Text(
                                                "+${workingTreeStatus.additions}",
                                                color = Green,
                                                fontFamily = MonoFont,
                                                fontSize = 11.sp,
                                            )
                                            Text(
                                                "-${workingTreeStatus.deletions}",
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
                }
                AndyHorizontalDivider(
                    modifier = Modifier.padding(vertical = AndySpace.Space1),
                    color = Border.copy(alpha = 0.65f),
                )
                if (creating) {
                    Column(
                        Modifier.padding(horizontal = AndySpace.Space1, vertical = AndySpace.Space1),
                        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    ) {
                        TextField(
                            value = newBranchName,
                            onValueChange = {
                                newBranchName = it
                                error = null
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = TextPrimary,
                                fontFamily = MonoFont,
                                fontSize = 12.sp,
                            ),
                            colors = fieldColors(),
                            chromeStyle = FieldChromeStyle.Standard,
                            placeholder = {
                                Text(
                                    "new-branch-name",
                                    color = TextSecondary,
                                    fontFamily = MonoFont,
                                    fontSize = 12.sp,
                                )
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                            Text(
                                if (busy) "Creating…" else "Create and checkout",
                                color = if (newBranchName.isNotBlank() && !busy) Yellow else TextSecondary,
                                fontFamily = DisplayFont,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(AndyShape.Interactive)
                                    .clickable(enabled = newBranchName.isNotBlank() && !busy) {
                                        busy = true
                                        error = null
                                        scope.launch {
                                            val failure = onCreateAndCheckoutBranch(newBranchName.trim())
                                            busy = false
                                            if (failure == null) {
                                                expanded = false
                                            } else {
                                                error = failure
                                            }
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            Text(
                                "Cancel",
                                color = TextSecondary,
                                fontFamily = DisplayFont,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(AndyShape.Interactive)
                                    .clickable(enabled = !busy) {
                                        creating = false
                                        newBranchName = ""
                                        error = null
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                } else {
                    AndyDropdownMenuItem(
                        label = "+ Create and checkout new branch...",
                        onClick = {
                            creating = true
                            newBranchName = query.trim()
                            error = null
                        },
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerContextMenuChip(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .height(AndyLayout.ControlHeightSm)
            .clip(RoundedCornerShape(AndyRadius.Interactive))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = AndySpace.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(
            text,
            color = TextSecondary,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Text(
            if (expanded) "▴" else "▾",
            color = TextSecondary.copy(alpha = 0.55f),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ComposerTemporaryLabeledToggle(
    temporary: Boolean,
    onTemporaryChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (temporary) Yellow else TextSecondary.copy(alpha = 0.85f)
    HoverTooltip(
        text = if (temporary) {
            "Temporary chat: on — discarded when closed"
        } else {
            "Temporary chat — never saved to history"
        },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .height(AndyLayout.ControlHeightSm)
                .clip(RoundedCornerShape(AndyRadius.Interactive))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Checkbox) { onTemporaryChange(!temporary) }
                .semantics {
                    contentDescription = if (temporary) "temporary chat on" else "temporary chat off"
                    role = Role.Checkbox
                }
                .padding(horizontal = AndySpace.Space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ComposerTemporaryIcon(
                color = tint,
                modifier = Modifier.size(TemporaryLabeledIconSize),
            )
            Text(
                "Temporary",
                color = tint,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

/** User-provided monitor artwork, pre-rasterized for toolbar size (see composeResources/drawable/composer_local.png). */
@Composable
private fun ComposerLocalIcon(color: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.composer_local),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

/** User-provided temporary-chat artwork, pre-rasterized for toolbar size. */
@Composable
private fun ComposerTemporaryIcon(color: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.composer_temporary),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

/** User-provided git branch artwork: 128px white silhouette, stroke-boosted to fill the canvas. */
@Composable
private fun ComposerGitBranchIcon(color: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.composer_git_branch),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

@Composable
private fun ComposerClockGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.10f, cap = StrokeCap.Round)
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color, radius = size.minDimension * 0.38f, center = c, style = stroke)
        drawLine(color, c, Offset(c.x, c.y - size.height * 0.22f), stroke.width, StrokeCap.Round)
        drawLine(color, c, Offset(c.x + size.width * 0.18f, c.y + size.height * 0.08f), stroke.width, StrokeCap.Round)
    }
}

private val TemporaryLabeledIconSize = 16.dp
private val GitBranchIconSize = 18.dp
