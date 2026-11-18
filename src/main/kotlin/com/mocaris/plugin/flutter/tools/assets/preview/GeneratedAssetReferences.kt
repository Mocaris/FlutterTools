package com.mocaris.plugin.flutter.tools.assets.preview

import com.mocaris.plugin.flutter.tools.assets.AssetsGenerator

/** Parse only our generated constructor shape; offsets are subsequently verified against PSI leaves. */
internal object GeneratedAssetReferences {
    data class Reference(val name: String, val offset: Int, val packageName: String, val path: String)
    const val MAX_FILE_CHARS = 2 * 1024 * 1024
    private const val STRING = "(\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"|'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+')"
    private val field = Regex("""\bfinal\s+([A-Za-z_][A-Za-z_0-9]*)Resource\s+([A-Za-z_][A-Za-z_0-9]*)\s*=\s*const\s+\1Resource\s*\(\s*\1ResourceMeta\s*\(\s*$STRING\s*,\s*$STRING\s*,\s*\1ResourceKind\s*\.\s*(?:assets|shaders)\b""")
    private val packageName = Regex("[a-z_][a-z0-9_]*")
    private val extensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp")
    fun isImage(path: String) = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in extensions

    fun parse(text: String): Map<Int, Reference> {
        if (text.length > MAX_FILE_CHARS || text.lineSequence().firstOrNull() != AssetsGenerator.GENERATED_MARKER) return emptyMap()
        val result = linkedMapOf<Int, Reference>()
        for (match in field.findAll(text)) {
            val owner = stringValue(match.groupValues[3]) ?: continue
            val path = stringValue(match.groupValues[4]) ?: continue
            if (path.length > 4096 || !packageName.matches(owner)) continue
            val offset = match.groups[4]!!.range.first
            result[offset] = Reference(match.groupValues[2], offset, owner, path)
        }
        return result
    }

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
}
