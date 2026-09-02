package app.andy.ui.logcat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.DataTableHeader
import app.andy.ui.components.DraggableScrollbar
import app.andy.ui.components.HeaderCell
import app.andy.ui.components.HeaderTrailingLabel
import app.andy.ui.components.LogLevelBadge
import app.andy.ui.components.logLevelForeground
import app.andy.model.LogLevel
import app.andy.model.LogcatEntry
import app.andy.model.LogcatTab
import app.andy.model.WorkspaceState
import app.andy.service.AndyServices
import app.andy.service.AppService
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import app.andy.ui.components.togglePrimaryButtonColors
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PackageSelector
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun LogcatScreen(
    services: AndyServices,
    serial: String?,
    state: LogcatState,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    iosMode: Boolean = false,
) {
    val logcatTab = LogcatTab.entries.firstOrNull { it.name == workspaceState.logcatTab } ?: LogcatTab.Stream

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterPill("Stream", logcatTab == LogcatTab.Stream, Rust) {
                onUpdateWorkspace { it.copy(logcatTab = LogcatTab.Stream.name) }
            }
            FilterPill("Crashes", logcatTab == LogcatTab.Crashes, Rust) {
                onUpdateWorkspace { it.copy(logcatTab = LogcatTab.Crashes.name) }
            }
        }

        when (logcatTab) {
            LogcatTab.Stream -> {
                LogcatPanel(
                    logcat = services.logcat,
                    appsService = services.apps,
                    serial = serial,
                    selectedPackage = selectedPackage,
                    onSelectedPackageChange = onSelectedPackageChange,
                    modifier = Modifier.fillMaxSize().weight(1f),
                    compact = false,
                    state = state,
                    iosMode = iosMode,
                )
            }
            LogcatTab.Crashes -> {
                CrashesPanel(
                    services = services,
                    serial = serial,
                    modifier = Modifier.fillMaxSize().weight(1f),
                )
            }
        }
    }
}

