package app.andy.ui.actions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import app.andy.ui.components.Button
import app.andy.ui.components.IconButton
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.dangerOutlinedButtonColors
import app.andy.ui.components.fieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.KanbanBoard
import app.andy.model.KanbanCard
import app.andy.model.KanbanLane
import app.andy.model.ActionProject
import app.andy.model.AgentCliStatus
import app.andy.model.AgentContextualProvenance
import app.andy.model.AgentStatus
import app.andy.domain.excludingTemporary
import app.andy.model.AgentTask
import app.andy.model.ContextualActionKind
import app.andy.service.AndyServices
import app.andy.service.KanbanLaneDirection
import app.andy.service.UnavailableKanbanService
import app.andy.ui.components.AndyAlertDialog
import app.andy.ui.components.EmptyState
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.LabeledField
import app.andy.ui.components.PendingConfirmation
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.agents.AgentPillIcon
import app.andy.ui.agents.AgentTaskComposerPane
import app.andy.ui.agents.agentStatusColor
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val LaneWidthDp = 280
private val GrabOffset = Offset(24f, 16f)
private const val KanbanMotionMs = 120

private data class KanbanDragState(
    val cardId: String,
    val sourceLaneId: String,
    val cardSize: IntSize,
    val pointerPositionInBoard: Offset,
)

internal data class KanbanDropTarget(
    val laneId: String,
    val index: Int,
)

