package app.andy.domain

import app.andy.model.NetworkExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkTrafficTreeTest {
    @Test
    fun networkUrlPartsSplitsAuthorityAndPath() {
        val parts = networkUrlParts("https://api.example.com/v1/users?id=1#frag")
        assertEquals("https://api.example.com", parts.baseUrl)
        assertEquals(listOf("v1", "users"), parts.pathSegments)
    }

    @Test
    fun networkUrlPartsUsesSlashWhenPathEmpty() {
        val parts = networkUrlParts("https://api.example.com")
        assertEquals("https://api.example.com", parts.baseUrl)
        assertEquals(listOf("/"), parts.pathSegments)
    }

    @Test
    fun buildAndFlattenTreeGroupsByHostAndPath() {
        val exchanges = listOf(
            exchange("1", "https://api.example.com/v1/users", 100),
            exchange("2", "https://api.example.com/v1/posts", 200),
            exchange("3", "https://other.example.com/health", 150),
        )
        val tree = buildNetworkTrafficTree(exchanges)
        assertEquals(2, tree.size)
        assertEquals("https://api.example.com", tree.first().label)

        val collapsed = flattenNetworkTrafficTree(tree, emptyMap())
        assertEquals(2, collapsed.size)
        assertTrue(collapsed.all { it.exchange == null })

        val apiRoot = tree.first { it.label == "https://api.example.com" }
        fun collectKeys(node: NetworkTrafficNode): List<String> =
            listOf(node.key) + node.children.flatMap(::collectKeys)
        val expanded = flattenNetworkTrafficTree(
            tree,
            collectKeys(apiRoot).associateWith { true },
        )
        assertTrue(expanded.any { it.exchange?.flowId == "1" })
        assertTrue(expanded.any { it.exchange?.flowId == "2" })
        assertNull(expanded.firstOrNull { it.exchange?.flowId == "3" })
    }

    @Test
    fun networkTrafficAncestorKeysListsBaseAndSegments() {
        val keys = networkTrafficAncestorKeys(exchange("1", "https://api.example.com/v1/users", 1))
        assertEquals(
            listOf(
                "base:https://api.example.com",
                "base:https://api.example.com/v1",
                "base:https://api.example.com/v1/users",
            ),
            keys,
        )
    }

    @Test
    fun filterExchangesByFocusedPathsUsesOrSemantics() {
        val exchanges = listOf(
            exchange("1", "https://api.example.com/v1/users", 100),
            exchange("2", "https://api.example.com/v1/posts", 200),
            exchange("3", "https://other.example.com/health", 150),
        )
        val filtered = filterExchangesByFocusedPaths(
            exchanges,
            setOf(
                "base:https://api.example.com/v1/users",
                "base:https://other.example.com",
            ),
        )
        assertEquals(listOf("1", "3"), filtered.map { it.flowId })
    }

    @Test
    fun filterExchangesByFocusedPathsEmptyReturnsAll() {
        val exchanges = listOf(exchange("1", "https://api.example.com/v1/users", 100))
        assertEquals(exchanges, filterExchangesByFocusedPaths(exchanges, emptySet()))
    }

    @Test
    fun toggleFocusedPathAddsAndRemoves() {
        val once = toggleFocusedPath(emptySet(), "base:https://api.example.com")
        assertEquals(setOf("base:https://api.example.com"), once)
        val twice = toggleFocusedPath(once, "base:https://api.example.com")
        assertEquals(emptySet(), twice)
    }

    @Test
    fun historyTrafficRowsAreNewestFirstAndUngrouped() {
        val exchanges = listOf(
            exchange("1", "https://api.example.com/v1/users", 100),
            exchange("2", "https://api.example.com/v1/posts", 300),
            exchange("3", "https://other.example.com/health", 200),
        )
        val rows = historyTrafficRows(exchanges)
        assertEquals(listOf("2", "3", "1"), rows.map { it.exchange?.flowId })
        assertTrue(rows.all { it.exchange != null && it.depth == 0 })
        assertEquals("https://api.example.com/v1/posts", rows.first().label)
    }

    @Test
    fun focusedTreeKeepsOtherTrafficCollapsedUnderOtherGroup() {
        val exchanges = listOf(
            exchange("1", "https://api.example.com/v1/users", 100),
            exchange("2", "https://api.example.com/v1/posts", 200),
            exchange("3", "https://other.example.com/health", 150),
        )
        val tree = buildFocusedNetworkTrafficTree(
            exchanges,
            setOf("base:https://api.example.com/v1/users"),
        )
        assertEquals(
            listOf("https://api.example.com", OtherTrafficLabel),
            tree.map { it.label },
        )
        val other = tree.last()
        assertEquals(OtherTrafficKey, other.key)
        assertEquals(2, other.count)
        assertEquals(2, other.children.size)
        assertTrue(other.children.all { it.depth == 1 })

        val collapsed = flattenNetworkTrafficTree(tree, emptyMap())
        assertEquals(
            listOf("base:https://api.example.com", OtherTrafficKey),
            collapsed.map { it.key },
        )
        assertNull(collapsed.firstOrNull { it.exchange?.flowId == "3" })

        val expandedOther = flattenNetworkTrafficTree(tree, mapOf(OtherTrafficKey to true))
        assertTrue(expandedOther.any { it.key == "focus:other|base:https://other.example.com" })
        assertTrue(expandedOther.any { it.key == "focus:other|base:https://api.example.com" && it.depth == 1 })
        assertEquals(
            "base:https://other.example.com",
            trafficFocusPathKey("focus:other|base:https://other.example.com"),
        )
        assertEquals(
            1,
            expandedOther.first { it.key == "focus:other|base:https://other.example.com" }.depth,
        )
    }

    @Test
    fun focusedHistoryKeepsOtherTrafficCollapsedUnderOtherGroup() {
        val exchanges = listOf(
            exchange("1", "https://api.example.com/v1/users", 100),
            exchange("2", "https://api.example.com/v1/posts", 300),
            exchange("3", "https://other.example.com/health", 200),
        )
        val collapsed = historyTrafficRows(
            exchanges,
            focusedPaths = setOf("base:https://api.example.com/v1/users"),
        )
        assertEquals("1", collapsed.first().exchange?.flowId)
        assertEquals(OtherTrafficKey, collapsed.last().key)
        assertEquals(2, collapsed.last().count)
        assertEquals(2, collapsed.size)

        val expanded = historyTrafficRows(
            exchanges,
            focusedPaths = setOf("base:https://api.example.com/v1/users"),
            expandedKeys = mapOf(OtherTrafficKey to true),
        )
        assertEquals(listOf("1", null, "2", "3"), expanded.map { it.exchange?.flowId })
        assertEquals(1, expanded.first { it.exchange?.flowId == "2" }.depth)
    }

    @Test
    fun networkTrafficHostAndLeafKeys() {
        val exchange = exchange("1", "https://api.example.com/v1/users", 1)
        assertEquals("base:https://api.example.com", networkTrafficHostKey(exchange))
        assertEquals("base:https://api.example.com/v1/users", networkTrafficLeafKey(exchange))
        assertEquals("https://api.example.com/v1/users", focusedPathLabel(networkTrafficLeafKey(exchange)))
    }

    private fun exchange(flowId: String, url: String, startedAt: Long) = NetworkExchange(
        id = flowId,
        startedAtMillis = startedAt,
        completedAtMillis = startedAt + 10,
        method = "GET",
        url = url,
        statusCode = 200,
        contentType = "application/json",
        sizeBytes = 10,
        durationMillis = 10,
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        requestBodyPreview = null,
        responseBodyPreview = null,
        error = null,
        tlsStatus = null,
        matchedRuleId = null,
        flowId = flowId,
    )
}
