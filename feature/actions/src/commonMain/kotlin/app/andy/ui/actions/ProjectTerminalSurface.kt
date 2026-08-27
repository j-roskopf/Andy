package app.andy.ui.actions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.AndyServices

@Composable
expect fun ProjectTerminalSurface(
    services: AndyServices,
    runId: String,
    modifier: Modifier = Modifier,
)
