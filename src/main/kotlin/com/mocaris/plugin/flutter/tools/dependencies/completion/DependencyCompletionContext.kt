package com.mocaris.plugin.flutter.tools.dependencies.completion

/** Recognize an unfinished block-mapping key without requiring a valid pubspec during typing. */
internal data class DependencyCompletionContext(val prefix: String, val name: String, val start: Int, val end: Int) {
    companion object {
        private val entry = Regex("( +)([a-z][a-z0-9_]*)(\\s*:.*|\\s+#.*|\\s*)?")
        private val section = Regex("(?:dependencies|dev_dependencies):\\s*(?:#.*)?")

        fun find(text: CharSequence, offset: Int): DependencyCompletionContext? {
            if (offset !in 0..text.length) return null
            val lineStart = text.lastIndexOf('\n', offset - 1) + 1
            val lineEnd = text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length
            val match = entry.matchEntire(text.subSequence(lineStart, lineEnd).toString().trimEnd('\r')) ?: return null
            val key = match.groups[2]!!
            val start = lineStart + key.range.first
            val end = lineStart + key.range.last + 1
            if (offset !in start..end || offset - start < 2) return null
            val indentation = match.groupValues[1].length
            val parent = text.subSequence(0, lineStart).lineSequence().toList().asReversed().firstOrNull {
                it.isNotBlank() && !it.trimStart().startsWith('#') && it.takeWhile { c -> c == ' ' }.length < indentation
            } ?: return null
            if (!section.matches(parent.trimEnd()) || parent.startsWith(' ')) return null
            // Structured SDK, path, Git and hosted declarations are not package-name suggestions.
            val next = text.subSequence(lineEnd, text.length).lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith('#') }
            if (next != null && next.takeWhile { it == ' ' }.length > indentation) return null
            return DependencyCompletionContext(text.subSequence(start, offset).toString(), key.value, start, end)
        }

        /** Add a version only to an absent/empty scalar; retain user constraints and comments. */
        fun versionEdit(suffix: String, version: String): Pair<Int, String>? {
            if (suffix.isBlank() || suffix.trimStart().startsWith('#')) return 0 to ": ^$version "
            val colon = suffix.indexOf(':')
            if (colon < 0) return null
            val value = suffix.substring(colon + 1)
            if (value.isNotBlank() && !value.trimStart().startsWith('#')) return null
            val spaces = value.takeWhile { it == ' ' }.length
            return colon + 1 + spaces to (if (spaces == 0) " " else "") + "^$version" + if (value.trimStart().startsWith('#')) " " else ""
        }
    }
}
