package app.andy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.AndyStroke
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Astryx TabList size — element heights match Button and TextInput. */
enum class TabListSize(val height: Dp) {
    Sm(AndyLayout.ControlHeightSm),
    Md(AndyLayout.ControlHeightMd),
    Lg(AndyLayout.ControlHeightLg),
}

/** Astryx TabList layout — hug content width or stretch tabs to fill. */
enum class TabListLayout {
    Hug,
    Fill,
}

private val LocalTabListSize = compositionLocalOf { TabListSize.Md }
private val LocalTabListLayout = compositionLocalOf { TabListLayout.Hug }

/**
 * Coordinates for the sliding folder-tab indicator. Selected [TabBarItem]s report their
 * layout node; [TabBarRow] resolves bounds in the strip's local space and animates the wrap.
 */
private class TabBarIndicatorHost {
    var rootCoordinates: LayoutCoordinates? = null
    var selectedCoordinates: LayoutCoordinates? = null
    var selectedBounds by mutableStateOf<Rect?>(null)

    fun onRootPositioned(coordinates: LayoutCoordinates) {
        rootCoordinates = coordinates
        syncBounds()
    }

    fun onSelectedPositioned(coordinates: LayoutCoordinates?) {
        selectedCoordinates = coordinates
        syncBounds()
    }

    private fun syncBounds() {
        val root = rootCoordinates
        val selected = selectedCoordinates
        if (root == null || selected == null || !root.isAttached || !selected.isAttached) return
        val topLeft = root.localPositionOf(selected, Offset.Zero)
        val next = Rect(
            left = topLeft.x,
            top = topLeft.y,
            right = topLeft.x + selected.size.width,
            bottom = topLeft.y + selected.size.height,
        )
        if (selectedBounds != next) selectedBounds = next
    }
}

private val LocalTabBarIndicatorHost = compositionLocalOf<TabBarIndicatorHost?> { null }

/**
 * Folder-tab bar: selected item sits in a three-sided border wrap that meets the baseline,
 * and the wrap slides/resizes as selection changes. Prefer this over [FilterPill] or
 * [SegmentedControl] when switching between distinct content panes.
 *
 * [trailing] is placed on the trailing edge of the tab row (e.g. action buttons).
 */
@Composable
fun TabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: TabListSize = TabListSize.Md,
    layout: TabListLayout = TabListLayout.Hug,
    hasDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    TabBarRow(
        modifier = modifier,
        scrollTabs = layout == TabListLayout.Hug,
        size = size,
        layout = layout,
        hasDivider = hasDivider,
        trailing = trailing,
    ) {
        tabs.forEachIndexed { index, label ->
            TabBarItem(
                label = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

/**
 * [TabBar] chrome with caller-supplied items, for tab strips that need more than a
 * label per tab (icons, close affordances). Emit [TabBarItem]s from [tabs].
 *
 * Set [scrollTabs] when tabs may overflow the row (also used by [TabBar]).
 */
@Composable
fun TabBarRow(
    modifier: Modifier = Modifier,
    scrollTabs: Boolean = false,
    size: TabListSize = TabListSize.Md,
    layout: TabListLayout = TabListLayout.Hug,
    hasDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    tabs: @Composable RowScope.() -> Unit,
) {
    val tabHeight = size.height
    val host = remember { TabBarIndicatorHost() }
    val leftAnim = remember { Animatable(0f) }
    val widthAnim = remember { Animatable(0f) }
    var indicatorReady by remember { mutableStateOf(false) }
    val selectedBounds = host.selectedBounds

    LaunchedEffect(selectedBounds) {
        val bounds = selectedBounds ?: return@LaunchedEffect
        if (!indicatorReady) {
            leftAnim.snapTo(bounds.left)
            widthAnim.snapTo(bounds.width)
            indicatorReady = true
        } else {
            // Animate position and width together so differently sized tabs morph while sliding.
            kotlinx.coroutines.coroutineScope {
                launch { leftAnim.animateTo(bounds.left, AndyMotion.standardTween(AndyMotion.SpatialMs)) }
                launch { widthAnim.animateTo(bounds.width, AndyMotion.standardTween(AndyMotion.SpatialMs)) }
            }
        }
    }

    CompositionLocalProvider(
        LocalTabListSize provides size,
        LocalTabListLayout provides layout,
        LocalTabBarIndicatorHost provides host,
    ) {
        Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Divider must live outside horizontalScroll: fillMaxWidth() inside an
            // unbounded scroll measure collapses to zero width, which is why the
            // baseline vanished under (and between) tabs.
            Box(
                Modifier
                    .then(
                        when {
                            trailing != null -> Modifier.weight(1f)
                            scrollTabs || layout == TabListLayout.Fill -> Modifier.fillMaxWidth()
                            else -> Modifier
                        },
                    )
                    .height(tabHeight),
            ) {
                // Baseline behind the strip so the selected-tab fill can mask the segment
                // under the wrap and keep the tab open to the content below.
                if (hasDivider) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(AndyStroke.Hairline)
                            .background(PaneDividerTint),
                    )
                }
                Box(
                    Modifier
                        .then(
                            when {
                                scrollTabs -> Modifier.horizontalScroll(rememberScrollState())
                                layout == TabListLayout.Fill -> Modifier.fillMaxWidth()
                                else -> Modifier
                            },
                        )
                        .height(tabHeight)
                        .onGloballyPositioned(host::onRootPositioned),
                ) {
                    if (indicatorReady && selectedBounds != null) {
                        FolderTabIndicator(
                            leftPx = leftAnim.value,
                            widthPx = widthAnim.value,
                            height = tabHeight,
                            maskBaseline = hasDivider,
                        )
                    }
                    Row(
                        Modifier
                            .then(if (layout == TabListLayout.Fill) Modifier.fillMaxWidth() else Modifier)
                            .height(tabHeight),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs()
                    }
                }
            }
            if (trailing != null) {
                Box(
                    Modifier
                        .padding(start = AndySpace.Space2)
                        .height(tabHeight),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    trailing()
                }
            }
        }
    }
}

