package app.andy.ui.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.AndyServices

@Composable
actual fun ProjectTerminalSurface(services: AndyServices, runId: String, modifier: Modifier) {
    Box(modifier) { Text("Terminal is available in Andy Desktop") }
}
