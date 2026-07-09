package app.andy.ui.logcat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.DraggableScrollbar
import app.andy.ui.components.HeaderCell
import app.andy.model.AndroidApp
import app.andy.model.LogLevel
import app.andy.model.LogcatEntry
import app.andy.service.AppService
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
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
internal fun LogcatScreen(
    logcat: LogcatService,
    appsService: AppService,
    serial: String?,
    state: LogcatState,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit
) {
    LogcatPanel(
        logcat = logcat,
        appsService = appsService,
        serial = serial,
        selectedPackage = selectedPackage,
        onSelectedPackageChange = onSelectedPackageChange,
        modifier = Modifier.fillMaxSize(),
        compact = false,
        state = state
    )
}

@Composable
internal fun LogcatPanel(
    logcat: LogcatService,
    appsService: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean,
    state: LogcatState = remember { LogcatState() }
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
                state.entries = emptyList()
            }
            state.lastSerial = serial
            state.lastSearch = state.search
            state.lastLevels = currentLevels
            state.lastLive = state.live
            state.lastPackage = selectedPackage

            if (serial == null || !state.live) return
            streamJob = scope.launch {
                logcat.stream(serial, LogcatFilter(state.search, currentLevels, packageName = selectedPackage)).collect { batch ->
                    state.entries = (state.entries + batch).takeLast(1200)
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
                        state.entries = (state.entries + batch).takeLast(1200)
                    }
                }
            }
        }
    }
    LaunchedEffect(serial, state.live, state.search, state.levels.values.toList(), selectedPackage) { restart() }

    PanelCard(modifier) {
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
                    placeholder = { Text("filter or package:com.example", color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(54.dp),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                    colors = fieldColors()
                )

                if (showPackageOnToolbar) {
                    PackageSelector(
                        appsService = appsService,
                        serial = serial,
                        selectedPackage = selectedPackage,
                        onSelectedPackageChange = onSelectedPackageChange,
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
                        colors = ButtonDefaults.buttonColors(containerColor = if (state.live) Rust else PanelSoft)
                    ) {
                        Text(if (state.live) "Live" else "Paused")
                    }
                    OutlinedButton(onClick = {
                        state.entries = emptyList()
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            shape = RoundedCornerShape(AndyRadius.R2),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
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
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
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
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
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
                                    state.entries = emptyList()
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
        LogcatEntryList(state.entries, compact, Modifier.fillMaxSize())
    }
}

@Composable
internal fun PackageSelector(
    appsService: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var installedApps by remember(serial) { mutableStateOf<List<AndroidApp>>(emptyList()) }
    var searchAppQuery by remember { mutableStateOf("") }

    LaunchedEffect(serial, expanded) {
        if (expanded && serial != null) {
            runCatching { appsService.listApps(serial) }
                .onSuccess { apps ->
                    installedApps = apps.sortedWith(compareBy({ it.label?.lowercase() ?: "" }, { it.packageName }))
                }
        }
    }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = TextPrimary
            ),
            shape = RoundedCornerShape(AndyRadius.R2),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val selectedApp = installedApps.firstOrNull { it.packageName == selectedPackage }
            val label = selectedApp?.label ?: selectedPackage ?: "All"
            Text("Pkg: $label", color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            Text("▼", color = TextSecondary, fontSize = 10.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AndyColors.Neutral750,
            modifier = Modifier.width(320.dp)
        ) {
            TextField(
                value = searchAppQuery,
                onValueChange = { searchAppQuery = it },
                placeholder = { Text("Search packages...", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .height(48.dp),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 13.sp),
                colors = fieldColors()
            )

            Spacer(Modifier.height(4.dp))

            val filteredApps = installedApps.filter {
                searchAppQuery.isBlank() ||
                it.packageName.contains(searchAppQuery, true) ||
                it.label?.contains(searchAppQuery, true) == true
            }

            Box(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "All Packages",
                                color = if (selectedPackage == null) Green else TextPrimary,
                                fontWeight = if (selectedPackage == null) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelectedPackageChange(null)
                            expanded = false
                            searchAppQuery = ""
                        }
                    )

                    filteredApps.forEach { app ->
                        val isSelected = app.packageName == selectedPackage
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        app.label ?: app.packageName,
                                        color = if (isSelected) Green else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                    if (app.label != null) {
                                        Text(
                                            app.packageName,
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            fontFamily = MonoFont
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelectedPackageChange(app.packageName)
                                expanded = false
                                searchAppQuery = ""
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LogcatEntryList(entries: List<LogcatEntry>, compact: Boolean, modifier: Modifier = Modifier) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stickToBottom by remember { mutableStateOf(true) }
    var timeWidth by remember { mutableStateOf(110f) }
    var levelWidth by remember { mutableStateOf(32f) }
    var tagWidth by remember { mutableStateOf(180f) }
    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= total - 1
            }
        }
    }
    LaunchedEffect(entries.size, stickToBottom) {
        if (stickToBottom && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling && !atBottom) stickToBottom = false
                if (atBottom) stickToBottom = true
            }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compact) {
            ResizableLogcatHeader(
                timeWidth = timeWidth,
                levelWidth = levelWidth,
                tagWidth = tagWidth,
                onTimeWidth = { timeWidth = it.coerceIn(70f, 240f) },
                onLevelWidth = { levelWidth = it.coerceIn(24f, 90f) },
                onTagWidth = { tagWidth = it.coerceIn(80f, 420f) },
            )
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = listState) {
                items(entries) { entry ->
                    if (compact) {
                        Text("${entry.time} ${entry.pid ?: "-"} ${entry.level.name.take(1)}/${entry.tag}: ${entry.message}", color = levelColor(entry.level), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else {
                        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.Top) {
                            MonoCell(entry.time, timeWidth.dp, TextSecondary)
                            MonoCell(entry.level.name.take(1), levelWidth.dp, levelColor(entry.level))
                            MonoCell(entry.tag, tagWidth.dp, levelColor(entry.level))
                            Text(entry.message, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            DraggableScrollbar(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                visibleItems = listState.layoutInfo.visibleItemsInfo.size,
                totalItems = listState.layoutInfo.totalItemsCount,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                onDragToIndex = { index ->
                    stickToBottom = index >= (listState.layoutInfo.totalItemsCount - listState.layoutInfo.visibleItemsInfo.size - 1)
                    scope.launch { listState.scrollToItem(index.coerceAtLeast(0)) }
                },
            )
        }
    }
}

@Composable
internal fun ResizableLogcatHeader(
    timeWidth: Float,
    levelWidth: Float,
    tagWidth: Float,
    onTimeWidth: (Float) -> Unit,
    onLevelWidth: (Float) -> Unit,
    onTagWidth: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("TIME", timeWidth.dp, onTimeWidth)
        HeaderCell("L", levelWidth.dp, onLevelWidth)
        HeaderCell("TAG", tagWidth.dp, onTagWidth)
        Text("MESSAGE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.Verbose -> TextSecondary
    LogLevel.Debug -> Cyan
    LogLevel.Info -> Green
    LogLevel.Warn -> Yellow
    LogLevel.Error, LogLevel.Fatal -> Red
    LogLevel.Silent -> TextSecondary
}
