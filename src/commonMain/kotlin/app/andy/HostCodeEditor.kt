package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun HostCodeEditor(
    path: String,
    text: String,
    languageHint: String,
    modifier: Modifier = Modifier,
    onTextChange: (String, String) -> Unit,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit,
    onSearchAll: () -> Unit = {},
    onSearchNames: () -> Unit = {},
    onSearchContents: () -> Unit = {},
)
