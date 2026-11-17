package com.mocaris.plugin.flutter.tools.assets

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.notification.NotificationType
import com.mocaris.plugin.flutter.tools.core.*
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.file.Path
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

open class RegisterAssetsAction(private val recursive: Boolean = false) : DumbAwareAction() {
    private val actionId get() = if (recursive) "FlutterTools.RegisterAssetsRecursively" else "FlutterTools.RegisterAssets"
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && selection(event)?.let { !recursive || it.second.all(VirtualFile::isDirectory) } == true
        event.presentation.text = message("action.$actionId.text")
        event.presentation.description = message("action.$actionId.description")
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val (root, files) = selection(event) ?: return
        try {
            val path = Path.of(root.path)
            val config = project.service<AssetsConfigService>().read(root).effectiveConfig
            val outputs = AssetsGenerator.outputPaths(path, config).values.toSet() + setOf(AssetsGenerator.validateOutput(path, config.outPath)) +
                AssetsGenerator.legacyOutputPaths(path, config)
            val paths = files.map { file ->
                val relative = path.relativize(Path.of(file.path)).toString().replace('\\', '/')
                val resolved = AssetsGenerator.resolvePath(path, relative)
                requireLocalized(resolved !in outputs && file.name !in setOf(PUBSPEC_FILE_NAME, TOOLS_FILE_NAME), "error.outputResource")
                relative + if (file.isDirectory) "/" else ""
            }
            if (recursive) {
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("assets.registerRecursiveCommand"), true) {
                    private var declarations = emptyList<String>()
                    override fun run(indicator: ProgressIndicator) {
                        declarations = AssetRegistration.recursivePaths(path, paths, indicator::checkCanceled)
                    }
                    override fun onSuccess() {
                        if (!project.isDisposed && root.isValid) register(project, root, declarations)
                    }
                    override fun onThrowable(error: Throwable) {
                        FlutterToolsNotifications.notify(project, message("error.details", error), NotificationType.ERROR)
                    }
                })
            } else register(project, root, paths)
        } catch (e: Exception) {
            FlutterToolsNotifications.notify(project, message("error.details", e), NotificationType.ERROR)
        }
    }

    private fun register(project: Project, root: VirtualFile, paths: List<String>) {
        try {
            var changed = false
            WriteCommandAction.runWriteCommandAction(project,
                message(if (recursive) "assets.registerRecursiveCommand" else "assets.registerCommand"), null, Runnable {
                val original = root.findChild(PUBSPEC_FILE_NAME) ?: error("Missing pubspec.yaml")
                val documents = FileDocumentManager.getInstance()
                val document = requireNotNull(documents.getDocument(original))
                val manager = PsiDocumentManager.getInstance(project)
                manager.commitDocument(document)
                val yaml = PsiManager.getInstance(project).findFile(original) as YAMLFile
                val updated = AssetRegistration.updatedText(yaml, paths)
                changed = updated != document.text
                if (changed) { document.setText(updated); manager.commitDocument(document); documents.saveDocument(document) }
            })
            project.service<AssetsSyncService>().generate(root)
            FlutterToolsNotifications.notify(project, message(if (changed) "assets.registered" else "assets.alreadyRegistered"), NotificationType.INFORMATION)
        } catch (e: Exception) {
            FlutterToolsNotifications.notify(project, message("error.details", e), NotificationType.ERROR)
        }
    }

    companion object {
        internal fun selection(event: AnActionEvent): Pair<VirtualFile, List<VirtualFile>>? {
            val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
                ?: listOfNotNull(event.getData(CommonDataKeys.VIRTUAL_FILE))
            if (files.isEmpty() || files.any { !it.isValid || !it.isInLocalFileSystem || it.name in setOf(PUBSPEC_FILE_NAME, TOOLS_FILE_NAME) }) return null
            val root = FlutterPackageLocator.containing(files.first()) ?: return null
            if (!FlutterPackageLocator.isFlutterPackage(root) || files.any { it == root || FlutterPackageLocator.containing(it)?.path != root.path }) return null
            return root to files
        }
    }
}

class RegisterAssetsRecursivelyAction : RegisterAssetsAction(recursive = true)
