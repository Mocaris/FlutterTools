package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/** Pub workspaces own one package configuration and SDK context for all member packages. */
object DartPackageContext {
    private fun property(spec: String?, overrides: String?, key: String): Any? {
        val external = overrides?.let { YamlSupport.load(it) as? Map<*, *> }
        if (external?.containsKey(key) == true) return external[key]
        return (spec?.let(YamlSupport::load) as? Map<*, *>)?.get(key)
    }

    private fun <T> context(root: T, parent: (T) -> T?, property: (T, String) -> Any?): T {
        var current = root
        while (property(current, "resolution") == "workspace") {
            var ancestor = parent(current)
            while (ancestor != null && property(ancestor, "workspace") !is List<*>) ancestor = parent(ancestor)
            current = ancestor ?: return current
        }
        return current
    }

    fun root(root: Path): Path = context(root.toAbsolutePath().normalize(), { it.parent }) { directory, key ->
        fun read(name: String): String? = directory.resolve(name).takeIf(Files::isRegularFile)?.let(Files::readString)
        property(read("pubspec.yaml"), read("pubspec_overrides.yaml"), key)
    }

    fun root(root: VirtualFile): VirtualFile = context(root, { it.parent }) { directory, key ->
        fun read(name: String): String? = directory.findChild(name)?.let { file ->
            FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: String(file.contentsToByteArray(), file.charset)
        }
        property(read("pubspec.yaml"), read("pubspec_overrides.yaml"), key)
    }

    fun packageConfig(root: Path): Path = root(root).resolve(".dart_tool/package_config.json")
    fun packageConfig(root: VirtualFile): VirtualFile? = root(root).findChild(".dart_tool")?.findChild("package_config.json")

    /** Also invalidate on ancestor metadata changes: resolution can switch before a new config is created. */
    fun metadataAffected(root: Path, changed: Path): Boolean {
        val base = root.toAbsolutePath().normalize()
        val path = changed.toAbsolutePath().normalize()
        return when (path.fileName?.toString()) {
            "pubspec.yaml", "pubspec_overrides.yaml" -> path.parent?.let(base::startsWith) == true
            ".dart_tool" -> path.parent?.let(base::startsWith) == true
            "package_config.json" -> path.parent?.fileName?.toString() == ".dart_tool" &&
                path.parent?.parent?.let(base::startsWith) == true
            else -> false
        }
    }

    fun sdkAffected(root: Path, changed: Path): Boolean {
        if (metadataAffected(root, changed)) return true
        val path = changed.toAbsolutePath().normalize()
        if (path.endsWith("bin/cache/flutter.version.json")) return true
        val index = path.indexOfFirst { it.toString() == ".fvm" }
        if (index < 0) return false
        val owner = if (index == 0) path.root else path.root.resolve(path.subpath(0, index))
        return root.toAbsolutePath().normalize().startsWith(owner)
    }
}
