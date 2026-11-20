package com.mocaris.plugin.flutter.tools.assets.completion

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Use Dart's PSI to exclude comments/directives without depending on Dart implementation classes. */
internal data class ResourceString(val start: Int, val end: Int, val value: String, val raw: Boolean, val quote: Char, val content: String) {
    fun prefix(offset: Int): String? {
        val prefix = content.take((offset - start).coerceIn(0, content.length))
        return if (raw) prefix else stringValue("$quote$prefix$quote")
    }
    fun encode(path: String) = if (raw) path.takeUnless { quote in it } else buildString {
        path.forEach { if (it == quote || it == '\\' || it == '$') append('\\'); append(it) }
    }

    companion object {
        internal fun stringValue(literal: String): String? {
            if (literal.length > 16390) return null
            val result = StringBuilder()
            var index = 1
            while (index < literal.length - 1) {
                val char = literal[index++]
                if (char == '$' || char == '\n' || char == '\r') return null // Never evaluate Dart interpolation.
                if (char != '\\') { result.append(char); continue }
                if (index >= literal.length - 1) return null
                val escaped = literal[index++]
                result.append(when (escaped) {
                    '\\', '\'', '"', '$' -> escaped
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000C'; 'v' -> '\u000B'
                    'x', 'u' -> {
                        val count = if (escaped == 'x') 2 else 4
                        if (index + count > literal.length - 1) return null
                        val value = literal.substring(index, index + count).toIntOrNull(16) ?: return null
                        index += count; value.toChar()
                    }
                    else -> return null
                })
            }
            return result.toString()
        }

        private val directives = setOf("IMPORT_STATEMENT", "EXPORT_STATEMENT", "PART_STATEMENT", "PART_OF_STATEMENT")
        fun find(file: PsiFile, offset: Int): ResourceString? {
            if (file.language.id != "Dart" || offset <= 0) return null
            val literal = literal(file.findElementAt(offset - 1)) ?: return null
            return parse(literal.text, literal.textOffset)?.takeIf { offset in it.start..it.end }
        }

        fun literal(element: PsiElement?): PsiElement? {
            var current = element
            var literal: PsiElement? = null
            while (current != null && current !is PsiFile) {
                val type = current.node.elementType.toString()
                if (type in directives) return null
                if (type == "STRING_LITERAL_EXPRESSION") literal = current
                current = current.parent
            }
            return literal
        }

        fun parse(text: String, offset: Int = 0): ResourceString? {
            if (text.length > 4096) return null
            val raw = text.startsWith('r')
            val open = if (raw) 1 else 0
            val quote = text.getOrNull(open)?.takeIf { it == '\'' || it == '"' } ?: return null
            val delimiter = if (text.startsWith("$quote$quote$quote", open)) "$quote$quote$quote" else "$quote"
            val start = open + delimiter.length
            val closed = text.length >= start + delimiter.length && text.endsWith(delimiter)
            val end = if (closed) text.length - delimiter.length else text.length
            val content = text.substring(start, end)
            if ('\n' in content || '\r' in content) return null
            val value = if (raw) content else stringValue("$quote$content$quote") ?: return null
            return ResourceString(offset + start, offset + end, value, raw, quote, content)
        }
    }
}
