package com.joetr.andy.mobile.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.SavedHost
import com.joetr.andy.mobile.data.vnc.Keysym
import com.joetr.andy.mobile.data.vnc.RfbClient
import com.joetr.andy.mobile.data.vnc.VncConnectionState
import com.joetr.andy.mobile.data.vnc.VncStreamQuality
import com.joetr.andy.mobile.data.vnc.diffText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max

private const val BUTTON_LEFT = 1
private const val BUTTON_RIGHT = 4
private const val WHEEL_UP = 8
private const val WHEEL_DOWN = 16

/** View pixels of two-finger travel per wheel click. */
private const val SCROLL_STEP = 56f

/** Pinch ceiling — high enough that ~12–24px remote controls become finger-sized. */
private const val MAX_ZOOM = 20f

private data class ViewTransform(val viewport: IntSize, val zoom: Float, val pan: Offset)

private enum class Gesture { Tap, LongPress, Pan, Multi }

@Composable
fun ScreenViewerScreen(
    host: SavedHost?,
    repository: HostRepository,
    onNeedHost: () -> Unit,
    fullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOpen: Boolean = false,
    onKeyboardOpenChange: (Boolean) -> Unit = {},
    rfbClient: RfbClient? = null,
    presenter: RemoteFramePresenter? = null,
) {
    val tokens = andyTokens()
    var internalKeyboardOpen by remember { mutableStateOf(false) }
    val isKeyboardOpen = keyboardOpen || internalKeyboardOpen
    val setKeyboardOpen: (Boolean) -> Unit = { open ->
        internalKeyboardOpen = open
        onKeyboardOpenChange(open)
    }

    BackHandler(enabled = isKeyboardOpen) {
        setKeyboardOpen(false)
    }

    if (host == null) {
        EmptyState(
            title = "No host selected",
            description = "Add a laptop on Hosts, then open its screen from here.",
            actions = {
                Button(onClick = onNeedHost) { Text("Go to Hosts") }
            },
            modifier = modifier
                .fillMaxSize()
                .padding(AndySpace.Space6),
        )
        return
    }

    val rememberedClient = remember { RfbClient() }
    val rememberedPresenter = remember { RemoteFramePresenter() }
    val client = rfbClient ?: rememberedClient
    val framePresenter = presenter ?: rememberedPresenter
    val scope = rememberCoroutineScope()
    val state by client.state.collectAsStateWithLifecycle()
    val framebuffer by client.framebuffer.collectAsStateWithLifecycle()

    // Transform state is deliberately never read at composable scope: the gesture handler
    // and the draw lambda read it directly, so pan/zoom/frames stay in the draw phase.
    val zoomState = remember { mutableFloatStateOf(1f) }
    val panState = remember { mutableStateOf(Offset.Zero) }
    val viewportState = remember { mutableStateOf(IntSize.Zero) }

    var selectedDisplay by remember { mutableStateOf<Int?>(0) }
    var keyboardText by remember { mutableStateOf("") }
    var ctrlHeld by remember { mutableStateOf(false) }
    var bufferMode by remember { mutableStateOf(repository.vncKeyboardBufferMode()) }
    var streamQuality by remember { mutableStateOf(repository.vncStreamQuality()) }
    val focusRequester = remember { FocusRequester() }
    val softKeyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current

    // ViewModel-owned clients outlive this composable across tab switches. Closing them here
    // called Inflater.end(), so returning to Screen failed with "Inflater has been closed".
    // Disconnect releases the socket; LaunchedEffect reconnects on re-entry.
    DisposableEffect(client, rfbClient) {
        onDispose {
            if (rfbClient == null) {
                client.close()
            } else {
                client.disconnect()
            }
        }
    }

    suspend fun connectWithQuality(quality: VncStreamQuality) {
        framePresenter.clear()
        val password = repository.vncPassword(host.id)
        client.connect(
            host = host.vncHost(),
            port = host.vncPort,
            password = password,
            username = host.vncUsername,
            quality = quality,
        )
    }

    LaunchedEffect(host.id) {
        zoomState.floatValue = 1f
        panState.value = Offset.Zero
        selectedDisplay = 0
        streamQuality = repository.vncStreamQuality()
        runCatching { connectWithQuality(streamQuality) }
    }

    val deskW = framebuffer?.width ?: 0
    val deskH = framebuffer?.height ?: 0
    val displayCount = inferDisplayCount(deskW, deskH)

    // Clamp the selection when the desktop layout changes under us.
    LaunchedEffect(displayCount) {
        val current = selectedDisplay
        selectedDisplay = when {
            displayCount <= 1 -> null
            current == null -> 0
            else -> current.coerceIn(0, displayCount - 1)
        }
    }

    val crop = remember(deskW, deskH, selectedDisplay) { displayCrop(deskW, deskH, selectedDisplay) }

    // Resample whenever a frame lands or the view transform moves. `conflate` collapses
    // bursts so a slow sample never queues work behind itself.
    LaunchedEffect(client, framePresenter, crop) {
        combine(
            client.framebuffer,
            client.frameVersion,
            snapshotFlow { ViewTransform(viewportState.value, zoomState.floatValue, panState.value) },
        ) { fb, _, transform -> fb to transform }
            .conflate()
            .collect { (fb, transform) ->
                if (fb == null) {
                    framePresenter.clear()
                    return@collect
                }
                val mapping = computeMapping(transform.viewport, crop, transform.zoom, transform.pan)
                withContext(Dispatchers.Default) {
                    framePresenter.present(fb, mapping, transform.viewport)
                }
            }
    }

    // Immersive only while the soft keyboard is down — otherwise the IME insets fight
    // the hidden system bars and the input row ends up under the keyboard.
    val immersive = fullScreen && !isKeyboardOpen
    LaunchedEffect(immersive) {
        val window = view.findActivityWindowOrNull() ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, view)
        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = view.findActivityWindowOrNull() ?: return@onDispose
            WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(isKeyboardOpen) {
        if (isKeyboardOpen) {
            delay(40)
            runCatching {
                focusRequester.requestFocus()
                softKeyboard?.show()
            }
        } else {
            ctrlHeld = false
            softKeyboard?.hide()
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        if (ctrlHeld) {
            client.postKey(true, Keysym.ControlL)
            client.postText(text)
            client.postKey(false, Keysym.ControlL)
            ctrlHeld = false
        } else {
            client.postText(text)
        }
    }

    fun sendKeyTap(keysym: Int) {
        if (ctrlHeld) {
            client.postKey(true, Keysym.ControlL)
            client.postKeyTap(keysym)
            client.postKey(false, Keysym.ControlL)
            ctrlHeld = false
        } else {
            client.postKeyTap(keysym)
        }
    }

    fun sendBufferedText() {
        if (keyboardText.isEmpty()) return
        val textToSend = keyboardText
        keyboardText = ""
        sendText(textToSend)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (!fullScreen && !isKeyboardOpen) {
            ViewerHeader(
                host = host,
                state = state,
                crop = crop,
                selectedDisplay = selectedDisplay,
                fullScreen = fullScreen,
                keyboardOpen = isKeyboardOpen,
                onToggleFullScreen = { onFullScreenChange(!fullScreen) },
                onToggleKeyboard = { setKeyboardOpen(!isKeyboardOpen) },
                onReconnect = {
                    scope.launch {
                        runCatching { connectWithQuality(streamQuality) }
                    }
                },
            )

            if (state is VncConnectionState.Connected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.palette.sidebarBg.copy(alpha = 0.94f))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space1),
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Quality",
                        color = tokens.palette.textTertiary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    VncStreamQuality.entries.forEach { quality ->
                        DisplayChip(
                            label = quality.label,
                            selected = streamQuality == quality,
                            onClick = {
                                if (streamQuality == quality) return@DisplayChip
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                streamQuality = quality
                                repository.saveVncStreamQuality(quality)
                                scope.launch { runCatching { connectWithQuality(quality) } }
                            },
                        )
                    }
                    if (displayCount > 1) {
                        Spacer(Modifier.width(AndySpace.Space2))
                        DisplayChip(
                            label = "All",
                            selected = selectedDisplay == null,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedDisplay = null
                                zoomState.floatValue = 1f
                                panState.value = Offset.Zero
                            },
                        )
                        repeat(displayCount) { index ->
                            DisplayChip(
                                label = "Display ${index + 1}",
                                selected = selectedDisplay == index,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedDisplay = index
                                    zoomState.floatValue = 1f
                                    panState.value = Offset.Zero
                                },
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Without this the zoomed image paints over the chrome and the bottom nav.
                .clipToBounds()
                .onSizeChanged { newSize ->
                    val old = viewportState.value
                    val zoom = zoomState.floatValue
                    if (old.width > 0 && old.height > 0 &&
                        (old.width != newSize.width || old.height != newSize.height)
                    ) {
                        // Keyboard / chrome / immersive bars resize the viewport. Cover scale
                        // tracks the new size, so re-zoom to keep remote pixels the same
                        // on-screen size, and keep the old centre remote pixel centred.
                        val remote = computeMapping(old, crop, zoom, panState.value)
                            .toRemoteClamped(old.width / 2f, old.height / 2f)
                        val newZoom = zoomPreservingAbsoluteScale(
                            oldViewport = old,
                            newViewport = newSize,
                            crop = crop,
                            oldZoom = zoom,
                            minZoom = 1f,
                            maxZoom = MAX_ZOOM,
                        )
                        viewportState.value = newSize
                        zoomState.floatValue = newZoom
                        if (remote != null) {
                            panState.value = panShowingRemoteAt(
                                viewport = newSize,
                                crop = crop,
                                zoom = newZoom,
                                remoteX = remote.first,
                                remoteY = remote.second,
                                viewX = newSize.width / 2f,
                                viewY = newSize.height / 2f,
                            )
                        } else {
                            panState.value = clampPan(newSize, crop, newZoom, panState.value)
                        }
                    } else {
                        viewportState.value = newSize
                    }
                }
                .pointerInput(crop) {
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        handleViewerGesture(
                            client = client,
                            crop = crop,
                            viewportState = viewportState,
                            zoomState = zoomState,
                            panState = panState,
                            touchSlop = touchSlop,
                            longPressMs = longPressMs,
                            haptics = haptics,
                            view = view,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (val current = state) {
                is VncConnectionState.Connecting -> CircularProgressIndicator(color = tokens.accent)
                is VncConnectionState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(AndySpace.Space5),
                    ) {
                        Text(current.message, color = tokens.error)
                        Spacer(Modifier.height(AndySpace.Space3))
                        Text(
                            "Check Tailscale, Screen Sharing, and the VNC password under Hosts → Edit.",
                            color = tokens.palette.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                else -> {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val presented = framePresenter.frame.value ?: return@Canvas
                        val mapping = computeMapping(
                            viewportState.value,
                            crop,
                            zoomState.floatValue,
                            panState.value,
                        )
                        drawPresentedFrame(presented, mapping)
                    }
                }
            }

            if (!fullScreen && !isKeyboardOpen) {
                Text(
                    "Pan · Tap click · Hold right-click · Hold-drag mouse · Pinch zoom",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(AndySpace.Space3)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.palette.sidebarBg.copy(alpha = 0.78f))
                        .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space1),
                    color = tokens.palette.textTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (fullScreen && !isKeyboardOpen) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(AndySpace.Space3)
                        .clip(AndyShape.Interactive)
                        .background(tokens.palette.sidebarBg.copy(alpha = 0.85f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = { setKeyboardOpen(!isKeyboardOpen) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Keyboard,
                            contentDescription = if (isKeyboardOpen) "Hide keyboard" else "Show keyboard",
                            tint = if (isKeyboardOpen) tokens.accent else tokens.palette.textPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { onFullScreenChange(false) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Outlined.FullscreenExit,
                            contentDescription = "Exit full screen",
                            tint = tokens.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        if (isKeyboardOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.palette.sidebarBg)
                    // Lift the whole input block above the IME (and the gesture bar when
                    // the keyboard is closed) — `union` takes the larger of the two.
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                ModifierKeyRow(
                    ctrlHeld = ctrlHeld,
                    bufferMode = bufferMode,
                    onCtrlToggle = { ctrlHeld = !ctrlHeld },
                    onToggleBufferMode = {
                        val next = !bufferMode
                        bufferMode = next
                        repository.saveVncKeyboardBufferMode(next)
                    },
                    onKey = ::sendKeyTap,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                ) {
                    BasicTextField(
                        value = keyboardText,
                        onValueChange = { next ->
                            if (bufferMode) {
                                keyboardText = next
                            } else {
                                val edit = diffText(keyboardText, next)
                                keyboardText = next
                                if (!edit.isEmpty) {
                                    if (edit.backspaces > 0) {
                                        repeat(edit.backspaces) { client.postKeyTap(Keysym.BackSpace) }
                                    }
                                    sendText(edit.insert)
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(AndyShape.Interactive)
                            .background(tokens.palette.surfaceRaised)
                            .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val native = event.nativeKeyEvent
                                if (native.unicodeChar != 0) return@onPreviewKeyEvent false
                                val keysym = androidKeyToKeysym(native.keyCode)
                                    ?: return@onPreviewKeyEvent false
                                sendKeyTap(keysym)
                                true
                            },
                        textStyle = TextStyle(
                            color = tokens.palette.textPrimary,
                            fontSize = 16.sp,
                        ),
                        singleLine = false,
                        maxLines = 4,
                        // In live mode, password type disables autocorrect and composing
                        // regions so keystrokes diff cleanly. In buffer mode, standard text
                        // input with autocorrect and suggestions is enabled.
                        keyboardOptions = if (bufferMode) {
                            KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                autoCorrectEnabled = true,
                                imeAction = ImeAction.Send,
                            )
                        } else {
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Send,
                            )
                        },
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (bufferMode) {
                                    sendBufferedText()
                                } else {
                                    sendKeyTap(Keysym.Return)
                                }
                            },
                        ),
                        decorationBox = { inner ->
                            if (keyboardText.isEmpty()) {
                                Text(
                                    if (bufferMode) "Type message to send all at once…" else "Type to send keys…",
                                    color = tokens.palette.textTertiary,
                                )
                            }
                            inner()
                        },
                    )
                    if (bufferMode) {
                        FilledTonalButton(
                            onClick = ::sendBufferedText,
                            enabled = keyboardText.isNotEmpty(),
                            shape = AndyShape.Interactive,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = tokens.accent,
                                contentColor = Color.White,
                                disabledContainerColor = tokens.palette.surfaceRaised,
                                disabledContentColor = tokens.palette.textDisabled,
                            ),
                            contentPadding = PaddingValues(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Send")
                        }
                    }
                    TextButton(onClick = { keyboardText = "" }, enabled = keyboardText.isNotEmpty()) { Text("Clear") }
                    TextButton(onClick = { setKeyboardOpen(false) }) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun ViewerHeader(
    host: SavedHost,
    state: VncConnectionState,
    crop: DisplayCrop,
    selectedDisplay: Int?,
    fullScreen: Boolean,
    keyboardOpen: Boolean,
    onToggleFullScreen: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onReconnect: () -> Unit,
) {
    val tokens = andyTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.palette.sidebarBg.copy(alpha = 0.94f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    host.displayName,
                    color = tokens.palette.textPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (state) {
                        is VncConnectionState.Connected -> {
                            val label = selectedDisplay?.let { "Display ${it + 1}" } ?: "All displays"
                            "$label · ${crop.width}×${crop.height}"
                        }
                        is VncConnectionState.Connecting -> "Connecting to ${state.target}…"
                        is VncConnectionState.Error -> state.message
                        VncConnectionState.Disconnected -> "Disconnected"
                    },
                    color = tokens.palette.textTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
            IconButton(onClick = onToggleKeyboard, modifier = Modifier.height(48.dp)) {
                Icon(
                    Icons.Outlined.Keyboard,
                    contentDescription = if (keyboardOpen) "Hide keyboard" else "Show keyboard",
                    tint = if (keyboardOpen) tokens.accent else tokens.palette.textPrimary,
                )
            }
            IconButton(onClick = onToggleFullScreen, modifier = Modifier.height(48.dp)) {
                Icon(
                    if (fullScreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = if (fullScreen) "Exit full screen" else "Enter full screen",
                    tint = if (fullScreen) tokens.accent else tokens.palette.textPrimary,
                )
            }
            IconButton(onClick = onReconnect, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Reconnect", tint = tokens.palette.textPrimary)
            }
        }
        AndyHorizontalDivider(color = tokens.palette.border)
    }
}

@Composable
private fun ModifierKeyRow(
    ctrlHeld: Boolean,
    bufferMode: Boolean,
    onCtrlToggle: () -> Unit,
    onToggleBufferMode: () -> Unit,
    onKey: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space1),
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap(
            label = if (bufferMode) "Buffer mode" else "Live mode",
            active = bufferMode,
            onClick = onToggleBufferMode,
        )
        // Sticky ctrl: tap it, then the next key or typed character is chorded.
        KeyCap("ctrl", active = ctrlHeld, onClick = onCtrlToggle)
        KeyCap("esc") { onKey(Keysym.Escape) }
        KeyCap("tab") { onKey(Keysym.Tab) }
        KeyCap("enter") { onKey(Keysym.Return) }
        KeyCap("↑") { onKey(Keysym.Up) }
        KeyCap("↓") { onKey(Keysym.Down) }
        KeyCap("←") { onKey(Keysym.Left) }
        KeyCap("→") { onKey(Keysym.Right) }
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean = false, onClick: () -> Unit) {
    val tokens = andyTokens()
    Text(
        text = label,
        color = if (active) tokens.palette.textPrimary else tokens.palette.textSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(AndyShape.Interactive)
            .background(if (active) tokens.accentSubtle else tokens.palette.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
    )
}

@Composable
private fun DisplayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = andyTokens()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = AndyShape.Interactive,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = tokens.accentSubtle,
            selectedLabelColor = tokens.palette.textPrimary,
            containerColor = tokens.palette.surfaceRaised,
            labelColor = tokens.palette.textSecondary,
        ),
    )
}

/**
 * One touch gesture, start to finish.
 *
 * Navigate-first: one-finger drag pans when the image overflows; tap left-clicks;
 * long-press + release right-clicks; long-press then drag arms a mouse click-drag
 * (haptic on arm). Two fingers pinch-zoom and pan, or scroll the wheel when nothing
 * overflows.
 *
 * Every event goes to [RfbClient.postPointer], which is a plain ordered queue. The
 * previous code launched a coroutine per event, so button-down could reach the socket
 * after the motion that followed it and drags never registered on the host.
 */
@Suppress("LongParameterList")
private suspend fun AwaitPointerEventScope.handleViewerGesture(
    client: RfbClient,
    crop: DisplayCrop,
    viewportState: MutableState<IntSize>,
    zoomState: MutableFloatState,
    panState: MutableState<Offset>,
    touchSlop: Float,
    longPressMs: Long,
    haptics: HapticFeedback,
    view: android.view.View,
) {
    fun mapping() = computeMapping(viewportState.value, crop, zoomState.floatValue, panState.value)
    fun applyPanDelta(delta: Offset) {
        if (!canPan(viewportState.value, crop, zoomState.floatValue)) return
        panState.value = clampPan(
            viewportState.value,
            crop,
            zoomState.floatValue,
            panState.value + delta,
        )
    }

    val down = awaitFirstDown(requireUnconsumed = false)
    var lastPos = down.position

    val classification = withTimeoutOrNull(longPressMs) {
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            when {
                pressed.size >= 2 -> return@withTimeoutOrNull Gesture.Multi
                pressed.isEmpty() -> {
                    event.changes.firstOrNull()?.let { lastPos = it.position }
                    return@withTimeoutOrNull Gesture.Tap
                }
                else -> {
                    lastPos = pressed.first().position
                    if ((lastPos - down.position).getDistance() > touchSlop) {
                        return@withTimeoutOrNull Gesture.Pan
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Gesture.Tap
    } ?: Gesture.LongPress

    if (classification == Gesture.Tap) {
        mapping().toRemote(lastPos.x, lastPos.y)?.let { (x, y) ->
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            client.postPointer(x, y, BUTTON_LEFT)
            client.postPointer(x, y, 0)
        }
        return
    }

    var mode = classification
    var mouseDown = false
    var longPressArmed = false
    val pinch = PinchState()

    when (mode) {
        Gesture.LongPress -> {
            // View API — Compose LocalHapticFeedback often no-ops for LongPress during
            // pointerInput coroutines; this is the "armed for right-click / hold-drag" cue.
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            longPressArmed = true
            lastPos = down.position
        }
        Gesture.Pan -> {
            applyPanDelta(lastPos - down.position)
        }
        else -> Unit
    }

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) {
            if (longPressArmed && !mouseDown) {
                event.changes.firstOrNull()?.let { lastPos = it.position }
                mapping().toRemote(lastPos.x, lastPos.y)?.let { (x, y) ->
                    client.postPointer(x, y, BUTTON_RIGHT)
                    client.postPointer(x, y, 0)
                }
            }
            break
        }

        if (pressed.size >= 2) {
            if (mouseDown) {
                mapping().toRemoteClamped(lastPos.x, lastPos.y)?.let { (x, y) ->
                    client.postPointer(x, y, 0)
                }
                mouseDown = false
            }
            longPressArmed = false
            mode = Gesture.Multi
            pinch.apply(
                event = event,
                pressed = pressed,
                client = client,
                crop = crop,
                viewport = viewportState.value,
                zoomState = zoomState,
                panState = panState,
                touchSlop = touchSlop,
                haptics = haptics,
            )
            continue
        }

        val change = pressed.first()
        val pos = change.position

        when {
            mouseDown -> {
                lastPos = pos
                mapping().toRemoteClamped(lastPos.x, lastPos.y)?.let { (x, y) ->
                    client.postPointer(x, y, BUTTON_LEFT)
                }
                if (change.positionChanged()) change.consume()
            }
            longPressArmed -> {
                lastPos = pos
                if ((pos - down.position).getDistance() > touchSlop) {
                    longPressArmed = false
                    // Press at the original contact, then track to the current point.
                    mapping().toRemote(down.position.x, down.position.y)?.let { (x, y) ->
                        mouseDown = true
                        client.postPointer(x, y, BUTTON_LEFT)
                    }
                    mapping().toRemoteClamped(pos.x, pos.y)?.let { (x, y) ->
                        mouseDown = true
                        client.postPointer(x, y, BUTTON_LEFT)
                    }
                }
                if (change.positionChanged()) change.consume()
            }
            mode == Gesture.Pan -> {
                applyPanDelta(pos - lastPos)
                lastPos = pos
                if (change.positionChanged()) change.consume()
            }
            mode == Gesture.Multi -> {
                if (change.positionChanged()) change.consume()
            }
        }
    }

    if (mouseDown) {
        mapping().toRemoteClamped(lastPos.x, lastPos.y)?.let { (x, y) ->
            client.postPointer(x, y, 0)
        }
    }
}

/** Per-gesture two-finger bookkeeping: arming, and leftover scroll travel. */
private class PinchState {
    private var armed = false
    private var lastCentroidSize = 0f
    private var scrollAccum = 0f
    private var hitMinZoom = false
    private var hitMaxZoom = false

    @Suppress("LongParameterList")
    fun apply(
        event: PointerEvent,
        pressed: List<PointerInputChange>,
        client: RfbClient,
        crop: DisplayCrop,
        viewport: IntSize,
        zoomState: MutableFloatState,
        panState: MutableState<Offset>,
        touchSlop: Float,
        haptics: HapticFeedback,
    ) {
        val panChange = event.calculatePan()
        if (!armed) {
            val centroidSize = event.calculateCentroidSize()
            val gain = max(centroidSize - lastCentroidSize, 0f) + panChange.getDistance()
            lastCentroidSize = centroidSize
            if (gain <= touchSlop) return
            armed = true
        }

        val centroid = event.calculateCentroid(useCurrent = true)
        val oldZoom = zoomState.floatValue
        val rawZoom = oldZoom * event.calculateZoom()
        val newZoom = rawZoom.coerceIn(1f, MAX_ZOOM)
        if (rawZoom < 1f && !hitMinZoom) {
            hitMinZoom = true
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else if (rawZoom > MAX_ZOOM && !hitMaxZoom) {
            hitMaxZoom = true
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else if (newZoom > 1f && newZoom < MAX_ZOOM) {
            hitMinZoom = false
            hitMaxZoom = false
        }

        if (!canPan(viewport, crop, oldZoom) && !canPan(viewport, crop, newZoom) &&
            newZoom <= 1.001f && oldZoom <= 1.001f
        ) {
            // Nothing to pan when the image already covers exactly, so scroll the wheel.
            scrollAccum += panChange.y
            val target = computeMapping(viewport, crop, oldZoom, panState.value)
                .toRemoteClamped(centroid.x, centroid.y)
            while (target != null && abs(scrollAccum) >= SCROLL_STEP) {
                val button = if (scrollAccum > 0) WHEEL_UP else WHEEL_DOWN
                client.postPointer(target.first, target.second, button)
                client.postPointer(target.first, target.second, 0)
                scrollAccum -= if (scrollAccum > 0) SCROLL_STEP else -SCROLL_STEP
            }
        } else {
            scrollAccum = 0f
            // Anchor on the centroid so the pixel under the fingers stays under them.
            val before = computeMapping(viewport, crop, oldZoom, panState.value)
            val scale = newZoom / oldZoom
            val left = centroid.x - (centroid.x - before.left) * scale
            val top = centroid.y - (centroid.y - before.top) * scale
            zoomState.floatValue = newZoom
            panState.value = clampPan(
                viewport,
                crop,
                newZoom,
                panForCorner(viewport, crop, newZoom, left, top) + panChange,
            )
        }
        pressed.forEach { if (it.positionChanged()) it.consume() }
    }
}

private fun android.view.View.findActivityWindowOrNull(): android.view.Window? {
    var ctx = context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}

private fun androidKeyToKeysym(keyCode: Int): Int? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_ENTER -> Keysym.Return
    AndroidKeyEvent.KEYCODE_DEL -> Keysym.BackSpace
    AndroidKeyEvent.KEYCODE_TAB -> Keysym.Tab
    AndroidKeyEvent.KEYCODE_ESCAPE -> Keysym.Escape
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> Keysym.Left
    AndroidKeyEvent.KEYCODE_DPAD_UP -> Keysym.Up
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> Keysym.Right
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> Keysym.Down
    else -> null
}
