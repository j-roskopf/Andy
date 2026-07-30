package app.andy.inspectordemo

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Playground for Andy's Inspector and Logcat crash panel: nested Compose, mixed semantics,
 * overlapping regions, traditional Android views, dialogs, invisible nodes, and crash triggers.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    InspectorDemoScreen()
                }
            }
        }
    }
}

@Composable
private fun InspectorDemoScreen() {
    var counter by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("Andy") }
    var showOverlay by remember { mutableStateOf(true) }
    var showInvisibleNodes by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showAnrWarning by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Inspector demo",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { contentDescription = "Screen title" },
        )
        Text(
            "Exercise Andy Inspector (capture, inspect clicks, diff, layers) and Logcat → Crashes " +
                "(Java/Kotlin crash, ANR, tagged ERROR logs). Package: app.andy.inspectordemo",
            style = MaterialTheme.typography.bodyMedium,
        )

        DemoSectionCard(title = "Compose hierarchy") {
            ProfileCard(name = name, counter = counter, onNameChange = { name = it })
            OverlappingLayersRegion(showOverlay = showOverlay)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { counter++ }) { Text("Increment ($counter)") }
                Button(onClick = { showOverlay = !showOverlay }) {
                    Text(if (showOverlay) "Hide overlay" else "Show overlay")
                }
            }
            repeat(3) { index ->
                DemoListRow(
                    title = "List item ${index + 1}",
                    subtitle = "Row ${index + 1} · bounds and text should be easy to spot in the tree",
                )
            }
        }

        DemoSectionCard(title = "Traditional Android views") {
            Text(
                "Embedded XML inside Compose — toggle Unmerged view tree in Inspector to see resource ids.",
                style = MaterialTheme.typography.bodySmall,
            )
            AndroidView(
                factory = { context ->
                    LayoutInflater.from(context).inflate(R.layout.embedded_traditional, null, false)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val context = LocalContext.current
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(context, TraditionalHierarchyActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open full XML activity")
            }
        }

        DemoSectionCard(title = "Window layers & dialogs") {
            Text(
                "Open a dialog, then capture hierarchy and switch to the Layers tab to see z-order.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { showDialog = true }) { Text("Show dialog") }
        }

        DemoSectionCard(title = "Invisible nodes") {
            Text(
                "Enable Include invisible in Inspector, then toggle hidden nodes below.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { showInvisibleNodes = !showInvisibleNodes }) {
                Text(if (showInvisibleNodes) "Hide invisible nodes" else "Show invisible nodes")
            }
            if (showInvisibleNodes) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .alpha(0f)
                        .background(Color(0xFFB39DDB), RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Alpha-zero box (invisible)" },
                )
                Text(
                    "Alpha-zero box above (invisible to user)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Color(0xFF90CAF9), RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Off-screen placeholder" },
                ) {
                    Text(
                        "Off-screen label",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = (-40).dp),
                    )
                }
            }
        }

        DemoSectionCard(
            title = "Crash & log diagnostics",
            containerColor = Color(0xFFFFEBEE),
        ) {
            Text(
                "After a crash, reopen Andy → Logcat → Crashes and Refresh. ANR blocks the UI ~20s.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Diagnostics.logSampleError() }) { Text("Log ERROR") }
                OutlinedButton(onClick = { Diagnostics.triggerJavaCrash() }) { Text("Java crash") }
                OutlinedButton(onClick = { Diagnostics.triggerKotlinCrash() }) { Text("Kotlin crash") }
            }
            Button(
                onClick = { showAnrWarning = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
            ) {
                Text("Trigger ANR")
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Layer test dialog") },
            text = {
                Text("Capture hierarchy while this dialog is open to inspect window z-order in Layers.")
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Dismiss") }
            },
        )
    }

    if (showAnrWarning) {
        AlertDialog(
            onDismissRequest = { showAnrWarning = false },
            title = { Text("Trigger ANR?") },
            text = {
                Text("The main thread will sleep for 20 seconds. Expect the system ANR dialog. Tap Wait if you want the trace written.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAnrWarning = false
                        Diagnostics.triggerAnr()
                    },
                ) {
                    Text("Sleep 20s")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAnrWarning = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DemoSectionCard(
    title: String,
    containerColor: Color = Color(0xFFF5F5F5),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ProfileCard(name: String, counter: Int, onNameChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FE)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Profile card", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Display name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6750A4))
                        .semantics { contentDescription = "Avatar placeholder" },
                )
                Column {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text("Tap counter: $counter", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OverlappingLayersRegion(showOverlay: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFFF3E0))
            .border(2.dp, Color(0xFFFFB74D), RoundedCornerShape(16.dp))
            .semantics { contentDescription = "Overlapping layers region" },
    ) {
        Text(
            "Background layer",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )
        if (showOverlay) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Floating card", fontWeight = FontWeight.SemiBold)
                    Text("Sits above the orange panel")
                }
            }
        }
    }
}

@Composable
private fun DemoListRow(title: String, subtitle: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(Color(0xFF80CBC4), RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
        }
    }
}
