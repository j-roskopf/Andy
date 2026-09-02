package app.andy.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.composer_attach
import app.andy.loadImageBitmap
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import app.andy.ui.theme.andyTokens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

/** Removable chip shown in the Astryx ChatComposerDrawer (files, skills, context). */
data class ChatComposerDrawerItem(
    val id: String,
    val label: String,
    val onRemove: () -> Unit,
    /** When set, the drawer renders a thumbnail preview instead of a text-only chip. */
    val imagePath: String? = null,
)

/**
 * Full-featured Astryx ChatComposer shell — drawer sheet, top action row, input, bottom selectors.
 *
 * Layout mirrors [astryx ChatComposer](https://astryx.atmeta.com/components/ChatComposer):
 * attachment drawer above, @ / attach + context progress on top, model + settings below the input.
 *
 * [contextBar] renders in a fixed strip above the input frame (no overlap offset) so the text
 * field stays anchored when switching between new and existing chats. The attachment drawer
 * only appears for [drawerItems].
 */
@Composable
fun ChatComposerLayout(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    drawerItems: List<ChatComposerDrawerItem> = emptyList(),
    /** Always-on strip above the input frame — e.g. new-chat git / temporary controls. */
    contextBar: (@Composable () -> Unit)? = null,
    contextFraction: Float? = null,
    /** Hover label for the context-window gauge; omitted when blank or null. */
    contextTooltip: String? = null,
    onMentionClick: (() -> Unit)? = null,
    onAttachClick: (() -> Unit)? = null,
    attachEnabled: Boolean = true,
    mentionEnabled: Boolean = true,
    topBarTrailing: (@Composable RowScope.() -> Unit)? = null,
    bottomBarLeading: @Composable RowScope.() -> Unit,
    bottomBarTrailing: @Composable RowScope.() -> Unit,
    wrapBottomControls: Boolean = false,
    input: @Composable () -> Unit,
    belowInput: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val hasAttachmentDrawer = drawerItems.isNotEmpty()
    val hasContextBar = contextBar != null
    val hasUpperArea = hasAttachmentDrawer || hasContextBar
    Column(modifier) {
        if (hasUpperArea) {
            ChatComposerHeaderArea(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .align(Alignment.CenterHorizontally),
            ) {
                if (contextBar != null) {
                    contextBar()
                }
                if (hasAttachmentDrawer) {
                    ChatComposerAttachmentItems(
                        items = drawerItems,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        ChatComposerFrame(
            modifier = Modifier.fillMaxWidth(),
            highlighted = highlighted,
            contentPadding = PaddingValues(
                start = AndySpace.Space3,
                end = AndySpace.Space3,
                top = AndySpace.Space2,
                bottom = AndySpace.Space3,
            ),
        ) {
            ChatComposerTopBar(
                onMentionClick = onMentionClick,
                onAttachClick = onAttachClick,
                attachEnabled = attachEnabled,
                mentionEnabled = mentionEnabled,
                contextFraction = contextFraction,
                contextTooltip = contextTooltip,
                trailing = topBarTrailing,
            )
            input()
            belowInput?.invoke(this)
            ChatComposerBottomBar(
                leading = bottomBarLeading,
                trailing = bottomBarTrailing,
                wrapControls = wrapBottomControls,
            )
            footer?.invoke(this)
        }
    }
}

/** Fixed header area above the input frame for context (git/temporary) and attachments (images/skills). */
@Composable
private fun ChatComposerHeaderArea(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = AndyRadius.Sheet,
        topEnd = AndyRadius.Sheet,
        bottomStart = HeaderAreaBottomRadius,
        bottomEnd = HeaderAreaBottomRadius,
    )
    Column(
        modifier = modifier
            .shadow(elevation = 1.dp, shape = shape, clip = false)
            // Rail/well tone — distinct from SurfacePopover (composer / chat chrome) in every mode.
            .background(AndyColors.SidebarBg, shape)
            .padding(
                start = AndySpace.Space3,
                end = AndySpace.Space3,
                top = AndySpace.Space2,
                bottom = AndySpace.Space2,
            ),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        content = content,
    )
}

/** Soft bottom corners on the strip above the chat frame — just enough to read as rounded. */
private val HeaderAreaBottomRadius: Dp = 4.dp

/** Collapsible flow row for referenced files, skills, and images in the composer header area. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatComposerAttachmentItems(
    items: List<ChatComposerDrawerItem>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        items.forEach { item ->
            if (item.imagePath != null) {
                ChatComposerDrawerImageChip(
                    label = item.label,
                    imagePath = item.imagePath,
                    onRemove = item.onRemove,
                )
            } else {
                ChatComposerDrawerChip(
                    label = item.label,
                    onRemove = item.onRemove,
                )
            }
        }
    }
}

@Composable
private fun ChatComposerDrawerImageChip(
    label: String,
    imagePath: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, imagePath) {
        value = withContext(Dispatchers.Default) {
            runCatching { loadImageBitmap(imagePath) }.getOrNull()
        }
    }
    var previewOpen by remember(imagePath) { mutableStateOf(false) }
    val image = bitmap
    val chipShape = RoundedCornerShape(AndyRadius.Interactive)
    Row(
        modifier
            .clip(chipShape)
            .background(AndyColors.Neutral800, chipShape)
            .border(1.dp, PaneDividerTint, chipShape)
            .padding(horizontal = AndySpace.Space1, vertical = AndySpace.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AndyColors.Neutral900.copy(alpha = 0.65f))
                .then(
                    if (image != null) {
                        Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { previewOpen = true }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = label,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    label.take(1).uppercase(),
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            label,
            color = TextPrimary.copy(alpha = 0.88f),
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 160.dp)
                .then(
                    if (image != null) {
                        Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { previewOpen = true }
                    } else {
                        Modifier
                    },
                ),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(AndyLayout.ControlHeightSm),
            contentDescription = "Remove $label",
        ) {
            ComposerCloseGlyph(color = TextSecondary.copy(alpha = 0.75f), modifier = Modifier.size(10.dp))
        }
    }
    if (previewOpen && image != null) {
        ChatComposerImagePreviewDialog(
            bitmap = image,
            fileName = label,
            onDismiss = { previewOpen = false },
        )
    }
}

@Composable
private fun ChatComposerImagePreviewDialog(
    bitmap: ImageBitmap,
    fileName: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .widthIn(max = 1100.dp)
                .heightIn(max = 860.dp)
                .background(
                    AndyColors.Neutral900.copy(alpha = AndyOverlay.Strong),
                    RoundedCornerShape(AndyRadius.Control),
                )
                .border(1.dp, PaneDividerTint, RoundedCornerShape(AndyRadius.Control))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                fileName.ifBlank { "image" },
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Image(
                bitmap = bitmap,
                contentDescription = fileName,
                modifier = Modifier
                    .widthIn(max = 1060.dp)
                    .heightIn(max = 780.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                "click to close",
                color = TextSecondary.copy(alpha = 0.8f),
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ChatComposerDrawerChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipShape = RoundedCornerShape(AndyRadius.Interactive)
    Row(
        modifier
            .clip(chipShape)
            .background(AndyColors.Neutral800, chipShape)
            .border(1.dp, PaneDividerTint, chipShape)
            .padding(start = AndySpace.Space2, end = AndySpace.Space1)
            .height(AndyLayout.ControlHeightSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = TextPrimary.copy(alpha = 0.88f),
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(AndyLayout.ControlHeightSm),
            contentDescription = "Remove $label",
        ) {
            ComposerCloseGlyph(color = TextSecondary.copy(alpha = 0.75f), modifier = Modifier.size(10.dp))
        }
    }
}

@Composable
private fun ChatComposerTopBar(
    onMentionClick: (() -> Unit)?,
    onAttachClick: (() -> Unit)?,
    attachEnabled: Boolean,
    mentionEnabled: Boolean,
    contextFraction: Float?,
    contextTooltip: String?,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onAttachClick != null) {
                IconButton(
                    onClick = onAttachClick,
                    enabled = attachEnabled,
                    contentDescription = "Attach file",
                ) {
                    Image(
                        painter = painterResource(Res.drawable.composer_attach),
                        contentDescription = null,
                        modifier = Modifier.size(AndyLayout.IconLg),
                        colorFilter = ColorFilter.tint(
                            if (attachEnabled) TextSecondary else AndyColors.TextDisabled,
                        ),
                    )
                }
            }
            if (onMentionClick != null) {
                ComposerMentionButton(
                    onClick = onMentionClick,
                    enabled = mentionEnabled,
                )
            }
        }
        Box(Modifier.weight(1f))
        if (contextFraction != null) {
            // Vertical padding widens the hover target; the fill itself is only 6.dp tall.
            val progressModifier = Modifier
                .widthIn(min = 72.dp, max = 140.dp)
                .padding(vertical = AndySpace.Space2)
            val tooltip = contextTooltip?.takeIf { it.isNotBlank() }
            if (tooltip != null) {
                Tooltip(text = tooltip) {
                    ChatComposerContextProgress(
                        fraction = contextFraction,
                        modifier = progressModifier,
                    )
                }
            } else {
                ChatComposerContextProgress(
                    fraction = contextFraction,
                    modifier = progressModifier,
                )
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
private fun ComposerMentionButton(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val contentColor = if (enabled) TextSecondary else AndyColors.TextDisabled
    Box(
        Modifier
            .size(AndyLayout.ControlHeightMd)
            .clip(AndyShape.Interactive)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "@",
            color = contentColor,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 20.sp,
        )
    }
}

/** Compact horizontal context-window gauge for the composer top bar. */
@Composable
fun ChatComposerContextProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val clamped = fraction.coerceIn(0f, 1f)
    val fillColor = when {
        clamped >= 0.9f -> Red
        clamped >= 0.75f -> Yellow
        else -> tokens.accent
    }
    val trackColor = if (AndyColors.isLight) {
        Color(0xFF053659).copy(alpha = 0.06f)
    } else {
        Color(0xFF111112).copy(alpha = 0.55f)
    }
    val shape = RoundedCornerShape(AndyRadius.Pill)
    Box(
        modifier
            .height(6.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(clamped)
                .clip(shape)
                .background(fillColor),
        )
    }
}

@Composable
private fun ChatComposerBottomBar(
    leading: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit,
    wrapControls: Boolean,
) {
    if (wrapControls) {
        Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                leading()
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing()
            }
        }
    } else {
        ComposerToolbarRow(leading = leading, trailing = trailing)
    }
}

