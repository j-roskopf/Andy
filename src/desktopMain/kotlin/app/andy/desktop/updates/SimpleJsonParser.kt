package app.andy.desktop.updates

class SimpleJsonParser(private val json: String) {
    private var pos = 0

    fun parse(): Any? {
        skipWhitespace()
        if (pos >= json.length) return null
        return when (val c = json[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> if (c == '-' || c in '0'..'9') parseNumber() else error("Unexpected char '$c' at $pos")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        match('{')
        val map = mutableMapOf<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            match('}')
            return map
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            match(':')
            val value = parse()
            map[key] = value
            skipWhitespace()
            when (val c = peek()) {
                ',' -> { match(','); continue }
                '}' -> { match('}'); break }
                else -> error("Expected ',' or '}' but got '$c' at $pos")
            }
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        match('[')
        val list = mutableListOf<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            match(']')
            return list
        }
        while (true) {
            list.add(parse())
            skipWhitespace()
            when (val c = peek()) {
                ',' -> { match(','); continue }
                ']' -> { match(']'); break }
                else -> error("Expected ',' or ']' but got '$c' at $pos")
            }
        }
        return list
    }

    private fun parseString(): String {
        match('"')
        val sb = StringBuilder()
        while (pos < json.length) {
            val c = json[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (pos >= json.length) break
                val escaped = json[pos++]
                when (escaped) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 <= json.length) {
                            val hex = json.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                    }
                    else -> sb.append(escaped)
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseBoolean(): Boolean {
        if (json.startsWith("true", pos)) {
            pos += 4
            return true
        }
        if (json.startsWith("false", pos)) {
            pos += 5
            return false
        }
        error("Expected boolean at $pos")
    }

    private fun parseNull(): Nothing? {
        if (json.startsWith("null", pos)) {
            pos += 4
            return null
        }
        error("Expected null at $pos")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (json[pos] == '-') pos++
        while (pos < json.length && (json[pos] in '0'..'9' || json[pos] == '.' || json[pos] == 'e' || json[pos] == 'E' || json[pos] == '+' || json[pos] == '-')) {
            pos++
        }
        val s = json.substring(start, pos)
        return s.toLongOrNull() ?: s.toDoubleOrNull() ?: error("Invalid number $s at $start")
    }

    private fun peek(): Char = if (pos < json.length) json[pos] else '\u0000'

    private fun match(expected: Char) {
        skipWhitespace()
        if (pos >= json.length || json[pos] != expected) {
            error("Expected '$expected' but got '${peek()}' at $pos")
        }
        pos++
    }

    private fun skipWhitespace() {
        while (pos < json.length && json[pos] in " \t\r\n") {
            pos++
        }
    }
}