@Composable
fun KanbanBoardScreen(
    services: AndyServices,
    project: ActionProject,
    onOpenChat: (String) -> Unit,
    onCreateSpec: (KanbanCard) -> Unit,
) {
    if (services.kanban is UnavailableKanbanService) {
        EmptyState(
            "Kanban isn't available while Andy is connected to a running andyd. " +
                "Quit andyd and restart Andy to edit your board.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val boards by services.kanban.boards.collectAsState()
    val board = boards[project.id] ?: KanbanBoard()
    val allAgentTasks by services.agentRuns.tasks.collectAsState()
    val agentTasks = allAgentTasks.excludingTemporary()
    val cliStatuses by services.agentRuns.cliStatuses.collectAsState()
    var dragState by remember { mutableStateOf<KanbanDragState?>(null) }
    var laneBounds by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }
    var boardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var laneNameDialog by remember { mutableStateOf<LaneNameDialogState?>(null) }
    var cardDialog by remember { mutableStateOf<CardDialogState?>(null) }
    var assignCard by remember { mutableStateOf<KanbanCard?>(null) }
    var showAddLaneDialog by remember { mutableStateOf(false) }

    val dropTarget = dragState?.let { drag ->
        resolveDropTarget(
            pointer = drag.pointerPositionInBoard,
            dragCardId = drag.cardId,
            lanes = board.lanes,
            laneBounds = laneBounds,
            cardBounds = cardBounds,
        )
    }

    pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(
            confirmation = confirmation,
            onDismiss = { pendingConfirmation = null },
            onConfirm = {
                pendingConfirmation = null
                confirmation.onConfirm()
            },
        )
    }

    laneNameDialog?.let { dialog ->
        LaneNameDialog(
            title = "Rename lane",
            initialName = dialog.initialName,
            confirmLabel = "Save",
            onDismiss = { laneNameDialog = null },
            onConfirm = { name ->
                services.kanban.renameLane(project.id, dialog.laneId, name)
                laneNameDialog = null
            },
        )
    }

    if (showAddLaneDialog) {
        KanbanAddLaneDialog(
            onDismiss = { showAddLaneDialog = false },
            onConfirm = { name ->
                services.kanban.addLane(project.id, name)
                showAddLaneDialog = false
            },
        )
    }

    assignCard?.let { card ->
        KanbanAssignDialog(
            services = services,
            project = project,
            card = card,
            cliStatuses = cliStatuses,
            onDismiss = { assignCard = null },
            onAssigned = { chatTaskId ->
                assignCard = null
                cardDialog = null
                onOpenChat(chatTaskId)
            },
        )
    }

    cardDialog?.let { dialog ->
        val editedCard = (dialog as? CardDialogState.Edit)?.card
        val activeChat = editedCard?.activeChatTaskId?.let { id -> agentTasks.firstOrNull { it.id == id } }
        val linkedChats = editedCard?.linkedChatTaskIds.orEmpty()
            .filterNot { it == editedCard?.activeChatTaskId }
            .mapNotNull { id -> agentTasks.firstOrNull { it.id == id } }
        KanbanCardDialog(
            state = dialog,
            activeChat = activeChat,
            linkedChats = linkedChats,
            onDismiss = { cardDialog = null },
            onSave = { title, description, tags ->
                when (dialog) {
                    is CardDialogState.Create -> services.kanban.addCard(project.id, dialog.laneId, title, description, tags)
                    is CardDialogState.Edit -> services.kanban.updateCard(project.id, dialog.card.id, title, description, tags)
                }
                cardDialog = null
            },
            onDelete = if (dialog is CardDialogState.Edit) {
                {
                    services.kanban.deleteCard(project.id, dialog.card.id)
                    cardDialog = null
                }
            } else {
                null
            },
            onAssign = { editedCard?.let { assignCard = it } },
            onCreateSpec = {
                cardDialog = null
                editedCard?.let(onCreateSpec)
            },
            onOpenChat = onOpenChat,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = AndySpace.Space4),
    ) {
        KanbanBoardHeader(
            board = board,
            onAddLane = { showAddLaneDialog = true },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { boardCoordinates = it },
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = AndySpace.Space4),
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space4),
            ) {
                board.lanes.forEachIndexed { laneIndex, lane ->
                    KanbanLaneColumn(
                        lane = lane,
                        cards = lane.cards,
                        agentTasks = agentTasks,
                        laneIndex = laneIndex,
                        laneCount = board.lanes.size,
                        dragState = dragState,
                        dropTarget = dropTarget,
                        onLaneBounds = { rect -> laneBounds = laneBounds + (lane.id to rect) },
                        onCardBounds = { cardId, rect ->
                            if (rect == null) cardBounds.remove(cardId) else cardBounds[cardId] = rect
                        },
                        onCardClick = { card -> cardDialog = CardDialogState.Edit(card) },
                        onAssignCard = { card -> assignCard = card },
                        onCreateSpecCard = onCreateSpec,
                        onOpenChat = onOpenChat,
                        onAddCard = { cardDialog = CardDialogState.Create(lane.id) },
                        onRenameLane = { laneNameDialog = LaneNameDialogState(lane.id, lane.name) },
                        onDeleteLane = {
                            if (lane.cards.isEmpty()) {
                                services.kanban.deleteLane(project.id, lane.id)
                            } else {
                                pendingConfirmation = PendingConfirmation(
                                    title = "Delete lane?",
                                    message = "Delete '${lane.name}' and its ${lane.cards.size} card(s)? This can't be undone.",
                                    confirmLabel = "Delete",
                                ) { services.kanban.deleteLane(project.id, lane.id) }
                            }
                        },
                        onMoveLaneLeft = { services.kanban.moveLane(project.id, lane.id, KanbanLaneDirection.Left) },
                        onMoveLaneRight = { services.kanban.moveLane(project.id, lane.id, KanbanLaneDirection.Right) },
                        onDragStart = { card, cardSize, pointerInBoard ->
                            dragState = KanbanDragState(
                                cardId = card.id,
                                sourceLaneId = lane.id,
                                cardSize = cardSize,
                                pointerPositionInBoard = pointerInBoard,
                            )
                        },
                        onDrag = { delta ->
                            dragState = dragState?.copy(
                                pointerPositionInBoard = dragState!!.pointerPositionInBoard + delta,
                            )
                        },
                        onDragEnd = {
                            val drag = dragState ?: return@KanbanLaneColumn
                            val target = resolveDropTarget(
                                pointer = drag.pointerPositionInBoard,
                                dragCardId = drag.cardId,
                                lanes = board.lanes,
                                laneBounds = laneBounds,
                                cardBounds = cardBounds,
                            )
                            if (target != null) {
                                services.kanban.moveCard(project.id, drag.cardId, target.laneId, target.index)
                            }
                            dragState = null
                        },
                        onDragCancel = { dragState = null },
                        boardCoordinates = boardCoordinates,
                        cardBounds = cardBounds,
                    )
                }
            }

            dragState?.let { drag ->
                val card = board.lanes.flatMap { it.cards }.firstOrNull { it.id == drag.cardId } ?: return@let
                val density = LocalDensity.current
                val cardWidth = with(density) { drag.cardSize.width.toDp() }
                KanbanCardView(
                    card = card,
                    suppressHover = true,
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = drag.pointerPositionInBoard.x - GrabOffset.x
                            translationY = drag.pointerPositionInBoard.y - GrabOffset.y
                        }
                        .width(cardWidth)
                        .alpha(0.95f),
                )
            }
        }
    }
}

