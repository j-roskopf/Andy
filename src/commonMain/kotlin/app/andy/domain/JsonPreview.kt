package app.andy.domain

internal sealed class JsonPreviewNode(
    open val path: String,
    open val label: String?,
) {
    val isContainer: Boolean
        get() = this is ObjectNode || this is ArrayNode

    data class ObjectNode(
        override val path: String,
        override val label: String?,
        val children: List<JsonPreviewNode>,
    ) : JsonPreviewNode(path, label)

    data class ArrayNode(
        override val path: String,
        override val label: String?,
        val children: List<JsonPreviewNode>,
    ) : JsonPreviewNode(path, label)

    data class ValueNode(
        override val path: String,
        override val label: String?,
        val value: String,
    ) : JsonPreviewNode(path, label)
}

internal data class JsonPreviewRow(
    val node: JsonPreviewNode,
    val depth: Int,
    val text: String,
)

internal fun parseJsonBodyPreview(body: String?): JsonPreviewNode? {
    val value = body?.trim().orEmpty()
    if (value.isBlank()) return null
    if (value.firstOrNull() !in setOf('{', '[')) return null
    return runCatching { JsonPreviewParser(value).parse() }.getOrNull()
}

internal fun flattenJsonPreview(root: JsonPreviewNode, expandedKeys: Map<String, Boolean>): List<JsonPreviewRow> {
    val rows = mutableListOf<JsonPreviewRow>()
    fun add(node: JsonPreviewNode, depth: Int) {
        rows += JsonPreviewRow(node, depth, jsonPreviewRowText(node, expandedKeys[node.path] == true))
        if (expandedKeys[node.path] == true) {
            when (node) {
                is JsonPreviewNode.ObjectNode -> node.children.forEach { add(it, depth + 1) }
                is JsonPreviewNode.ArrayNode -> node.children.forEach { add(it, depth + 1) }
                is JsonPreviewNode.ValueNode -> Unit
            }
        }
    }
    add(root, 0)
    return rows
}

internal fun jsonPreviewRowText(node: JsonPreviewNode, expanded: Boolean): String {
    val label = node.label?.let { "$it: " }.orEmpty()
    return when (node) {
        is JsonPreviewNode.ObjectNode -> {
            if (expanded) "$label{${node.children.size} keys}" else "$label{...}  ${node.children.size} keys"
        }
        is JsonPreviewNode.ArrayNode -> {
            if (expanded) "$label[${node.children.size} items]" else "$label[...]  ${node.children.size} items"
        }
        is JsonPreviewNode.ValueNode -> "$label${node.value}"
    }
}

internal class JsonPreviewParser(private val source: String) {
    private var index = 0

    fun parse(): JsonPreviewNode {
        val node = parseValue("$", null)
        skipWhitespace()
        require(index == source.length)
        return node
    }

    private fun parseValue(path: String, label: String?): JsonPreviewNode {
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject(path, label)
            '[' -> parseArray(path, label)
            '"' -> JsonPreviewNode.ValueNode(path, label, quoteJsonPreview(parseString()))
            else -> JsonPreviewNode.ValueNode(path, label, parseLiteral())
        }
    }

    private fun parseObject(path: String, label: String?): JsonPreviewNode.ObjectNode {
        expect('{')
        skipWhitespace()
        val children = mutableListOf<JsonPreviewNode>()
        if (consume('}')) return JsonPreviewNode.ObjectNode(path, label, children)
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            children += parseValue("$path.${escapePathSegment(key)}", quoteJsonPreview(key))
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        return JsonPreviewNode.ObjectNode(path, label, children)
    }

    private fun parseArray(path: String, label: String?): JsonPreviewNode.ArrayNode {
        expect('[')
        skipWhitespace()
        val children = mutableListOf<JsonPreviewNode>()
        if (consume(']')) return JsonPreviewNode.ArrayNode(path, label, children)
        var childIndex = 0
        while (true) {
            children += parseValue("$path[$childIndex]", "[$childIndex]")
            childIndex += 1
            skipWhitespace()
            if (consume(']')) break
            expect(',')
        }
        return JsonPreviewNode.ArrayNode(path, label, children)
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> {
                    require(index < source.length)
                    val escaped = source[index++]
                    builder.append('\\')
                    builder.append(escaped)
                    if (escaped == 'u') {
                        repeat(4) {
                            require(index < source.length)
                            builder.append(source[index++])
                        }
                    }
                }
                else -> builder.append(char)
            }
        }
        error("Unterminated string")
    }

    private fun parseLiteral(): String {
        val start = index
        while (index < source.length && !source[index].isJsonLiteralTerminator()) {
            index += 1
        }
        require(index > start)
        return source.substring(start, index)
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) {
            index += 1
        }
    }

    private fun peek(): Char {
        require(index < source.length)
        return source[index]
    }

    private fun consume(char: Char): Boolean {
        if (index < source.length && source[index] == char) {
            index += 1
            return true
        }
        return false
    }

    private fun expect(char: Char) {
        require(consume(char))
    }
}

internal fun escapePathSegment(value: String): String {
    return value.replace("\\", "\\\\").replace(".", "\\.")
}

private fun Char.isJsonLiteralTerminator(): Boolean {
    return this == ',' || this == '}' || this == ']' || this == ' ' || this == '\n' || this == '\r' || this == '\t'
}

internal fun quoteJsonPreview(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
