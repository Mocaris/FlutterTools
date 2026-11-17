package com.mocaris.plugin.flutter.tools.assets

import com.mocaris.plugin.flutter.tools.core.requireLocalized
import com.mocaris.plugin.flutter.tools.core.errorLocalized
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** PSI locates edits; source-range replacements preserve comments and unrelated formatting. */
object AssetsConfigEditor {
    private data class Edit(val start: Int, val end: Int, val text: String)

    fun update(yaml: YAMLFile, config: AssetsSyncConfig, previous: AssetsSyncConfig?): String {
        val scalarYaml = Yaml(DumperOptions().apply {
            defaultScalarStyle = DumperOptions.ScalarStyle.DOUBLE_QUOTED
            setSplitLines(false)
        })
        fun quoted(value: String) = scalarYaml.dump(value).trimEnd()
        fun list(values: Set<String>) = values.joinToString(", ", "[", "]") { quoted(it) }
        val values = linkedMapOf(
            "excluded" to (list(config.excluded) to (previous?.excluded == config.excluded)),
            "out_path" to (quoted(config.outPath) to (previous?.outPath == config.outPath)),
            "generate_dir" to (quoted(config.generateDir) to (previous?.generateDir == config.generateDir)),
            "out_class" to (quoted(config.outClass) to (previous?.outClass == config.outClass)),
            "out_extension" to (config.outExtension.toString() to (previous?.outExtension == config.outExtension)),
            "watch" to (config.watcher.toString() to (previous?.watcher == config.watcher)))
        fun field(key: String, flow: Boolean): String = if (key == "excluded" && !flow && config.excluded.isNotEmpty()) {
            "excluded:\n" + config.excluded.joinToString("\n") { "  - ${quoted(it)}" }
        } else "$key: ${values.getValue(key).first}"
        fun fields(flow: Boolean) = values.keys.map { field(it, flow) }
        fun indented(text: String, indent: String) = text.lineSequence().joinToString("\n") { indent + it }
        val source = yaml.text
        if (source.isBlank()) return "flutter_tools:\n  assets_sync:\n" + fields(false).joinToString("\n", postfix = "\n") { indented(it, "    ") }
        // Older YAML PSI can attach inline comments outside their key/value subtree.
        val commentTokens = mutableListOf<Edit>()
        val lexer = org.jetbrains.yaml.YAMLParserDefinition().createLexer(yaml.project)
        lexer.start(source)
        while (lexer.tokenType != null) {
            if (lexer.tokenType == org.jetbrains.yaml.YAMLTokenTypes.COMMENT) {
                commentTokens.add(Edit(lexer.tokenStart, lexer.tokenEnd, source.substring(lexer.tokenStart, lexer.tokenEnd)))
            }
            lexer.advance()
        }
        fun commentsIn(start: Int, end: Int) = commentTokens.filter { it.start >= start && it.end <= end }.map { it.text }
        val top = yaml.documents.singleOrNull()?.topLevelValue as? YAMLMapping ?: errorLocalized("error.configSingle")
        val edits = mutableListOf<Edit>()
        fun appendEntries(mapping: YAMLMapping, entries: List<String>) {
            if (entries.isEmpty()) return
            val end = mapping.textRange.endOffset
            if (mapping.text.trimStart().startsWith("{")) {
                val close = source.lastIndexOf('}', end - 1)
                requireLocalized(close >= mapping.textOffset, "error.flowMapping")
                val tail = source.substring(
                    mapping.keyValues.lastOrNull()?.textRange?.endOffset ?: (mapping.textOffset + 1), close
                ).lineSequence().joinToString("") { it.substringBefore('#') }
                val remaining = mapping.keyValues.filter { it.keyText != "sync_path" }
                val separator = if (remaining.isNotEmpty() && ',' !in tail) ", " else " "
                edits.add(Edit(close, close, separator + entries.joinToString(", ") + " "))
            } else {
                val first = mapping.keyValues.firstOrNull() ?: errorLocalized("error.indentation")
                val lineStart = source.lastIndexOf('\n', first.textOffset - 1) + 1
                val indent = source.substring(lineStart, first.textOffset).takeWhile { it == ' ' || it == '\t' }
                val lineEnd = source.indexOf('\n', end).takeIf { it >= 0 } ?: source.length
                edits.add(Edit(lineEnd, lineEnd, "\n" + entries.joinToString("\n") { indented(it, indent) }))
            }
        }
        val toolsKey = top.getKeyValueByKey("flutter_tools")
        if (toolsKey == null) {
            val entry = if (top.text.trimStart().startsWith("{")) {
                "flutter_tools: {assets_sync: {${fields(true).joinToString(", ")}}}"
            } else "flutter_tools:\n  assets_sync:\n" + fields(false).joinToString("\n") { indented(it, "    ") }
            appendEntries(top, listOf(entry))
        } else {
            val tools = toolsKey.value as? YAMLMapping ?: errorLocalized("error.toolsMapping")
            val assetsKey = tools.getKeyValueByKey("assets_sync")
            if (assetsKey == null) {
                val entry = if (tools.text.trimStart().startsWith("{")) {
                    "assets_sync: {${fields(true).joinToString(", ")}}"
                } else "assets_sync:\n" + fields(false).joinToString("\n") { indented(it, "  ") }
                appendEntries(tools, listOf(entry))
            } else {
                val assets = assetsKey.value as? YAMLMapping ?: errorLocalized("error.assetsMapping")
                val flow = assets.text.trimStart().startsWith("{")
                val legacy = assets.getKeyValueByKey("sync_path")
                if (legacy != null) {
                    if (assets.text.trimStart().startsWith("{")) {
                        val keys = assets.keyValues.toList()
                        val index = keys.indexOf(legacy)
                        val next = keys.getOrNull(index + 1)
                        val previousKey = keys.getOrNull(index - 1)
                        val start = if (next == null && previousKey != null) previousKey.textRange.endOffset else legacy.textOffset
                        var end = next?.textOffset ?: legacy.textRange.endOffset
                        if (keys.size == 1) {
                            val close = source.lastIndexOf('}', assets.textRange.endOffset - 1)
                            val comma = source.indexOf(',', end)
                            if (comma in end until close) end = comma + 1
                        }
                        val comments = commentsIn(start, end)
                        edits.add(Edit(start, end, if (comments.isEmpty()) "" else "\n" + comments.joinToString("\n") + "\n"))
                    } else {
                        val start = source.lastIndexOf('\n', legacy.textOffset - 1) + 1
                        val lineEnd = source.indexOf('\n', legacy.textRange.endOffset).takeIf { it >= 0 } ?: source.length
                        val indent = source.substring(start, legacy.textOffset)
                        val kept = commentsIn(start, lineEnd)
                        edits.add(Edit(start, lineEnd,
                            if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n") { indent + it }))
                    }
                }
                val missing = mutableListOf<String>()
                values.forEach { (key, pair) ->
                    val existing = assets.getKeyValueByKey(key)
                    when {
                        existing == null -> missing.add(field(key, flow))
                        !pair.second || key == "excluded" && !flow && config.excluded.isNotEmpty() && existing.value?.text?.trimStart()?.startsWith("[") == true -> {
                            val value = existing.value
                            if (value == null && key == "excluded" && !flow) {
                                val start = source.lastIndexOf('\n', existing.textOffset - 1) + 1
                                val indent = source.substring(start, existing.textOffset).takeWhile { it == ' ' || it == '\t' }
                                edits.add(Edit(existing.textOffset, existing.textRange.endOffset, field(key, false).replace("\n", "\n$indent")))
                            } else if (value == null) edits.add(Edit(existing.textRange.endOffset, existing.textRange.endOffset, " ${pair.first}"))
                            else {
                                val start = source.lastIndexOf('\n', existing.textOffset - 1) + 1
                                val indent = source.substring(start, existing.textOffset).takeWhile { it == ' ' || it == '\t' }
                                val comments = commentsIn(value.textRange.startOffset, value.textRange.endOffset)
                                if (comments.isNotEmpty() && (key != "excluded" || flow)) {
                                    edits.add(Edit(existing.textOffset, existing.textOffset, comments.joinToString("\n$indent", postfix = "\n$indent")))
                                }
                                if (key == "excluded" && !flow) {
                                    val replacement = field(key, false).replace("\n", "\n$indent")
                                    val prefix = if (comments.isEmpty()) "" else comments.joinToString("\n$indent", postfix = "\n$indent")
                                    edits.add(Edit(existing.textOffset, value.textRange.endOffset, prefix + replacement))
                                } else edits.add(Edit(value.textRange.startOffset, value.textRange.endOffset, pair.first))
                            }
                        }
                    }
                }
                appendEntries(assets, missing)
            }
        }
        var result = source
        edits.sortedByDescending { it.start }.forEach { result = result.replaceRange(it.start, it.end, it.text) }
        // A malformed candidate must never be written to the user's document.
        requireLocalized(AssetsGenerator.parseText(result) == config, "error.configValidation")
        return result
    }
}
