package app.andy.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PendingConfirmation
import app.andy.loadImageBitmap
import app.andy.model.AndroidActivity
import app.andy.model.AndroidApp
import app.andy.model.AndroidPermission
import app.andy.service.AndyServices
import app.andy.service.AppService
import app.andy.service.CommandResult
import app.andy.ui.components.Button
import app.andy.ui.components.HorizontalPaneDivider
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Green
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun AppsScreen(
    services: AndyServices,
    serial: String?,
    listPaneWidth: Float,
    detailsPaneHeight: Float,
    onPaneChange: (Float, Float) -> Unit,
) {
    val apps = services.apps
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AndroidApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AndroidApp?>(null) }
    var permissions by remember { mutableStateOf<List<AndroidPermission>>(emptyList()) }
    var activities by remember { mutableStateOf<List<AndroidActivity>>(emptyList()) }
    var status by remember { mutableStateOf("Select a device") }
    var localListPaneWidth by remember(listPaneWidth) { mutableStateOf(listPaneWidth) }
    var localDetailsPaneHeight by remember(detailsPaneHeight) { mutableStateOf(detailsPaneHeight) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    val iconCache = remember(serial) { mutableStateMapOf<String, ByteArray?>() }

    fun refresh() {
        if (serial == null) {
            status = "Select an online device"
            return
        }
        scope.launch {
            status = "Loading packages..."
            rows = apps.listApps(serial)
            selected = selected?.let { current -> rows.firstOrNull { it.packageName == current.packageName } }
            status = "${rows.size} apps"
        }
    }

    fun runAppAction(label: String, packageName: String? = selected?.packageName, appLabel: String? = selected?.label, block: suspend () -> CommandResult) {
        scope.launch {
            packageName?.let { services.bugs.recordAction("app", "$label $it", appLabel) }
            val result = block()
            status = "$label: " + if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
            if (label == "Uninstall" || label == "Clear data") refresh()
        }
    }

    LaunchedEffect(serial) { refresh() }
    val filtered = rows.filter { app -> query.isBlank() || app.packageName.contains(query, true) || app.label?.contains(query, true) == true }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(localListPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toolbar("Apps", status, onPrimary = { refresh() }, primaryLabel = "Refresh")
            TextField(query, { query = it }, placeholder = { Text("Filter packages", color = TextSecondary) }, singleLine = true, modifier = Modifier.fillMaxWidth().height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
            TableHeader(listOf("" to 56.dp, "TYPE" to 70.dp, "STATE" to 80.dp, "VERSION" to 90.dp, "APP NAME" to 160.dp, "PACKAGE" to 1.dp))
            LazyColumn {
                items(filtered) { app ->
                    TableRow(Modifier.clickable {
                        selected = app
                        if (serial != null) scope.launch {
                            permissions = apps.listPermissions(serial, app.packageName)
                            activities = apps.listActivities(serial, app.packageName)
                        }
                    }) {
                        Box(Modifier.width(56.dp)) {
                            if (serial != null) AppIconCell(serial, app.packageName, apps, iconCache)
                        }
                        MonoCell(if (app.system) "system" else "user", 70.dp, if (app.system) TextSecondary else Green)
                        MonoCell(if (app.enabled) "enabled" else "disabled", 80.dp, if (app.enabled) TextPrimary else Rust)
                        MonoCell(app.versionCode ?: "-", 90.dp, TextSecondary)
                        MonoCell(app.label ?: "-", 160.dp, TextSecondary)
                        MonoCell(app.packageName, 1.dp, if (selected?.packageName == app.packageName) Rust else TextPrimary, Modifier.weight(1f))
                    }
                }
            }
        }
        PaneDivider(
            onDrag = { dragX -> localListPaneWidth = (localListPaneWidth + dragX).coerceIn(320f, 1100f) },
            onDragEnd = { onPaneChange(localListPaneWidth, localDetailsPaneHeight) },
        )
        Column(Modifier.fillMaxSize().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelCard(Modifier.fillMaxWidth().height(localDetailsPaneHeight.dp)) {
                val app = selected
                Text(app?.packageName ?: "No app selected", color = TextPrimary, fontWeight = FontWeight.Bold)
                if (app != null && serial != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { runAppAction("Launch", app.packageName, app.label) { apps.launch(serial, app.packageName) } }) { Text("Launch") }
                        OutlinedButton(onClick = { runAppAction("Stop", app.packageName, app.label) { apps.stop(serial, app.packageName) } }) { Text("Stop") }
                        OutlinedButton(onClick = {
                            pendingConfirmation = PendingConfirmation("Clear app data?", app.packageName) {
                                runAppAction("Clear data", app.packageName, app.label) { apps.clearData(serial, app.packageName) }
                            }
                        }) { Text("Clear") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { runAppAction("Reset permissions", app.packageName, app.label) { apps.resetPermissions(serial, app.packageName) } }) { Text("Reset perms") }
                        OutlinedButton(onClick = {
                            pendingConfirmation = PendingConfirmation("Uninstall app?", app.packageName) {
                                runAppAction("Uninstall", app.packageName, app.label) { apps.uninstall(serial, app.packageName) }
                            }
                        }, enabled = !app.system) { Text("Uninstall") }
                    }
                    Text("Permissions", color = TextPrimary, fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(permissions) { permission ->
                            Text("${permission.granted?.let { if (it) "granted" else "denied" } ?: "declared"}  ${permission.name}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Text("Choose an app to launch, stop, clear data, reset permissions, uninstall, or inspect permissions and activities.", color = TextSecondary)
                }
            }
            HorizontalPaneDivider(
                onDrag = { dragY -> localDetailsPaneHeight = (localDetailsPaneHeight + dragY).coerceIn(200f, 800f) },
                onDragEnd = { onPaneChange(localListPaneWidth, localDetailsPaneHeight) },
            )
            PanelCard(Modifier.fillMaxWidth().weight(1f)) {
                Text("Activities", color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(selected?.packageName ?: "Select an app", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(activities) { activity ->
                        Text(activity.name, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
            pendingConfirmation = null
            confirmation.onConfirm()
        })
    }
}

@Composable
private fun AppIconCell(serial: String, packageName: String, apps: AppService, cache: MutableMap<String, ByteArray?>) {
    val icon = cache[packageName]
    LaunchedEffect(serial, packageName) {
        if (!cache.containsKey(packageName)) {
            val bytes = runCatching { apps.getIcon(serial, packageName) }.getOrNull()
            cache[packageName] = bytes
        }
    }
    val bitmap = remember(icon) { icon?.let { loadImageBitmap(it) } }
    Box(
        Modifier.padding(vertical = 4.dp).size(48.dp).clip(RoundedCornerShape(AndyRadius.R4)).background(AndyColors.Neutral750),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}
