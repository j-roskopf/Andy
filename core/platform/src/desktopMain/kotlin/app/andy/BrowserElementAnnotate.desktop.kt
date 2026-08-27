package app.andy

import app.andy.desktop.browser.BROWSER_ELEMENT_INSPECTOR_SCRIPT
import app.andy.desktop.browser.BROWSER_ELEMENT_INSPECTOR_TEARDOWN_SCRIPT
import app.andy.desktop.browser.WkBrowserJni
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val annotateListeners = CopyOnWriteArrayList<(BrowserElementAnnotateEvent) -> Unit>()
private val annotateBridgeInstalled = AtomicBoolean(false)
private val annotateJson = Json { ignoreUnknownKeys = true }

actual fun setBrowserElementInspectEnabled(enabled: Boolean) {
    if (!WkBrowserJni.available) return
    if (System.getProperty("andy.screenshot.renderer") == "compose") return
    ensureAnnotateBridge()
    val js = if (enabled) {
        "$BROWSER_ELEMENT_INSPECTOR_SCRIPT;window.__ANDY_ANNOTATE__&&window.__ANDY_ANNOTATE__.setEnabled(true);"
    } else {
        BROWSER_ELEMENT_INSPECTOR_TEARDOWN_SCRIPT
    }
    WkBrowserJni.evaluateJavaScript(js)
}

actual fun observeBrowserElementAnnotations(
    onEvent: (BrowserElementAnnotateEvent) -> Unit,
): () -> Unit {
    if (!WkBrowserJni.available) return {}
    ensureAnnotateBridge()
    annotateListeners += onEvent
    return { annotateListeners.remove(onEvent) }
}

private fun ensureAnnotateBridge() {
    if (!annotateBridgeInstalled.compareAndSet(false, true)) return
    WkBrowserJni.onAnnotate = { json, png ->
        val event = parseAnnotateEvent(json, png)
        EventQueue.invokeLater {
            annotateListeners.forEach { listener ->
                runCatching { listener(event) }
            }
        }
    }
}

fun parseAnnotateEvent(json: String?, png: ByteArray?): BrowserElementAnnotateEvent {
    val root = runCatching {
        annotateJson.parseToJsonElement(json.orEmpty()) as? JsonObject
    }.getOrNull()
    val type = root?.string("type")
    if (type != "submit") return BrowserElementAnnotateEvent.Cancelled
    val imagePath = png?.takeIf { it.isNotEmpty() }?.let(::persistBrowserAnnotationPng)
    return BrowserElementAnnotateEvent.Submitted(
        BrowserElementAnnotation(
            comment = root.string("comment"),
            tag = root.string("tag"),
            selector = root.string("selector"),
            url = root.string("url"),
            pageTitle = root.string("title"),
            width = root.int("width"),
            height = root.int("height"),
            color = root.string("color"),
            font = root.string("font"),
            innerText = root.string("text"),
            imagePath = imagePath,
        ),
    )
}

private fun persistBrowserAnnotationPng(bytes: ByteArray): String? = runCatching {
    val dir = File(System.getProperty("user.home"), ".andy/browser-annotations")
    val path = uniqueLocalPath(dir.absolutePath, "element.png")
    File(path).writeBytes(bytes)
    path
}.getOrNull()

private fun JsonObject?.string(key: String): String =
    this?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject?.int(key: String): Int {
    val primitive = this?.get(key)?.jsonPrimitive ?: return 0
    return primitive.intOrNull
        ?: primitive.doubleOrNull?.toInt()
        ?: primitive.contentOrNull?.toDoubleOrNull()?.toInt()
        ?: 0
}
