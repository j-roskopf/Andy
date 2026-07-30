package app.andy

import app.andy.domain.findBestNodeAt
import app.andy.domain.parseBounds
import app.andy.model.AccessibilityNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccessibilityHitTest {
    @Test
    fun prefersLabeledInteractiveChildOverFullscreenContainer() {
        val play = node(
            id = "play",
            className = "android.view.View",
            contentDescription = "Play",
            bounds = "[925,1804][1043,1922]",
            clickable = true,
        )
        val bottomSheet = node(
            id = "bottom-sheet",
            className = "android.view.View",
            bounds = "[0,1600][1080,2200]",
            children = listOf(
                node(id = "title", className = "android.widget.TextView", text = "Song 2", bounds = "[132,1710][420,1768]"),
                play,
            ),
        )
        val root = node(
            id = "root",
            className = "android.widget.FrameLayout",
            bounds = "[0,0][1080,2340]",
            children = listOf(bottomSheet),
        )

        assertEquals("play", root.findBestNodeAt(988, 1862)?.id)
    }

    @Test
    fun prefersDeeplyNestedOverlappingChildOverAncestorContainers() {
        val icon = node(
            id = "icon",
            className = "android.widget.ImageView",
            contentDescription = "Delete",
            bounds = "[500,500][560,560]",
            clickable = true,
        )
        val row = node(id = "row", className = "android.view.ViewGroup", bounds = "[0,480][1080,600]", children = listOf(icon))
        val list = node(id = "list", className = "android.widget.ListView", bounds = "[0,200][1080,2200]", children = listOf(row))
        val root = node(id = "root", className = "android.widget.FrameLayout", bounds = "[0,0][1080,2340]", children = listOf(list))

        assertEquals("icon", root.findBestNodeAt(530, 530)?.id)
    }

    @Test
    fun prefersLabeledOverlappingSiblingOverLargerUnlabeledOneAtTheSameDepth() {
        val badge = node(id = "badge", className = "android.widget.TextView", text = "3", bounds = "[900,50][960,110]", clickable = true)
        val card = node(id = "card", className = "android.view.ViewGroup", bounds = "[800,40][1000,300]", clickable = true)
        val root = node(id = "root", className = "android.widget.FrameLayout", bounds = "[0,0][1080,2340]", children = listOf(card, badge))

        // Both bounds contain the tap point; the labeled, smaller badge should win over its
        // larger, unlabeled sibling.
        assertEquals("badge", root.findBestNodeAt(930, 80)?.id)
    }

    @Test
    fun returnsNullWhenPointIsFarFromAllNodes() {
        val root = node(
            id = "root",
            className = "android.widget.FrameLayout",
            bounds = "[0,0][100,100]",
        )
        assertNull(root.findBestNodeAt(2000, 2000))
    }

    @Test
    fun parseBoundsExtractsFourIntegers() {
        assertEquals(listOf(10, 20, 30, 40), parseBounds("[10,20][30,40]"))
        assertNull(parseBounds("[10,20]"))
        assertNull(parseBounds(null))
    }

    private fun node(
        id: String,
        className: String,
        bounds: String,
        text: String? = null,
        contentDescription: String? = null,
        clickable: Boolean = false,
        children: List<AccessibilityNode> = emptyList(),
    ) = AccessibilityNode(
        id = id,
        className = className,
        packageName = "com.phoebe.app.debug",
        resourceId = null,
        text = text,
        contentDescription = contentDescription,
        bounds = bounds,
        clickable = clickable,
        focusable = clickable,
        enabled = true,
        children = children,
    )
}
