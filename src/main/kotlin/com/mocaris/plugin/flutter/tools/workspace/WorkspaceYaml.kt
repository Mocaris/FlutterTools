package com.mocaris.plugin.flutter.tools.workspace

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.errorLocalized
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** Locate edits with PSI, then replace only the requested value or insert a missing subtree. */
internal object WorkspaceYaml {
    /** Expand only flow collections beneath this key; leave existing block YAML and comments intact. */
    fun block(project: Project, source: String, key: String): String {
        var result = source
        while (true) {
            val file = PsiFileFactory.getInstance(project).createFileFromText("pubspec.yaml", YAMLFileType.YML, result) as YAMLFile
            val mapping = file.documents.singleOrNull()?.topLevelValue as? YAMLMapping ?: errorLocalized("melos.invalidYaml")
            val value = mapping.getKeyValueByKey(key)?.value ?: return result
            fun flow(element: com.intellij.psi.PsiElement): YAMLValue? {
                if (element is YAMLMapping && element.text.trimStart().startsWith('{') ||
                    element is YAMLSequence && element.text.trimStart().startsWith('[')) {
                    if (element.children.any { it is YAMLKeyValue || it is YAMLSequenceItem }) return element as YAMLValue
                }
                return element.children.firstNotNullOfOrNull(::flow)
            }
            val collection = flow(if (mapping.text.trimStart().startsWith('{')) mapping else value) ?: return result
            val start = collection.textRange.startOffset
            val lineStart = result.lastIndexOf('\n', start - 1) + 1
            val prefix = result.substring(lineStart, start)
            val ownLine = prefix.isBlank()
            val padding = if (collection == mapping) "" else prefix.takeWhile { it == ' ' } + if (ownLine) "" else "  "
            val rendered = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; setSplitLines(false) })
                .dump(YamlSupport.load(collection.text)).trimEnd().prependIndent(padding)
            val replaceStart = if (ownLine) lineStart else start - prefix.takeLastWhile { it == ' ' || it == '\t' }.length
            result = result.replaceRange(replaceStart, collection.textRange.endOffset,
                if (collection == mapping || ownLine) rendered else "\n$rendered")
            YamlSupport.load(result)
        }
    }

    fun put(project: Project, source: String, path: List<String>, value: Any): String {
        fun dump(value: Any, flow: Boolean): String = Yaml(DumperOptions().apply {
            defaultFlowStyle = if (flow) DumperOptions.FlowStyle.FLOW else DumperOptions.FlowStyle.BLOCK
            setSplitLines(false)
        }).dump(value).trimEnd()
        fun tree(index: Int): Any = if (index == path.size) value else linkedMapOf(path[index] to tree(index + 1))
        if (source.isBlank()) return dump(tree(0), false) + "\n"
        val file = PsiFileFactory.getInstance(project).createFileFromText("pubspec.yaml", YAMLFileType.YML, source) as YAMLFile
        val top = file.documents.singleOrNull()?.topLevelValue as? YAMLMapping ?: errorLocalized("melos.invalidYaml")
        fun indent(offset: Int) = source.substring(source.lastIndexOf('\n', offset - 1) + 1, offset).takeWhile { it == ' ' }
        fun lineEnd(offset: Int) = source.indexOf('\n', offset).takeIf { it >= 0 } ?: source.length
        fun visit(mapping: YAMLMapping, index: Int): String {
            val key = mapping.getKeyValueByKey(path[index])
            val next = key?.value
            if (index < path.lastIndex && next is YAMLMapping) return visit(next, index + 1)
            if (index < path.lastIndex && next != null && next.text.trim() !in setOf("null", "~")) errorLocalized("melos.invalidYaml")
            val replacement = tree(index + 1)
            if (key != null) {
                if (next != null && YamlSupport.load(next.text) == replacement) return source
                val rendered = dump(replacement, true)
                if (next != null) return source.replaceRange(next.textRange.startOffset, next.textRange.endOffset, rendered)
                val colon = source.indexOf(':', key.key!!.textRange.endOffset)
                return source.replaceRange(colon + 1, colon + 1, " $rendered")
            }
            if (mapping.text.trimStart().startsWith('{')) {
                val close = mapping.textRange.endOffset - 1
                val tail = source.substring(mapping.keyValues.lastOrNull()?.textRange?.endOffset ?: mapping.textOffset + 1, close)
                    .lineSequence().joinToString("") { it.substringBefore('#') }
                val separator = if (mapping.keyValues.isNotEmpty() && ',' !in tail) ", " else " "
                return source.replaceRange(close, close, "$separator${path[index]}: ${dump(replacement, true)} ")
            }
            val padding = indent(mapping.keyValues.first().textOffset)
            val rendered = if (replacement is Map<*, *> || replacement is List<*> && replacement.isNotEmpty())
                "${path[index]}:\n" + dump(replacement, false).prependIndent("  ")
            else "${path[index]}: ${dump(replacement, true)}"
            val offset = lineEnd(mapping.textRange.endOffset)
            val separator = if (mapping === top) "\n\n" else "\n"
            return source.replaceRange(offset, offset, separator + rendered.prependIndent(padding))
        }
        return visit(top, 0).also { YamlSupport.load(it) }
    }
}
