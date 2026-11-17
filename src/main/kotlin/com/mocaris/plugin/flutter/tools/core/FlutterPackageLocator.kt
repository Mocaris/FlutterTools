package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Files
import java.nio.file.Path

object FlutterPackageLocator {
    private val excluded = setOf(".git", ".idea", ".gradle", ".dart_tool", "build", "node_modules", ".pub-cache")

    fun isFlutterPackage(directory: Path): Boolean = runCatching {
        isFlutterPubspec(Files.readString(directory.resolve("pubspec.yaml")))
    }.getOrDefault(false)

    fun isFlutterPackage(directory: VirtualFile): Boolean = runCatching {
        val pubspec = directory.findChild("pubspec.yaml") ?: return false
        val text = FileDocumentManager.getInstance().getCachedDocument(pubspec)?.text ?: VfsUtilCore.loadText(pubspec)
        isFlutterPubspec(text)
    }.getOrDefault(false)

    private fun isFlutterPubspec(text: String): Boolean {
        val yaml = YamlSupport.load(text) as? Map<*, *> ?: return false
        val dependencies = yaml["dependencies"] as? Map<*, *> ?: return false
        val flutter = dependencies["flutter"] as? Map<*, *> ?: return false
        return flutter["sdk"] == "flutter"
    }

    fun findPackages(project: Project): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots.toMutableList()
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.let { roots.add(it) }
        val packages = linkedMapOf<String, VirtualFile>()
        roots.distinctBy { it.path }.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, { !it.isDirectory || it.name !in excluded && !java.nio.file.Files.isSymbolicLink(java.nio.file.Path.of(it.path)) }) { file ->
                if (!file.isDirectory && file.name == "pubspec.yaml") packages[file.parent.path] = file.parent
                true
            }
        }
        return packages.values.sortedBy { it.path }
    }

    fun choosePackage(project: Project, candidates: List<VirtualFile>, file: VirtualFile?): VirtualFile? {
        val fromFile = containing(file)
        if (fromFile != null && candidates.any { it.path == fromFile.path }) return fromFile
        val selected = project.getService(FlutterToolsSettings::class.java).state.selectedPackagePath
        return candidates.firstOrNull { it.path == selected } ?: candidates.singleOrNull()
    }

    fun containing(file: VirtualFile?): VirtualFile? {
        var directory = if (file?.isDirectory == true) file else file?.parent
        while (directory != null) {
            if (directory.findChild("pubspec.yaml") != null) return directory
            directory = directory.parent
        }
        return null
    }
}
