package com.mocaris.plugin.flutter.tools.dependencies

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.errorLocalized
import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageDependency
import java.net.URI
import org.jetbrains.yaml.psi.*

object PubspecDependencyParser {
    fun forVersionChecks(dependencies: List<PackageDependency>): List<PackageDependency> =
        dependencies.filter { it.isPubDev }

    fun parseEditor(file: YAMLFile): List<PackageDependency> {
        val overrideFile = file.virtualFile?.parent?.findChild("pubspec_overrides.yaml") ?: return parse(file)
        val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(overrideFile)?.text
            ?: String(overrideFile.contentsToByteArray(), overrideFile.charset)
        val overrides = com.intellij.psi.PsiFileFactory.getInstance(file.project)
            .createFileFromText("pubspec_overrides.yaml", org.jetbrains.yaml.YAMLFileType.YML, text) as YAMLFile
        return parse(file, overrides)
    }

    /** An external dependency_overrides property replaces the entire inline mapping, even when empty. */
    fun parse(file: YAMLFile, external: YAMLFile? = null): List<PackageDependency> {
        fun mapping(source: YAMLFile, allowEmpty: Boolean = false): YAMLMapping? {
            requireLocalized(PsiTreeUtil.findChildOfType(source, PsiErrorElement::class.java) == null, "error.yamlWaiting")
            val value = YamlSupport.load(source.text)
            if (allowEmpty && value == null) return null
            return source.documents.singleOrNull()?.topLevelValue as? YAMLMapping ?: errorLocalized("error.pubspecSingle")
        }
        val top = mapping(file)!!
        val externalTop = external?.let { mapping(it, allowEmpty = true) }
        val overridesKey = externalTop?.getKeyValueByKey("dependency_overrides") ?: top.getKeyValueByKey("dependency_overrides")
        val overrides = overridesKey?.value as? YAMLMapping
        requireLocalized(overridesKey == null || overrides != null ||
            YamlSupport.load(overridesKey.value?.text.orEmpty()) == null, "error.sectionMapping", "dependency_overrides")
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
