package app.andy.ui.hostfiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.HostCodeEditor
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PendingConfirmation
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.intellij_filetype_c_dark
import app.andy.andy.generated.resources.intellij_filetype_config_dark
import app.andy.andy.generated.resources.intellij_filetype_cpp_dark
import app.andy.andy.generated.resources.intellij_filetype_css_dark
import app.andy.andy.generated.resources.intellij_filetype_csv_dark
import app.andy.andy.generated.resources.intellij_filetype_docker_dark
import app.andy.andy.generated.resources.intellij_filetype_gitignore
import app.andy.andy.generated.resources.intellij_filetype_gradle_dark
import app.andy.andy.generated.resources.intellij_filetype_groovy_dark
import app.andy.andy.generated.resources.intellij_filetype_h_dark
import app.andy.andy.generated.resources.intellij_filetype_html_dark
import app.andy.andy.generated.resources.intellij_filetype_image_dark
import app.andy.andy.generated.resources.intellij_filetype_javaScript_dark
import app.andy.andy.generated.resources.intellij_filetype_java_dark
import app.andy.andy.generated.resources.intellij_filetype_json_dark
import app.andy.andy.generated.resources.intellij_filetype_kotlinScript_dark
import app.andy.andy.generated.resources.intellij_filetype_kotlin_dark
import app.andy.andy.generated.resources.intellij_filetype_markdown_dark
import app.andy.andy.generated.resources.intellij_filetype_modified_dark
import app.andy.andy.generated.resources.intellij_filetype_properties_dark
import app.andy.andy.generated.resources.intellij_filetype_shell_dark
import app.andy.andy.generated.resources.intellij_filetype_sql_dark
import app.andy.andy.generated.resources.intellij_filetype_text_dark
import app.andy.andy.generated.resources.intellij_filetype_toml_dark
import app.andy.andy.generated.resources.intellij_filetype_unknown_dark
import app.andy.andy.generated.resources.intellij_filetype_xml_dark
import app.andy.andy.generated.resources.intellij_filetype_yaml_dark
import app.andy.andy.generated.resources.intellij_node_folder_dark
import app.andy.model.HostFileSaveResult
import app.andy.model.HostSearchMatchKind
import app.andy.model.HostSearchMode
import app.andy.model.WorkspaceState
import app.andy.pickDirectory
import app.andy.service.HostFileService
import app.andy.ui.components.Button
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun HostFilesScreen(
    service: HostFileService,
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(service) { HostFilesScreenState(service) }
    var selectedRoot by remember(workspaceState.hostFileRoots, workspaceState.lastHostFilePath) {
        mutableStateOf(resolveHostRootForPath(workspaceState.lastHostFilePath, workspaceState.hostFileRoots) ?: workspaceState.hostFileRoots.firstOrNull())
    }
    var selectedPath by remember(workspaceState.lastHostFilePath, selectedRoot) {
        val saved = workspaceState.lastHostFilePath
        val selected = selectedRoot
        mutableStateOf(if (saved != null && selected != null && hostPathStartsWith(saved, selected)) saved else selected.orEmpty())
    }
    val treeListState = rememberLazyListState()
    var localHostFileTreePaneWidth by remember(workspaceState.hostFileTreePaneWidth) {
        mutableStateOf(workspaceState.hostFileTreePaneWidth.coerceIn(220f, 620f))
    }
    var localHostFileSearchPaneWidth by remember(workspaceState.hostFileSearchPaneWidth) {
        mutableStateOf(workspaceState.hostFileSearchPaneWidth.coerceIn(500f, 980f))
    }
    val activeTab = state.activePath?.let { path -> state.tabs.firstOrNull { it.path == path } }
    val dirtyPaths = remember(state.tabs) { state.tabs.filter { it.dirty }.map { it.path }.toSet() }
    val treeRows = remember(selectedRoot, state.treeChildren.toMap(), state.expandedPaths.toMap()) {
        selectedRoot?.let { root -> hostTreeRows(root, state.treeChildren, state.expandedPaths) }.orEmpty()
    }

    fun updateRecent(path: String) {
        onUpdateWorkspace {
            it.copy(
                lastHostFilePath = path,
                recentHostFiles = (listOf(path) + it.recentHostFiles.filterNot { recent -> recent == path }).take(10),
            )
        }
    }

    fun loadPath(path: String = selectedPath) {
        if (path.isBlank()) return
        scope.launch {
            runCatching { state.service.list(path) }
                .onSuccess {
                    selectedPath = path
                    state.treeChildren[path] = it
                    if (state.message.endsWith("entries")) state.message = ""
                    onUpdateWorkspace { state -> state.copy(lastHostFilePath = path) }
                }
                .onFailure { state.message = it.message ?: "Browse failed" }
        }
    }

    fun openFile(path: String) {
        scope.launch {
            runCatching { state.service.read(path) }
                .onSuccess { doc ->
                    val next = HostEditorTab(doc.path, doc.content, doc.content, doc.modifiedMillis, doc.sizeBytes, doc.languageHint)
                    state.tabs = (state.tabs.filterNot { it.path == doc.path } + next)
                    state.activePath = doc.path
                    updateRecent(doc.path)
                    if (state.message.startsWith("Opened ") || state.message.startsWith("Saved ")) state.message = ""
                }
                .onFailure { state.message = it.message ?: "Open failed" }
        }
    }

    fun revealFileInTree(path: String) {
        val root = resolveHostRootForPath(path, workspaceState.hostFileRoots) ?: return
        val parent = hostParentPath(path)
        selectedRoot = root
        selectedPath = parent
        state.searchQuery = ""
        state.pendingTreeScrollPath = path
        hostAncestorDirectories(path, root).forEach { directory ->
            state.expandedPaths[directory] = true
            loadPath(directory)
        }
    }

    fun saveTab(tab: HostEditorTab, overwrite: Boolean = false, visiblePath: String? = state.activePath) {
        if (visiblePath != tab.path) {
            val visibleName = visiblePath?.let(::hostFileName) ?: "no active file"
            state.message = "Save blocked: ${hostFileName(tab.path)} is not the visible editor file ($visibleName)."
            state.tabs = state.tabs.map { if (it.path == tab.path) it.copy(message = "Save blocked: not the visible editor file") else it }
            return
        }
        scope.launch {
            val currentTab = state.tabs.firstOrNull { it.path == tab.path }
            if (currentTab == null || state.activePath != tab.path) {
                state.message = "Save blocked: active editor changed before save."
                return@launch
            }
            when (val result = state.service.save(currentTab.path, currentTab.content, if (overwrite) 0L else currentTab.modifiedMillis)) {
                is HostFileSaveResult.Saved -> {
                    state.tabs = state.tabs.map { if (it.path == currentTab.path) it.copy(savedContent = currentTab.content, modifiedMillis = result.modifiedMillis, message = "") else it }
                    if (state.message.startsWith("Opened ") || state.message.startsWith("Saved ")) state.message = ""
                }
                is HostFileSaveResult.Conflict -> {
                    state.conflictTab = currentTab.copy(message = "Changed outside Andy at ${result.currentModifiedMillis}")
                }
                is HostFileSaveResult.Failed -> {
                    state.tabs = state.tabs.map { if (it.path == currentTab.path) it.copy(message = result.message) else it }
                    state.message = result.message
                }
            }
        }
    }

    fun updateEditorTextForPath(path: String, value: String) {
        state.updateEditorTextForPath(path, value)
    }

    fun saveEditorContentForPath(path: String, value: String) {
        if (path != state.activePath) {
            state.message = "Save blocked: editor event did not match the visible file."
            return
        }
        val currentTab = state.tabs.firstOrNull { it.path == path }
        if (currentTab == null) {
            state.message = "Save blocked: file tab is no longer open."
            return
        }
        val updated = currentTab.copy(content = value)
        state.tabs = state.tabs.map { if (it.path == path) updated else it }
        saveTab(updated, visiblePath = path)
    }

    fun closeActiveTab() {
        state.closeActiveTab()
    }

    fun setSearchModeAndFocus(mode: HostSearchMode) {
        state.searchMode = mode
        scope.launch {
            delay(30)
            state.searchFocusRequester.requestFocus()
        }
    }

    fun toggleTreeDirectory(path: String) {
        val expanded = state.expandedPaths[path] == true
        if (expanded) {
            state.expandedPaths[path] = false
            return
        }
        state.expandedPaths[path] = true
        loadPath(path)
    }

    LaunchedEffect(workspaceState.hostFileRoots) {
        workspaceState.hostFileRoots.forEach { root ->
            state.expandedPaths[root] = true
            launch { state.service.indexRoot(root).collect { state.statuses[root] = it } }
        }
        selectedRoot?.let { root ->
            if (selectedPath.isBlank() || !hostPathStartsWith(selectedPath, root)) selectedPath = root
            loadPath(root)
            if (selectedPath != root) loadPath(selectedPath)
        }
    }

    LaunchedEffect(state.searchQuery, state.searchMode, selectedRoot) {
        delay(180)
        val root = selectedRoot
        state.searchResults = if (state.searchQuery.isBlank() || root.isNullOrBlank()) {
            emptyList()
        } else {
            state.service.search(state.searchQuery, state.searchMode, listOf(root), 200)
        }
    }

    LaunchedEffect(state.pendingTreeScrollPath, treeRows) {
        val target = state.pendingTreeScrollPath ?: return@LaunchedEffect
        val index = treeRows.indexOfFirst { it.entry.path == target }
        if (index >= 0) {
            delay(50)
            treeListState.animateScrollToItem(index)
            state.pendingTreeScrollPath = null
        }
    }

    Column(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val command = event.isMetaPressed || event.isCtrlPressed
                if (!command) return@onPreviewKeyEvent false
                when {
                    event.key == Key.S && activeTab != null -> {
                        state.tabs.firstOrNull { it.path == activeTab.path }?.let { saveTab(it) }
                        true
                    }
                    event.key == Key.W -> {
                        closeActiveTab()
                        true
                    }
                    event.key == Key.O -> {
                        scope.launch {
                            pickDirectory(selectedRoot)?.let { picked ->
                                onUpdateWorkspace { state -> state.copy(hostFileRoots = (state.hostFileRoots + picked).distinct(), lastHostFilePath = picked) }
                                selectedRoot = picked
                                selectedPath = picked
                                state.expandedPaths[picked] = true
                                loadPath(picked)
                            }
                        }
                        true
                    }
                    event.isShiftPressed && event.key == Key.A -> {
                        setSearchModeAndFocus(HostSearchMode.Combined)
                        true
                    }
                    event.isShiftPressed && event.key == Key.N -> {
                        setSearchModeAndFocus(HostSearchMode.FileName)
                        true
                    }
                    event.isShiftPressed && event.key == Key.F -> {
                        setSearchModeAndFocus(HostSearchMode.Content)
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Toolbar("Computer Files", "${workspaceState.hostFileRoots.size} roots · ${state.statuses.values.sumOf { it.indexedFiles }} indexed", onPrimary = {
            scope.launch {
                pickDirectory(selectedRoot)?.let { picked ->
                    onUpdateWorkspace { state -> state.copy(hostFileRoots = (state.hostFileRoots + picked).distinct(), lastHostFilePath = picked) }
                    selectedRoot = picked
                    selectedPath = picked
                    state.expandedPaths[picked] = true
                    loadPath(picked)
                }
            }
        }, primaryLabel = "Add root")
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelCard(Modifier.width(localHostFileTreePaneWidth.dp).fillMaxHeight()) {
                Text("Roots", color = TextPrimary, fontWeight = FontWeight.Bold)
                if (workspaceState.hostFileRoots.isEmpty()) {
                    Text("Add a folder to start indexing files on this computer.", color = TextSecondary, fontSize = 12.sp)
                }
                workspaceState.hostFileRoots.forEach { root ->
                    val status = state.statuses[root]
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (root == selectedRoot) AndyColors.OrangeSubtle else PanelSoft, RoundedCornerShape(AndyRadius.R2))
                            .border(1.dp, if (root == selectedRoot) AndyColors.OrangeBorder.copy(alpha = 0.52f) else Border, RoundedCornerShape(AndyRadius.R2))
                            .clickable {
                                selectedRoot = root
                                selectedPath = root
                                state.expandedPaths[root] = true
                                loadPath(root)
                            }
                            .padding(8.dp),
                    ) {
                        Text(hostFileName(root).ifBlank { root }, color = if (root == selectedRoot) Rust else TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status?.message ?: "Queued", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = Border)
                Text("Recent", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                workspaceState.recentHostFiles.forEach { recent ->
                    val recentRoot = resolveHostRootForPath(recent, workspaceState.hostFileRoots)
                    val relativePath = recentRoot?.let { hostDisplayPath(recent, it) } ?: recent
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                revealFileInTree(recent)
                                openFile(recent)
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HostFileIcon(hostFileIconForPath(recent, isDirectory = false))
                        Column(Modifier.weight(1f)) {
                            Text(recentRoot?.let(::hostFileName) ?: "outside roots", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(relativePath, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            PaneDivider(
                onDrag = { dragX ->
                    localHostFileTreePaneWidth = (localHostFileTreePaneWidth + dragX).coerceIn(220f, 620f)
                },
                onDragEnd = {
                    onUpdateWorkspace { state -> state.copy(hostFileTreePaneWidth = localHostFileTreePaneWidth) }
                },
            )
            PanelCard(Modifier.width(localHostFileSearchPaneWidth.dp).fillMaxHeight()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        selectedPath,
                        { selectedPath = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { loadPath() }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Text("Go") }
                        OutlinedButton(onClick = { loadPath(hostParentPath(selectedPath)) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Text("Up") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            selectedRoot?.let { root ->
                                scope.launch {
                                    var sawIndexing = false
                                    state.service.indexRoot(root).first { status ->
                                        state.statuses[root] = status
                                        if (status.indexing) sawIndexing = true
                                        sawIndexing && !status.indexing
                                    }
                                }
                            }
                        }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("Refresh index")
                        }
                    }
                }
                TextField(
                    state.searchQuery,
                    { state.searchQuery = it },
                    placeholder = { Text("Search indexed files", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(state.searchFocusRequester),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchModePill("All", "ctrl shift A", state.searchMode == HostSearchMode.Combined, Rust) { setSearchModeAndFocus(HostSearchMode.Combined) }
                    SearchModePill("Names", "ctrl shift N", state.searchMode == HostSearchMode.FileName, Cyan) { setSearchModeAndFocus(HostSearchMode.FileName) }
                    SearchModePill("Contents", "ctrl shift F", state.searchMode == HostSearchMode.Content, Green) { setSearchModeAndFocus(HostSearchMode.Content) }
                }
                if (state.message.isNotBlank()) Text(state.message, color = Rust, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.searchQuery.isNotBlank()) {
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.searchResults) { result ->
                            val icon = hostFileIconForPath(result.path, isDirectory = false)
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        revealFileInTree(result.path)
                                        openFile(result.path)
                                    }
                                    .padding(vertical = 7.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    HostFileIcon(icon)
                                    Text(hostDisplayPath(result.path, result.root), color = if (result.kind == HostSearchMatchKind.FileName) Cyan else TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (dirtyPaths.contains(result.path)) {
                                        Image(
                                            painter = painterResource(Res.drawable.intellij_filetype_modified_dark),
                                            contentDescription = "Unsaved",
                                            modifier = Modifier.size(13.dp),
                                        )
                                    }
                                }
                                Text(listOfNotNull(result.kind.name.lowercase(), result.lineNumber?.let { "line $it" }, result.preview.takeIf { it.isNotBlank() }).joinToString(" · "), color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), state = treeListState) {
                        items(treeRows, key = { it.entry.path }) { row ->
                            HostTreeRowView(
                                row = row,
                                expanded = state.expandedPaths[row.entry.path] == true,
                                selected = !row.entry.isDirectory && state.activePath == row.entry.path,
                                dirty = dirtyPaths.contains(row.entry.path),
                                onClick = {
                                    if (row.entry.isDirectory) toggleTreeDirectory(row.entry.path) else openFile(row.entry.path)
                                },
                            )
                        }
                    }
                }
            }
            PaneDivider(
                onDrag = { dragX ->
                    localHostFileSearchPaneWidth = (localHostFileSearchPaneWidth + dragX).coerceIn(500f, 980f)
                },
                onDragEnd = {
                    onUpdateWorkspace { state -> state.copy(hostFileSearchPaneWidth = localHostFileSearchPaneWidth) }
                },
            )
            PanelCard(Modifier.weight(1f).fillMaxHeight()) {
                val tab = activeTab
                if (tab == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Open a file from the browser or indexed results.", color = TextSecondary, fontFamily = MonoFont)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HostFileIcon(hostFileIconForPath(tab.path, isDirectory = false))
                        Text(tab.path, color = if (tab.dirty) Rust else TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (tab.dirty) {
                            Image(
                                painter = painterResource(Res.drawable.intellij_filetype_modified_dark),
                                contentDescription = "Unsaved",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    if (tab.message.isNotBlank()) Text(tab.message, color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
                    HostCodeEditor(
                        path = tab.path,
                        text = tab.content,
                        languageHint = tab.languageHint,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onTextChange = ::updateEditorTextForPath,
                        onSave = ::saveEditorContentForPath,
                        onClose = { closeActiveTab() },
                        onSearchAll = { setSearchModeAndFocus(HostSearchMode.Combined) },
                        onSearchNames = { setSearchModeAndFocus(HostSearchMode.FileName) },
                        onSearchContents = { setSearchModeAndFocus(HostSearchMode.Content) },
                    )
                }
            }
        }
    }

    state.conflictTab?.let { tab ->
        ConfirmationDialog(
            confirmation = PendingConfirmation("Overwrite external changes?", "${tab.path}\nThe file changed since Andy opened it.") {
                saveTab(tab, overwrite = true)
                state.conflictTab = null
            },
            onDismiss = { state.conflictTab = null },
            onConfirm = {
                saveTab(tab, overwrite = true)
                state.conflictTab = null
            },
        )
    }
}
private data class HostFileIconSpec(val resource: DrawableResource?)

@Composable
private fun HostTreeRowView(row: HostTreeRow, expanded: Boolean, selected: Boolean, dirty: Boolean, onClick: () -> Unit) {
    val entry = row.entry
    val icon = hostFileIconForPath(entry.path, entry.isDirectory)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 24.dp)
            .background(if (selected) AndyColors.OrangeSubtle else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
            .then(if (selected) Modifier.border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.42f), RoundedCornerShape(AndyRadius.R2)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = (row.depth * 16).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (entry.isDirectory) {
                if (expanded) "v" else ">"
            } else {
                " "
            },
            color = if (entry.isDirectory) Cyan else TextSecondary,
            fontFamily = MonoFont,
            fontSize = 12.sp,
            modifier = Modifier.width(16.dp),
        )
        HostFileIcon(icon)
        Spacer(Modifier.width(7.dp))
        Text(
            entry.name,
            color = when {
                dirty -> Rust
                selected -> Rust
                entry.isDirectory -> Cyan
                else -> TextPrimary
            },
            fontFamily = MonoFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dirty) {
            Image(
                painter = painterResource(Res.drawable.intellij_filetype_modified_dark),
                contentDescription = "Unsaved",
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (!entry.isDirectory) {
            Text(entry.extension.takeIf { it.isNotBlank() } ?: entry.sizeBytes.toString(), color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun HostFileIcon(spec: HostFileIconSpec) {
    if (spec.resource == null) {
        Spacer(Modifier.size(18.dp))
    } else {
        Image(
            painter = painterResource(spec.resource),
            contentDescription = "File type",
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun hostFileIconForPath(path: String, isDirectory: Boolean): HostFileIconSpec {
    if (isDirectory) return HostFileIconSpec(Res.drawable.intellij_node_folder_dark)
    val name = hostFileName(path).lowercase()
    val ext = name.substringAfterLast('.', "")
    return when {
        name == ".gitignore" -> HostFileIconSpec(Res.drawable.intellij_filetype_gitignore)
        name == "build.gradle" || name == "settings.gradle" || name.endsWith(".gradle.kts") || name.endsWith(".gradle") -> HostFileIconSpec(Res.drawable.intellij_filetype_gradle_dark)
        name == "dockerfile" || name == "containerfile" -> HostFileIconSpec(Res.drawable.intellij_filetype_docker_dark)
        name == "makefile" -> HostFileIconSpec(Res.drawable.intellij_filetype_config_dark)
        ext == "kt" -> HostFileIconSpec(Res.drawable.intellij_filetype_kotlin_dark)
        ext == "kts" -> HostFileIconSpec(Res.drawable.intellij_filetype_kotlinScript_dark)
        ext == "java" -> HostFileIconSpec(Res.drawable.intellij_filetype_java_dark)
        ext == "groovy" -> HostFileIconSpec(Res.drawable.intellij_filetype_groovy_dark)
        ext == "json" -> HostFileIconSpec(Res.drawable.intellij_filetype_json_dark)
        ext == "xml" -> HostFileIconSpec(Res.drawable.intellij_filetype_xml_dark)
        ext == "html" || ext == "htm" -> HostFileIconSpec(Res.drawable.intellij_filetype_html_dark)
        ext == "css" || ext == "scss" || ext == "sass" -> HostFileIconSpec(Res.drawable.intellij_filetype_css_dark)
        ext == "js" || ext == "jsx" || ext == "mjs" || ext == "cjs" -> HostFileIconSpec(Res.drawable.intellij_filetype_javaScript_dark)
        ext == "ts" || ext == "tsx" -> HostFileIconSpec(Res.drawable.intellij_filetype_javaScript_dark)
        ext == "md" || ext == "markdown" -> HostFileIconSpec(Res.drawable.intellij_filetype_markdown_dark)
        ext == "c" -> HostFileIconSpec(Res.drawable.intellij_filetype_c_dark)
        ext == "cpp" || ext == "cc" || ext == "cxx" -> HostFileIconSpec(Res.drawable.intellij_filetype_cpp_dark)
        ext == "h" || ext == "hpp" -> HostFileIconSpec(Res.drawable.intellij_filetype_h_dark)
        ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "gif" || ext == "webp" || ext == "svg" -> HostFileIconSpec(Res.drawable.intellij_filetype_image_dark)
        ext == "yml" || ext == "yaml" -> HostFileIconSpec(Res.drawable.intellij_filetype_yaml_dark)
        ext == "toml" -> HostFileIconSpec(Res.drawable.intellij_filetype_toml_dark)
        ext == "sh" || ext == "bash" || ext == "zsh" -> HostFileIconSpec(Res.drawable.intellij_filetype_shell_dark)
        ext == "sql" -> HostFileIconSpec(Res.drawable.intellij_filetype_sql_dark)
        ext == "csv" -> HostFileIconSpec(Res.drawable.intellij_filetype_csv_dark)
        ext == "properties" -> HostFileIconSpec(Res.drawable.intellij_filetype_properties_dark)
        ext == "conf" || ext == "cfg" || ext == "ini" -> HostFileIconSpec(Res.drawable.intellij_filetype_config_dark)
        ext == "txt" -> HostFileIconSpec(Res.drawable.intellij_filetype_text_dark)
        ext.isBlank() -> HostFileIconSpec(Res.drawable.intellij_filetype_unknown_dark)
        else -> HostFileIconSpec(Res.drawable.intellij_filetype_text_dark)
    }
}

@Composable
private fun SearchModePill(text: String, shortcut: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp)
            .background(if (selected) color.copy(alpha = 0.16f) else AndyColors.Neutral900.copy(alpha = 0.35f), RoundedCornerShape(AndyRadius.Pill))
            .border(1.dp, color.copy(alpha = if (selected) 0.52f else 0.22f), RoundedCornerShape(AndyRadius.Pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = if (selected) color else TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (shortcut.isNotBlank()) {
            Text(shortcut, color = TextSecondary, fontFamily = MonoFont, fontSize = 9.sp)
        }
    }
}

