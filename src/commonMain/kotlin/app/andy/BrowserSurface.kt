package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.andy.ui.shell.BrowserNavCommand
import kotlinx.coroutines.flow.Flow

/**
 * Embedded web page surface for one Browser tab. Desktop (macOS) uses a WKWebView
 * AppKit overlay; other targets render an "unsupported" placeholder. [navCommands]
 * carries Back/Forward/Refresh/GoTo intents in; [onNavStateChanged] reports
 * title/url/back-forward-availability/loading back out so [app.andy.ui.shell.ShellState]
 * can keep the address bar in sync.
 */
@Composable
internal expect fun BrowserSurface(
    url: String,
    navCommands: Flow<BrowserNavCommand>,
    onNavStateChanged: (title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
    modifier: Modifier,
    chromeColor: Color = Color(0xFF171717),
    bottomCornerRadiusPx: Float = 18f,
)

/** Coarse stage of a one-time (per-machine) embedded-browser-engine install/init. */
internal enum class BrowserEngineStage { Locating, Downloading, Extracting, Install, Initializing, Initialized }

internal sealed class BrowserEngineState {
    data object NotStarted : BrowserEngineState()
    data class Initializing(val stage: BrowserEngineStage, val percent: Float?) : BrowserEngineState()
    data object Ready : BrowserEngineState()
    data class Failed(val message: String) : BrowserEngineState()
}

/**
 * Starts the platform browser engine (idempotent) and reports its live readiness so
 * [app.andy.ui.shell.BrowserPaneView] can disable navigation until the surface can render.
 */
@Composable
internal expect fun rememberBrowserEngineState(): BrowserEngineState

/**
 * Hand keyboard focus back to Andy's AWT/Compose window so the address bar and dock tabs
 * can accept input. No-op on targets without a WKWebView overlay.
 */
internal expect fun resignEmbeddedBrowserKey()

/**
 * Tear down the process-wide embedded browser (WKWebView). Call when the last Browser
 * dock tab is closed; hiding a pane or switching tabs must not destroy it or the page
 * reloads on the way back.
 */
internal expect fun closeEmbeddedBrowser()
