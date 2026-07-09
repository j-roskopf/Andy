package app.andy.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.andy.model.DeviceFile
import app.andy.service.FileService
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun FilesScreen(files: FileService, serial: String?) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/sdcard") }
    var rows by remember { mutableStateOf<List<DeviceFile>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    fun load(targetPath: String = path) {
        if (serial == null) return
        scope.launch {
            path = targetPath
            runCatching { files.list(serial, targetPath) }
                .onSuccess { rows = it; message = "${it.size} entries" }
                .onFailure { message = it.message ?: "Failed" }
        }
    }
    LaunchedEffect(serial) { load() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(path, { path = it }, singleLine = true, modifier = Modifier.weight(1f).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
            Button(onClick = { load() }) { Text("Browse") }
            OutlinedButton(onClick = { load(parentPath(path)) }) { Text("Up") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("/", "/sdcard", "/data/local/tmp", "/storage/emulated/0").forEach { root ->
                FilterPill(root, path == root, Cyan) { load(root) }
            }
        }
        if (message.isNotBlank()) Text(message, color = Rust)
        TableHeader(listOf("MODE" to 120.dp, "SIZE" to 100.dp, "MODIFIED" to 190.dp, "NAME" to 1.dp))
        LazyColumn {
            items(rows) { file ->
                TableRow(modifier = Modifier.clickable {
                    if (file.isDirectory) {
                        load(file.path)
                    }
                }) {
                    MonoCell(file.permissions ?: "-", 120.dp, TextSecondary)
                    MonoCell(file.sizeBytes?.toString() ?: "-", 100.dp, TextSecondary)
                    MonoCell(file.modified ?: "-", 190.dp, TextSecondary)
                    MonoCell(if (file.isDirectory) "${file.name}/" else file.name, 1.dp, if (file.isDirectory) Cyan else TextPrimary, Modifier.weight(1f))
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
