package app.andy.ui.snapshots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PendingConfirmation
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.hardware_capture
import app.andy.loadImageBitmap
import app.andy.model.EmulatorSnapshot
import app.andy.model.VirtualDevice
import app.andy.service.AvdService
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun SnapshotsScreen(avd: AvdService) {
    val scope = rememberCoroutineScope()
    var avds by remember { mutableStateOf<List<VirtualDevice>>(emptyList()) }
    var selectedAvd by remember { mutableStateOf<VirtualDevice?>(null) }
    var snapshots by remember { mutableStateOf<List<EmulatorSnapshot>>(emptyList()) }
    var status by remember { mutableStateOf("Select an AVD") }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var savingSnapshot by remember { mutableStateOf(false) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var renameSnapshotTarget by remember { mutableStateOf<EmulatorSnapshot?>(null) }

    fun refresh() {
        scope.launch {
            avds = avd.listVirtualDevices()
            selectedAvd = selectedAvd?.let { current -> avds.firstOrNull { it.name == current.name } } ?: avds.firstOrNull()
            selectedAvd?.let {
                snapshots = avd.listSnapshots(it.name)
                status = "${snapshots.size} snapshots for ${it.name}"
            }
        }
    }

    fun refreshSnapshots(target: VirtualDevice?, updateStatus: Boolean = true) {
        if (target == null) return
        scope.launch {
            snapshots = avd.listSnapshots(target.name)
            if (updateStatus) status = "${snapshots.size} snapshots for ${target.name}"
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val target = selectedAvd
        val isRunning = target?.running == true
        Toolbar(
            title = "Snapshots",
            subtitle = status,
            onPrimary = if (isRunning && !savingSnapshot) { { showSaveDialog = true } } else null,
            primaryLabel = "+ Save snapshot"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            avds.forEach { row ->
                FilterPill(row.name, selectedAvd?.name == row.name, if (row.running) Green else Rust) {
                    selectedAvd = row
                    refreshSnapshots(row)
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(snapshots) { snapshot ->
                SnapshotCard(
                    snapshot = snapshot,
                    selectedAvd = selectedAvd,
                    avd = avd,
                    scope = scope,
                    onStatusChange = { status = it },
                    onRefresh = { refresh() },
                    onDeleteClick = { snap ->
                        val targetAvd = selectedAvd ?: return@SnapshotCard
                        pendingConfirmation = PendingConfirmation("Delete snapshot?", "${targetAvd.name} / ${snap.name}") {
                            scope.launch {
                                val result = avd.deleteSnapshot(targetAvd.name, snap.name)
                                status = if (result.isSuccess) result.stdout.ifBlank { "Deleted ${snap.name}" } else result.stderr.ifBlank { result.stdout }
                                refreshSnapshots(targetAvd)
                            }
                        }
                    },
                    onRenameClick = { snap ->
                        renameSnapshotTarget = snap
                    }
                )
            }

        }

        pendingConfirmation?.let { confirmation ->
            ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
                pendingConfirmation = null
                confirmation.onConfirm()
            })
        }

        if (showSaveDialog) {
            SaveSnapshotDialog(
                onDismiss = { showSaveDialog = false },
                onSave = { name ->
                    showSaveDialog = false
                    val activeAvd = selectedAvd ?: return@SaveSnapshotDialog
                    scope.launch {
                        savingSnapshot = true
                        status = "Saving snapshot $name..."
                        try {
                            val result = avd.saveSnapshot(activeAvd.name, name)
                            snapshots = avd.listSnapshots(activeAvd.name)
                            status = if (result.isSuccess) {
                                result.stdout.ifBlank { "Saved $name" }
                            } else {
                                result.stderr.ifBlank { result.stdout.ifBlank { "Failed to save $name" } }
                            }
                        } finally {
                            savingSnapshot = false
                        }
                    }
                }
            )
        }

        renameSnapshotTarget?.let { snapshot ->
            RenameSnapshotDialog(
                snapshotName = snapshot.name,
                onDismiss = { renameSnapshotTarget = null },
                onRename = { newName ->
                    renameSnapshotTarget = null
                    val activeAvd = selectedAvd ?: return@RenameSnapshotDialog
                    scope.launch {
                        status = "Renaming snapshot ${snapshot.name} to $newName..."
                        val result = avd.renameSnapshot(activeAvd.name, snapshot.name, newName)
                        status = if (result.isSuccess) result.stdout.ifBlank { "Renamed ${snapshot.name} to $newName" } else result.stderr.ifBlank { result.stdout }
                        refreshSnapshots(activeAvd)
                    }
                }
            )
        }
    }
}

@Composable
private fun SnapshotCard(
    snapshot: EmulatorSnapshot,
    selectedAvd: VirtualDevice?,
    avd: AvdService,
    scope: CoroutineScope,
    onStatusChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onDeleteClick: (EmulatorSnapshot) -> Unit,
    onRenameClick: (EmulatorSnapshot) -> Unit,
) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, snapshot.screenshotPath) {
        value = withContext(Dispatchers.IO) {
            snapshot.screenshotPath?.let { loadImageBitmap(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(AndyRadius.R3))
            .background(Panel, RoundedCornerShape(AndyRadius.R3))
            .clip(RoundedCornerShape(AndyRadius.R3))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { screenshot ->
                Image(
                    bitmap = screenshot,
                    contentDescription = snapshot.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } ?: run {
                Image(
                    painter = painterResource(Res.drawable.hardware_capture),
                    contentDescription = "No screenshot",
                    modifier = Modifier.size(28.dp),
                    colorFilter = ColorFilter.tint(TextSecondary.copy(alpha = 0.4f))
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = snapshot.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onRenameClick(snapshot) },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        drawLine(
                            color = TextSecondary,
                            start = Offset(2.dp.toPx(), 10.dp.toPx()),
                            end = Offset(10.dp.toPx(), 2.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )
                        drawCircle(
                            color = TextSecondary,
                            center = Offset(10.dp.toPx(), 2.dp.toPx()),
                            radius = 1.5.dp.toPx()
                        )
                    }
                }
            }

            val detailsText = listOfNotNull(snapshot.size, snapshot.createdTime).joinToString("  ·  ")
            Text(
                text = detailsText.ifBlank { "--" },
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = MonoFont
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (snapshot.compatible) {
                    Text(
                        text = "Restore",
                        color = Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            val target = selectedAvd ?: return@clickable
                            scope.launch {
                                onStatusChange("Restoring ${snapshot.name}...")
                                val result = avd.restoreSnapshot(target.name, snapshot.name)
                                onStatusChange(if (result.isSuccess) result.stdout.ifBlank { "Restored ${snapshot.name}" } else result.stderr.ifBlank { result.stdout })
                                onRefresh()
                            }
                        }
                    )
                } else {
                    Text(
                        text = "Incompatible",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Text(
                    text = "Delete",
                    color = Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        onDeleteClick(snapshot)
                    }
                )
            }
        }
    }
}

@Composable
private fun SaveStateCard(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(262.dp)
            .border(
                BorderStroke(1.dp, SolidColor(Color.White.copy(alpha = 0.08f))),
                shape = RoundedCornerShape(AndyRadius.R3)
            )
            .background(if (enabled) Panel else Panel.copy(alpha = 0.5f), RoundedCornerShape(AndyRadius.R3))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(24.dp)) {
                val sizePx = size.width
                val strokePx = 2.dp.toPx()
                drawLine(
                    color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.3f),
                    start = Offset(0f, sizePx / 2f),
                    end = Offset(sizePx, sizePx / 2f),
                    strokeWidth = strokePx
                )
                drawLine(
                    color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.3f),
                    start = Offset(sizePx / 2f, 0f),
                    end = Offset(sizePx / 2f, sizePx),
                    strokeWidth = strokePx
                )
            }
            Text(
                text = "Save current state",
                color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.3f),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SaveSnapshotDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf("manual") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Save snapshot", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a name for the new snapshot:", color = TextSecondary, fontSize = 12.sp)
                TextField(
                    text,
                    { text = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                    colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
                colors = primaryButtonColors()
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameSnapshotDialog(
    snapshotName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var text by remember { mutableStateOf(snapshotName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Rename snapshot", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a new name for the snapshot:", color = TextSecondary, fontSize = 12.sp)
                TextField(
                    text,
                    { text = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                    colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(text) },
                enabled = text.isNotBlank() && text != snapshotName,
                colors = primaryButtonColors()
            ) { Text("Rename") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
