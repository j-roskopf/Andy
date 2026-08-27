package app.andy.domain

import app.andy.model.AccessibilityNode

enum class HierarchyDiffKind { Added, Removed, Changed }

data class HierarchyDiffEntry(
    /** A stable-ish path built from resource-id/class + sibling index, for display and de-dup. */
    val path: String,
    val kind: HierarchyDiffKind,
    val node: AccessibilityNode,
    /** Human-readable `field: before -> after` lines; empty for [HierarchyDiffKind.Added]/[HierarchyDiffKind.Removed]. */
    val changes: List<String> = emptyList(),
)

/**
 * Structural tree diff between two hierarchy captures (§D.4's "snapshot compare") — a sibling to
 * [parseUnifiedDiff] for text. Nodes are matched at each depth by resource-id when present,
 * else by class name, plus a same-key occurrence index; that is good enough for "what changed
 * after this interaction" without a stable cross-capture node identity (uiautomator/dumpsys
 * assign none).
 */
fun diffHierarchyTrees(before: AccessibilityNode?, after: AccessibilityNode?): List<HierarchyDiffEntry> {
    val entries = mutableListOf<HierarchyDiffEntry>()

    fun matchKey(node: AccessibilityNode): String = node.resourceId?.takeIf { it.isNotBlank() } ?: (node.className ?: "?")

    fun keyedChildren(node: AccessibilityNode): List<Pair<String, AccessibilityNode>> {
        val seen = mutableMapOf<String, Int>()
        return node.children.map { child ->
            val base = matchKey(child)
            val occurrence = seen[base] ?: 0
            seen[base] = occurrence + 1
            "$base#$occurrence" to child
        }
    }

    fun changesBetween(beforeNode: AccessibilityNode, afterNode: AccessibilityNode): List<String> = buildList {
        if (beforeNode.bounds != afterNode.bounds) add("bounds: ${beforeNode.bounds} -> ${afterNode.bounds}")
        if (beforeNode.text != afterNode.text) add("text: ${beforeNode.text} -> ${afterNode.text}")
        if (beforeNode.contentDescription != afterNode.contentDescription) {
            add("content-desc: ${beforeNode.contentDescription} -> ${afterNode.contentDescription}")
        }
        if (beforeNode.visible != afterNode.visible) add("visible: ${beforeNode.visible} -> ${afterNode.visible}")
        if (beforeNode.enabled != afterNode.enabled) add("enabled: ${beforeNode.enabled} -> ${afterNode.enabled}")
        if (beforeNode.selected != afterNode.selected) add("selected: ${beforeNode.selected} -> ${afterNode.selected}")
        if (beforeNode.checked != afterNode.checked) add("checked: ${beforeNode.checked} -> ${afterNode.checked}")
        if (beforeNode.className != afterNode.className) add("class: ${beforeNode.className} -> ${afterNode.className}")
    }

    fun walk(path: String, beforeNode: AccessibilityNode?, afterNode: AccessibilityNode?) {
        when {
            beforeNode == null && afterNode != null -> {
                entries += HierarchyDiffEntry(path, HierarchyDiffKind.Added, afterNode)
                keyedChildren(afterNode).forEach { (key, child) -> walk("$path/$key", null, child) }
            }
            beforeNode != null && afterNode == null -> {
                entries += HierarchyDiffEntry(path, HierarchyDiffKind.Removed, beforeNode)
                keyedChildren(beforeNode).forEach { (key, child) -> walk("$path/$key", child, null) }
            }
            beforeNode != null && afterNode != null -> {
                val changes = changesBetween(beforeNode, afterNode)
                if (changes.isNotEmpty()) entries += HierarchyDiffEntry(path, HierarchyDiffKind.Changed, afterNode, changes)
                val beforeChildren = keyedChildren(beforeNode)
                val afterChildren = keyedChildren(afterNode)
                val orderedKeys = LinkedHashSet<String>().apply {
                    addAll(beforeChildren.map { it.first })
                    addAll(afterChildren.map { it.first })
                }
                orderedKeys.forEach { key ->
                    val b = beforeChildren.firstOrNull { it.first == key }?.second
                    val a = afterChildren.firstOrNull { it.first == key }?.second
                    walk("$path/$key", b, a)
                }
            }
        }
    }

    walk("root", before, after)
    return entries
}
