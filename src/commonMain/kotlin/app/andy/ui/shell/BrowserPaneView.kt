package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.BrowserEngineStage
import app.andy.BrowserEngineState
import app.andy.BrowserSurface
import app.andy.rememberBrowserEngineState
import app.andy.resignEmbeddedBrowserKey
import app.andy.service.AndyServices
import app.andy.ui.components.EmptyState
import app.andy.ui.components.TextField
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Address bar + [BrowserSurface] for one Browser dock tab. Owns the URL text field and a local
 * command bus so Back/Forward/Refresh reach the platform surface directly, while GoTo also
 * bubbles up through [onNav] so [ShellState.browserPanes] stays the source of truth for the
 * persisted URL (needed so tab switching/reopening can restore the address bar
 * without a fresh GoTo).
 */
@Composable
internal fun BrowserPaneView(
    services: AndyServices,
    state: BrowserPaneState,
    onNav: (BrowserNavCommand) -> Unit,
    onNavStateChanged: (title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val commands = remember {
        MutableSharedFlow<BrowserNavCommand>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    var addressText by remember(state.url) { mutableStateOf(state.url) }
    // Starts the (possibly one-time, per-machine) engine download/init as soon as a Browser
    // tab is open, not lazily on first navigation — so its progress is visible immediately
    // and the address bar can stay disabled until it's actually safe to load a page into.
    val engineState = rememberBrowserEngineState()
    val engineReady = engineState is BrowserEngineState.Ready
    val bottomCornerRadiusPx = with(LocalDensity.current) { AndyRadius.Sheet.toPx() }

    fun markLoading(url: String = state.url) {
        onNavStateChanged(state.title, url, state.canGoBack, state.canGoForward, true)
    }

    fun submit() {
        if (!engineReady) return
        val target = normalizeBrowserUrl(addressText)
        if (target.isBlank()) return
        val command = BrowserNavCommand.GoTo(target)
        addressText = target
        markLoading(target)
        onNav(command)
        scope.launch { commands.emit(command) }
    }

    fun navigate(command: BrowserNavCommand) {
        markLoading()
        scope.launch { commands.emit(command) }
    }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(AndyColors.SurfaceRaised)
                .padding(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            BrowserBarGlyphButton(
                glyph = "←",
                enabled = engineReady && state.canGoBack,
                contentDescription = "Back",
                onClick = {
                    resignEmbeddedBrowserKey()
                    navigate(BrowserNavCommand.Back)
                },
            )
            BrowserBarGlyphButton(
                glyph = "→",
                enabled = engineReady && state.canGoForward,
                contentDescription = "Forward",
                onClick = {
                    resignEmbeddedBrowserKey()
                    navigate(BrowserNavCommand.Forward)
                },
            )
            if (state.loading && engineReady) {
                Box(
                    Modifier
                        .size(28.dp)
                        .semantics { contentDescription = "Loading"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Cyan,
                    )
                }
            } else {
                BrowserBarGlyphButton(
                    glyph = "⟳",
                    enabled = engineReady && state.url.isNotBlank(),
                    contentDescription = "Refresh",
                    onClick = {
                        resignEmbeddedBrowserKey()
                        navigate(BrowserNavCommand.Refresh)
                    },
                )
            }
            TextField(
                value = addressText,
                onValueChange = { addressText = it },
                enabled = engineReady,
                singleLine = true,
                placeholder = {
                    Text(
                        if (engineReady) "Search or enter a URL" else engineStatusLabel(engineState),
                        color = TextSecondary,
                        fontFamily = DisplayFont,
                        fontSize = 13.sp,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .onFocusChanged { focus ->
                        // WKWebView's borderless child holds key-window status while you type
                        // in the page; hand it back so Compose can receive address-bar input.
                        if (focus.isFocused) resignEmbeddedBrowserKey()
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(AndyColors.SurfaceRaised),
        ) {
            if (state.loading && engineReady) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = Cyan,
                    trackColor = Color.Transparent,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                engineState is BrowserEngineState.Failed ->
                    EmptyState("Browser engine unavailable: ${engineState.message}")
                !engineReady -> EmptyState(engineStatusLabel(engineState))
                state.url.isBlank() -> EmptyState("Enter a URL to start browsing")
                else -> BrowserSurface(
                    url = state.url,
                    navCommands = commands,
                    onNavStateChanged = onNavStateChanged,
                    modifier = Modifier.fillMaxSize(),
                    chromeColor = AndyColors.SurfaceRaised,
                    bottomCornerRadiusPx = bottomCornerRadiusPx,
                )
            }
        }
    }
}

/** User-facing label for the one-time engine download/init — shown in the address bar
 * placeholder while disabled and as the pane's empty state. */
private fun engineStatusLabel(state: BrowserEngineState): String = when (state) {
    BrowserEngineState.NotStarted -> "Starting browser engine…"
    is BrowserEngineState.Initializing -> {
        val stage = when (state.stage) {
            BrowserEngineStage.Locating -> "Locating browser engine"
            BrowserEngineStage.Downloading -> "Downloading browser engine"
            BrowserEngineStage.Extracting -> "Extracting browser engine"
            BrowserEngineStage.Install -> "Installing browser engine"
            BrowserEngineStage.Initializing -> "Starting browser engine"
            BrowserEngineStage.Initialized -> "Starting browser engine"
        }
        val percent = state.percent
        if (percent != null) "$stage… ${percent.toInt()}%" else "$stage…"
    }
    BrowserEngineState.Ready -> ""
    is BrowserEngineState.Failed -> "Browser engine unavailable"
}

@Composable
private fun BrowserBarGlyphButton(
    glyph: String,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(28.dp)
            .background(Color.Transparent, RoundedCornerShape(6.dp))
            .semantics { this.contentDescription = contentDescription; role = Role.Button }
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.4f),
            fontFamily = MonoFont,
            fontSize = 15.sp,
        )
    }
}

/** Adds a scheme to bare host/search text, same heuristic every address bar uses. */
internal fun normalizeBrowserUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.contains("://")) return trimmed
    val isLocal = trimmed.startsWith("localhost") ||
        trimmed.startsWith("127.") ||
        trimmed.startsWith("[::1]") ||
        trimmed.startsWith("::1")
    val looksLikeUrl = trimmed.contains(".") && !trimmed.contains(" ")
    return when {
        isLocal -> "http://$trimmed"
        looksLikeUrl -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
    }
}