@Composable
private fun KanbanBoardHeader(
    board: KanbanBoard,
    onAddLane: () -> Unit,
) {
    val totalCards = board.lanes.sumOf { it.cards.size }
    val completedCards = board.lanes
        .filter { lane -> isCompletedLane(lane) }
        .sumOf { it.cards.size }
    val activeCards = totalCards - completedCards

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = AndySpace.Space5, bottom = AndySpace.Space4),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space4),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space4),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AndySpace.Space1)) {
                Text(
                    "Kanban",
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                )
                Text(
                    "Drag cards to move them across the board",
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                )
            }
            KanbanSummaryMetric("cards", totalCards, TextPrimary)
            KanbanSummaryMetric("active", activeCards, Cyan)
            KanbanSummaryMetric("done", completedCards, Green)
            KanbanAddLaneAction(onClick = onAddLane)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Border.copy(alpha = 0.65f)),
        )
    }
}

@Composable
private fun KanbanSummaryMetric(label: String, value: Int, color: Color) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space1),
    ) {
        Text(
            value.toString(),
            color = color,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Text(
            label.uppercase(),
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 9.sp,
            letterSpacing = 0.7.sp,
        )
    }
}

private fun isCompletedLane(lane: KanbanLane): Boolean =
    isCompletedKanbanLane(laneId = lane.id, laneName = lane.name)

/**
 * Treats a lane as completed only for explicit done-ish ids/labels.
 *
 * Substring matching is intentionally avoided so names like "Incomplete", "Undone",
 * or "Not done" do not inflate the board's done total.
 */
fun isCompletedKanbanLane(laneId: String, laneName: String): Boolean {
    if (laneId.equals("done", ignoreCase = true)) return true
    val name = laneName.trim()
    if (name.isEmpty()) return false
    val lower = name.lowercase()
    if (UNFINISHED_LANE_LABEL.containsMatchIn(lower)) return false
    return COMPLETED_LANE_LABEL.containsMatchIn(lower)
}

private val COMPLETED_LANE_LABEL =
    Regex("""\b(done|complete|completed|finished)\b""")

private val UNFINISHED_LANE_LABEL =
    Regex("""\b(incomplete|undone|not[\s-]+done|not[\s-]+complete[d]?)\b""")

