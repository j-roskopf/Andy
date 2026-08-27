package app.andy.domain

import app.andy.model.AccessibilityNode
import app.andy.service.MirrorInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MirrorInputDescriptionTest {
    @Test
    fun androidKeyLabelMapsVolumeKeys() {
        assertEquals("Volume up", androidKeyLabel(24))
        assertEquals("Volume down", androidKeyLabel(25))
        assertNull(androidKeyLabel(66))
    }

    @Test
    fun mirrorInputBugTextFormatsHardwareAndText() {
        assertEquals("Back" to null, mirrorInputBugText(MirrorInput.Back, null))
        assertEquals("Key 24" to "Volume up", mirrorInputBugText(MirrorInput.Key(24), null))
        assertEquals("Text input" to "hello", mirrorInputBugText(MirrorInput.Text("hello"), null))
    }

    @Test
    fun mirrorSwipeBugTextReportsDirectionAndDistance() {
        val (title, detail) = mirrorSwipeBugText(0, 0, 100, 0, 250)
        assertEquals("Swipe right", title)
        assertEquals("100px · 250ms · 0,0 -> 100,0", detail)
    }

    @Test
    fun mirrorTapBugTextUsesAccessibilityLabelWhenAvailable() {
        val root = AccessibilityNode(
            id = "root",
            className = "android.widget.FrameLayout",
            packageName = "com.example",
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = "[0,0][200,200]",
            clickable = false,
            focusable = false,
            enabled = true,
            children = listOf(
                AccessibilityNode(
                    id = "btn",
                    className = "android.widget.Button",
                    packageName = "com.example",
                    resourceId = "com.example:id/ok",
                    text = "OK",
                    contentDescription = null,
                    bounds = "[50,50][150,150]",
                    clickable = true,
                    focusable = true,
                    enabled = true,
                ),
            ),
        )
        val (title, detail) = mirrorTapBugText(100, 100, root)
        assertEquals("Tap \"OK\" [Button]", title)
        assertEquals(true, detail?.contains("100,100"))
        assertEquals(true, detail?.contains("com.example:id/ok"))
    }

    @Test
    fun accessibilityStateSummaryListsFlags() {
        val node = AccessibilityNode(
            id = "n",
            className = "android.view.View",
            packageName = null,
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = null,
            clickable = true,
            focusable = true,
            enabled = false,
            selected = true,
        )
        assertEquals("clickable,focusable,selected,disabled", node.accessibilityStateSummary())
    }
}
