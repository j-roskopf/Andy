package app.andy.domain

import app.andy.model.NetworkExchange

 class MutableNetworkTrafficNode(
    val key: String,
    val label: String,
    val depth: Int,
) {
    val children = linkedMapOf<String, MutableNetworkTrafficNode>()
    val exchanges = mutableListOf<NetworkExchange>()
}

 data class NetworkTrafficNode(
    val key: String,
    val label: String,
    val depth: Int,
    val exchanges: List<NetworkExchange>,
    val children: List<NetworkTrafficNode>,
) {
    val count: Int = exchanges.size + children.sumOf { it.count }
    val latest: NetworkExchange? = (exchanges + children.mapNotNull { it.latest }).maxByOrNull { it.completedAtMillis ?: it.startedAtMillis }
}

 data class NetworkTrafficRow(
    val key: String,
    val label: String,
    val depth: Int,
    val hasChildren: Boolean,
    val count: Int,
    val latest: NetworkExchange?,
    val exchange: NetworkExchange?,
)

 data class NetworkUrlParts(
    val baseUrl: String,
    val pathSegments: List<String>,
)

 enum class NetworkTrafficView {
    Tree,
    History,
}

/** Synthetic top-level group for traffic that does not match the current focus set. */
 const val OtherTrafficKey = "focus:other"
 const val OtherTrafficLabel = "Other"
private const val OtherTrafficKeyPrefix = "$OtherTrafficKey|"

 fun isOtherTrafficKey(key: String): Boolean {
    return key == OtherTrafficKey || key.startsWith(OtherTrafficKeyPrefix)
}

/** Path key used for focus matching; strips the Other-group UI prefix when present. */
 fun trafficFocusPathKey(rowKey: String): String {
    return rowKey.removePrefix(OtherTrafficKeyPrefix)
}

 fun buildNetworkTrafficTree(exchanges: List<NetworkExchange>): List<NetworkTrafficNode> {
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

 fun MutableNetworkTrafficNode.toImmutableNode(): NetworkTrafficNode {
    return NetworkTrafficNode(
        key = key,
        label = label,
        depth = depth,
        exchanges = exchanges.sortedByDescending { it.completedAtMillis ?: it.startedAtMillis },
        children = children.values.map { it.toImmutableNode() }.sortedBy { it.label.lowercase() },
    )
}

 fun flattenNetworkTrafficTree(nodes: List<NetworkTrafficNode>, expandedKeys: Map<String, Boolean>): List<NetworkTrafficRow> {
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

 fun historyTrafficRows(
    exchanges: List<NetworkExchange>,
    focusedPaths: Set<String> = emptySet(),
    expandedKeys: Map<String, Boolean> = emptyMap(),
): List<NetworkTrafficRow> {
    if (focusedPaths.isEmpty()) {
        return historyCallRows(exchanges, depth = 0)
    }
    val (focused, other) = partitionExchangesByFocusedPaths(exchanges, focusedPaths)
    val rows = historyCallRows(focused, depth = 0).toMutableList()
    if (other.isNotEmpty()) {
        val sortedOther = other.sortedByDescending { it.completedAtMillis ?: it.startedAtMillis }
        rows += NetworkTrafficRow(
            key = OtherTrafficKey,
            label = OtherTrafficLabel,
            depth = 0,
            hasChildren = true,
            count = other.size,
            latest = sortedOther.firstOrNull(),
            exchange = null,
        )
        if (expandedKeys[OtherTrafficKey] == true) {
            rows += historyCallRows(sortedOther, depth = 1)
        }
    }
    return rows
}

private fun historyCallRows(exchanges: List<NetworkExchange>, depth: Int): List<NetworkTrafficRow> {
    return exchanges
        .sortedByDescending { it.completedAtMillis ?: it.startedAtMillis }
        .map { exchange ->
            NetworkTrafficRow(
                key = "call:${exchange.flowId}",
                label = exchange.url,
                depth = depth,
                hasChildren = false,
                count = 1,
                latest = exchange,
                exchange = exchange,
            )
        }
}

 fun buildFocusedNetworkTrafficTree(
    exchanges: List<NetworkExchange>,
    focusedPaths: Set<String>,
): List<NetworkTrafficNode> {
    if (focusedPaths.isEmpty()) return buildNetworkTrafficTree(exchanges)
    val (focused, other) = partitionExchangesByFocusedPaths(exchanges, focusedPaths)
    val focusedRoots = buildNetworkTrafficTree(focused)
    if (other.isEmpty()) return focusedRoots
    val otherWrapper = NetworkTrafficNode(
        key = OtherTrafficKey,
        label = OtherTrafficLabel,
        depth = 0,
        exchanges = emptyList(),
        children = buildNetworkTrafficTree(other).map {
            it.withDepthOffset(1).withKeyPrefix(OtherTrafficKeyPrefix)
        },
    )
    return focusedRoots + otherWrapper
}

 fun NetworkTrafficNode.withDepthOffset(offset: Int): NetworkTrafficNode {
    return copy(
        depth = depth + offset,
        children = children.map { it.withDepthOffset(offset) },
    )
}

 fun NetworkTrafficNode.withKeyPrefix(prefix: String): NetworkTrafficNode {
    return copy(
        key = prefix + key,
        children = children.map { it.withKeyPrefix(prefix) },
    )
}

 fun exchangeMatchesFocusedPaths(
    exchange: NetworkExchange,
    focusedPaths: Set<String>,
): Boolean {
    if (focusedPaths.isEmpty()) return true
    return networkTrafficAncestorKeys(exchange).any { it in focusedPaths }
}

 fun partitionExchangesByFocusedPaths(
    exchanges: List<NetworkExchange>,
    focusedPaths: Set<String>,
): Pair<List<NetworkExchange>, List<NetworkExchange>> {
    if (focusedPaths.isEmpty()) return exchanges to emptyList()
    val focused = ArrayList<NetworkExchange>()
    val other = ArrayList<NetworkExchange>()
    exchanges.forEach { exchange ->
        if (exchangeMatchesFocusedPaths(exchange, focusedPaths)) {
            focused += exchange
        } else {
            other += exchange
        }
    }
    return focused to other
}

 fun filterExchangesByFocusedPaths(
    exchanges: List<NetworkExchange>,
    focusedPaths: Set<String>,
): List<NetworkExchange> {
    if (focusedPaths.isEmpty()) return exchanges
    return exchanges.filter { exchangeMatchesFocusedPaths(it, focusedPaths) }
}

 fun toggleFocusedPath(focusedPaths: Set<String>, path: String): Set<String> {
    return if (path in focusedPaths) focusedPaths - path else focusedPaths + path
}

 fun networkTrafficHostKey(exchange: NetworkExchange): String {
    return "base:${networkUrlParts(exchange.url).baseUrl}"
}

 fun networkTrafficLeafKey(exchange: NetworkExchange): String {
    return networkTrafficAncestorKeys(exchange).last()
}

 fun focusedPathLabel(path: String): String = path.removePrefix("base:")

 fun networkTrafficAncestorKeys(exchange: NetworkExchange): List<String> {
    val parts = networkUrlParts(exchange.url)
    val keys = mutableListOf("base:${parts.baseUrl}")
    var key = keys.first()
    parts.pathSegments.forEach { segment ->
        key += "/$segment"
        keys += key
    }
    return keys
}

 fun networkUrlParts(url: String): NetworkUrlParts {
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