@Composable
fun <T> TabBar(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    size: TabListSize = TabListSize.Md,
    layout: TabListLayout = TabListLayout.Hug,
    hasDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    TabBar(
        tabs = tabs.map(label),
        selectedIndex = tabs.indexOf(selected).coerceAtLeast(0),
        onSelect = { index -> onSelect(tabs[index]) },
        modifier = modifier,
        size = size,
        layout = layout,
        hasDivider = hasDivider,
        trailing = trailing,
    )
}

/**
 * Three-sided folder-tab wrap. When [maskBaseline] is set, a content-colored fill covers the
 * baseline under the tab so the wrap opens into the pane below; stroke uses the same tint as
 * pane dividers ([PaneDividerTint]).
 */
@Composable
private fun FolderTabIndicator(
    leftPx: Float,
    widthPx: Float,
    height: Dp,
    maskBaseline: Boolean,
) {
    if (widthPx <= 0f) return
    val density = LocalDensity.current
    val strokePx = with(density) { AndyStroke.Hairline.toPx() }
    val cornerPx = with(density) { AndyRadius.Control.toPx() }
    val heightPx = with(density) { height.toPx() }
    val borderColor = PaneDividerTint
    val fillColor = AndyColors.ContentBg

    Canvas(
        Modifier
            .offset { IntOffset(leftPx.roundToInt(), 0) }
            .size(
                width = with(density) { widthPx.toDp() },
                height = with(density) { heightPx.toDp() },
            ),
    ) {
        val w = size.width
        if (maskBaseline) {
            val fillPath = Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, cornerPx)
                quadraticBezierTo(0f, 0f, cornerPx, 0f)
                lineTo(w - cornerPx, 0f)
                quadraticBezierTo(w, 0f, w, cornerPx)
                lineTo(w, size.height)
                close()
            }
            drawPath(fillPath, fillColor)
        }

        val inset = strokePx / 2f
        // Open bottom: verticals stop on the baseline so the masked gap reads as the tab floor.
        val strokePath = Path().apply {
            moveTo(inset, size.height)
            lineTo(inset, cornerPx.coerceAtLeast(inset))
            quadraticBezierTo(inset, inset, cornerPx.coerceAtLeast(inset), inset)
            lineTo((w - cornerPx).coerceAtMost(w - inset), inset)
            quadraticBezierTo(w - inset, inset, w - inset, cornerPx.coerceAtLeast(inset))
            lineTo(w - inset, size.height)
        }
        drawPath(
            strokePath,
            borderColor,
            style = Stroke(width = strokePx, cap = StrokeCap.Butt),
        )
    }
}

