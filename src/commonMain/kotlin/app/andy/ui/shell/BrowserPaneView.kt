package app.andy.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import app.andy.BrowserElementAnnotateEvent
import app.andy.BrowserEngineStage
import app.andy.BrowserEngineState
import app.andy.BrowserSurface
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.browser_select_element
import app.andy.focusEmbeddedBrowser
import app.andy.formatBrowserElementAnnotation
import app.andy.observeBrowserElementAnnotations
import app.andy.rememberBrowserEngineState
import app.andy.resignEmbeddedBrowserKey
import app.andy.setBrowserElementInspectEnabled
import app.andy.service.AndyServices
import app.andy.ui.agents.ChatComposerAttachment
import app.andy.ui.agents.LocalChatComposerInbox
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
import org.jetbrains.compose.resources.painterResource

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
    var inspectEnabled by remember { mutableStateOf(false) }
    var inspectHintVisible by remember { mutableStateOf(false) }
    val composerInbox = LocalChatComposerInbox.current
    val latestInbox = rememberUpdatedState(composerInbox)
    // Starts the (possibly one-time, per-machine) engine download/init as soon as a Browser
    // tab is open, not lazily on first navigation — so its progress is visible immediately
    // and the address bar can stay disabled until it's actually safe to load a page into.
    val engineState = rememberBrowserEngineState()
    val engineReady = engineState is BrowserEngineState.Ready
    val bottomCornerRadiusPx = with(LocalDensity.current) { AndyRadius.Sheet.toPx() }
    val canInspect = engineReady && state.url.isNotBlank()

    DisposableEffect(Unit) {
        val unregister = observeBrowserElementAnnotations { event ->
            when (event) {
                BrowserElementAnnotateEvent.Cancelled -> {
                    inspectEnabled = false
                    setBrowserElementInspectEnabled(false)
                }
                is BrowserElementAnnotateEvent.Submitted -> {
                    inspectEnabled = false
                    setBrowserElementInspectEnabled(false)
                    latestInbox.value.offer(
                        ChatComposerAttachment(
                            imagePaths = listOfNotNull(event.annotation.imagePath),
                            text = formatBrowserElementAnnotation(event.annotation),
                        ),
                    )
                }
            }
        }
        onDispose {
            unregister()
            setBrowserElementInspectEnabled(false)
        }
    }
    LaunchedEffect(canInspect) {
        if (!canInspect) inspectEnabled = false
    }
    LaunchedEffect(inspectEnabled, canInspect, state.loading, state.url) {
        if (inspectEnabled && canInspect && !state.loading) {
            setBrowserElementInspectEnabled(true)
            focusEmbeddedBrowser()
        } else if (!inspectEnabled) {
            setBrowserElementInspectEnabled(false)
        }
    }

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
            BrowserSelectElementButton(
                enabled = canInspect,
                selected = inspectEnabled,
                onHoverChange = { inspectHintVisible = it },
                onClick = {
                    if (!canInspect) return@BrowserSelectElementButton
                    val next = !inspectEnabled
                    inspectEnabled = next
                    if (next) {
                        setBrowserElementInspectEnabled(true)
                        focusEmbeddedBrowser()
                    } else {
                        setBrowserElementInspectEnabled(false)
                        resignEmbeddedBrowserKey()
                    }
                },
            )
        }
        if (inspectHintVisible) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(AndyColors.SurfaceRaised)
                    .padding(start = AndySpace.Space4, end = AndySpace.Space4, bottom = AndySpace.Space2),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    "Select an element to annotate",
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(AndyColors.Neutral900, RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
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

@Composable
private fun BrowserSelectElementButton(
    enabled: Boolean,
    selected: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    DisposableEffect(hovered, enabled) {
        onHoverChange(hovered && enabled)
        onDispose { onHoverChange(false) }
    }
    val fill = when {
        !enabled -> Color.Transparent
        selected || hovered -> if (AndyColors.isLight) {
            Color.Black.copy(alpha = 0.08f)
        } else {
            Color.White.copy(alpha = 0.10f)
        }
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(28.dp)
            .background(fill, RoundedCornerShape(6.dp))
            .semantics {
                contentDescription = "Select an element to annotate"
                role = Role.Button
            }
            .hoverable(interactionSource, enabled = enabled)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.browser_select_element),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            colorFilter = ColorFilter.tint(
                when {
                    !enabled -> TextSecondary.copy(alpha = 0.4f)
                    selected -> TextPrimary
                    else -> TextPrimary.copy(alpha = 0.82f)
                },
            ),
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
        else -> "https://www.google.com/search?q=${encodeBrowserQuery(trimmed)}"
    }
}

/** Percent-encodes address-bar search text so `&`, `#`, `%`, and non-ASCII stay in `q`. */
internal fun encodeBrowserQuery(text: String): String {
    val hex = "0123456789ABCDEF"
    return buildString(text.length + 8) {
        for (byte in text.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            when {
                b == 0x20 -> append('+')
                b in 0x30..0x39 || b in 0x41..0x5A || b in 0x61..0x7A -> append(b.toChar())
                b.toChar() in "-._~" -> append(b.toChar())
                else -> {
                    append('%')
                    append(hex[b shr 4])
                    append(hex[b and 0xF])
                }
            }
        }
    }
}
