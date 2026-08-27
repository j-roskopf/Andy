package app.andy

import androidx.compose.ui.Modifier

expect fun Modifier.onExternalFileDrop(
    enabled: Boolean = true,
    onDrop: (paths: List<String>) -> Unit,
): Modifier