@Composable
private fun KanbanLaneColumn(
    lane: KanbanLane,
    cards: List<KanbanCard>,
    agentTasks: List<AgentTask>,
    laneIndex: Int,
    laneCount: Int,
    dragState: KanbanDragState?,
    dropTarget: KanbanDropTarget?,
    onLaneBounds: (Rect) -> Unit,
    onCardBounds: (String, Rect?) -> Unit,
    onCardClick: (KanbanCard) -> Unit,
    onAssignCard: (KanbanCard) -> Unit,
    onCreateSpecCard: (KanbanCard) -> Unit,
    onOpenChat: (String) -> Unit,
    onAddCard: () -> Unit,
    onRenameLane: () -> Unit,
    onDeleteLane: () -> Unit,
    onMoveLaneLeft: () -> Unit,
    onMoveLaneRight: () -> Unit,
    onDragStart: (KanbanCard, IntSize, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    boardCoordinates: LayoutCoordinates?,
    cardBounds: Map<String, Rect>,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isDropTarget = dropTarget?.laneId == lane.id && dragState != null
    // Light mode needs an opaque well — PaneBg@alpha washes out on the canvas.
    // Dark keeps a soft translucent pane so lanes sit lightly on the deep content bg.
    val idleLane = if (AndyColors.isLight) AndyColors.PaneBg else AndyColors.PaneBg.copy(alpha = 0.62f)
    val dropLane = if (AndyColors.isLight) {
        AndyColors.SurfaceHover
    } else {
        AndyColors.PaneBg.copy(alpha = 0.72f)
    }
    val laneBackground by animateColorAsState(
        targetValue = if (isDropTarget) dropLane else idleLane,
        animationSpec = tween(KanbanMotionMs),
        label = "kanbanLaneBackground",
    )

    Column(
        Modifier
            .width(LaneWidthDp.dp)
            .fillMaxHeight()
            .clip(AndyShape.Row)
            .background(laneBackground, AndyShape.Row)
            .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space3)
            .onGloballyPositioned { coordinates ->
                val boardOrigin = boardCoordinates?.positionInRoot() ?: Offset.Zero
                val bounds = coordinates.boundsInRoot()
                onLaneBounds(
                    Rect(
                        bounds.left - boardOrigin.x,
                        bounds.top - boardOrigin.y,
                        bounds.right - boardOrigin.x,
                        bounds.bottom - boardOrigin.y,
                    ),
                )
            },
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Text(
                lane.name,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                cards.size.toString(),
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(28.dp),
                    contentDescription = "Actions for ${lane.name}",
                ) {
                    Text("⋯", color = TextSecondary, fontSize = 16.sp)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = Panel,
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename", color = TextPrimary) },
                        onClick = { menuExpanded = false; onRenameLane() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete lane", color = if (laneCount <= 1) TextSecondary else TextPrimary) },
                        enabled = laneCount > 1,
                        onClick = { menuExpanded = false; onDeleteLane() },
                    )
                    DropdownMenuItem(
                        text = { Text("Move left", color = if (laneIndex == 0) TextSecondary else TextPrimary) },
                        enabled = laneIndex > 0,
                        onClick = { menuExpanded = false; onMoveLaneLeft() },
                    )
                    DropdownMenuItem(
                        text = { Text("Move right", color = if (laneIndex >= laneCount - 1) TextSecondary else TextPrimary) },
                        enabled = laneIndex < laneCount - 1,
                        onClick = { menuExpanded = false; onMoveLaneRight() },
                    )
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            val insertionIndex = if (isDropTarget) dropTarget.index else null
            if (cards.isEmpty() && insertionIndex == 0) {
                KanbanInsertionIndicator()
            }
            cards.forEachIndexed { index, card ->
                if (insertionIndex == index) {
                    KanbanInsertionIndicator()
                }
                val isDragging = dragState?.cardId == card.id
                KanbanCardView(
                    card = card,
                    activeChat = card.activeChatTaskId?.let { id -> agentTasks.firstOrNull { it.id == id } },
                    suppressHover = dragState != null,
                    onClick = { if (!isDragging) onCardClick(card) },
                    onAssign = { onAssignCard(card) },
                    onCreateSpec = { onCreateSpecCard(card) },
                    onOpenChat = onOpenChat,
                    modifier = Modifier
                        .alpha(if (isDragging) 0.3f else 1f)
                        .onGloballyPositioned { coordinates ->
                            if (!isDragging) {
                                val boardOrigin = boardCoordinates?.positionInRoot() ?: Offset.Zero
                                val bounds = coordinates.boundsInRoot()
                                onCardBounds(
                                    card.id,
                                    Rect(
                                        bounds.left - boardOrigin.x,
                                        bounds.top - boardOrigin.y,
                                        bounds.right - boardOrigin.x,
                                        bounds.bottom - boardOrigin.y,
                                    ),
                                )
                            }
                        }
                        .pointerInput(card.id, boardCoordinates) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val cardRect = cardBounds[card.id]
                                    val pointerInBoard = if (cardRect != null) {
                                        Offset(cardRect.left + offset.x, cardRect.top + offset.y)
                                    } else {
                                        offset
                                    }
                                    onDragStart(card, IntSize(size.width, size.height), pointerInBoard)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount)
                                },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                            )
                        },
                )
            }
            if (insertionIndex == cards.size) {
                KanbanInsertionIndicator()
            }
            KanbanTextAction(
                label = "+ Add card",
                onClick = onAddCard,
                modifier = Modifier.align(Alignment.Start),
            )
        }
    }
}

