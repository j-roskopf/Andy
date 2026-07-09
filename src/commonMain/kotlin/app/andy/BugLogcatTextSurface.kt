package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun BugLogcatTextSurface(
    text: String,
    modifier: Modifier = Modifier,
)
