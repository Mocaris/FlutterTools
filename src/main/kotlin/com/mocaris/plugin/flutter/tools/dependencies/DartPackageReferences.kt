package com.mocaris.plugin.flutter.tools.dependencies

/** Dart directives precede declarations. Lex only that header, ignoring comments and body strings. */
internal object DartPackageReferences {
    private val namePattern = Regex("[a-z_][a-z0-9_]*")
    fun parse(source: String): Set<String>? = Reader(source).read()

    private class Reader(private val source: String) {
        private var offset = 0
        private val packages = mutableSetOf<String>()
        private fun word(): String {
            val start = offset
            while (offset < source.length && (source[offset].isLetterOrDigit() || source[offset] in "_$")) offset++
            return source.substring(start, offset)
        }
        private fun trivia(): Boolean {
            while (offset < source.length) {
                when {
                    source[offset].isWhitespace() || source[offset] == '\uFEFF' -> offset++
                    source.startsWith("//", offset) || offset <= 1 && source.startsWith("#!", offset) -> {
                        offset = source.indexOf('\n', offset).takeIf { it >= 0 } ?: source.length
                    }
                    source.startsWith("/*", offset) -> {
                        offset += 2
                        var depth = 1
                        while (offset < source.length && depth > 0) {
                            when {
                                source.startsWith("/*", offset) -> { depth++; offset += 2 }
                                source.startsWith("*/", offset) -> { depth--; offset += 2 }
                                else -> offset++
                            }
                        }
                        if (depth != 0) return false
                    }
                    else -> return true
                }
            }
            return true
        }
        private fun string(): Pair<String, Boolean>? {
            val raw = source[offset] == 'r'
            if (raw) offset++
            val quote = source[offset].toString()
            val delimiter = if (source.startsWith(quote.repeat(3), offset)) quote.repeat(3) else quote
            offset += delimiter.length
            val start = offset
            while (offset < source.length) {
                if (source.startsWith(delimiter, offset)) {
                    val value = source.substring(start, offset)
                    offset += delimiter.length
                    return value to raw
                }
                if (!raw && source[offset] == '\\') offset++
                offset++
            }
            return null
        }
        private fun isString() = offset < source.length && (source[offset] in "'\"" || source[offset] == 'r' && source.getOrNull(offset + 1) in listOf('\'', '"'))
        private fun directive(references: Boolean): Boolean {
            var literal = false
            while (trivia() && offset < source.length) {
                if (source[offset] == ';') { offset++; return !references || literal }
                if (isString()) {
                    val (uri, raw) = string() ?: return false
                    literal = true
                    if (references) {
                        // Nonliteral/escaped URIs are inconclusive rather than an unused dependency.
                        if (!raw && (uri.contains('\\') || uri.contains('$'))) return false
                        if (uri.startsWith("package:")) {
                            val name = uri.removePrefix("package:").substringBefore('/')
                            if (!namePattern.matches(name)) return false
                            packages.add(name)
                        }
                    }
                } else offset++
            }
            return false
        }
        private fun annotation(): Boolean {
            offset++
            if (word().isEmpty()) return false
            while (offset < source.length && source[offset] == '.') { offset++; if (word().isEmpty()) return false }
            if (!trivia()) return false
            if (source.getOrNull(offset) != '(') return true
            offset++
            var depth = 1
            while (trivia() && offset < source.length) {
                if (isString()) { if (string() == null) return false }
                else when (source[offset++]) { '(' -> depth++; ')' -> if (--depth == 0) return true }
            }
            return false
        }
        fun read(): Set<String>? {
            while (true) {
                if (!trivia()) return null
                if (offset == source.length) return packages
                if (source[offset] == '@') { if (!annotation()) return null; continue }
                val token = word()
                when (token) {
                    "import", "export" -> if (!directive(true)) return null
                    "library", "part" -> if (!directive(false)) return null
                    else -> return if (token.isEmpty() || listOf("import", "export", "library", "part").any { it.startsWith(token) }) null else packages
                }
            }
        }
    }
}
