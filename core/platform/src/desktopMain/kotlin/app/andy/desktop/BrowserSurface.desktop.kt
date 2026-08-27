package app.andy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import app.andy.desktop.browser.WkBrowserJni
import app.andy.desktop.nsWindowNumber
import app.andy.LocalSuppressHeavyweightSurfaces
import java.awt.Canvas
import java.awt.Color as AwtColor
import java.awt.Component
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * macOS: WKWebView as a borderless AppKit child window over a Compose Canvas placeholder
 * (same parenting pattern as the Live Metal mirror). In-process JCEF is not used — CEF
 * owns AppKit's main thread and JOGL OSR then destroys NSWindows from AWT-EDT, which
 * traps with "Must only be used from the main thread".
 */
@Composable
actual fun BrowserSurface(
    url: String,
    navCommands: Flow<BrowserNavCommand>,
    onNavStateChanged: (title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
    modifier: Modifier,
    chromeColor: Color,
    bottomCornerRadiusPx: Float,
) {
    if (isScreenshotRenderer()) {
        Box(modifier.background(chromeColor))
        return
    }
    if (!WkBrowserJni.available) {
        Box(modifier.background(chromeColor), contentAlignment = Alignment.Center) {
            Text(
                WkBrowserJni.failureMessage() ?: "Embedded browser requires macOS",
                color = Color.White,
            )
        }
        return
    }

    val suppressHeavyweight = LocalSuppressHeavyweightSurfaces.current
    val host = remember { WkBrowserHost() }
    var tracker by remember { mutableStateOf<Component?>(null) }

    DisposableEffect(onNavStateChanged) {
        host.onNavStateChanged = onNavStateChanged
        onDispose { }
    }
    DisposableEffect(Unit) {
        host.ensureOpen(bottomCornerRadiusPx)
        onDispose {
            // Hide, don't destroy: tab switches and pane hide leave composition, and
            // reloading the same URL on the way back would throw away the page. The
            // last Browser tab close calls [closeEmbeddedBrowser].
            host.pause()
        }
    }
    LaunchedEffect(url) { host.load(url) }
    LaunchedEffect(navCommands) { navCommands.collect { host.handle(it) } }
    LaunchedEffect(bottomCornerRadiusPx) {
        WkBrowserJni.setBottomCornerRadius(bottomCornerRadiusPx)
    }
    // Same rule as MirrorVideoSurface: an invisible SwingPanel still punches a Skia clear-hole
    // that clips chrome DropdownMenus. Tear the interop host down and hide the WKWebView
    // child window while menus/dialogs are up.
    LaunchedEffect(suppressHeavyweight) {
        WkBrowserJni.setVisible(!suppressHeavyweight)
        if (suppressHeavyweight) {
            tracker = null
        } else {
            host.invalidateGeometry()
            tracker?.let { host.syncGeometry(it) }
        }
    }
    LaunchedEffect(tracker, suppressHeavyweight) {
        val component = tracker ?: return@LaunchedEffect
        if (suppressHeavyweight) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.Main.immediate) {
                host.syncGeometry(component)
            }
            kotlinx.coroutines.delay(32)
        }
    }

    Box(modifier.background(chromeColor)) {
        if (!suppressHeavyweight) {
            SwingPanel(
                modifier = Modifier.fillMaxSize(),
                background = chromeColor,
                factory = {
                    host.createTracker(chromeColor).also { created ->
                        EventQueue.invokeLater { tracker = created }
                    }
                },
                update = { component ->
                    tracker = component
                    host.syncGeometry(component)
                },
            )
        }
    }
}

@Composable
actual fun rememberBrowserEngineState(): BrowserEngineState {
    if (isScreenshotRenderer()) return BrowserEngineState.Ready
    return if (WkBrowserJni.available) {
        BrowserEngineState.Ready
    } else {
        BrowserEngineState.Failed(
            WkBrowserJni.failureMessage() ?: "Embedded browser requires macOS (WKWebView)",
        )
    }
}

actual fun resignEmbeddedBrowserKey() {
    if (!isScreenshotRenderer()) WkBrowserJni.resignKey()
}

