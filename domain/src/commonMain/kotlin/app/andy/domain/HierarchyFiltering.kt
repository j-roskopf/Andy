package app.andy.domain

import app.andy.model.AccessibilityNode

/**
 * Drops nodes that are not visible-to-user, for [app.andy.model.HierarchyOptions.includeInvisible]
 * (§D.3/D.4). A node survives if it is itself visible, or if it has at least one surviving
 * visible descendant (an invisible container wrapping visible content is rare, but keeping the
 * structure in that case beats silently losing children).
 */
fun AccessibilityNode.filterInvisible(): AccessibilityNode? {
    val filteredChildren = children.mapNotNull { it.filterInvisible() }
    if (!visible && filteredChildren.isEmpty()) return null
    return copy(children = filteredChildren)
}

/** True if [query] (case-insensitive) appears in this node's own text/id/class fields. */
fun AccessibilityNode.matchesHierarchyQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return listOfNotNull(text, contentDescription, resourceId, className)
        .any { it.contains(query, ignoreCase = true) }
}

/**
 * Keeps a node if it or any descendant matches [query] (§D.4's filter/search), preserving
 * ancestor structure the way [filterInvisible] does. `null`/blank query keeps the whole tree.
 */
fun AccessibilityNode.filterBySearch(query: String): AccessibilityNode? {
    if (query.isBlank()) return this
    val filteredChildren = children.mapNotNull { it.filterBySearch(query) }
    if (!matchesHierarchyQuery(query) && filteredChildren.isEmpty()) return null
    return copy(children = filteredChildren)
}
