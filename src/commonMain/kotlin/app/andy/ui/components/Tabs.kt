package app.andy.ui.components

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Underline-style tab bar for page-level navigation. Prefer this over [FilterPill]
 * when switching between distinct content panes.
 *
 * [trailing] is placed on the trailing edge of the tab row (e.g. filter pills).
 */
@Composable
internal fun TabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    TabBarRow(modifier = modifier, trailing = trailing) {
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
 * Set [scrollTabs] when the tab set is unbounded and may overflow the row.
 */
@Composable
internal fun TabBarRow(
    modifier: Modifier = Modifier,
    scrollTabs: Boolean = false,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    tabs: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                // Weighted so the trailing content keeps its intrinsic width on the
                // trailing edge while the tabs take whatever is left.
                Modifier
                    .then(if (trailing != null) Modifier.weight(1f) else Modifier)
                    .then(if (scrollTabs) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space5),
                verticalAlignment = Alignment.Bottom,
            ) {
                tabs()
            }
            if (trailing != null) {
                Box(
                    Modifier
                        // Match TabBarItem bottom inset so trailing content cannot grow the bar.
                        .padding(bottom = AndySpace.Space2)
                        .height(28.dp)
                        .horizontalScroll(rememberScrollState()),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    trailing()
                }
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Border),
            )
        }
    }
}

@Composable
internal fun <T> TabBar(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    TabBar(
        tabs = tabs.map(label),
        selectedIndex = tabs.indexOf(selected).coerceAtLeast(0),
        onSelect = { index -> onSelect(tabs[index]) },
        modifier = modifier,
        trailing = trailing,
    )
}

/**
 * One tab in a [TabBar] / [TabBarRow]. [trailing] receives the item's hover state so
 * callers can reveal affordances such as a close button only while hovered.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TabBarItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorColor: Color = Rust,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable (hovered: Boolean) -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var editing by remember { mutableStateOf(false) }
    var draft by remember(label) { mutableStateOf(label) }
    var editorHadFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val textColor = when {
        selected -> TextPrimary
        hovered -> TextPrimary.copy(alpha = 0.82f)
        else -> TextSecondary
    }
    fun finishEditing() {
        if (!editing) return
        val updated = draft.trim()
        if (updated.isNotEmpty() && updated != label) onRename?.invoke(updated)
        draft = updated.ifEmpty { label }
        editing = false
        editorHadFocus = false
    }
    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }
    Column(
        modifier
            .clip(RoundedCornerShape(AndyRadius.Control))
            .hoverable(interactionSource)
            .combinedClickable(
                enabled = !editing,
                onClick = onClick,
                onLongClick = onRename?.let {
                    {
                        draft = label
                        editing = true
                    }
                },
            )
            .padding(bottom = AndySpace.Space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                    cursorBrush = SolidColor(indicatorColor),
                )
            } else {
                Text(
                    label,
                    color = textColor,
                    fontFamily = DisplayFont,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            trailing?.invoke(hovered)
        }
        Box(
            Modifier
                .padding(top = 6.dp)
                .height(2.dp)
                .width(if (selected) 28.dp else 0.dp)
                .background(if (selected) indicatorColor else Color.Transparent, RoundedCornerShape(AndyRadius.Pill)),
        )
    }
}

/**
 * Compact segmented control for mutually exclusive options within a section
 * (e.g. stream quality presets).
 */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = AndyShape.Interactive
    Row(
        modifier
            .clip(shape)
            .background(AndyColors.SurfaceHover, shape),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Text(
                label,
                color = if (selected) TextPrimary else TextSecondary,
                fontFamily = DisplayFont,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) AndyColors.SurfaceSelected else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
