package com.mocaris.plugin.flutter.tools.dependencies.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.notification.NotificationType
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.FlutterToolsNotifications
import com.mocaris.plugin.flutter.tools.core.requireLocalized
import com.mocaris.plugin.flutter.tools.dependencies.*
import org.jetbrains.yaml.psi.YAMLFile
import java.awt.event.MouseEvent

object DependencyUpdatePopup {
    fun show(project: Project, root: VirtualFile, result: DependencyResult, event: MouseEvent) {
        if (!DependencyVersionEditor.updateAvailable(result)) return
        val latest = result.latest ?: return
        val choices = listOf(message("packages.updateTo", latest), message("packages.updateAndGet"))
        JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<String>(message("packages.updateTitle", result.dependency.name), choices) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
                update(project, root, result, selectedValue == choices[1])
            }
        }).show(RelativePoint(event))
    }

    private fun update(project: Project, root: VirtualFile, result: DependencyResult, pubGet: Boolean) {
        try {
            WriteCommandAction.runWriteCommandAction(project, message("command.updateDependency"), null, Runnable {
                val documents = FileDocumentManager.getInstance()
                val psiDocuments = PsiDocumentManager.getInstance(project)
                psiDocuments.commitAllDocuments()
                val original = root.findChild("pubspec.yaml") ?: error("Missing pubspec.yaml")
                val pubspec = PsiManager.getInstance(project).findFile(original) as? YAMLFile ?: error("Missing YAML support")
                val current = PubspecDependencyParser.parseEditor(pubspec).firstOrNull { it.name == result.dependency.name && it.section == result.dependency.section }
                requireLocalized(current?.signature() == result.dependency.signature(), "error.updateChanged")
                val external = root.findChild("pubspec_overrides.yaml")?.let { PsiManager.getInstance(project).findFile(it) as? YAMLFile }
                val externalEntry = external?.let { DependencyVersionEditor.entry(it, result.dependency) }
                val target = if (externalEntry != null) requireNotNull(external) else pubspec
                val entry = externalEntry ?: DependencyVersionEditor.entry(pubspec, result.dependency) ?: error("Missing dependency")
                val document = psiDocuments.getDocument(target) ?: error("Cannot edit dependency")
                val updated = DependencyVersionEditor.updatedText(target, entry, result.latest!!)
                document.setText(updated)
                psiDocuments.commitDocument(document)
                // Only persist the changed declaration and pubspec needed by the command.
                documents.saveDocument(document)
                if (pubGet && target != pubspec) documents.getDocument(original)?.let(documents::saveDocument)
            })
            if (pubGet) FlutterPubGetRunner.run(project, root)
        } catch (e: Exception) {
            FlutterToolsNotifications.notify(project, e.localizedMessage ?: message("error.unknown"), NotificationType.ERROR)
        }
    }
}
