package app.andy.domain

import app.andy.model.NetworkExchange

internal class MutableNetworkTrafficNode(
    val key: String,
    val label: String,
    val depth: Int,
) {
    val children = linkedMapOf<String, MutableNetworkTrafficNode>()
    val exchanges = mutableListOf<NetworkExchange>()
}

internal data class NetworkTrafficNode(
    val key: String,
    val label: String,
    val depth: Int,
    val exchanges: List<NetworkExchange>,
    val children: List<NetworkTrafficNode>,
) {
    val count: Int = exchanges.size + children.sumOf { it.count }
    val latest: NetworkExchange? = (exchanges + children.mapNotNull { it.latest }).maxByOrNull { it.completedAtMillis ?: it.startedAtMillis }
}

internal data class NetworkTrafficRow(
    val key: String,
    val label: String,
    val depth: Int,
    val hasChildren: Boolean,
    val count: Int,
    val latest: NetworkExchange?,
    val exchange: NetworkExchange?,
)

internal data class NetworkUrlParts(
    val baseUrl: String,
    val pathSegments: List<String>,
)

internal fun buildNetworkTrafficTree(exchanges: List<NetworkExchange>): List<NetworkTrafficNode> {
    val roots = linkedMapOf<String, MutableNetworkTrafficNode>()
    exchanges.forEach { exchange ->
        val parts = networkUrlParts(exchange.url)
        val baseKey = "base:${parts.baseUrl}"
        var current = roots.getOrPut(baseKey) { MutableNetworkTrafficNode(baseKey, parts.baseUrl, 0) }
        var pathKey = baseKey
        parts.pathSegments.forEachIndexed { index, segment ->
            pathKey += "/$segment"
            current = current.children.getOrPut(pathKey) {
                MutableNetworkTrafficNode(pathKey, segment, index + 1)
            }
        }
        current.exchanges += exchange
    }
    return roots.values.map { it.toImmutableNode() }.sortedBy { it.label.lowercase() }
}

internal fun MutableNetworkTrafficNode.toImmutableNode(): NetworkTrafficNode {
    return NetworkTrafficNode(
        key = key,
        label = label,
        depth = depth,
        exchanges = exchanges.sortedByDescending { it.completedAtMillis ?: it.startedAtMillis },
        children = children.values.map { it.toImmutableNode() }.sortedBy { it.label.lowercase() },
    )
}

internal fun flattenNetworkTrafficTree(nodes: List<NetworkTrafficNode>, expandedKeys: Map<String, Boolean>): List<NetworkTrafficRow> {
    val rows = mutableListOf<NetworkTrafficRow>()
    fun addNode(node: NetworkTrafficNode) {
        rows += NetworkTrafficRow(
            key = node.key,
            label = node.label,
            depth = node.depth,
            hasChildren = node.children.isNotEmpty() || node.exchanges.isNotEmpty(),
            count = node.count,
            latest = node.latest,
            exchange = null,
        )
        if (expandedKeys[node.key] == true) {
            node.children.forEach(::addNode)
            node.exchanges.forEach { exchange ->
                rows += NetworkTrafficRow(
                    key = "call:${exchange.flowId}",
                    label = exchange.url.substringAfterLast('/').substringBefore('?').ifBlank { "/" },
                    depth = node.depth + 1,
                    hasChildren = false,
                    count = 1,
                    latest = exchange,
                    exchange = exchange,
                )
            }
        }
    }
    nodes.forEach(::addNode)
    return rows
}

internal fun networkTrafficAncestorKeys(exchange: NetworkExchange): List<String> {
    val parts = networkUrlParts(exchange.url)
    val keys = mutableListOf("base:${parts.baseUrl}")
    var key = keys.first()
    parts.pathSegments.forEach { segment ->
        key += "/$segment"
        keys += key
    }
    return keys
}

internal fun networkUrlParts(url: String): NetworkUrlParts {
    val withoutFragment = url.substringBefore('#')
    val schemeSplit = withoutFragment.indexOf("://")
    val afterAuthorityStart = if (schemeSplit >= 0) schemeSplit + 3 else 0
    val firstPathIndex = withoutFragment.indexOf('/', startIndex = afterAuthorityStart).takeIf { it >= 0 }
    val firstQueryIndex = withoutFragment.indexOf('?', startIndex = afterAuthorityStart).takeIf { it >= 0 }
    val authorityEnd = listOfNotNull(firstPathIndex, firstQueryIndex).minOrNull() ?: withoutFragment.length
    val authority = withoutFragment.substring(0, authorityEnd).ifBlank { "unknown" }
    val pathStart = firstPathIndex ?: withoutFragment.length
    val rawPath = withoutFragment.substring(pathStart).substringBefore('?')
    val segments = rawPath.split('/').filter { it.isNotBlank() }
    return NetworkUrlParts(authority, segments.ifEmpty { listOf("/") })
}
