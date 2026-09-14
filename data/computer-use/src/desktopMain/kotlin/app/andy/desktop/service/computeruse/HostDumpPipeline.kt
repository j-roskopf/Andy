package app.andy.desktop.service.computeruse

import app.andy.model.HostAccessibilityElement
import app.andy.model.HostAccessibilityTree
import app.andy.model.HostElementBounds
import app.andy.model.HostElementKind

/** Raw node from a recorded AX fixture or a pre-prune native walk. */
data class HostRawNode(
    val id: String,
    val role: String,
    val subrole: String? = null,
    val label: String? = null,
    val bounds: HostElementBounds? = null,
    val actions: Set<String> = emptySet(),
    val windowBounds: HostElementBounds? = null,
)

/**
 * §4.1 dump pipeline — scope is assumed already applied (windows-only nodes).
 * Viewport clip → classify by AXPress → label resolution → compact emit with payload cap.
 */
object HostDumpPipeline {
    const val DefaultPayloadCapBytes = 32 * 1024
    const val DefaultLabelMaxChars = 72

    fun prune(
        appName: String,
        nodes: List<HostRawNode>,
        windowCount: Int = 1,
        windowsOnScreen: Boolean = true,
        payloadCapBytes: Int = DefaultPayloadCapBytes,
        labelMaxChars: Int = DefaultLabelMaxChars,
    ): HostAccessibilityTree {
        val emitted = mutableListOf<HostAccessibilityElement>()
        var payload = 0
        var truncated = false

        for (node in nodes) {
            if (truncated) break
            val bounds = node.bounds ?: continue
            val viewport = node.windowBounds
            if (viewport != null && !intersectsVisible(bounds, viewport)) continue

            val pressable = "AXPress" in node.actions
            val secure = isSecure(node.role, node.subrole)
            val label = node.label?.trim()?.takeIf { it.isNotEmpty() }?.take(labelMaxChars)

            val kind = when {
                pressable || secure -> HostElementKind.Control
                node.role == "AXStaticText" || node.role == "AXHeading" -> HostElementKind.Text
                else -> HostElementKind.Other
            }

            // Controls: emit even without label (§4.1 unlabeled still reachable via bounds).
            // Text: require a label.
            val include = when (kind) {
                HostElementKind.Control -> true
                HostElementKind.Text -> label != null
                HostElementKind.Other -> false
            }
            if (!include) continue

            val element = HostAccessibilityElement(
                id = node.id,
                role = node.role,
                label = label,
                bounds = bounds,
                pressable = pressable,
                secure = secure,
                kind = kind,
                subrole = node.subrole,
            )
            val bytes = estimatePayloadBytes(element)
            if (payload + bytes > payloadCapBytes) {
                truncated = true
                break
            }
            payload += bytes
            emitted += element
        }

        return HostAccessibilityTree(
            appName = appName,
            elements = emitted,
            truncated = truncated,
            payloadBytes = payload,
            windowCount = windowCount,
            windowsOpenOnScreen = windowsOnScreen,
            untrusted = true,
        )
    }

    fun compactJson(tree: HostAccessibilityTree): String = buildString {
        append("{\"app\":\"").append(escape(tree.appName)).append('"')
        append(",\"truncated\":").append(tree.truncated)
        append(",\"payloadBytes\":").append(tree.payloadBytes)
        append(",\"elements\":[")
        tree.elements.forEachIndexed { index, el ->
            if (index > 0) append(',')
            append("{\"i\":\"").append(escape(el.id)).append('"')
            append(",\"r\":\"").append(escape(el.role)).append('"')
            append(",\"k\":\"").append(
                when (el.kind) {
                    HostElementKind.Control -> "control"
                    HostElementKind.Text -> "text"
                    HostElementKind.Other -> "other"
                },
            ).append('"')
            el.label?.let { append(",\"l\":\"").append(escape(it)).append('"') }
            el.bounds?.let { append(",\"b\":\"").append(it.packed()).append('"') }
            if (el.pressable) append(",\"p\":true")
            if (el.secure) append(",\"s\":true")
            append('}')
        }
        append("]}")
    }

    fun wrapUntrusted(text: String): String =
        "<untrusted_screen_content>\n$text\n</untrusted_screen_content>"

    internal fun intersectsVisible(bounds: HostElementBounds, viewport: HostElementBounds): Boolean {
        if (bounds.width <= 1 || bounds.height <= 1) return false
        val ax2 = bounds.x + bounds.width
        val ay2 = bounds.y + bounds.height
        val bx2 = viewport.x + viewport.width
        val by2 = viewport.y + viewport.height
        return bounds.x < bx2 && ax2 > viewport.x && bounds.y < by2 && ay2 > viewport.y
    }

    internal fun isSecure(role: String, subrole: String?): Boolean =
        role == "AXSecureTextField" || subrole == "AXSecureTextField"

    private fun estimatePayloadBytes(el: HostAccessibilityElement): Int {
        // Rough UTF-8 size of compact JSON object + comma.
        var n = 40 + el.id.length + el.role.length
        el.label?.let { n += it.length + 6 }
        el.bounds?.let { n += it.packed().length + 6 }
        if (el.pressable) n += 8
        if (el.secure) n += 8
        return n + 1
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
