package app.andy.ui.hierarchy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.domain.parseBounds
import app.andy.model.AccessibilityNode
import app.andy.ui.components.DetailRow
import app.andy.ui.components.DetailSection
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun HierarchyNodeRow(
    row: HierarchyTreeRow,
    hoveredBounds: String?,
    selectedId: String?,
    isCollapsed: Boolean,
    onHover: (String?) -> Unit,
    onSelect: (AccessibilityNode) -> Unit,
    onToggleCollapse: () -> Unit,
) {
    val node = row.node
    val active = node.bounds == hoveredBounds || node.id == selectedId
    val hasChildren = node.children.isNotEmpty()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        onHover(if (isHovered) node.bounds else null)
    }
    Row(
        Modifier.widthIn(min = 900.dp)
            .background(if (active) Rust.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(4.dp))
            .hoverable(interactionSource)
            .clickable { onSelect(node) }
            .padding(start = (row.depth * 12).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clickable(
                    enabled = hasChildren,
                    onClick = onToggleCollapse,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (hasChildren) {
                Text(
                    text = if (isCollapsed) ">" else "v",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${node.className?.substringAfterLast('.') ?: "node"}  ${node.bounds ?: ""}",
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val label = listOfNotNull(node.resourceId, node.text, node.contentDescription).joinToString(" · ")
            if (label.isNotBlank()) {
                Text(label, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal data class HierarchyTreeRow(val node: AccessibilityNode, val depth: Int)

internal fun AccessibilityNode.flattenHierarchyTree(
    collapsedNodes: Map<String, Boolean>,
    depth: Int = 0,
): List<HierarchyTreeRow> {
    val row = HierarchyTreeRow(this, depth)
    val isCollapsed = collapsedNodes[this.id] == true
    return if (isCollapsed) {
        listOf(row)
    } else {
        listOf(row) + children.flatMap { it.flattenHierarchyTree(collapsedNodes, depth + 1) }
    }
}

internal fun AccessibilityNode.countNodes(): Int = 1 + children.sumOf { it.countNodes() }

internal fun AccessibilityNode.findNodeById(id: String): AccessibilityNode? {
    if (this.id == id) return this
    return children.firstNotNullOfOrNull { it.findNodeById(id) }
}

/** Re-select the same logical node after a fresh hierarchy capture when tree paths shift. */
internal fun AccessibilityNode.findEquivalentNode(previous: AccessibilityNode): AccessibilityNode? {
    findNodeById(previous.id)?.let { return it }
    return findFirst {
        it.className == previous.className &&
            it.resourceId == previous.resourceId &&
            it.text == previous.text &&
            it.contentDescription == previous.contentDescription
    }
}

private fun AccessibilityNode.findFirst(predicate: (AccessibilityNode) -> Boolean): AccessibilityNode? {
    if (predicate(this)) return this
    return children.firstNotNullOfOrNull { it.findFirst(predicate) }
}

internal fun AccessibilityNode.isInterestingHierarchyNode(): Boolean {
    if (!visible || !enabled) return false
    val pkg = packageName
    if (pkg.isNullOrBlank() || pkg.startsWith("com.android.systemui")) return false
    val hasIdentity = !text.isNullOrBlank() || !contentDescription.isNullOrBlank() || !resourceId.isNullOrBlank()
    return hasIdentity || clickable || scrollable
}

@Composable
internal fun HierarchyDetailsPanel(node: AccessibilityNode?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Selected", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text(
            node?.className ?: "No node",
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(node?.id?.let { "node[$it]" } ?: "-", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (node == null) return@Column
        val issues = buildList {
            if (node.clickable && node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()) add("No accessibility label")
            if (!node.visible) add("Not visible to user")
            if (!node.enabled) add("Disabled")
        }
        if (issues.isNotEmpty()) {
            Text("${issues.size} issues", color = Rust, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            issues.forEach { issue ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Red, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("NAF", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(issue, color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
        DetailSection("Identity")
        DetailRow("resource-id", node.resourceId)
        DetailRow("class", node.className?.substringAfterLast('.'))
        DetailRow("class-full", node.className)
        DetailRow("package", node.packageName)
        DetailRow("node-id", node.id)
        DetailRow("children", node.children.size.toString())
        DetailSection("Content")
        DetailRow("text", node.text)
        DetailRow("content-desc", node.contentDescription)
        DetailRow("hint", node.hint)
        DetailSection("Geometry")
        DetailRow("bounds", node.bounds)
        DetailRow("size", parseBounds(node.bounds)?.let { "${it[2] - it[0]}x${it[3] - it[1]}" })
        DetailSection("State")
        DetailRow("clickable", node.clickable.toString())
        DetailRow("long-clickable", node.longClickable.toString())
        DetailRow("focusable", node.focusable.toString())
        DetailRow("focused", node.focused.toString())
        DetailRow("enabled", node.enabled.toString())
        DetailRow("selected", node.selected.toString())
        DetailRow("checkable", node.checkable.toString())
        DetailRow("checked", node.checked.toString())
        DetailRow("scrollable", node.scrollable.toString())
        DetailRow("password", node.password.toString())
        DetailRow("visible", node.visible.toString())
        DetailSection("Computed")
        DetailRow("contrast", "-")
        DetailRow("label", node.contentDescription ?: node.text ?: node.hint)
        if (node.attributes.isNotEmpty()) {
            DetailSection("Raw Dump")
            node.attributes.entries.sortedBy { it.key }.forEach { (key, value) ->
                DetailRow(key, value)
            }
        }
    }
}