actual fun focusEmbeddedBrowser() {
    if (!isScreenshotRenderer()) WkBrowserJni.focus()
}

actual fun closeEmbeddedBrowser() {
    if (!isScreenshotRenderer()) WkBrowserJni.close()
}

private fun isScreenshotRenderer(): Boolean =
    System.getProperty("andy.screenshot.renderer") == "compose"

private class WkBrowserHost {
    var onNavStateChanged: (title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit =
        { _, _, _, _, _ -> }

    @Volatile private var opened = false
    @Volatile private var lastUrl: String = ""
    private var lastGeometryKey: String? = null

    fun ensureOpen(bottomCornerRadiusPx: Float) {
        if (opened) return
        val installed = WkBrowserJni.install { title, url, canBack, canForward, loading ->
            EventQueue.invokeLater {
                onNavStateChanged(title, url, canBack, canForward, loading)
            }
        }
        if (!installed) return
        WkBrowserJni.setBottomCornerRadius(bottomCornerRadiusPx)
        opened = WkBrowserJni.open()
        if (opened && lastUrl.isNotBlank()) {
            WkBrowserJni.loadUrl(lastUrl)
        }
    }

    fun createTracker(chromeColor: Color): Canvas {
        val awt = AwtColor(
            chromeColor.red,
            chromeColor.green,
            chromeColor.blue,
            chromeColor.alpha,
        )
        val canvas = object : Canvas() {
            init {
                background = awt
                // Keep the AWT hole non-focusable so Compose chrome (address bar / tabs)
                // can take focus; WKWebView is a separate key-capable AppKit child.
                isFocusable = false
            }
        }
        val sync = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = syncGeometry(canvas)
            override fun componentMoved(e: ComponentEvent?) = syncGeometry(canvas)
            override fun componentShown(e: ComponentEvent?) = syncGeometry(canvas)
        }
        canvas.addComponentListener(sync)
        canvas.addHierarchyListener(HierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L ||
                e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L
            ) {
                syncGeometry(canvas)
            }
        })
        // Match MirrorVideoSurface: ancestor move/resize (dock drag, flyout reflow) must
        // re-sync or the WKWebView child window keeps covering tab strip / address bar.
        canvas.addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {
            override fun ancestorResized(e: HierarchyEvent?) = syncGeometry(canvas)
            override fun ancestorMoved(e: HierarchyEvent?) = syncGeometry(canvas)
        })
        // If AWT ever sees a press in the hole (e.g. before the child is parented), promote
        // the WKWebView to key so page fields can accept input.
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                WkBrowserJni.focus()
            }
        })
        return canvas
    }

    fun invalidateGeometry() {
        lastGeometryKey = null
    }

    fun syncGeometry(component: Component) {
        if (!opened || !component.isDisplayable || !component.isShowing) return
        val loc = runCatching { component.locationOnScreen }.getOrNull() ?: return
        val scale = component.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        val w = component.width.coerceAtLeast(1)
        val h = component.height.coerceAtLeast(1)
        val parentWindowNumber = SwingUtilities.getWindowAncestor(component)?.nsWindowNumber() ?: 0
        val key = "${loc.x},${loc.y},$w,$h,$scale,$parentWindowNumber"
        if (key == lastGeometryKey) return
        lastGeometryKey = key
        WkBrowserJni.updateFrame(loc.x, loc.y, w, h, scale, parentWindowNumber)
    }

    fun load(url: String) {
        if (url.isBlank()) return
        lastUrl = url
        if (opened) WkBrowserJni.loadUrl(url)
    }

    fun handle(command: BrowserNavCommand) {
        when (command) {
            BrowserNavCommand.Back -> WkBrowserJni.goBack()
            BrowserNavCommand.Forward -> WkBrowserJni.goForward()
            BrowserNavCommand.Refresh -> WkBrowserJni.reload()
            is BrowserNavCommand.GoTo -> load(command.url)
        }
    }

    fun pause() {
        lastGeometryKey = null
        WkBrowserJni.resignKey()
        WkBrowserJni.setVisible(false)
    }
}
