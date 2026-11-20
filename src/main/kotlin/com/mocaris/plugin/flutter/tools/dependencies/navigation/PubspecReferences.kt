package com.mocaris.plugin.flutter.tools.dependencies.navigation

import com.intellij.openapi.paths.WebReference
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.mocaris.plugin.flutter.tools.dependencies.PubspecDependencyParser
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

class PubspecReferences : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLKeyValue::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
                (element as? YAMLKeyValue)?.let { references(it) } ?: PsiReference.EMPTY_ARRAY
        })
    }

    fun references(entry: YAMLKeyValue): Array<PsiReference> {
        val file = entry.containingFile as? YAMLFile ?: return PsiReference.EMPTY_ARRAY
        if (file.name != "pubspec.yaml") return PsiReference.EMPTY_ARRAY
        val section = (entry.parent as? YAMLMapping)?.parent as? YAMLKeyValue ?: return PsiReference.EMPTY_ARRAY
        if (section.keyText !in setOf("dependencies", "dev_dependencies", "dependency_overrides") || section.parent?.parent !is YAMLDocument) return PsiReference.EMPTY_ARRAY
        val dependency = try {
            PubspecDependencyParser.parseEditor(file).firstOrNull { it.name == entry.keyText && (it.section == section.keyText || section.keyText == "dependency_overrides") }
        } catch (e: ProcessCanceledException) { throw e } catch (_: Exception) { null }
        if (dependency?.isPubDev != true || !Regex("[a-z][a-z0-9_]*").matches(dependency.packageName)) return PsiReference.EMPTY_ARRAY
        val range = (entry.key?.textRange ?: entry.textRange).shiftRight(-entry.textOffset)
        return arrayOf(WebReference(entry, range, "https://pub.dev/packages/${dependency.packageName}"))
    }
}
