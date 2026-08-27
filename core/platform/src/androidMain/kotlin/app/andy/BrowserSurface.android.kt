package app.andy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow

@Composable
actual fun BrowserSurface(
    url: String,
    navCommands: Flow<BrowserNavCommand>,
    onNavStateChanged: (title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
    modifier: Modifier,
    chromeColor: Color,
    bottomCornerRadiusPx: Float,
) {
    Box(modifier.background(chromeColor), contentAlignment = Alignment.Center) {
        Text("Embedded browser isn't available on Android yet", color = Color.White)
    }
}

@Composable
actual fun rememberBrowserEngineState(): BrowserEngineState =
    BrowserEngineState.Failed("Embedded browser isn't available on Android yet")

actual fun resignEmbeddedBrowserKey() = Unit

actual fun focusEmbeddedBrowser() = Unit

actual fun closeEmbeddedBrowser() = Unit
