package app.andy.domain

import app.andy.model.AccessibilityNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HierarchyDomainTest {
    private fun node(
        id: String,
        className: String? = "android.view.View",
        bounds: String? = "[0,0][10,10]",
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        visible: Boolean = true,
        scrollable: Boolean = false,
        attributes: Map<String, String> = emptyMap(),
        children: List<AccessibilityNode> = emptyList(),
    ) = AccessibilityNode(
        id = id,
        className = className,
        resourceId = resourceId,
        text = text,
        contentDescription = contentDescription,
        bounds = bounds,
        clickable = false,
        focusable = false,
        enabled = true,
        visible = visible,
        scrollable = scrollable,
        attributes = attributes,
        children = children,
    )

    // ---- filterInvisible ----------------------------------------------------------------

    @Test
    fun filterInvisibleDropsInvisibleLeafButKeepsVisibleSiblings() {
        val root = node(
            "root",
            children = listOf(
                node("visible-child", visible = true),
                node("hidden-child", visible = false),
            ),
        )

        val filtered = root.filterInvisible()

        assertEquals(listOf("visible-child"), filtered?.children?.map { it.id })
    }

    @Test
    fun filterInvisibleKeepsInvisibleContainerThatHasAVisibleDescendant() {
        val root = node(
            "root",
            visible = false,
            children = listOf(node("visible-grandchild", visible = true)),
        )

        val filtered = root.filterInvisible()

        assertEquals("root", filtered?.id)
        assertEquals(listOf("visible-grandchild"), filtered?.children?.map { it.id })
    }

    @Test
    fun filterInvisibleDropsWholeSubtreeWhenNothingIsVisible() {
        val root = node(
            "root",
            visible = false,
            children = listOf(node("also-hidden", visible = false)),
        )

        assertNull(root.filterInvisible())
    }

    // ---- search filter --------------------------------------------------------------------

    @Test
    fun matchesHierarchyQueryChecksTextContentDescriptionResourceIdAndClassName() {
        val byText = node("a", text = "Add plant")
        val byContentDescription = node("b", contentDescription = "Add plant button")
        val byResourceId = node("c", resourceId = "com.example.garden:id/add")
        val byClassName = node("d", className = "android.widget.AddButton")
        val noMatch = node("e", text = "Remove")

        assertTrue(byText.matchesHierarchyQuery("add"))
        assertTrue(byContentDescription.matchesHierarchyQuery("ADD"))
        assertTrue(byResourceId.matchesHierarchyQuery("id/add"))
        assertTrue(byClassName.matchesHierarchyQuery("addbutton"))
        assertTrue(noMatch.matchesHierarchyQuery("")) // blank query matches everything
        assertEquals(false, noMatch.matchesHierarchyQuery("add"))
    }

    @Test
    fun filterBySearchKeepsAncestorsOfAMatchingDescendantOnly() {
        val root = node(
            "root",
            children = listOf(
                node("title", text = "My garden"),
                node(
                    "toolbar",
                    children = listOf(
                        node("add-button", contentDescription = "Add plant"),
                        node("settings-button", contentDescription = "Settings"),
                    ),
                ),
            ),
        )

        val filtered = root.filterBySearch("add")

        assertEquals("root", filtered?.id)
        assertEquals(listOf("toolbar"), filtered?.children?.map { it.id })
        assertEquals(listOf("add-button"), filtered?.children?.single()?.children?.map { it.id })
    }

    @Test
    fun filterBySearchWithBlankQueryReturnsTheWholeTreeUnchanged() {
        val root = node("root", children = listOf(node("child")))

        assertEquals(root, root.filterBySearch(""))
        assertEquals(root, root.filterBySearch("   "))
    }

    @Test
    fun filterBySearchReturnsNullWhenNothingMatches() {
        val root = node("root", text = "Nothing here", children = listOf(node("child", text = "Still nothing")))

        assertNull(root.filterBySearch("garden"))
    }

    // ---- structural diff --------------------------------------------------------------------

    @Test
    fun diffHierarchyTreesReportsAddedRemovedAndChangedNodes() {
        val before = node(
            "root",
            children = listOf(
                node("stable", resourceId = "com.example:id/stable", bounds = "[0,0][100,50]"),
                node("removed", resourceId = "com.example:id/removed"),
            ),
        )
        val after = node(
            "root",
            children = listOf(
                node("stable", resourceId = "com.example:id/stable", bounds = "[0,0][100,80]"),
                node("added", resourceId = "com.example:id/added"),
            ),
        )

        val entries = diffHierarchyTrees(before, after)

        val added = entries.single { it.kind == HierarchyDiffKind.Added }
        assertEquals("com.example:id/added", added.node.resourceId)

        val removed = entries.single { it.kind == HierarchyDiffKind.Removed }
        assertEquals("com.example:id/removed", removed.node.resourceId)

        val changed = entries.single { it.kind == HierarchyDiffKind.Changed }
        assertEquals("com.example:id/stable", changed.node.resourceId)
        assertTrue(changed.changes.single().contains("bounds"))
    }

    @Test
    fun diffHierarchyTreesReturnsNoEntriesForIdenticalTrees() {
        val tree = node("root", children = listOf(node("child", text = "Same")))

        assertEquals(emptyList(), diffHierarchyTrees(tree, tree))
    }

    @Test
    fun diffHierarchyTreesTreatsAWhollyNewRootAsAdded() {
        val after = node("root", text = "New")

        val entries = diffHierarchyTrees(null, after)

        assertEquals(1, entries.size)
        assertEquals(HierarchyDiffKind.Added, entries.single().kind)
    }

    @Test
    fun resolveHierarchyDisplaySizePrefersRootBoundsOverWmSize() {
        val size = resolveHierarchyDisplaySize(
            rootBounds = "[0,0][1080,2340]",
            wmWidth = 1080,
            wmHeight = 2400,
            deviceScreenSize = "1080x2400",
        )

        assertEquals(1080 to 2340, size)
    }

    @Test
    fun highlightContentUvMapsDisplayBoundsToNormalizedCoordinates() {
        val uv = highlightContentUv(
            bounds = "[96,528][362,608]",
            displayWidth = 1080,
            displayHeight = 2340,
            streamWidth = 498,
            streamHeight = 1080,
        )

        assertEquals(96f / 1080f, uv!![0], 0.0001f)
        assertEquals(528f / 2340f, uv[1], 0.0001f)
        assertEquals(362f / 1080f, uv[2], 0.0001f)
        assertEquals(608f / 2340f, uv[3], 0.0001f)
    }

    @Test
    fun effectiveHighlightBoundsScalesNormalizedBoundsIntoStreamPixels() {
        val bounds = effectiveHighlightBounds(
            bounds = "[96,528][362,608]",
            displayWidth = 1080,
            displayHeight = 2340,
            streamWidth = 498,
            streamHeight = 1080,
        )

        assertEquals(listOf(44, 244, 167, 281), bounds)
    }

    @Test
    fun offsetBoundsYShiftsBoundsUpByScrollAmount() {
        assertEquals("[96,428][362,508]", offsetBoundsY("[96,528][362,608]", 100))
    }

    @Test
    fun resolveHighlightBoundsIsStableAtScrollTop() {
        val scrollView = inspectorDemoScrollTree(scrollY = 0)
        val text = scrollView.children.single()
        val root = node(id = "root", bounds = "[0,0][1080,2340]", children = listOf(scrollView))

        repeat(100) {
            assertEquals(
                "[48,528][1032,608]",
                resolveHighlightBounds(text.bounds, root, text),
            )
        }
    }

    @Test
    fun resolveHighlightBoundsTranslatesStaleContentBoundsAfterScroll() {
        val scrollView = inspectorDemoScrollTree(scrollY = 300)
        val text = scrollView.children.single()
        val root = node(id = "root", bounds = "[0,0][1080,2340]", children = listOf(scrollView))

        assertEquals("[48,228][1032,308]", resolveHighlightBounds(text.bounds, root, text))
    }

    @Test
    fun resolveHighlightBoundsKeepsFreshScreenBoundsAfterRecapture() {
        val scrollView = inspectorDemoScrollTree(scrollY = 300)
        val text = scrollView.children.single().copy(bounds = "[48,228][1032,468]")
        val scrolled = scrollView.copy(children = listOf(text))
        val root = node(id = "root", bounds = "[0,0][1080,2340]", children = listOf(scrolled))

        assertEquals("[48,228][1032,468]", resolveHighlightBounds(text.bounds, root, text))
    }

    @Test
    fun resolveHighlightBoundsHidesContentScrolledAboveViewport() {
        val scrollView = inspectorDemoScrollTree(scrollY = 300)
        val text = scrollView.children.single().copy(bounds = "[48,197][1032,437]")
        val scrolled = scrollView.copy(children = listOf(text))
        val root = node(id = "root", bounds = "[0,0][1080,2340]", children = listOf(scrolled))

        assertNull(resolveHighlightBounds(text.bounds, root, text))
    }

    private fun inspectorDemoScrollTree(scrollY: Int): AccessibilityNode =
        node(
            id = "scroll",
            className = "android.widget.ScrollView",
            bounds = "[0,200][1080,2200]",
            scrollable = true,
            attributes = mapOf("scroll-y" to scrollY.toString()),
            children = listOf(
                node(
                    id = "text",
                    className = "android.widget.TextView",
                    bounds = "[48,528][1032,608]",
                    text = "Inspector demo",
                ),
            ),
        )
}
