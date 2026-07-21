package app.andy.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.andy.downloadsDirectory
import app.andy.model.DeviceFile
import app.andy.model.FilesTab
import app.andy.onExternalFileDrop
import app.andy.pickFiles
import app.andy.service.AppService
import app.andy.service.FileService
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.transfer.LocalDropKind
import app.andy.transfer.classifyLocalPaths
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun FilesScreen(
    files: FileService,
    apps: AppService,
    sharedPrefs: app.andy.service.SharedPrefsService,
    appDatabase: app.andy.service.AppDatabaseService,
    serial: String?,
    transfer: DeviceTransferCoordinator,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    selectedTab: FilesTab,
    onSelectedTabChange: (FilesTab) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Files", selectedTab == FilesTab.Files, Rust) {
                onSelectedTabChange(FilesTab.Files)
            }
            FilterPill("Shared Preferences", selectedTab == FilesTab.SharedPreferences, Rust) {
                onSelectedTabChange(FilesTab.SharedPreferences)
            }
            FilterPill("Database", selectedTab == FilesTab.Database, Rust) {
                onSelectedTabChange(FilesTab.Database)
            }
        }
        when (selectedTab) {
            FilesTab.Files -> DeviceFilesBrowser(
                files = files,
                apps = apps,
                serial = serial,
                transfer = transfer,
            )
            FilesTab.SharedPreferences -> SharedPreferencesPane(
                service = sharedPrefs,
                apps = apps,
                serial = serial,
                selectedPackage = selectedPackage,
                onSelectedPackageChange = onSelectedPackageChange,
            )
            FilesTab.Database -> DatabasePane(
                service = appDatabase,
                apps = apps,
                serial = serial,
                selectedPackage = selectedPackage,
                onSelectedPackageChange = onSelectedPackageChange,
            )
        }
    }
}

