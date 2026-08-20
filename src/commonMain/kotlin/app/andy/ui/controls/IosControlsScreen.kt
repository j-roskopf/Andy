package app.andy.ui.controls

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.andy.loadImageBitmap
import app.andy.service.CommandResult
import app.andy.service.IosDeviceService
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.FormRow
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DynamicTypeSizes = listOf("xSmall", "small", "medium", "large", "xLarge", "xxLarge", "xxxLarge") +
    listOf("accessibilityMedium", "accessibilityLarge", "accessibilityExtraLarge", "accessibilityExtraExtraLarge", "accessibilityExtraExtraExtraLarge")

private val PrivacyServices = listOf(
    "contacts", "location", "photos", "camera", "microphone", "calendar", "reminders", "media-library", "motion", "all",
)

private data class SweepShot(val label: String, val bytes: ByteArray)

/**
 * Simulator controls via `simctl`. Android-only concepts (airplane mode, hinge, GSM, TalkBack)
 * are intentionally absent.
 */
@Composable
internal fun IosControlsScreen(
    serial: String?,
    iosDevices: IosDeviceService,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready") }
    var contentSize by remember { mutableStateOf("medium") }
    var latitude by remember { mutableStateOf("37.3349") }
    var longitude by remember { mutableStateOf("-122.0090") }
    var privacyBundle by remember { mutableStateOf("") }
    var clipboardText by remember { mutableStateOf("") }
    var screenshotBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var pushBundleId by remember { mutableStateOf("") }
    var pushPayload by remember {
        mutableStateOf(
            "{\n  \"aps\": {\n    \"alert\": \"Hello from Andy\",\n    \"sound\": \"default\"\n  }\n}",
        )
    }
    var sweepRunning by remember { mutableStateOf(false) }
    val sweepShots = remember { mutableStateListOf<SweepShot>() }
    var sweepOpen by remember { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> CommandResult) {
        if (serial == null) {
            status = "Select a booted simulator"
            return
        }
        scope.launch {
            val result = block()
            status = if (result.isSuccess) {
                result.stdout.ifBlank { label }
            } else {
                result.stderr.ifBlank { result.stdout }.ifBlank { "$label failed" }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PanelCard {
            Text("Appearance", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    run("Light") { iosDevices.simctl(listOf("ui", serial!!, "appearance", "light")) }
                }) { Text("Light") }
                Button(onClick = {
                    run("Dark") { iosDevices.simctl(listOf("ui", serial!!, "appearance", "dark")) }
                }) { Text("Dark") }
                Button(onClick = {
                    run("Increase contrast") {
                        iosDevices.simctl(listOf("ui", serial!!, "increase_contrast", "enabled"))
                    }
                }) { Text("Increase contrast") }
            }
        }
        PanelCard {
            Text("Dynamic Type", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("xSmall", "small", "medium", "large", "xLarge", "xxLarge", "xxxLarge").forEach { size ->
                    FilterPill(size, size.equals(contentSize, ignoreCase = true), Rust) {
                        contentSize = size
                        run("Font $size") { iosDevices.simctl(listOf("ui", serial!!, "content_size", size)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "accessibilityMedium", "accessibilityLarge", "accessibilityExtraLarge",
                    "accessibilityExtraExtraLarge", "accessibilityExtraExtraExtraLarge",
                ).forEach { size ->
                    FilterPill(size.removePrefix("accessibility"), size == contentSize, Rust) {
                        contentSize = size
                        run("Font $size") { iosDevices.simctl(listOf("ui", serial!!, "content_size", size)) }
                    }
                }
            }
            Text(
                "Dynamic Type sweep captures a screenshot at every size and shows them side by side.",
                color = TextSecondary,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = serial != null && !sweepRunning,
                    onClick = {
                        val target = serial ?: return@Button
                        sweepRunning = true
                        sweepShots.clear()
                        scope.launch {
                            val originalSize = contentSize
                            for (size in DynamicTypeSizes) {
                                iosDevices.simctl(listOf("ui", target, "content_size", size))
                                delay(350)
                                val bytes = iosDevices.captureScreenshot(target)
                                if (bytes != null) sweepShots += SweepShot(size, bytes)
                            }
                            iosDevices.simctl(listOf("ui", target, "content_size", originalSize))
                            sweepRunning = false
                            sweepOpen = sweepShots.isNotEmpty()
                            status = if (sweepShots.isNotEmpty()) "Captured ${sweepShots.size} sizes" else "Sweep failed"
                        }
                    },
                ) { Text(if (sweepRunning) "Sweeping…" else "Run sweep") }
                if (sweepShots.isNotEmpty()) {
                    OutlinedButton(onClick = { sweepOpen = true }) { Text("View board (${sweepShots.size})") }
                }
            }
        }
        PanelCard {
            Text("Status bar & screenshot studio", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    run("Status bar") {
                        iosDevices.simctl(
                            listOf(
                                "status_bar", serial!!, "override",
                                "--time", "9:41",
                                "--batteryLevel", "100",
                                "--batteryState", "charged",
                                "--cellularBars", "4",
                                "--wifiBars", "3",
                            ),
                        )
                    }
                }) { Text("Studio override") }
                Button(
                    enabled = serial != null,
                    onClick = {
                        val target = serial ?: return@Button
                        scope.launch {
                            val bytes = iosDevices.captureScreenshot(target)
                            screenshotBitmap = bytes?.let { runCatching { loadImageBitmap(it) }.getOrNull() }
                            status = if (bytes != null) "Captured studio screenshot" else "Screenshot failed"
                        }
                    },
                ) { Text("Screenshot") }
                Button(onClick = {
                    run("Clear status bar") { iosDevices.simctl(listOf("status_bar", serial!!, "clear")) }
                    screenshotBitmap = null
                }) { Text("Clear") }
            }
            screenshotBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Studio screenshot",
                    modifier = Modifier.height(240.dp),
                )
            }
        }
        PanelCard {
            Text("Location", color = TextPrimary, fontWeight = FontWeight.Bold)
            FormRow("Latitude") {
                TextField(latitude, { latitude = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            }
            FormRow("Longitude") {
                TextField(longitude, { longitude = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            }
            Button(onClick = {
                run("Set location") {
                    iosDevices.simctl(listOf("location", serial!!, "set", "$latitude,$longitude"))
                }
            }) { Text("Set location") }
        }
        PanelCard {
            Text("Privacy matrix", color = TextPrimary, fontWeight = FontWeight.Bold)
            FormRow("Bundle ID") {
                TextField(privacyBundle, { privacyBundle = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Service", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.width(140.dp))
                    Text("Actions", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                }
                PrivacyServices.forEach { service ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(service, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(140.dp))
                        Button(onClick = {
                            run("Grant $service") {
                                iosDevices.simctl(listOf("privacy", serial!!, "grant", service, privacyBundle))
                            }
                        }) { Text("Grant") }
                        Button(onClick = {
                            run("Revoke $service") {
                                iosDevices.simctl(listOf("privacy", serial!!, "revoke", service, privacyBundle))
                            }
                        }) { Text("Revoke") }
                        Button(onClick = {
                            run("Reset $service") {
                                iosDevices.simctl(listOf("privacy", serial!!, "reset", service, privacyBundle))
                            }
                        }) { Text("Reset") }
                    }
                }
            }
        }
        PanelCard {
            Text("Push workbench", color = TextPrimary, fontWeight = FontWeight.Bold)
            FormRow("Bundle ID") {
                TextField(pushBundleId, { pushBundleId = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            }
            Text("Payload (APNs JSON)", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
            TextField(
                pushPayload,
                { pushPayload = it },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                colors = fieldColors(),
            )
            Button(
                enabled = serial != null && pushBundleId.isNotBlank(),
                onClick = {
                    val target = serial ?: return@Button
                    scope.launch {
                        val result = iosDevices.push(target, pushBundleId, pushPayload)
                        status = if (result.isSuccess) {
                            result.stdout.ifBlank { "Push sent" }
                        } else {
                            result.stderr.ifBlank { result.stdout }.ifBlank { "Push failed" }
                        }
                    }
                },
            ) { Text("Push") }
        }
        PanelCard {
            Text("Clipboard", color = TextPrimary, fontWeight = FontWeight.Bold)
            FormRow("Text") {
                TextField(clipboardText, { clipboardText = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    run("Copy") { iosDevices.simctl(listOf("pbcopy", serial!!, clipboardText)) }
                }) { Text("pbcopy") }
                Button(onClick = {
                    run("Paste") {
                        val result = iosDevices.simctl(listOf("pbpaste", serial!!))
                        if (result.isSuccess) clipboardText = result.stdout.trim()
                        result
                    }
                }) { Text("pbpaste") }
            }
        }
        PanelCard {
            Text("Status", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                status,
                color = if (status.contains("fail", ignoreCase = true)) Rust else Green,
                fontFamily = FontFamily.Monospace,
            )
            if (serial == null) {
                Text("Select a booted iOS simulator.", color = TextSecondary)
            }
        }
    }

    if (sweepOpen && sweepShots.isNotEmpty()) {
        DynamicTypeSweepDialog(sweepShots.toList(), onDismiss = { sweepOpen = false })
    }
}

@Composable
private fun DynamicTypeSweepDialog(shots: List<SweepShot>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PanelCard(
            modifier = Modifier.width(720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Dynamic Type board", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shots) { shot ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val bitmap = remember(shot.bytes) { runCatching { loadImageBitmap(shot.bytes) }.getOrNull() }
                        Text(shot.label, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                        if (bitmap != null) {
                            Image(bitmap = bitmap, contentDescription = shot.label, modifier = Modifier.height(320.dp))
                        } else {
                            Text("No preview", color = TextSecondary, modifier = Modifier.size(160.dp, 320.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
