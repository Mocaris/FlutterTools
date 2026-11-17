package com.mocaris.plugin.flutter.tools.assets

import com.mocaris.plugin.flutter.tools.core.requireLocalized
import org.jetbrains.yaml.psi.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Add declarations by source offsets so comments, fonts and other pubspec settings survive. */
internal object AssetRegistration {
    /** Enumerate ordinary directories without following links or crossing package boundaries. */
    fun recursivePaths(root: Path, paths: List<String>, checkCanceled: () -> Unit = {}): List<String> {
        val base = root.toAbsolutePath().normalize()
        val directories = linkedSetOf<Path>()
        for (path in paths) {
            checkCanceled()
            val selected = AssetsGenerator.resolvePath(base, logicalPath(path, false))
            requireLocalized(Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS), "error.assetMissing", path)
            Files.walkFileTree(selected, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCanceled()
                    if (dir.fileName.toString().matches(MUT_PATTERN) ||
                        dir != selected && Files.exists(dir.resolve(PUBSPEC_FILE_NAME)) ||
                        !directories.add(dir)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCanceled()
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return directories.map { base.relativize(it).toString().replace('\\', '/') + "/" }.sorted()
    }

    fun logicalPath(path: String, resolveVariant: Boolean = true): String {
        val normalized = Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/')
        if (normalized.isEmpty() && path.endsWith('/')) return "./"
        requireLocalized(normalized.isNotBlank() && normalized != "." && !normalized.startsWith("../") && !Path.of(normalized).isAbsolute, "error.relativePath", path)
        if (path.endsWith('/')) return "$normalized/"
        val directory = normalized.substringBeforeLast('/', "")
        return if (resolveVariant && directory.substringAfterLast('/').matches(MUT_PATTERN)) {
            listOf(directory.substringBeforeLast('/', ""), normalized.substringAfterLast('/')).filter(String::isNotEmpty).joinToString("/")
        } else normalized
    }

    fun updatedText(file: YAMLFile, paths: List<String>): String {
        val source = file.text
        val existing = PubspecAssets.parse(source).filter { it.kind == PubspecAssets.Kind.ASSET }.map { logicalPath(it.path, false) }
        val requested = paths.map(::logicalPath).distinct()
        val additions = requested.filter { path ->
            existing.none { it == path } && (existing + requested).none { declared -> declared.endsWith('/') && !path.endsWith('/') &&
                path.substringBeforeLast('/', "") == Path.of(declared).normalize().toString().replace('\\', '/') }
        }
        if (additions.isEmpty()) return source
        val scalar = Yaml(DumperOptions().apply { defaultScalarStyle = DumperOptions.ScalarStyle.DOUBLE_QUOTED; setSplitLines(false) })
        val values = additions.map { scalar.dump(it).trimEnd() }
        val list = values.joinToString(", ", "[", "]")
        val top = file.documents.singleOrNull()?.topLevelValue as? YAMLMapping ?: error("Invalid pubspec mapping")
        fun flow(value: YAMLValue) = value.text.trimStart().startsWith('{') || value.text.trimStart().startsWith('[')
        fun indent(offset: Int) = source.substring(source.lastIndexOf('\n', offset - 1) + 1, offset).takeWhile { it == ' ' || it == '\t' }
        fun lineEnd(offset: Int) = source.indexOf('\n', offset).takeIf { it >= 0 } ?: source.length
        fun empty(value: YAMLValue?) = value == null || value is YAMLScalar && value.text.trim() in setOf("null", "~")
        fun fill(key: YAMLKeyValue, owner: YAMLMapping, inline: String, block: String): String {
            val value = key.value
            if (flow(owner)) return if (value == null) source.replaceRange(key.textRange.endOffset, key.textRange.endOffset, " $inline")
                else source.replaceRange(value.textRange.startOffset, value.textRange.endOffset, inline)
            val offset = lineEnd(key.textRange.endOffset)
            val updated = source.replaceRange(offset, offset, "\n" + block.prependIndent(indent(key.textOffset) + "  "))
            return if (value == null) updated else updated.removeRange(value.textRange.startOffset, value.textRange.endOffset)
        }
        fun append(mapping: YAMLMapping, text: String): String {
            if (flow(mapping)) {
                val close = mapping.textRange.endOffset - 1
                val tail = source.substring(mapping.keyValues.lastOrNull()?.textRange?.endOffset ?: mapping.textOffset + 1, close)
                    .lineSequence().joinToString("") { it.substringBefore('#') }
                val separator = if (mapping.keyValues.isNotEmpty() && ',' !in tail) ", " else " "
                return source.replaceRange(close, close, "$separator$text ")
            }
            val prefix = indent(mapping.keyValues.first().textOffset)
            val offset = lineEnd(mapping.textRange.endOffset)
            return source.replaceRange(offset, offset, "\n" + text.lineSequence().joinToString("\n") { prefix + it })
        }
        fun block() = "assets:\n" + values.joinToString("\n") { "  - $it" }
        val flutterKey = top.getKeyValueByKey("flutter")
        val flutter = flutterKey?.value?.takeUnless(::empty)
        val result = when (flutter) {
            is YAMLMapping -> {
                val key = flutter.getKeyValueByKey("assets")
                when (val sequence = key?.value?.takeUnless(::empty)) {
                    is YAMLSequence -> if (flow(sequence)) {
                        val close = sequence.textRange.endOffset - 1
                        val tail = source.substring(sequence.items.lastOrNull()?.textRange?.endOffset ?: sequence.textOffset + 1, close)
                            .lineSequence().joinToString("") { it.substringBefore('#') }
                        val separator = if (sequence.items.isNotEmpty() && ',' !in tail) ", " else " "
                        source.replaceRange(close, close, separator + values.joinToString(", ") + " ")
                    } else {
                        val last = sequence.items.last()
                        val offset = lineEnd(last.textRange.endOffset)
                        source.replaceRange(offset, offset, "\n" + values.joinToString("\n") { indent(last.textOffset) + "- $it" })
                    }
                    null -> if (key == null) append(flutter, if (flow(flutter)) "assets: $list" else block())
                        else fill(key, flutter, list, values.joinToString("\n") { "- $it" })
                    else -> error("Invalid flutter.assets sequence")
                }
            }
            null -> if (flutterKey == null) append(top, if (flow(top)) "flutter: {assets: $list}" else "flutter:\n" + block().prependIndent("  "))
                else fill(flutterKey, top, "{assets: $list}", block())
            else -> error("Invalid flutter mapping")
        }
        PubspecAssets.parse(result)
        return result
    }
}
