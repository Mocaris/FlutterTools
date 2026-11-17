package com.mocaris.plugin.flutter.tools.dependencies

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile

object DependencyInstaller {
    data class Context(val dependency: PackageDependency?)
    private fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)?.text
        ?: String(file.contentsToByteArray(), file.charset)
    private fun yaml(project: Project, file: VirtualFile) = PsiFileFactory.getInstance(project)
        .createFileFromText(file.name, YAMLFileType.YML, text(file)) as YAMLFile
    private fun overrides(project: Project, root: VirtualFile) = root.findChild("pubspec_overrides.yaml")
        ?.let { yaml(project, it) }

    fun context(project: Project, root: VirtualFile, name: String): Context = ReadAction.compute<Context, RuntimeException> {
        val file = requireNotNull(root.findChild("pubspec.yaml"))
        val spec = yaml(project, file)
        Context(DependencyAdder.existing(spec, name, overrides(project, root)))
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
