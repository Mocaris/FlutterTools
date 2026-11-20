package com.mocaris.plugin.flutter.tools.dependencies.commands

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.mocaris.plugin.flutter.tools.assets.AssetsSyncService
import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.FlutterToolsNotifications
import com.mocaris.plugin.flutter.tools.core.PackageActionPresentation
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message

class RunPubGetAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project?.takeUnless { it.isDisposed } ?: return
        val candidates = project.service<AssetsSyncService>().roots.filter { it.isValid }
        val root = FlutterPackageLocator.choosePackage(project, candidates, e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
        if (FlutterPubGetRunner.isRunning(project, root)) return
        try {
            saveConfiguration(root)
            FlutterPubGetRunner.run(project, root)
        } catch (error: Exception) {
            FlutterToolsNotifications.notify(project, error.localizedMessage ?: message("error.unknown"), NotificationType.ERROR)
        }
    }

    internal fun saveConfiguration(root: VirtualFile) = WriteIntentReadAction.run(Runnable {
        val documents = FileDocumentManager.getInstance()
        listOf(root, DartPackageContext.root(root)).distinct().forEach { directory ->
            for (name in listOf("pubspec.yaml", "pubspec_overrides.yaml")) {
                directory.findChild(name)?.let(documents::getCachedDocument)?.let(documents::saveDocument)
            }
        }
    })

    override fun update(e: AnActionEvent) {
        val project = e.project?.takeUnless { it.isDisposed }
        val roots = project?.service<AssetsSyncService>()?.roots.orEmpty()
        PackageActionPresentation.update(e, "FlutterTools.RunPubGet", roots) { FlutterPubGetRunner.isRunning(project!!, it) }
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
