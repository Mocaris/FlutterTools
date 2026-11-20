package com.mocaris.plugin.flutter.tools.dependencies.inspection

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.WithAttributesPresentation
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.dependencies.PubspecDependencyParser
import com.mocaris.plugin.flutter.tools.dependencies.ui.DependencyUpdatePopup
import com.mocaris.plugin.flutter.tools.dependencies.ui.EditorTextPresentation
import com.mocaris.plugin.flutter.tools.dependencies.usage.DependencyUsageService
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageVersionService
import javax.swing.JComponent
import javax.swing.JPanel
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

@Suppress("UnstableApiUsage")
class PubspecInlayHints : InlayHintsProvider<NoSettings> {
    companion object {
        /** Dependency metadata changes even when the YAML modification stamp stays the same. */
        fun invalidate(editor: Editor) = InlayHintsRefresh.invalidate(editor)
    }

    override val key = SettingsKey<NoSettings>("fluttertools.package.versions")
    override val name get() = message("packages.hints")
    override val previewText: String? = null
    override fun createSettings() = NoSettings()
    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener): JComponent = JPanel()
    }
    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: NoSettings, sink: InlayHintsSink): InlayHintsCollector? {
        if (file !is YAMLFile || file.name != "pubspec.yaml") return null
        val root = FlutterPackageLocator.containing(file.virtualFile) ?: return null
        val versions = file.project.service<PackageVersionService>()
        val dependencies = try { PubspecDependencyParser.parseEditor(file) } catch (e: com.intellij.openapi.progress.ProcessCanceledException) { throw e } catch (_: Exception) { return null }
        val usage = file.project.service<DependencyUsageService>()
        usage.ensure(root)
        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                val entry = element as? YAMLKeyValue ?: return true
                val section = (entry.parent as? YAMLMapping)?.parent as? YAMLKeyValue ?: return true
                val dep = dependencies.firstOrNull { it.name == entry.keyText && it.section == section.keyText } ?: return true
                val result = if (file.project.service<FlutterToolsSettings>().effective().dependencyVersionHints)
                    versions.resultFor(root, dep) else null
                val unused = usage.unused(root, dep) == true
                val text = listOfNotNull(result?.latest?.let {
                    message("packages.latestHint", it) + (if (dep.overridden) message("packages.overrideHint") else "") + (if (result.error) message("packages.cachedHint") else "")
                }, if (unused) message("packages.unusedHint") else null).joinToString(" ")
                if (text.isEmpty()) return true
                val label = WithAttributesPresentation(EditorTextPresentation(editor, text),
                    DefaultLanguageHighlighterColors.INLAY_DEFAULT, editor,
                    WithAttributesPresentation.AttributesFlags().withIsDefault(true))
                val presentation = if (result != null && DependencyUpdatePopup.choices(result).isNotEmpty()) factory.referenceOnHover(label) { event, _ ->
                    DependencyUpdatePopup.show(file.project, root, result, event)
                } else label
                val description = listOfNotNull(result?.status, if (unused) message("packages.unusedTooltip") else null).joinToString("\n")
                val tooltip = factory.withTooltip(description, presentation)
                sink.addInlineElement(entry.textRange.endOffset, true, factory.inset(tooltip, left = com.intellij.util.ui.JBUI.scale(8)), false)
                return true
            }
        }
    }
}