/** Model selector chip — sparkle icon + label + chevron (Astryx full-featured composer). */
@Composable
fun ComposerModelChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ComposerChip(
        text = text,
        selected = true,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        showBackground = false,
        leadingContent = {
            ComposerSparkleGlyph(
                color = if (enabled) TextSecondary else AndyColors.TextDisabled,
                modifier = Modifier.size(14.dp),
            )
        },
    )
}

/** Permissions / access selector chip. */
@Composable
fun ComposerPermissionsChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ComposerChip(
        text = text,
        selected = true,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        showBackground = false,
    )
}

/** Reasoning effort selector chip. */
@Composable
fun ComposerEffortChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ComposerChip(
        text = text,
        selected = true,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        showBackground = false,
    )
}

/** Provider selector chip — optional leading icon + label + chevron. */
@Composable
fun ComposerProviderChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    ComposerChip(
        text = text,
        selected = true,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        showBackground = false,
        leadingContent = leadingContent,
    )
}

@Composable
fun ComposerSparkleGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = size.minDimension * 0.42f
        val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round)
        drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke.width, StrokeCap.Round)
        val diag = arm * 0.72f
        drawLine(color, Offset(cx - diag, cy - diag), Offset(cx + diag, cy + diag), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(cx + diag, cy - diag), Offset(cx - diag, cy + diag), stroke.width, StrokeCap.Round)
    }
}

