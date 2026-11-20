package com.mocaris.plugin.flutter.tools.dependencies.inspection

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.dependencies.PubspecDependencyParser
import com.mocaris.plugin.flutter.tools.dependencies.installation.DependencyVersionEditor
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageVersionService
import org.jetbrains.yaml.psi.YAMLFile

/** Annotates a file once using its live declarations and cached dependency results. */
class PubspecAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? YAMLFile ?: return
        if (file.name != "pubspec.yaml") return
        if (!file.project.service<FlutterToolsSettings>().effective().dependencyVersionHints) return
        val root = FlutterPackageLocator.containing(file.virtualFile) ?: return
        val versions = file.project.service<PackageVersionService>()
        val live = try { PubspecDependencyParser.parseEditor(file) } catch (e: ProcessCanceledException) { throw e } catch (_: Exception) { return }
        live.forEach { dependency ->
            val result = versions.resultFor(root, dependency) ?: return@forEach
            if (!dependency.isPubDev || result.latest == null && !result.error) return@forEach
            val details = message("packages.details", dependency.constraint, result.installed ?: message("unknown"), result.latest ?: message("unknown"), result.status)
            val key = file.findElementAt(dependency.keyOffset) ?: return@forEach
            holder.newAnnotation(HighlightSeverity.INFORMATION, details).range(key.textRange).create()
            if (DependencyVersionEditor.updateAvailable(result)) {
                val entry = DependencyVersionEditor.entry(file, dependency) ?: return@forEach
                val range = DependencyVersionEditor.versionRange(entry) ?: return@forEach
                holder.newAnnotation(HighlightSeverity.WEAK_WARNING, message("packages.updateAvailable", result.latest))
                    .range(range).enforcedTextAttributes(TextAttributes(null, null, JBColor(0xC58B16, 0xD6A448), EffectType.WAVE_UNDERSCORE, 0)).create()
            }
        }
    }
}
