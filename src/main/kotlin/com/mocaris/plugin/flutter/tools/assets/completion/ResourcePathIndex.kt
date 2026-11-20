package com.mocaris.plugin.flutter.tools.assets.completion

import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import com.mocaris.plugin.flutter.tools.assets.config.MUT_PATTERN
import com.mocaris.plugin.flutter.tools.assets.preview.AssetImageLoader
import com.mocaris.plugin.flutter.tools.assets.preview.ImageRequest
import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal data class ResourceCandidate(val request: ImageRequest, val file: Path, val bytes: Long?) {
    val directory get() = request.path.endsWith('/')
}

/** Bounded path browsing below declared resource directories; generation keeps Flutter's shallow rules. */
internal object ResourcePathIndex {
    const val MAX_ENTRIES = 10_000
    private const val MAX_PATH_CHARS = 2 * 1024 * 1024
    data class Snapshot(val items: Map<String, ResourceCandidate>, val watched: List<PubspecAssets.Resolved>) {
        private val sorted = items as? java.util.SortedMap<String, ResourceCandidate> ?: items.toSortedMap()

        fun matches(prefix: String): Sequence<ResourceCandidate> {
            val base = prefix.substringBeforeLast('/', "") + "/"
            // Seek directly to the prefix instead of walking every resource on each keystroke.
            val candidates = sorted.tailMap(prefix).values.asSequence().takeWhile { it.request.path.startsWith(prefix) }
                .filter { !it.directory || it.request.path != prefix }
            return candidates.filter { it.directory && '/' !in it.request.path.removePrefix(base).trimEnd('/') } +
                candidates.filterNot { it.directory }
        }
    }

    fun collect(root: Path, text: String): Snapshot {
        val resolve = PubspecAssets.resolver(root)
        val items = linkedMapOf<String, ResourceCandidate>()
        val watched = mutableListOf<PubspecAssets.Resolved>()
        val variants = LinkedHashMap<Path, List<Path>>(16, .75f, true)
        var visited = 0
        var chars = 0
        fun budget(): Boolean {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return visited < MAX_ENTRIES * 2 && items.size < MAX_ENTRIES && chars < MAX_PATH_CHARS
        }
        fun add(declaration: PubspecAssets.Declaration, file: Path) {
            if (!budget() || declaration.path.length > 4096) return
            if (declaration.directory) {
                if (!Files.isDirectory(file, NOFOLLOW_LINKS)) return
            } else if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return
            fun rank(path: Path) = path.parent.fileName.toString().takeIf { it.matches(MUT_PATTERN) }
                ?.removeSuffix("x")?.toDoubleOrNull() ?: 0.0
            val previous = items[declaration.path]
            if (previous != null && rank(previous.file) <= rank(file)) return
            if (previous == null) chars += declaration.path.length
            items[declaration.path] = ResourceCandidate(ImageRequest(root.toString(), declaration.packageName, declaration.path), file,
                if (declaration.directory) null else Files.size(file))
        }
        fun scan(resource: PubspecAssets.Resolved, depth: Int = 0) {
            if (!budget() || depth >= 32 || resource.bundleDirectory.length > 4096) return
            add(resource.declaration.copy(path = resource.bundleDirectory), resource.file)
            Files.newDirectoryStream(resource.file).use { children ->
                for (file in children) {
                    if (!budget()) break
                    visited++
                    val path = resource.bundleDirectory + file.fileName
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS)) add(resource.declaration.copy(path = path), file)
                    else if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                        // Resolution variants stay represented by their logical image path.
                        if (file.fileName.toString().matches(MUT_PATTERN)) {
                            Files.newDirectoryStream(file).use { variants ->
                                for (variant in variants) {
                                    if (!budget()) break
                                    visited++
                                    if (AssetImageLoader.isImage(variant.toString()))
                                        add(resource.declaration.copy(path = resource.bundleDirectory + variant.fileName), variant)
                                }
                            }
                        } else scan(PubspecAssets.Resolved(resource.declaration.copy(path = "$path/"), file), depth + 1)
                    }
                }
            }
        }
        val config = DartPackageContext.packageConfig(root)
        val declarations = runCatching { PubspecAssets.parse(text) }.getOrDefault(emptyList())
        for (declaration in declarations.take(MAX_ENTRIES)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (!budget()) break
            try {
                if (declaration.sourcePackage != null && Files.isRegularFile(config) && Files.size(config) > 1024 * 1024) continue
                val resource = resolve(declaration)
                watched.add(resource)
                if (declaration.directory && Files.isDirectory(resource.file, NOFOLLOW_LINKS)) {
                    scan(resource)
                } else if (!declaration.directory) {
                    visited++
                    if (Files.isRegularFile(resource.file, NOFOLLOW_LINKS)) add(declaration, resource.file)
                    else if (AssetImageLoader.isImage(declaration.path) && Files.isDirectory(resource.file.parent, NOFOLLOW_LINKS)) {
                        val directories = variants.getOrPut(resource.file.parent) {
                            if (variants.size >= 128) variants.remove(variants.keys.first())
                            AssetImageLoader.variantDirectories(resource.file.parent)
                        }
                        directories.asSequence().map { it.resolve(resource.file.fileName) }.firstOrNull { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                            ?.let { add(declaration, it) }
                    }
                }
            } catch (e: InterruptedException) { throw e }
            catch (_: Exception) { /* Missing/malformed declarations must not block other suggestions. */ }
        }
        return Snapshot(items.toSortedMap(), watched)
    }
}
