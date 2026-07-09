package app.andy.domain

import app.andy.model.AccessibilityNode
import app.andy.service.MirrorInput

internal fun mirrorInputBugText(input: MirrorInput, accessibilityRoot: AccessibilityNode?): Pair<String, String?> = when (input) {
    is MirrorInput.Touch -> "${input.action.name} ${input.x},${input.y}" to null
    is MirrorInput.Tap -> mirrorTapBugText(input.x, input.y, accessibilityRoot)
    is MirrorInput.Swipe -> mirrorSwipeBugText(input.startX, input.startY, input.endX, input.endY, input.durationMillis)
    is MirrorInput.Key -> "Key ${input.keyCode}" to androidKeyLabel(input.keyCode)
    is MirrorInput.Text -> "Text input" to input.value.take(80)
    MirrorInput.Back -> "Back" to null
    MirrorInput.Home -> "Home" to null
    MirrorInput.Recents -> "Recents" to null
    MirrorInput.Power -> "Power" to null
}

internal fun mirrorTapBugText(x: Int, y: Int, accessibilityRoot: AccessibilityNode?): Pair<String, String?> {
    val node = accessibilityRoot?.findBestNodeAt(x, y) ?: return "Tap $x,$y" to null
    val label = node.accessibilityBugLabel()
    val className = node.className?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
    val title = buildString {
        append("Tap")
        if (label != null) append(" \"").append(label.take(80)).append("\"")
        if (className != null) append(" [").append(className).append("]")
    }
    val detail = buildList {
        add("$x,$y")
        node.resourceId?.takeIf { it.isNotBlank() }?.let(::add)
        node.packageName?.takeIf { it.isNotBlank() }?.let(::add)
        node.bounds?.takeIf { it.isNotBlank() }?.let(::add)
        node.accessibilityStateSummary()?.let(::add)
    }.joinToString(" · ")
    return title to detail
}

internal fun mirrorSwipeBugText(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int): Pair<String, String?> {
    val dx = endX - startX
    val dy = endY - startY
    val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
    val direction = when {
        kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx > 0 -> "right"
        kotlin.math.abs(dx) > kotlin.math.abs(dy) -> "left"
        dy > 0 -> "down"
        else -> "up"
    }
    return "Swipe $direction" to "${distance}px · ${durationMillis}ms · $startX,$startY -> $endX,$endY"
}

internal fun AccessibilityNode.accessibilityBugLabel(): String? {
    return listOf(text, contentDescription, hint, resourceId)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
}

internal fun AccessibilityNode.accessibilityStateSummary(): String? {
    val values = buildList {
        if (clickable) add("clickable")
        if (focusable) add("focusable")
        if (scrollable) add("scrollable")
        if (selected) add("selected")
        if (checked) add("checked")
        if (!enabled) add("disabled")
    }
    return values.takeIf { it.isNotEmpty() }?.joinToString(",")
}

internal fun androidKeyLabel(keyCode: Int): String? = when (keyCode) {
    24 -> "Volume up"
    25 -> "Volume down"
    else -> null
}
