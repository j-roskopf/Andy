package app.andy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.andy.ui.shell.BrowserNavCommand
import kotlinx.coroutines.flow.Flow

/** No embedded browser option in the browser target itself — Andy Web has no Browser pane. */
@Composable
internal actual fun BrowserSurface(
    url: String,
    navCommands: Flow<BrowserNavCommand>,
    onNavStateChanged: (title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
    modifier: Modifier,
    chromeColor: Color,
    @Suppress("UNUSED_PARAMETER") bottomCornerRadiusPx: Float,
) {
    Box(modifier.background(chromeColor), contentAlignment = Alignment.Center) {
        Text("Browser pane isn't available in Andy Web", color = Color.White)
    }
}

@Composable
internal actual fun rememberBrowserEngineState(): BrowserEngineState =
    BrowserEngineState.Failed("Browser pane isn't available in Andy Web")

internal actual fun resignEmbeddedBrowserKey() = Unit

internal actual fun closeEmbeddedBrowser() = Unit