/**
 * One tab in a [TabBar] / [TabBarRow]. [trailing] receives the item's hover state so
 * callers can reveal affordances such as a close button only while hovered.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun RowScope.TabBarItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable (hovered: Boolean) -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
) {
    val size = LocalTabListSize.current
    val layout = LocalTabListLayout.current
    val host = LocalTabBarIndicatorHost.current
    val tabHeight = size.height
    val accent = indicatorColor ?: AndyColors.Blue
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var editing by remember { mutableStateOf(false) }
    var draft by remember(label) { mutableStateOf(TextFieldValue(label)) }
    var editorHadFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val hoverOverlay = if (AndyColors.isLight) {
        Color.Black.copy(alpha = 0.04f)
    } else {
        Color.White.copy(alpha = 0.06f)
    }
    val textColor by animateColorAsState(
        targetValue = if (selected) accent else TextSecondary,
        animationSpec = AndyMotion.standardTween(),
        label = "tabText",
    )
    fun finishEditing() {
        if (!editing) return
        val updated = draft.text.trim()
        if (updated.isNotEmpty() && updated != label) onRename?.invoke(updated)
        draft = TextFieldValue(updated.ifEmpty { label })
        editing = false
        editorHadFocus = false
    }
    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }
    // Keep last layout node so selection changes re-sync the indicator even when
    // Compose skips a relayout (same intrinsic size via the semibold sizer).
    var layoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    SideEffect {
        if (selected) layoutCoordinates?.let { host?.onSelectedPositioned(it) }
    }
    Box(
        modifier
            .then(if (layout == TabListLayout.Fill) Modifier.weight(1f) else Modifier)
            .height(tabHeight)
            .hoverable(interactionSource)
            .combinedClickable(
                enabled = !editing,
                onClick = onClick,
                onLongClick = onRename?.let {
                    {
                        draft = TextFieldValue(text = label, selection = TextRange(label.length))
                        editing = true
                    }
                },
            )
            .onGloballyPositioned { coordinates ->
                layoutCoordinates = coordinates
                if (selected) host?.onSelectedPositioned(coordinates)
            },
    ) {
        if (hovered && !editing) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(hoverOverlay, RoundedCornerShape(AndyRadius.Control)),
            )
        }
        // Width comes from label intrinsic size — avoid matchParentSize on this Row or
        // horizontalScroll tab strips measure every item at zero width.
        Row(
            Modifier
                .then(if (layout == TabListLayout.Fill) Modifier.fillMaxWidth() else Modifier)
                .height(tabHeight)
                .padding(horizontal = AndySpace.Space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (layout == TabListLayout.Fill) {
                Arrangement.Center
            } else {
                Arrangement.spacedBy(6.dp)
            },
        ) {
            leading?.invoke()
            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .widthIn(min = 48.dp, max = 180.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                editorHadFocus = true
                            } else if (editorHadFocus) {
                                finishEditing()
                            }
                        },
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                    cursorBrush = SolidColor(accent),
                )
            } else {
                Box {
                    // Semibold sizer keeps tab width stable when selection changes.
                    Text(
                        label,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                        color = Color.Transparent,
                    )
                    Text(
                        label,
                        color = textColor,
                        fontFamily = DisplayFont,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                }
            }
            trailing?.invoke(hovered)
        }
    }
}

/**
 * Compact segmented control for mutually exclusive options within a section
 * (e.g. stream quality presets). Not for navigation — use [TabBar] instead.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val outerShape = AndyShape.Interactive
    val segmentShape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .clip(outerShape)
            .background(tokens.neutralFill, outerShape)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Text(
                label,
                color = if (selected) TextPrimary else TextSecondary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .clip(segmentShape)
                    .background(
                        if (selected) AndyColors.SurfaceRaised else Color.Transparent,
                        segmentShape,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space1),
            )
        }
    }
}
