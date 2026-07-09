package app.andy.ui.hostfiles

import app.andy.model.HostFileEntry

internal data class HostTreeRow(val entry: HostFileEntry, val depth: Int)


internal fun hostFileName(path: String): String {
    val trimmed = trimHostTrailingSeparators(path)
    val index = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return if (index >= 0 && index < trimmed.lastIndex) trimmed.substring(index + 1) else trimmed.ifBlank { path }
}

internal fun hostTreeRows(root: String, children: Map<String, List<HostFileEntry>>, expanded: Map<String, Boolean>): List<HostTreeRow> {
    val rootEntry = HostFileEntry(
        path = root,
        name = hostFileName(root).ifBlank { root },
        isDirectory = true,
        sizeBytes = 0L,
        modifiedMillis = 0L,
    )
    val rows = mutableListOf(HostTreeRow(rootEntry, 0))
    fun append(parent: HostFileEntry, depth: Int) {
        if (expanded[parent.path] != true) return
        children[parent.path].orEmpty().forEach { child ->
            rows += HostTreeRow(child, depth)
            if (child.isDirectory) append(child, depth + 1)
        }
    }
    append(rootEntry, 1)
    return rows
}

internal fun hostParentPath(path: String): String {
    val trimmed = trimHostTrailingSeparators(path)
    if (trimmed == "/" || trimmed.matches(Regex("^[A-Za-z]:$"))) return trimmed
    val index = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return when {
        index < 0 -> trimmed.ifBlank { "/" }
        index == 0 -> trimmed.substring(0, 1)
        index == 2 && trimmed.getOrNull(1) == ':' -> trimmed.substring(0, 3)
        else -> trimmed.substring(0, index).ifBlank { "/" }
    }
}

internal fun resolveHostRootForPath(path: String?, roots: List<String>): String? {
    val normalizedPath = path?.let(::normalizeHostPath) ?: return null
    return roots.sortedByDescending { normalizeHostPath(it).length }.firstOrNull { root ->
        hostPathStartsWith(normalizedPath, root)
    }
}

internal fun hostPathStartsWith(path: String, root: String): Boolean {
    val normalizedPath = normalizeHostPath(path)
    val normalizedRoot = normalizeHostPath(root)
    val childPrefix = if (normalizedRoot.endsWith('/')) normalizedRoot else "$normalizedRoot/"
    return normalizedPath == normalizedRoot || normalizedPath.startsWith(childPrefix)
}

internal fun normalizeHostPath(path: String): String = trimHostTrailingSeparators(path).replace('\\', '/').ifBlank { "/" }

internal fun trimHostTrailingSeparators(path: String): String {
    var value = path.trim()
    while (value.length > 1 && (value.endsWith('/') || value.endsWith('\\'))) {
        if (value.length == 3 && value[1] == ':') break
        value = value.dropLast(1)
    }
    return value.ifBlank { "/" }
}

internal fun hostAncestorDirectories(path: String, root: String): List<String> {
    val normalizedRoot = normalizeHostPath(root)
    val displayRoot = trimHostTrailingSeparators(root)
    val parent = hostParentPath(path)
    if (!hostPathStartsWith(parent, normalizedRoot)) return listOf(displayRoot)
    val relativeParent = normalizeHostPath(parent).removePrefix(normalizedRoot).trim('/')
    if (relativeParent.isBlank()) return listOf(displayRoot)
    val separator = if (root.contains('\\') && !root.contains('/')) "\\" else "/"
    val ancestors = mutableListOf(displayRoot)
    var current = displayRoot
    relativeParent.split('/').filter { it.isNotBlank() }.forEach { segment ->
        current = when {
            current == "/" -> "/$segment"
            current.endsWith('/') || current.endsWith('\\') -> "$current$segment"
            else -> "$current$separator$segment"
        }
        ancestors += current
    }
    return ancestors
}

internal fun hostDisplayPath(path: String, root: String): String {
    val normalizedPath = normalizeHostPath(path)
    val normalizedRoot = normalizeHostPath(root)
    return normalizedPath.removePrefix(normalizedRoot).trimStart('/').ifBlank { hostFileName(path) }
}
