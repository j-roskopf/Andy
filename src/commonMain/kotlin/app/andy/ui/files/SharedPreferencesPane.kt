package app.andy.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.PrefEntry
import app.andy.model.PrefType
import app.andy.service.AppService
import app.andy.service.SharedPrefsService
import app.andy.ui.components.Button
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PackageSelector
import app.andy.ui.components.PaneDivider
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
internal fun SharedPreferencesPane(
    service: SharedPrefsService,
    apps: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<PrefEntry>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf(PrefType.String) }
    var newValue by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var listPaneWidth by remember { mutableStateOf(260f) }

    fun refreshFiles() {
        if (serial == null || selectedPackage.isNullOrBlank()) {
            files = emptyList()
            selectedFile = null
            entries = emptyList()
            return
        }
        scope.launch {
            busy = true
            service.listFiles(serial, selectedPackage)
                .onSuccess {
                    files = it
                    if (selectedFile !in it) selectedFile = it.firstOrNull()
                    message = if (it.isEmpty()) {
                        "No shared_prefs files — launch the app on the device if you expect data"
                    } else {
                        "${it.size} prefs files"
                    }
                }
                .onFailure {
                    files = emptyList()
                    selectedFile = null
                    entries = emptyList()
                    message = it.message ?: "Failed to list prefs"
                }
            busy = false
        }
    }

    fun refreshEntries(fileName: String?) {
        if (serial == null || selectedPackage.isNullOrBlank() || fileName.isNullOrBlank()) {
            entries = emptyList()
            return
        }
        scope.launch {
            busy = true
            service.read(serial, selectedPackage, fileName)
                .onSuccess {
                    entries = it.sortedBy { entry -> entry.key }
                    message = "${it.size} keys"
                }
                .onFailure {
                    entries = emptyList()
                    message = it.message ?: "Failed to read prefs"
                }
            busy = false
        }
    }

    LaunchedEffect(serial, selectedPackage) {
        refreshFiles()
    }
    LaunchedEffect(selectedFile, serial, selectedPackage) {
        refreshEntries(selectedFile)
    }

    if (serial == null) {
        InspectorEmptyState("Select an online device to inspect shared preferences.")
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageSelector(
                appsService = apps,
                serial = serial,
                selectedPackage = selectedPackage,
                onSelectedPackageChange = onSelectedPackageChange,
                allowAll = false,
                placeholder = "Select app…",
                buttonPrefix = "App: ",
                modifier = Modifier.width(320.dp),
            )
            OutlinedButton(onClick = {
                refreshFiles()
                refreshEntries(selectedFile)
            }, enabled = !busy && !selectedPackage.isNullOrBlank()) { Text("Refresh") }
            if (message.isNotBlank()) {
                Text(message, color = if (message.contains("fail", ignoreCase = true)) Rust else TextSecondary, fontSize = 12.sp)
            }
        }
        if (selectedPackage.isNullOrBlank()) {
            InspectorEmptyState(
                "Search and select a debuggable app to inspect its shared preferences.",
            )
        } else {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(listPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Files", color = TextSecondary, fontSize = 12.sp)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files) { file ->
                        Text(
                            file,
                            color = if (file == selectedFile) Cyan else TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (file == selectedFile) PanelSoft else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { selectedFile = file }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            PaneDivider(
                onDrag = { dragX -> listPaneWidth = (listPaneWidth + dragX).coerceIn(180f, 480f) },
            )
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val file = selectedFile
                if (file == null) {
                    Text("Select a prefs file", color = TextSecondary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            newKey,
                            { newKey = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f).height(44.dp),
                            placeholder = { Text("new key", color = TextSecondary) },
                            colors = fieldColors(),
                        )
                        PrefType.entries.forEach { type ->
                            FilterTypeChip(type.name, newType == type) { newType = type }
                        }
                        TextField(
                            newValue,
                            { newValue = it },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f).height(44.dp),
                            placeholder = { Text("value", color = TextSecondary) },
                            colors = fieldColors(),
                        )
                        Button(
                            onClick = {
                                if (newKey.isBlank()) {
                                    message = "Key is required"
                                    return@Button
                                }
                                scope.launch {
                                    busy = true
                                    val result = service.upsert(
                                        serial,
                                        selectedPackage,
                                        file,
                                        PrefEntry(newKey.trim(), newType, newValue),
                                    )
                                    message = if (result.isSuccess) "Saved ${newKey.trim()}" else result.stderr.ifBlank { "Save failed" }
                                    if (result.isSuccess) {
                                        newKey = ""
                                        newValue = ""
                                        refreshEntries(file)
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                        ) { Text("Add") }
                    }
                    TableHeader(listOf("KEY" to 220.dp, "TYPE" to 100.dp, "VALUE" to 1.dp, "" to 160.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.key }) { entry ->
                            val editing = editingKey == entry.key
                            TableRow {
                                MonoCell(entry.key, 220.dp, TextPrimary)
                                MonoCell(entry.type.name, 100.dp, TextSecondary)
                                if (editing) {
                                    TextField(
                                        editingValue,
                                        { editingValue = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        colors = fieldColors(),
                                    )
                                } else {
                                    MonoCell(
                                        entry.value.replace('\n', '·'),
                                        1.dp,
                                        TextPrimary,
                                        Modifier.weight(1f),
                                    )
                                }
                                Row(Modifier.width(160.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (editing) {
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                busy = true
                                                val result = service.upsert(
                                                    serial,
                                                    selectedPackage,
                                                    file,
                                                    entry.copy(value = editingValue),
                                                )
                                                message = if (result.isSuccess) "Updated ${entry.key}" else result.stderr.ifBlank { "Update failed" }
                                                editingKey = null
                                                if (result.isSuccess) refreshEntries(file)
                                                busy = false
                                            }
                                        }, enabled = !busy) { Text("Save") }
                                        OutlinedButton(onClick = { editingKey = null }) { Text("Cancel") }
                                    } else {
                                        OutlinedButton(onClick = {
                                            editingKey = entry.key
                                            editingValue = entry.value
                                        }) { Text("Edit") }
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                busy = true
                                                val result = service.delete(serial, selectedPackage, file, entry.key)
                                                message = if (result.isSuccess) "Deleted ${entry.key}" else result.stderr.ifBlank { "Delete failed" }
                                                if (result.isSuccess) refreshEntries(file)
                                                busy = false
                                            }
                                        }, enabled = !busy) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun InspectorEmptyState(message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun FilterTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label.take(3).lowercase(),
        color = if (selected) Cyan else TextSecondary,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(PanelSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}
