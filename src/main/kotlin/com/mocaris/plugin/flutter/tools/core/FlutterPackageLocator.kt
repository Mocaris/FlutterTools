package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
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
        val owners = linkedMapOf<String, Pair<VirtualFile, WorkspaceLayout.Rules>>()
        roots.map { WorkspaceLayout.owner(it)?.first ?: it }.distinctBy { it.path }.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, { !it.isDirectory || it.name !in excluded && !java.nio.file.Files.isSymbolicLink(java.nio.file.Path.of(it.path)) }) { file ->
                if (file.isDirectory && (file.findChild("pubspec.yaml") != null || file.findChild("melos.yaml") != null)) {
                    WorkspaceLayout.rules(file)?.let { owners[file.path] = file to it }
                }
                if (!file.isDirectory && file.name == "pubspec.yaml") packages[file.parent.path] = file.parent
                true
            }
        }
        return packages.values.filter { candidate ->
            val enclosing = owners.values.filter { candidate.path == it.first.path || candidate.path.startsWith("${it.first.path}/") }
            enclosing.none { it.second.excludes(candidate.path.removePrefix(it.first.path).trimStart('/')) } &&
                (enclosing.isEmpty() || enclosing.any { it.second.includes(candidate.path.removePrefix(it.first.path).trimStart('/')) })
        }.sortedBy { it.path }
    }

    fun choosePackage(project: Project, candidates: List<VirtualFile>, file: VirtualFile?): VirtualFile? {
        val fromFile = containing(file)
        if (fromFile != null && candidates.any { it.path == fromFile.path }) return fromFile
        val selected = project.getService(FlutterToolsSettings::class.java).state.selectedPackagePath
        return candidates.firstOrNull { it.path == selected } ?: candidates.singleOrNull()
    }

    fun displayName(project: Project, root: VirtualFile): String =
        if (root.path == project.basePath) root.name else root.path.removePrefix("${project.basePath}/")

    fun containing(file: VirtualFile?): VirtualFile? {
        var directory = if (file?.isDirectory == true) file else file?.parent
        while (directory != null) {
            if (directory.findChild("pubspec.yaml") != null) return directory
            directory = directory.parent
        }
        return null
    }
}
