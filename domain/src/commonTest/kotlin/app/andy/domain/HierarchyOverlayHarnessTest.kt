package app.andy.domain

import app.andy.model.AccessibilityNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * End-to-end harness for inspector mirror overlays: hierarchy bounds → display space → stream UV.
 *
 * Fixtures mirror the inspector-demo Compose scroll layout on a 1080×2340 device with a
 * 498×1080 scrcpy stream.
 */
class HierarchyOverlayHarnessTest {
    private val displayWidth = 1080
    private val displayHeight = 2340
    private val streamWidth = 498
    private val streamHeight = 1080

    @Test
    fun overlayPipelineAtScrollTopDoesNotDriftAcrossRepeatedResolves() {
        val (root, text) = inspectorDemoFixture(scrollY = 0, textBounds = "[48,197][1032,437]")

        val resolved = (1..50).map { resolveHighlightBounds(text.bounds, root, text) }
        assertEquals(1, resolved.toSet().size)
        assertEquals("[48,200][1032,437]", resolved.first())

        val streamPixels = effectiveHighlightBounds(
            bounds = resolved.first(),
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            streamWidth = streamWidth,
            streamHeight = streamHeight,
        )
        assertEquals(listOf(22, 92, 476, 202), streamPixels)
    }

    @Test
    fun overlayPipelineAfterScrollUsesActivityTopScrollY() {
        val (root, text) = inspectorDemoFixture(scrollY = 300, textBounds = "[48,528][1032,608]")

        val resolved = resolveHighlightBounds(text.bounds, root, text)
        assertEquals("[48,228][1032,308]", resolved)

        val streamPixels = effectiveHighlightBounds(
            bounds = resolved,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            streamWidth = streamWidth,
            streamHeight = streamHeight,
        )
        assertEquals(listOf(22, 105, 476, 142), streamPixels)
    }

    @Test
    fun overlayPipelineUsesRecapturedScreenBoundsWithoutDoubleTranslation() {
        val (root, text) = inspectorDemoFixture(scrollY = 300, textBounds = "[48,228][1032,308]")

        val resolved = resolveHighlightBounds(text.bounds, root, text)
        assertEquals("[48,228][1032,308]", resolved)

        val streamPixels = effectiveHighlightBounds(
            bounds = resolved,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            streamWidth = streamWidth,
            streamHeight = streamHeight,
        )
        assertEquals(listOf(22, 105, 476, 142), streamPixels)
    }

    @Test
    fun overlayPipelineHidesNodesScrolledAboveViewport() {
        val (root, text) = inspectorDemoFixture(scrollY = 300, textBounds = "[48,197][1032,437]")

        assertNull(resolveHighlightBounds(text.bounds, root, text))
    }

    @Test
    fun recaptureAfterScrollUpdatesOverlayFromFreshSnapshot() {
        val before = inspectorDemoFixture(scrollY = 0, textBounds = "[48,197][1032,437]")
        val beforeHighlight = resolveHighlightBounds(before.second.bounds, before.first, before.second)
        assertEquals("[48,200][1032,437]", beforeHighlight)

        val after = inspectorDemoFixture(scrollY = 300, textBounds = "[48,228][1032,308]")
        val afterHighlight = resolveHighlightBounds(after.second.bounds, after.first, after.second)
        assertEquals("[48,228][1032,308]", afterHighlight)

        val beforeStream = effectiveHighlightBounds(
            bounds = beforeHighlight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            streamWidth = streamWidth,
            streamHeight = streamHeight,
        )
        val afterStream = effectiveHighlightBounds(
            bounds = afterHighlight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            streamWidth = streamWidth,
            streamHeight = streamHeight,
        )
        assertEquals(listOf(22, 92, 476, 202), beforeStream)
        assertEquals(listOf(22, 105, 476, 142), afterStream)
    }

    @Test
    fun needsScrollTranslationNeverFiresAtScrollTop() {
        val viewport = listOf(0, 200, 1080, 2200)
        val bounds = listOf(48, 528, 1032, 608)

        repeat(20) {
            assertEquals(false, needsScrollTranslation(bounds, viewport, scrollY = 0))
        }
    }

    private fun inspectorDemoFixture(scrollY: Int, textBounds: String): Pair<AccessibilityNode, AccessibilityNode> {
        val text = AccessibilityNode(
            id = "text",
            className = "android.widget.TextView",
            resourceId = "app.andy.inspectordemo:id/title",
            text = "Inspector demo",
            contentDescription = null,
            bounds = textBounds,
            clickable = false,
            focusable = false,
            enabled = true,
        )
        val scrollView = AccessibilityNode(
            id = "scroll",
            className = "android.widget.ScrollView",
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = "[0,200][1080,2200]",
            clickable = false,
            focusable = false,
            enabled = true,
            scrollable = true,
            attributes = mapOf("scroll-y" to scrollY.toString(), "view-hash" to "a1b2c3"),
            children = listOf(text),
        )
        val root = AccessibilityNode(
            id = "root",
            className = "android.widget.FrameLayout",
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = "[0,0][1080,2340]",
            clickable = false,
            focusable = false,
            enabled = true,
            children = listOf(scrollView),
        )
        return root to text
    }
}
