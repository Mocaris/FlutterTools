package com.mocaris.plugin.flutter.tools.dependencies

import com.intellij.openapi.util.TextRange
import com.mocaris.plugin.flutter.tools.core.errorLocalized
import com.mocaris.plugin.flutter.tools.core.requireLocalized
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import org.jetbrains.yaml.psi.*

/** Plans a small text edit, preserving quotes, comments, hosted URLs and surrounding YAML. */
object DependencyVersionEditor {
    fun updateAvailable(result: DependencyResult): Boolean {
        if (!result.dependency.isPubDev || result.error) return false
        val latest = result.latest?.let(PubVersion::parse) ?: return false
        result.installed?.let(PubVersion::parse)?.let { return latest > it }
        val constraint = result.dependency.constraint.trim()
        val declared = PubVersion.parse(constraint.removePrefix("^").trim())
        if (declared != null) return latest > declared
        val floors = Regex("(?:>=|>)\\s*([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?)")
            .findAll(constraint).mapNotNull { PubVersion.parse(it.groupValues[1]) }.toList()
        if (floors.any { latest <= it }) return false
        return PubConstraint.allows(constraint, latest) == false
    }

    fun entry(file: YAMLFile, dependency: PackageDependency): YAMLKeyValue? {
        val top = file.documents.singleOrNull()?.topLevelValue as? YAMLMapping ?: return null
        val override = (top.getKeyValueByKey("dependency_overrides")?.value as? YAMLMapping)?.getKeyValueByKey(dependency.name)
        if (dependency.overridden && override != null) return override
        return (top.getKeyValueByKey(dependency.section)?.value as? YAMLMapping)?.getKeyValueByKey(dependency.name)
    }

    fun versionRange(entry: YAMLKeyValue): TextRange? = when (val value = entry.value) {
        is YAMLScalar -> value.textRange
        is YAMLMapping -> value.getKeyValueByKey("version")?.value?.textRange
        else -> null
    }

    fun updatedText(file: YAMLFile, entry: YAMLKeyValue, latest: String): String {
        requireLocalized(PubVersion.parse(latest) != null, "error.updateVersion")
        val dependency = PubspecDependencyParser.dependency(entry, "dependencies")
        requireLocalized(dependency.isPubDev, "error.updateSource")
        val constraint = if (PubVersion.parse(dependency.constraint.trim()) != null) latest else "^$latest"
        val mapping = entry.value as? YAMLMapping
        val versionEntry = mapping?.getKeyValueByKey("version")
        val value = if (mapping == null) entry.value else versionEntry?.value
        val text = file.text
        val result = when {
            value is YAMLScalar -> {
                val quote = value.text.firstOrNull()?.takeIf { it == '\'' || it == '"' }
                val replacement = if (quote == null) constraint else "$quote$constraint$quote"
                text.replaceRange(value.textRange.startOffset, value.textRange.endOffset, replacement)
            }
            value != null -> errorLocalized("error.updateScalar")
            versionEntry != null || mapping == null -> {
                val offset = (versionEntry ?: entry).textRange.endOffset
                text.replaceRange(offset, offset, " $constraint")
            }
            mapping.text.trimStart().startsWith('{') -> {
                val offset = mapping.textRange.endOffset - 1
                val separator = when {
                    mapping.keyValues.isEmpty() -> ""
                    text.substring(mapping.textRange.startOffset, offset).trimEnd().endsWith(',') -> " "
                    else -> ", "
                }
                text.replaceRange(offset, offset, "${separator}version: $constraint")
            }
            else -> {
                val first = mapping.keyValues.firstOrNull() ?: errorLocalized("error.updateScalar")
                val lineStart = text.lastIndexOf('\n', first.textOffset - 1) + 1
                val indent = text.substring(lineStart, first.textOffset)
                val nextLine = text.indexOf('\n', mapping.textRange.endOffset)
                val offset = if (nextLine < 0) text.length else nextLine + 1
                val prefix = if (offset > 0 && text[offset - 1] != '\n') "\n" else ""
                text.replaceRange(offset, offset, "$prefix${indent}version: $constraint\n")
            }
        }
        YamlSupport.load(result)
        return result
    }
}
