package com.mocaris.plugin.flutter.tools.dependencies.installation

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.dependencies.commands.FlutterPubGetRunner
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageDependency
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile

object DependencyInstaller {
    private fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)?.text
        ?: String(file.contentsToByteArray(), file.charset)
    private fun yaml(project: Project, file: VirtualFile) = PsiFileFactory.getInstance(project)
        .createFileFromText(file.name, YAMLFileType.YML, text(file)) as YAMLFile
    private fun overrides(project: Project, root: VirtualFile) = root.findChild("pubspec_overrides.yaml")
        ?.let { yaml(project, it) }

    fun existing(project: Project, root: VirtualFile, name: String): PackageDependency? = ReadAction.compute<PackageDependency?, RuntimeException> {
        val file = requireNotNull(root.findChild("pubspec.yaml"))
        val spec = yaml(project, file)
        DependencyAdder.existing(spec, name, overrides(project, root))
    }

    fun add(project: Project, root: VirtualFile, name: String, version: String, section: String, pubGet: Boolean) {
        WriteCommandAction.runWriteCommandAction(project, message("search.commandAdd"), null, Runnable {
            val file = requireNotNull(root.findChild("pubspec.yaml"))
            val documents = FileDocumentManager.getInstance()
            val document = requireNotNull(documents.getDocument(file))
            val manager = PsiDocumentManager.getInstance(project)
            manager.commitDocument(document)
            val spec = PsiManager.getInstance(project).findFile(file) as YAMLFile
            val updated = DependencyAdder.updatedText(spec, name, version, section, overrides(project, root))
            if (document.text != updated) document.setText(updated)
            manager.commitDocument(document)
            documents.saveDocument(document)
        })
        if (pubGet) FlutterPubGetRunner.run(project, root)
    }
}