@Composable
private fun ComposerCloseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pad = size.minDimension * 0.22f
        val stroke = size.minDimension * 0.12f
        drawLine(color, Offset(pad, pad), Offset(size.width - pad, size.height - pad), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - pad, pad), Offset(pad, size.height - pad), stroke, StrokeCap.Round)
    }
}

fun chatComposerDrawerItemsFromPaths(
    skillLabels: List<Pair<String, () -> Unit>>,
    imagePaths: List<String>,
    onRemoveImage: (String) -> Unit,
): List<ChatComposerDrawerItem> = buildList {
    skillLabels.forEach { (label, onRemove) ->
        add(ChatComposerDrawerItem(id = "skill:$label", label = label, onRemove = onRemove))
    }
    imagePaths.forEach { path ->
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        add(
            ChatComposerDrawerItem(
                id = "image:$path",
                label = name,
                imagePath = path,
                onRemove = { onRemoveImage(path) },
            ),
        )
    }
}


/**
 * Chat input shell — Astryx ChatComposer: `--radius-chat`, popover surface, low elevation.
 */
@Composable
fun ChatComposerFrame(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(AndySpace.Space3),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = AndyShape.Chat
    val tokens = andyTokens()
    Box(
        modifier
            .shadow(
                elevation = if (highlighted) 4.dp else 2.dp,
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .background(AndyColors.SurfacePopover, shape),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            content = content,
        )
        if (highlighted) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(2.dp, tokens.accent, shape),
            )
        }
    }
}

