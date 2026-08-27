package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Receives image files dragged from the desktop when the current platform supports it. */
@Composable
expect fun Modifier.onImageFilesDropped(
    onFiles: (List<String>) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier
