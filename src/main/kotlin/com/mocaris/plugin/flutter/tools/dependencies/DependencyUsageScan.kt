package com.mocaris.plugin.flutter.tools.dependencies

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Cache references, not source/PSI. Incomplete scans must never imply an unused dependency. */
internal data class DependencyUsageSnapshot(val files: Map<Path, Set<String>?>, val complete: Boolean) {
    private val references = files.values.filterNotNull().flatten().toSet()
    private val conclusive = complete && files.values.none { it == null }
    fun unused(dependency: PackageDependency): Boolean? {
        if (dependency.section != "dependencies" || dependency.source == "sdk") return null
        if (dependency.name in references || dependency.packageName in references) return false
        return if (conclusive) true else null
    }
}

internal object DependencyUsageScan {
    const val MAX_FILE_BYTES = 1024 * 1024L
    const val MAX_FILES = 10_000
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024L
    private val excluded = setOf(".git", ".idea", ".gradle", ".dart_tool", ".fvm", ".pub-cache", "build", "node_modules", "Pods", ".symlinks")
    private val registrants = setOf("generated_plugin_registrant.dart", "dart_plugin_registrant.dart")

    fun relevant(root: Path, path: Path): Boolean = path.startsWith(root) && path != root && root.relativize(path).none { it.toString() in excluded }
    fun source(root: Path, path: Path): Boolean {
        if (!relevant(root, path) || !path.toString().endsWith(".dart") || path.fileName.toString() in registrants) return false
        var parent = path.parent
        while (parent != root && parent != null) {
            if (Files.isRegularFile(parent.resolve("pubspec.yaml")) || Files.isSymbolicLink(parent)) return false
            parent = parent.parent
        }
        return !Files.isSymbolicLink(path)
    }

    fun scan(root: Path, read: (Path) -> String?, checkCancelled: () -> Unit = {}): DependencyUsageSnapshot {
        if (Files.isSymbolicLink(root)) return DependencyUsageSnapshot(emptyMap(), false)
        val files = linkedMapOf<Path, Set<String>?>()
        var complete = true
        var bytes = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                return if (dir != root && (!relevant(root, dir) || Files.isRegularFile(dir.resolve("pubspec.yaml")))) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (!file.toString().endsWith(".dart") || file.fileName.toString() in registrants || attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                bytes += attrs.size()
                if (files.size >= MAX_FILES || bytes > MAX_TOTAL_BYTES) { complete = false; return FileVisitResult.TERMINATE }
                files[file] = if (attrs.size() <= MAX_FILE_BYTES) read(file)?.let(DartPackageReferences::parse) else null
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                complete = false
                return FileVisitResult.CONTINUE
            }
        })
        return DependencyUsageSnapshot(files, complete)
    }

    fun update(root: Path, previous: DependencyUsageSnapshot, paths: Set<Path>, read: (Path) -> String?, checkCancelled: () -> Unit = {}): DependencyUsageSnapshot {
        val files = previous.files.toMutableMap()
        var complete = previous.complete
        for (path in paths) {
            checkCancelled()
            if (!source(root, path) || !Files.isRegularFile(path)) files.remove(path)
            else if (!files.containsKey(path) && files.size >= MAX_FILES) complete = false
            else files[path] = read(path)?.let(DartPackageReferences::parse)
        }
        return DependencyUsageSnapshot(files, complete)
    }
}