@Composable
internal fun KanbanAddLaneAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KanbanTextAction(
        label = "+ Add lane",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun KanbanTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val textColor by animateColorAsState(
        targetValue = if (hovered) TextPrimary else TextSecondary,
        animationSpec = tween(KanbanMotionMs),
        label = "kanbanTextActionColor",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(KanbanMotionMs),
        label = "kanbanTextActionScale",
    )
    Text(
        label,
        color = textColor,
        fontFamily = DisplayFont,
        fontSize = 12.sp,
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(AndyShape.Interactive)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = AndySpace.Space2, vertical = 5.dp),
    )
}

@Composable
internal fun KanbanAddLaneDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    LaneNameDialog(
        title = "New lane",
        initialName = "",
        confirmLabel = "Add",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun KanbanInsertionIndicator() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Rust, RoundedCornerShape(AndyRadius.Pill)),
    )
}

@Composable
private fun KanbanCardView(
    card: KanbanCard,
    activeChat: AgentTask? = null,
    suppressHover: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onAssign: () -> Unit = {},
    onCreateSpec: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
) {
    val interactionSource = remember(card.id) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val showHover = hovered && !suppressHover
    val cardBackground by animateColorAsState(
        targetValue = if (showHover) AndyColors.SurfaceHover else AndyColors.SurfaceRaised,
        animationSpec = tween(KanbanMotionMs),
        label = "kanbanCardBackground",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null && !suppressHover) 0.97f else 1f,
        animationSpec = tween(KanbanMotionMs),
        label = "kanbanCardScale",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(AndyShape.Row)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .background(cardBackground, AndyShape.Row)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (!suppressHover) Modifier.hoverable(interactionSource) else Modifier,
            )
            .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Text(
            card.title,
            color = TextPrimary,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.description.isNotBlank()) {
            Text(
                card.description,
                color = TextSecondary,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                card.tags.forEach { tag ->
                    KanbanTagChip(tag)
                }
            }
        }
        if (activeChat == null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
                modifier = Modifier.align(Alignment.Start),
            ) {
                KanbanTextAction(label = "+ Assign", onClick = onAssign)
                KanbanTextAction(label = "+ Create spec", onClick = onCreateSpec)
            }
        } else {
            KanbanChatLinkRow(
                chat = activeChat,
                onOpenChat = onOpenChat,
                onReassign = onAssign.takeIf { canReassignKanbanCard(activeChat) },
            )
        }
    }
}

@Composable
private fun KanbanChatLinkRow(
    chat: AgentTask,
    onOpenChat: (String) -> Unit,
    onReassign: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        AgentPillIcon(chat.agent, Modifier.size(14.dp))
        Box(
            Modifier
                .size(6.dp)
                .background(agentStatusColor(chat.status), RoundedCornerShape(AndyRadius.Pill)),
        )
        Text(
            chat.status?.name ?: "Queued",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
        )
        KanbanTextAction("Open chat", { onOpenChat(chat.id) })
        onReassign?.let { KanbanTextAction("Reassign", it) }
    }
}

