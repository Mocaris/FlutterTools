package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.requireLocalized
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

/** Insert one dependency without reserializing the rest of the user's pubspec. */
object DependencyAdder {
    fun existing(file: YAMLFile, name: String, overrides: List<PackageDependency> = emptyList()): PackageDependency? =
        PubspecDependencyParser.applyOverrides(PubspecDependencyParser.parse(file), overrides)
            .firstOrNull { it.name == name || it.packageName == name }
            ?: overrides.firstOrNull { it.name == name || it.packageName == name }?.copy(overridden = true)

    fun updatedText(file: YAMLFile, name: String, version: String, section: String,
                    overrides: List<PackageDependency> = emptyList()): String {
        PubDevClient.validateName(name)
        requireLocalized(PubVersion.parse(version) != null, "error.updateVersion")
        require(section in setOf("dependencies", "dev_dependencies"))
        val current = existing(file, name, overrides)
        if (current != null) {
            requireLocalized(current.isPubDev && !current.overridden && current.name == name, "search.conflict", name, current.source)
            requireLocalized(current.section == section, "search.otherSection", name, current.section)
            val entry = DependencyVersionEditor.entry(file, current)!!
            return DependencyVersionEditor.updatedText(file, entry, version)
        }
        val top = file.documents.single().topLevelValue as YAMLMapping
        val source = file.text
        val field = "$name: ^$version"
        fun flow(mapping: YAMLMapping) = mapping.text.trimStart().startsWith('{')
        fun insert(mapping: YAMLMapping, field: String): String {
            if (flow(mapping)) {
                val offset = mapping.textRange.endOffset - 1
                val tail = source.substring(mapping.keyValues.lastOrNull()?.textRange?.endOffset ?: (mapping.textOffset + 1), offset)
                    .lineSequence().joinToString("") { it.substringBefore('#') }
                val separator = if (mapping.keyValues.isNotEmpty() && ',' !in tail) ", " else " "
                return source.replaceRange(offset, offset, "$separator$field ")
            }
            val first = mapping.keyValues.first()
            val indent = source.substring(source.lastIndexOf('\n', first.textOffset - 1) + 1, first.textOffset)
            val offset = source.indexOf('\n', mapping.textRange.endOffset).takeIf { it >= 0 } ?: source.length
            return source.replaceRange(offset, offset, "\n" + field.lineSequence().joinToString("\n") { indent + it })
        }
        val key = top.getKeyValueByKey(section)
        val result = when (val mapping = key?.value) {
            is YAMLMapping -> insert(mapping, field)
            null -> if (key == null) {
                insert(top, if (flow(top)) "$section: {$field}" else "$section:\n  $field")
            } else {
                val indent = source.substring(source.lastIndexOf('\n', key.textOffset - 1) + 1, key.textOffset)
                val offset = source.indexOf('\n', key.textRange.endOffset).takeIf { it >= 0 } ?: source.length
                if (flow(top)) source.replaceRange(key.textRange.endOffset, key.textRange.endOffset, " {$field}")
                else source.replaceRange(offset, offset, "\n$indent  $field")
            }
            else -> throw IllegalArgumentException("Invalid dependency mapping")
        }.let { if (it.endsWith('\n')) it else "$it\n" }
        YamlSupport.load(result)
        return result
    }
}
