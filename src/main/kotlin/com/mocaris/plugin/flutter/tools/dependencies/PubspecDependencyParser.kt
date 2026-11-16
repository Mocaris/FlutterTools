package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.core.requireLocalized
import com.mocaris.plugin.flutter.tools.core.errorLocalized
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.*
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import java.net.URI

object PubspecDependencyParser {
    fun applyOverrides(dependencies: List<PackageDependency>, overrides: List<PackageDependency>): List<PackageDependency> {
        val effective = overrides.associateBy { it.name }
        return dependencies.map { original -> effective[original.name]?.copy(section = original.section, overridden = true,
            keyOffset = original.keyOffset, versionOffset = original.versionOffset) ?: original }
    }

    /** Classify the effective source after overrides, so local replacements never reach pub.dev. */
    fun forVersionChecks(dependencies: List<PackageDependency>, overrides: List<PackageDependency> = emptyList()): List<PackageDependency> =
        applyOverrides(dependencies, overrides).filter { it.isPubDev }

    fun parseEditor(file: YAMLFile): List<PackageDependency> {
        val dependencies = parse(file)
        val overrideFile = file.virtualFile?.parent?.findChild("pubspec_overrides.yaml") ?: return dependencies
        val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(overrideFile)?.text
            ?: String(overrideFile.contentsToByteArray(), overrideFile.charset)
        val overrides = com.intellij.psi.PsiFileFactory.getInstance(file.project)
            .createFileFromText("pubspec_overrides.yaml", org.jetbrains.yaml.YAMLFileType.YML, text) as YAMLFile
        return applyOverrides(dependencies, parse(overrides))
    }

    fun parse(file: YAMLFile): List<PackageDependency> {
        requireLocalized(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) == null, "error.yamlWaiting")
        // Validate duplicate keys and malformed mappings as well as editor syntax errors.
        YamlSupport.load(file.text)
        val top = file.documents.singleOrNull()?.topLevelValue as? YAMLMapping ?: errorLocalized("error.pubspecSingle")
        val overrides = top.getKeyValueByKey("dependency_overrides")?.value as? YAMLMapping
        val result = mutableListOf<PackageDependency>()
        for (section in listOf("dependencies", "dev_dependencies")) {
            val sectionNode = top.getKeyValueByKey(section) ?: continue
            if (sectionNode.value == null) continue
            val dependencies = sectionNode.value as? YAMLMapping ?: errorLocalized("error.sectionMapping", section)
            for (entry in dependencies.keyValues) {
                val effective = overrides?.getKeyValueByKey(entry.keyText) ?: entry
                result.add(dependency(effective, section, effective !== entry, entry))
            }
        }
        overrides?.keyValues?.filter { override -> result.none { it.name == override.keyText } }?.forEach {
            result.add(dependency(it, "dependency_overrides", true, it))
        }
        return result
    }

    fun dependency(entry: YAMLKeyValue, section: String, overridden: Boolean = false, anchor: YAMLKeyValue = entry): PackageDependency {
        val mapping = entry.value as? YAMLMapping
        fun scalar(key: String) = (mapping?.getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue
        val hosted = mapping?.getKeyValueByKey("hosted")?.value
        val hostedMapping = hosted as? YAMLMapping
        val url = when (hosted) {
            is YAMLScalar -> hosted.textValue
            is YAMLMapping -> (hosted.getKeyValueByKey("url")?.value as? YAMLScalar)?.textValue ?: "https://pub.dev"
            else -> "https://pub.dev"
        }
        val public = try { val uri = URI(url); uri.scheme in setOf("https", "http") && uri.host in setOf("pub.dev", "pub.dartlang.org") && uri.userInfo == null && uri.port == -1 && uri.path in setOf("", "/") && uri.query == null && uri.fragment == null } catch (_: Exception) { false }
        val source = when {
            mapping?.getKeyValueByKey("sdk") != null -> "sdk"
            mapping?.getKeyValueByKey("git") != null -> "git"
            mapping?.getKeyValueByKey("path") != null -> "path"
            !public -> "custom hosted"
            else -> "pub.dev"
        }
        val name = entry.keyText
        val actualName = (hostedMapping?.getKeyValueByKey("name")?.value as? YAMLScalar)?.textValue ?: name
        val constraint = when (val value = entry.value) { is YAMLScalar -> value.textValue; is YAMLMapping -> scalar("version") ?: "any"; null -> "any"; else -> errorLocalized("error.dependency", name) }
        val versionElement = if (mapping != null) mapping.getKeyValueByKey("version")?.value else entry.value
        return PackageDependency(name, actualName, section, constraint, source, overridden,
            anchor.key?.textRange?.startOffset ?: anchor.textOffset, if (overridden) anchor.textRange.endOffset else versionElement?.textRange?.endOffset ?: anchor.textRange.endOffset)
    }
}