@Composable
fun LogcatPanel(
    logcat: LogcatService,
    appsService: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean,
    embedded: Boolean = false,
    state: LogcatState = remember { LogcatState() },
    iosMode: Boolean = false,
) {
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun restart() {
        val currentLevels = state.levels.filterValues { it }.keys.toSet()
        val parametersChanged = serial != state.lastSerial ||
                state.search != state.lastSearch ||
                currentLevels != state.lastLevels ||
                state.live != state.lastLive ||
                selectedPackage != state.lastPackage

        if (parametersChanged) {
            streamJob?.cancel()
            streamJob = null
            val filtersChanged = serial != state.lastSerial ||
                    state.search != state.lastSearch ||
                    currentLevels != state.lastLevels ||
                    selectedPackage != state.lastPackage
            if (filtersChanged) {
                state.clearEntries()
            }
            state.lastSerial = serial
            state.lastSearch = state.search
            state.lastLevels = currentLevels
            state.lastLive = state.live
            state.lastPackage = selectedPackage

            if (serial == null || !state.live) return
            streamJob = scope.launch {
                logcat.stream(serial, LogcatFilter(state.search, currentLevels, packageName = selectedPackage)).collect { batch ->
                    state.appendBatch(batch)
                }
            }
        } else {
            if (streamJob == null && serial != null && state.live) {
                state.lastSerial = serial
                state.lastSearch = state.search
                state.lastLevels = currentLevels
                state.lastLive = state.live
                state.lastPackage = selectedPackage
                streamJob = scope.launch {
                    logcat.stream(serial, LogcatFilter(state.search, currentLevels, packageName = selectedPackage)).collect { batch ->
                        state.appendBatch(batch)
                    }
                }
            }
        }
    }
    LaunchedEffect(serial, state.live, state.search, state.levels.values.toList(), selectedPackage) { restart() }
    DisposableEffect(Unit) {
        onDispose {
            streamJob?.cancel()
            streamJob = null
        }
    }

    val panelContent: @Composable () -> Unit = {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val toolbarWidth = maxWidth
            var overflowExpanded by remember { mutableStateOf(false) }

            val showLevelsOnToolbar = toolbarWidth >= 720.dp
            val showActionsOnToolbar = toolbarWidth >= 520.dp
            val showPackageOnToolbar = toolbarWidth >= 380.dp
            val showOverflowButton = !showLevelsOnToolbar || !showActionsOnToolbar || !showPackageOnToolbar

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!compact && toolbarWidth >= 800.dp) {
                    Text("Logcat", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
                TextField(
                    value = state.search,
                    onValueChange = { state.search = it },
                    placeholder = {
                        Text(
                            if (iosMode) "subsystem:category or message" else "filter or package:com.example",
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = AndyLayout.FieldHeight),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                    colors = fieldColors()
                )

                if (showPackageOnToolbar) {
                    PackageSelector(
                        appsService = appsService,
                        serial = serial,
                        selectedPackage = selectedPackage,
                        onSelectedPackageChange = onSelectedPackageChange,
                        autoSelectForeground = true,
                        modifier = if (compact) Modifier.widthIn(max = 180.dp) else Modifier.widthIn(max = 300.dp)
                    )
                }

                if (showLevelsOnToolbar) {
                    LogLevel.entries.filter { it != LogLevel.Silent }.forEach { level ->
                        FilterPill(
                            text = level.name.take(1),
                            selected = state.levels[level] == true,
                            color = levelColor(level)
                        ) {
                            state.levels[level] = !(state.levels[level] ?: false)
                        }
                    }
                }

                if (showActionsOnToolbar) {
                    Button(
                        onClick = { state.live = !state.live },
                        colors = togglePrimaryButtonColors(state.live),
                    ) {
                        Text(if (state.live) "Live" else "Paused")
                    }
                    OutlinedButton(onClick = {
                        state.clearEntries()
                        if (serial != null) {
                            scope.launch {
                                logcat.clear(serial)
                            }
                        }
                    }) {
                        Text("Clear")
                    }
                }

                if (showOverflowButton) {
                    Box {
                        OutlinedButton(
                            onClick = { overflowExpanded = true },
                            shape = RoundedCornerShape(AndyRadius.Control),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("More ▼", fontSize = 12.sp)
                        }

                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                            containerColor = AndyColors.Neutral750,
                            modifier = Modifier.width(if (!showPackageOnToolbar) 260.dp else 220.dp)
                        ) {
                            if (!showPackageOnToolbar) {
                                Text("Package Filter", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                PackageSelector(
                                    appsService = appsService,
                                    serial = serial,
                                    selectedPackage = selectedPackage,
                                    onSelectedPackageChange = onSelectedPackageChange,
                                    autoSelectForeground = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                AndyHorizontalDivider()
                                Spacer(Modifier.height(4.dp))
                            }

                            if (!showLevelsOnToolbar) {
                                Text("Log Levels", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    LogLevel.entries.filter { it != LogLevel.Silent }.forEach { level ->
                                        FilterPill(
                                            text = level.name.take(1),
                                            selected = state.levels[level] == true,
                                            color = levelColor(level)
                                        ) {
                                            state.levels[level] = !(state.levels[level] ?: false)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                AndyHorizontalDivider()
                                Spacer(Modifier.height(4.dp))
                            }

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.size(8.dp).background(if (state.live) Green else Rust, RoundedCornerShape(4.dp)))
                                        Text(if (state.live) "Pause Stream" else "Resume Stream", color = TextPrimary, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    state.live = !state.live
                                    overflowExpanded = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Clear Logs", color = TextPrimary, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    state.clearEntries()
                                    if (serial != null) {
                                        scope.launch {
                                            logcat.clear(serial)
                                        }
                                    }
                                    overflowExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
        LogcatEntryList(state.entries, compact, Modifier.fillMaxSize(), iosMode = iosMode)
    }
    if (embedded) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            panelContent()
        }
    } else {
        PanelCard(modifier) {
            panelContent()
        }
    }
}

@Composable
internal fun LogcatEntryList(entries: List<StampedLogcatEntry>, compact: Boolean, modifier: Modifier = Modifier, iosMode: Boolean = false) {
    val listState = rememberLazyListState()
    var followLive by remember { mutableStateOf(true) }
    var timeWidth by remember { mutableStateOf(152f) }
    var levelWidth by remember { mutableStateOf(32f) }
    var tagWidth by remember { mutableStateOf(180f) }
    val scope = rememberCoroutineScope()
    val latestEntryCount = remember { mutableIntStateOf(entries.size) }
    SideEffect { latestEntryCount.intValue = entries.size }

    fun jumpToLatest() {
        followLive = true
        scope.launch { listState.scrollLogcatToLiveEdge() }
    }

    // Reverse layout keeps the live edge pinned while followLive is on. If we drift (e.g. layout
    // catch-up), nudge back once without restarting on every batch — unlike LaunchedEffect(size).
    LaunchedEffect(followLive, listState) {
        if (!followLive) return@LaunchedEffect
        snapshotFlow {
            latestEntryCount.intValue to listState.firstVisibleItemIndex
        }.distinctUntilChanged().collect { (_, index) ->
            if (index != 0) {
                listState.scrollToItem(0)
            }
        }
    }

    // Re-arm when the user manually scrolls back to the live edge.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { (index, offset) ->
            if (logcatIsAtLiveEdge(index, offset)) {
                followLive = true
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!compact) {
            ResizableLogcatHeader(
                timeWidth = timeWidth,
                levelWidth = levelWidth,
                tagWidth = tagWidth,
                onTimeWidth = { timeWidth = it.coerceIn(70f, 240f) },
                onLevelWidth = { levelWidth = it.coerceIn(24f, 90f) },
                onTagWidth = { tagWidth = it.coerceIn(80f, 420f) },
                iosMode = iosMode,
            )
        }
        Box(Modifier.fillMaxSize()) {
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 8.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.type == PointerEventType.Scroll) {
                                        followLive = false
                                    }
                                }
                            }
                        },
                ) {
                    items(
                        count = entries.size,
                        key = { index -> entries[entries.lastIndex - index].id },
                    ) { index ->
                        val entry = entries[entries.lastIndex - index].entry
                        if (compact) {
                            Row(verticalAlignment = Alignment.Top) {
                                DisableSelection {
                                    Text(
                                        "${entry.time} ${entry.pid ?: "-"} ${entry.level.name.take(1)}/${entry.tag}: ",
                                        color = levelColor(entry.level),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    )
                                }
                                Text(
                                    entry.message,
                                    color = levelColor(entry.level),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                )
                            }
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 22.dp)
                                    .padding(vertical = 1.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                DisableSelection {
                                    MonoCell(entry.time, timeWidth.dp, TextSecondary, compact = true)
                                    Box(Modifier.width(levelWidth.dp), contentAlignment = Alignment.CenterStart) {
                                        LogLevelBadge(entry.level)
                                    }
                                    MonoCell(entry.tag, tagWidth.dp, logLevelForeground(entry.level), compact = true)
                                }
                                Text(
                                    entry.message,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            DraggableScrollbar(
                listState = listState,
                reverseLayout = true,
                onScroll = { followLive = false },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = !followLive && entries.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            ) {
                Text(
                    "↓ follow live",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AndyRadius.Pill))
                        .background(PanelSoft.copy(alpha = 0.92f))
                        .clickable(onClick = ::jumpToLatest)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Live edge is index zero in the reverse log list. */
internal fun logcatIsAtLiveEdge(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= 8

/** Jump once to the live edge; streaming tailing relies on reverseLayout after this. */
internal suspend fun androidx.compose.foundation.lazy.LazyListState.scrollLogcatToLiveEdge() {
    scrollToItem(0)
    withFrameMillis { }
    scrollToItem(0)
}

@Composable
internal fun ResizableLogcatHeader(
    timeWidth: Float,
    levelWidth: Float,
    tagWidth: Float,
    onTimeWidth: (Float) -> Unit,
    onLevelWidth: (Float) -> Unit,
    onTagWidth: (Float) -> Unit,
    iosMode: Boolean = false,
) {
    DataTableHeader {
        HeaderCell("line", timeWidth.dp, onWidthChange = onTimeWidth)
        HeaderCell("lv", levelWidth.dp, showLeadingDivider = true, onWidthChange = onLevelWidth)
        HeaderCell(if (iosMode) "subsystem:category" else "tag", tagWidth.dp, showLeadingDivider = true, onWidthChange = onTagWidth)
        HeaderTrailingLabel(
            "msg",
            modifier = Modifier.weight(1f).padding(end = 4.dp),
            showLeadingDivider = true,
        )
    }
}

@Composable
internal fun levelColor(level: LogLevel): Color = logLevelForeground(level)
