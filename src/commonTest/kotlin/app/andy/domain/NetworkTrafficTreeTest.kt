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