@Composable
private fun KanbanTagChip(tag: String) {
    val color = tagColor(tag)
    Row(
        Modifier
            .clip(RoundedCornerShape(AndyRadius.Control))
            .background(color.copy(alpha = 0.13f), RoundedCornerShape(AndyRadius.Control))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1),
    ) {
        Box(Modifier.size(5.dp).background(color, RoundedCornerShape(AndyRadius.Pill)))
        Text(
            tag,
            color = color,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun tagColor(tag: String): Color =
    Color.hsv(hue = (tag.hashCode().mod(360)).toFloat(), saturation = 0.55f, value = 0.85f)

private data class LaneNameDialogState(val laneId: String, val initialName: String)

private sealed class CardDialogState {
    data class Create(val laneId: String) : CardDialogState()
    data class Edit(val card: KanbanCard) : CardDialogState()
}

@Composable
private fun LaneNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AndyAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun KanbanCardDialog(
    state: CardDialogState,
    activeChat: AgentTask?,
    linkedChats: List<AgentTask>,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, tags: List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onAssign: () -> Unit,
    onCreateSpec: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val initial = when (state) {
        is CardDialogState.Create -> Triple("", "", emptyList<String>())
        is CardDialogState.Edit -> Triple(state.card.title, state.card.description, state.card.tags)
    }
    var title by remember(state) { mutableStateOf(initial.first) }
    var description by remember(state) { mutableStateOf(initial.second) }
    var tags by remember(state) { mutableStateOf(initial.third) }
    var tagInput by remember(state) { mutableStateOf("") }
    var historyExpanded by remember(state) { mutableStateOf(false) }

    fun commitTagInput() {
        val candidate = tagInput.trim()
        if (candidate.isNotEmpty() && candidate !in tags) {
            tags = tags + candidate
        }
        tagInput = ""
    }

    AndyAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Text(
                if (state is CardDialogState.Create) "New card" else "Edit card",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                LabeledField("Title", title, { title = it }, Modifier.fillMaxWidth())
                LabeledField(
                    "Description (optional)",
                    description,
                    { description = it },
                    Modifier.fillMaxWidth(),
                    singleLine = false,
                    minHeight = 96.dp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Tags",
                        color = AndyColors.TextTertiary,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    )
                    TextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = AndyLayout.FieldHeight)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter || event.key == Key.Comma) {
                                    commitTagInput()
                                    true
                                } else {
                                    false
                                }
                            },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = TextPrimary,
                            fontFamily = DisplayFont,
                            fontSize = 13.sp,
                        ),
                        placeholder = { Text("Type a tag and press Enter", color = TextSecondary) },
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitTagInput() }),
                    )
                }
                if (tags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        tags.forEach { tag ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                KanbanTagChip(tag)
                                Text(
                                    "×",
                                    color = TextSecondary,
                                    modifier = Modifier
                                        .clickable { tags = tags - tag }
                                        .padding(horizontal = 2.dp),
                                )
                            }
                        }
                    }
                }
                if (state is CardDialogState.Edit) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    ) {
                        Text(
                            "Agent",
                            color = AndyColors.TextTertiary,
                            fontFamily = DisplayFont,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                        )
                        if (activeChat == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                                OutlinedButton(onClick = onAssign) { Text("Assign to agent") }
                                OutlinedButton(onClick = onCreateSpec) { Text("Create spec") }
                            }
                        } else {
                            KanbanChatLinkRow(
                                chat = activeChat,
                                onOpenChat = onOpenChat,
                                onReassign = onAssign.takeIf { canReassignKanbanCard(activeChat) },
                            )
                        }
                        if (linkedChats.isNotEmpty()) {
                            KanbanTextAction(
                                label = "${if (historyExpanded) "Hide" else "Show"} history (${linkedChats.size})",
                                onClick = { historyExpanded = !historyExpanded },
                                modifier = Modifier.align(Alignment.Start),
                            )
                            if (historyExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                                    linkedChats.forEach { chat ->
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                                        ) {
                                            AgentPillIcon(chat.agent, Modifier.size(14.dp))
                                            Text(
                                                chat.title,
                                                color = TextSecondary,
                                                fontFamily = DisplayFont,
                                                fontSize = 11.sp,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            KanbanTextAction(
                                                label = "Open",
                                                onClick = { onOpenChat(chat.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    commitTagInput()
                    onSave(title.trim(), description.trim(), tags)
                },
                enabled = title.isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete, colors = dangerOutlinedButtonColors()) {
                        Text("Delete card")
                    }
                }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun KanbanAssignDialog(
    services: AndyServices,
    project: ActionProject,
    card: KanbanCard,
    cliStatuses: List<AgentCliStatus>,
    onDismiss: () -> Unit,
    onAssigned: (chatTaskId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val initialPrompt = defaultKanbanAssignPrompt(card.title, card.description)
    AndyAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Text(
                "Assign \"${card.title}\"",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Box(Modifier.width(760.dp).height(600.dp)) {
                AgentTaskComposerPane(
                    services = services,
                    cliStatuses = cliStatuses,
                    projectContext = project,
                    initialPrompt = initialPrompt,
                    wrapComposerControls = true,
                    onSubmit = { draft ->
                        scope.launch {
                            val task = services.agentRuns.createAndStart(
                                draft.copy(
                                    title = card.title,
                                    prompt = draft.prompt.ifBlank { initialPrompt },
                                    provenance = AgentContextualProvenance(
                                        sourceKind = ContextualActionKind.Kanban,
                                        kanbanCardId = card.id,
                                    ),
                                ),
                            )
                            services.kanban.linkChat(project.id, card.id, task.id)
                            onAssigned(task.id)
                        }
                    },
                    onCancel = onDismiss,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

fun defaultKanbanAssignPrompt(title: String, description: String): String {
    val trimmedTitle = title.trim()
    val trimmedDescription = description.trim()
    return when {
        trimmedDescription.isBlank() -> trimmedTitle
        trimmedTitle.isBlank() || trimmedDescription.startsWith(trimmedTitle) -> trimmedDescription
        else -> "$trimmedTitle\n\n$trimmedDescription"
    }
}

fun canReassignKanbanCard(activeChat: AgentTask?): Boolean =
    activeChat?.status == AgentStatus.Done || activeChat?.status == AgentStatus.Error

private fun resolveDropTarget(
    pointer: Offset,
    dragCardId: String,
    lanes: List<KanbanLane>,
    laneBounds: Map<String, Rect>,
    cardBounds: Map<String, Rect>,
): KanbanDropTarget? {
    if (laneBounds.isEmpty()) return null
    val targetLane = laneBounds.entries
        .firstOrNull { (_, rect) -> pointer.x in rect.left..rect.right }
        ?.key
        ?: laneBounds.minByOrNull { (_, rect) ->
            val centerX = rect.left + rect.width / 2f
            abs(pointer.x - centerX)
        }?.key
        ?: return null

    val lane = lanes.firstOrNull { it.id == targetLane } ?: return null
    val cardsInLane = lane.cards.filter { it.id != dragCardId }
    if (cardsInLane.isEmpty()) return KanbanDropTarget(targetLane, 0)

    val orderedRects = cardsInLane.mapNotNull { card -> cardBounds[card.id]?.let { card.id to it } }
    val index = orderedRects.indexOfFirst { (_, rect) ->
        pointer.y < rect.top + rect.height / 2f
    }.let { found ->
        if (found < 0) cardsInLane.size else found
    }
    return KanbanDropTarget(targetLane, index)
}

internal fun resolveKanbanDropTargetForTest(
    pointer: Offset,
    dragCardId: String,
    board: KanbanBoard,
    laneBounds: Map<String, Rect>,
    cardBounds: Map<String, Rect>,
): KanbanDropTarget? = resolveDropTarget(pointer, dragCardId, board.lanes, laneBounds, cardBounds)