@Composable
private fun DeviceFilesBrowser(
    files: FileService,
    apps: AppService,
    serial: String?,
    transfer: DeviceTransferCoordinator,
) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/sdcard") }
    var rows by remember { mutableStateOf<List<DeviceFile>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var anchorIndex by remember { mutableStateOf<Int?>(null) }
    var contextMenuPath by remember { mutableStateOf<String?>(null) }

    fun load(targetPath: String = path) {
        if (serial == null) return
        scope.launch {
            path = targetPath
            runCatching { files.list(serial, targetPath) }
                .onSuccess {
                    rows = it
                    selectedPaths = selectedPaths.filter { selected -> it.any { file -> file.path == selected } }.toSet()
                    message = "${it.size} entries"
                }
                .onFailure { message = it.message ?: "Failed" }
        }
    }

    fun openDirectory(file: DeviceFile) {
        if (file.isDirectory) {
            selectedPaths = emptySet()
            anchorIndex = null
            load(file.path)
        }
    }

    fun selectRow(index: Int, file: DeviceFile, meta: Boolean, shift: Boolean) {
        when {
            shift -> {
                val anchor = anchorIndex
                when {
                    anchor != null && anchor in rows.indices -> {
                        val from = minOf(anchor, index)
                        val to = maxOf(anchor, index)
                        selectedPaths = rows.slice(from..to).map { it.path }.toSet()
                    }
                    meta -> {
                        selectedPaths = if (file.path in selectedPaths) selectedPaths - file.path else selectedPaths + file.path
                        anchorIndex = index
                    }
                    else -> {
                        selectedPaths = setOf(file.path)
                        anchorIndex = index
                    }
                }
            }
            meta -> {
                selectedPaths = if (file.path in selectedPaths) selectedPaths - file.path else selectedPaths + file.path
                anchorIndex = index
            }
            else -> {
                selectedPaths = setOf(file.path)
                anchorIndex = index
            }
        }
    }

    fun startDownload(targets: List<DeviceFile>) {
        if (serial == null) {
            message = "Select an online device"
            return
        }
        if (targets.isEmpty()) {
            message = "Select files to download"
            return
        }
        transfer.tryStart(scope, "Pulling…") {
            pullAll(files, serial, targets.map { it.path }, downloadsDirectory())
        }
    }

    fun handleIncoming(localPaths: List<String>) {
        if (serial == null) {
            message = "Select an online device"
            return
        }
        when (classifyLocalPaths(localPaths)) {
            LocalDropKind.Empty -> Unit
            LocalDropKind.Mixed -> {
                message = "Drop APKs or other files separately — mixed drops are not allowed"
            }
            LocalDropKind.Apks -> {
                transfer.tryStart(scope, "Installing…") {
                    installAll(apps, serial, localPaths)
                }
            }
            LocalDropKind.Files -> {
                val existing = rows.map { it.name }.toSet()
                transfer.tryStart(scope, "Pushing…") {
                    pushAll(files, serial, localPaths, path, existing) { load() }
                }
            }
        }
    }

    fun startUpload() {
        if (serial == null) {
            message = "Select an online device"
            return
        }
        if (transfer.busy) {
            message = "Wait for the current transfer to finish"
            return
        }
        scope.launch {
            val picked = pickFiles()
            if (picked.isNotEmpty()) handleIncoming(picked)
        }
    }

    LaunchedEffect(serial) {
        selectedPaths = emptySet()
        anchorIndex = null
        load()
    }

    LaunchedEffect(transfer.status) {
        if (transfer.status.isNotBlank()) message = transfer.status
    }

    Column(
        Modifier
            .fillMaxSize()
            .onExternalFileDrop(enabled = serial != null) { handleIncoming(it) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                    val directory = rows.filter { it.path in selectedPaths }.singleOrNull()?.takeIf { it.isDirectory }
                    if (directory != null) {
                        openDirectory(directory)
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                path,
                { path = it },
                singleLine = true,
                modifier = Modifier.weight(1f).height(54.dp),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
            Button(onClick = { load() }) { Text("Browse") }
            OutlinedButton(onClick = { load(parentPath(path)) }) { Text("Up") }
            Button(
                onClick = { startDownload(rows.filter { it.path in selectedPaths }) },
                enabled = serial != null && selectedPaths.isNotEmpty() && !transfer.busy,
            ) { Text("Download") }
            OutlinedButton(
                onClick = { startUpload() },
                enabled = serial != null && !transfer.busy,
            ) { Text("Upload…") }
            if (transfer.busy) {
                OutlinedButton(onClick = { transfer.cancel() }) { Text("Cancel") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("/", "/sdcard", "/data/local/tmp", "/storage/emulated/0").forEach { root ->
                FilterPill(root, path == root, Cyan) { load(root) }
            }
        }
        if (message.isNotBlank()) {
            Text(
                message,
                color = when {
                    message.startsWith("App installed") ||
                        message.startsWith("App replaced") ||
                        message.startsWith("Installed ") ||
                        message.startsWith("Replaced ") ||
                        message.startsWith("Downloaded ") ||
                        message.startsWith("Pushed ") -> Cyan
                    message.contains("failed", ignoreCase = true) ||
                        message.contains("rejected", ignoreCase = true) ||
                        message.contains("not allowed", ignoreCase = true) -> Rust
                    else -> Rust
                },
                fontWeight = if (
                    message.startsWith("App installed") ||
                    message.startsWith("App replaced") ||
                    message.startsWith("Installed ") ||
                    message.startsWith("Replaced ")
                ) FontWeight.Bold else FontWeight.Normal,
            )
        }
        TableHeader(listOf("MODE" to 120.dp, "SIZE" to 100.dp, "MODIFIED" to 190.dp, "NAME" to 1.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, file -> file.path }) { index, file ->
                val selected = file.path in selectedPaths
                Box {
                    TableRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) PanelSoft else Color.Transparent)
                            .pointerInput(file.path, rows, selectedPaths, anchorIndex) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type != PointerEventType.Press) continue
                                        val change = event.changes.firstOrNull() ?: continue
                                        if (event.buttons.isSecondaryPressed) {
                                            if (file.path !in selectedPaths) {
                                                selectedPaths = setOf(file.path)
                                                anchorIndex = index
                                            }
                                            contextMenuPath = file.path
                                            change.consume()
                                            continue
                                        }
                                        val meta = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                                        val shift = event.keyboardModifiers.isShiftPressed
                                        selectRow(index, file, meta, shift)
                                    }
                                }
                            }
                            .pointerInput(file.path) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (file.isDirectory) openDirectory(file)
                                    },
                                )
                            },
                    ) {
                        MonoCell(file.permissions ?: "-", 120.dp, TextSecondary)
                        MonoCell(file.sizeBytes?.toString() ?: "-", 100.dp, TextSecondary)
                        MonoCell(file.modified ?: "-", 190.dp, TextSecondary)
                        MonoCell(
                            if (file.isDirectory) "${file.name}/" else file.name,
                            1.dp,
                            if (file.isDirectory) Cyan else TextPrimary,
                            Modifier.weight(1f),
                        )
                    }
                    DropdownMenu(
                        expanded = contextMenuPath == file.path,
                        onDismissRequest = { contextMenuPath = null },
                        containerColor = PanelSoft,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Download", color = TextPrimary) },
                            onClick = {
                                contextMenuPath = null
                                val targets = rows.filter { it.path in selectedPaths }.ifEmpty { listOf(file) }
                                startDownload(targets)
                            },
                            enabled = !transfer.busy,
                        )
                    }
                }
            }
        }
    }
}

internal fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/').ifBlank { "/" }
    if (trimmed == "/") return "/"
    return trimmed.substringBeforeLast('/', "").ifBlank { "/" }
}