/** Quiet ghost chip for the composer toolbar (model, effort, access). */
@Composable
fun ComposerChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    showChevron: Boolean = true,
    showBackground: Boolean = false,
) {
    val tokens = andyTokens()
    val contentColor = when {
        !enabled -> AndyColors.TextDisabled
        selected -> TextSecondary
        else -> TextSecondary.copy(alpha = 0.70f)
    }
    val container = when {
        !showBackground -> Color.Transparent
        selected -> tokens.palette.surfaceHover
        else -> Color.Transparent
    }
    val chipRadius = maxOf(AndyRadius.Interactive.value, AndyRadius.Chat.value - AndySpace.Space3.value).dp
    Row(
        modifier
            .height(AndyLayout.ControlHeightSm)
            .clip(RoundedCornerShape(chipRadius))
            .background(container, RoundedCornerShape(chipRadius))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leadingContent?.invoke()
        Text(
            text,
            color = contentColor,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showChevron) {
            Box(
                Modifier.size(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "⌄",
                    color = contentColor.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    modifier = Modifier.offset(y = (-1).dp),
                )
            }
        }
    }
}

@Composable
fun ComposerToolbarRow(
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = AndySpace.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            content = leading,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
            content = trailing,
        )
    }
}

@Composable
fun ComposerPlaceholderHint(
    text: String,
    highlighted: Boolean = false,
    focusHint: String? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            color = if (highlighted) tokens.accent else AndyColors.TextDisabled,
            fontFamily = DisplayFont,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f).padding(end = AndySpace.Space3),
        )
        if (focusHint != null) {
            Text(
                focusHint,
                color = AndyColors.TextDisabled,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
fun ComposerStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val variant = when (color) {
        tokens.success, AndyColors.Green -> StatusDotVariant.Success
        tokens.warning, AndyColors.Warning -> StatusDotVariant.Warning
        tokens.error, AndyColors.Error -> StatusDotVariant.Error
        else -> StatusDotVariant.Info
    }
    StatusDot(modifier = modifier, variant = variant)
}
