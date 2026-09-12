package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** A searchable command-palette item. */
data class CommandPaletteItem(
    val id: String,
    val label: String,
    val group: String,
    val supporting: String? = null,
    val keywords: List<String> = emptyList(),
)

/** Chat ids already covered by metadata results — skip duplicate transcript hits. */
fun commandPaletteExcludedChatIds(syncItems: List<CommandPaletteItem>): Set<String> =
    syncItems.mapNotNull { item ->
        item.id.removePrefix("chat:").takeIf { item.id.startsWith("chat:") }
    }.toSet()

/**
 * Astryx CommandPalette — modal search with grouped results and keyboard footer.
 *
 * Visual markers: PowerSearch field chrome, popover surface, item overlay hover/selected,
 * footer `↑↓ Navigate · ↵ Select · Esc Close`.
 *
 * When [asyncSearch] is set, metadata [items] filter instantly and transcript hits stream
 * into the list after a short debounce, with a bottom spinner while the Flow is active.
 */
@Composable
fun CommandPalette(
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    items: List<CommandPaletteItem>,
    onSelect: (CommandPaletteItem) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Type to search",
    title: String? = null,
    asyncSearch: ((query: String, excludeChatIds: Set<String>) -> Flow<CommandPaletteItem>)? = null,
) {
    if (!isOpen) return
    SuppressHeavyweightSurfacesWhileOpen()
    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    var asyncItems by remember { mutableStateOf<List<CommandPaletteItem>>(emptyList()) }
    var asyncSearching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val filtered = remember(items, query) {
        filterCommandPaletteItems(items, query)
    }
    val excludeChatIds = remember(filtered) { commandPaletteExcludedChatIds(filtered) }
    val flat = remember(filtered, asyncItems) { filtered + asyncItems }
    // Parent screens (Actions) recompose often while a chat streams; never key the search
    // effect on the lambda identity or each recomposition cancels before hits arrive.
    val asyncSearchUpdated by rememberUpdatedState(asyncSearch)
    LaunchedEffect(flat) {
        highlighted = highlighted.coerceIn(0, (flat.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(isOpen) {
        if (isOpen) {
            query = ""
            highlighted = 0
            asyncItems = emptyList()
            asyncSearching = false
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(query, excludeChatIds) {
        val search = asyncSearchUpdated
        val trimmed = query.trim()
        if (search == null || trimmed.length < 2) {
            asyncItems = emptyList()
            asyncSearching = false
            return@LaunchedEffect
        }
        asyncItems = emptyList()
        asyncSearching = true
        try {
            delay(150)
            search(trimmed, excludeChatIds).collect { hit ->
                val chatId = hit.id.removePrefix("chat:")
                if (hit.id.startsWith("chat:") && chatId in excludeChatIds) return@collect
                if (asyncItems.none { it.id == hit.id }) {
                    asyncItems = asyncItems + hit
                }
            }
        } finally {
            asyncSearching = false
        }
    }

    fun selectIndex(index: Int) {
        flat.getOrNull(index)?.let {
            onSelect(it)
            onOpenChange(false)
        }
    }

    fun scrollToHighlighted() {
        if (flat.isEmpty()) return
        val index = highlighted.coerceIn(0, flat.lastIndex)
        scope.launch {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (info == null || info.offset < 0 || info.offset + info.size > listState.layoutInfo.viewportEndOffset) {
                listState.scrollToItem(index)
            }
        }
    }

    Dialog(
        onDismissRequest = { onOpenChange(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .fillMaxWidth(0.92f)
                .shadow(8.dp, AndyShape.Sheet)
                .clip(AndyShape.Sheet)
                .background(AndyColors.SurfacePopover)
                .border(1.dp, PaneDividerTint, AndyShape.Sheet)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            if (flat.isNotEmpty()) {
                                highlighted = (highlighted + 1) % flat.size
                                scrollToHighlighted()
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (flat.isNotEmpty()) {
                                highlighted = (highlighted - 1 + flat.size) % flat.size
                                scrollToHighlighted()
                            }
                            true
                        }
                        Key.Enter -> {
                            selectIndex(highlighted)
                            true
                        }
                        Key.Escape -> {
                            onOpenChange(false)
                            true
                        }
                        else -> false
                    }
                },
        ) {
            if (title != null) {
                Text(
                    title,
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        start = AndySpace.Space4,
                        end = AndySpace.Space4,
                        top = AndySpace.Space3,
                    ),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AndySpace.Space4, vertical = AndySpace.Space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                Text("⌕", color = TextSecondary.copy(alpha = 0.7f), fontSize = 18.sp)
                TextField(
                    value = query,
                    onValueChange = {
                        query = it
                        highlighted = 0
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(placeholder, color = TextSecondary.copy(alpha = 0.66f), fontFamily = DisplayFont, fontSize = 13.sp)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = TextPrimary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp),
            ) {
                if (flat.isEmpty() && !asyncSearching) {
                    Text(
                        "No results",
                        color = TextSecondary,
                        fontFamily = DisplayFont,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(AndySpace.Space4),
                    )
                } else {
                    val indexed = remember(flat) {
                        flat.mapIndexed { index, item -> index to item }
                    }
                    val groupedIndexed = remember(indexed) {
                        indexed.groupBy { it.second.group }.entries.toList()
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().padding(AndySpace.Space2),
                    ) {
                        groupedIndexed.forEach { (group, groupItems) ->
                            item(key = "group-$group") {
                                Text(
                                    group.uppercase(),
                                    color = TextSecondary,
                                    fontFamily = MonoFont,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp,
                                    modifier = Modifier.padding(
                                        horizontal = AndySpace.Space3,
                                        vertical = AndySpace.Space2,
                                    ),
                                )
                            }
                            itemsIndexed(
                                groupItems,
                                key = { _, pair -> pair.second.id },
                            ) { _, (index, item) ->
                                CommandPaletteItemRow(
                                    item = item,
                                    selected = index == highlighted,
                                    onClick = { selectIndex(index) },
                                )
                            }
                        }
                        if (asyncSearching) {
                            item(key = "async-searching") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                                ) {
                                    Spinner(spinnerSize = SpinnerSize.Sm, shade = SpinnerShade.Subtle)
                                    Text(
                                        "Searching transcripts…",
                                        color = TextSecondary,
                                        fontFamily = DisplayFont,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            CommandPaletteFooter()
        }
    }
}

/** Sync metadata filter used by [CommandPalette] — extracted for tests. */
fun filterCommandPaletteItems(items: List<CommandPaletteItem>, query: String): List<CommandPaletteItem> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return items
    return items.filter { item ->
        item.label.contains(trimmed, ignoreCase = true) ||
            item.supporting?.contains(trimmed, ignoreCase = true) == true ||
            item.group.contains(trimmed, ignoreCase = true) ||
            item.keywords.any { it.contains(trimmed, ignoreCase = true) }
    }
}

@Composable
private fun CommandPaletteItemRow(
    item: CommandPaletteItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected || hovered -> AndyColors.SurfaceHover
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AndyShape.Interactive)
            .background(background, AndyShape.Interactive)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.supporting != null) {
                Text(
                    item.supporting,
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun CommandPaletteFooter(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space4)
                .height(1.dp)
                .background(PaneDividerTint),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterHint("↑↓", "Navigate")
            FooterHint("↵", "Select")
            FooterHint("Esc", "Close")
        }
    }
}

@Composable
private fun FooterHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            modifier = Modifier
                .clip(AndyShape.Interactive)
                .background(AndyColors.SurfaceHover)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(AndySpace.Space1))
        Text(label, color = TextSecondary, fontFamily = DisplayFont, fontSize = 12.sp)
    }
}
