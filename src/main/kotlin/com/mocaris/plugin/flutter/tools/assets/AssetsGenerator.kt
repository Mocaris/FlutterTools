package com.mocaris.plugin.flutter.tools.assets

import com.mocaris.plugin.flutter.tools.core.requireLocalized
import com.mocaris.plugin.flutter.tools.core.errorLocalized
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption

/** Pure parsing and generation; IDE document writes are owned by the project service. */
object AssetsGenerator {
    fun parseYaml(toolsYamlFile: File, pubYamlFile: File): AssetsSyncConfig =
        parseText((if (toolsYamlFile.exists()) toolsYamlFile else pubYamlFile).readText(Charsets.UTF_8))

    fun parseText(text: String, defaultOutClass: String = DEFAULT_OUT_CLASS): AssetsSyncConfig {
        val root = YamlSupport.load(text) as? Map<*, *>
            ?: errorLocalized("error.configMapping")
        val tools = root["flutter_tools"] as? Map<*, *> ?: errorLocalized("error.toolsMissing")
        val node = tools["assets_sync"] as? Map<*, *> ?: errorLocalized("error.assetsConfigMissing")
        fun strings(key: String, default: Set<String>? = null): Set<String> {
            val value = node[key] ?: return default ?: errorLocalized("error.configKeyMissing", key)
            requireLocalized(value is List<*> && value.all { it is String && it.isNotBlank() }, "error.configList", key)
            return value.map { it as String }.toSet()
        }
        fun string(key: String, default: String): String = (node[key] ?: default).let {
            requireLocalized(it is String && it.isNotBlank(), "error.configString", key); it
        }
        fun boolean(key: String): Boolean = (node[key] ?: false).let {
            requireLocalized(it is Boolean, "error.configBoolean", key); it
        }
        return AssetsSyncConfig(string("out_path", DEFAULT_OUT_PATH),
            string("out_class", defaultOutClass), boolean("watch"), boolean("out_extension"), strings("excluded", DEFAULT_EXCLUDE))
    }

    fun resolvePath(root: Path, relative: String, allowRoot: Boolean = false): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val input = Path.of(relative.replace('\\', '/'))
        requireLocalized(!input.isAbsolute, "error.relativePath", relative)
        val result = normalizedRoot.resolve(input).normalize()
        requireLocalized(result.startsWith(normalizedRoot) && (allowRoot || result != normalizedRoot), "error.pathInside", relative)
        var existing: Path? = result
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
        requireLocalized(existing != null && existing.toRealPath().startsWith(normalizedRoot.toRealPath()), "error.pathOutside", relative)
        return result
    }

    fun generate(projectPath: String, config: AssetsSyncConfig, pubspecText: String? = null): Map<Path, String> {
        val root = Path.of(projectPath).toAbsolutePath().normalize()
        val text = pubspecText ?: Files.readString(root.resolve(PUBSPEC_FILE_NAME))
        return generate(root, config, PubspecAssets.resolve(root, PubspecAssets.parse(text)))
    }

    fun generate(root: Path, config: AssetsSyncConfig, resources: List<PubspecAssets.Resolved>): Map<Path, String> {
        validateClassName(config.outClass)
        val outputs = outputPaths(root, config)
        val entry = validateOutput(root, config.outPath)
        val allOutputs = outputs.values.toSet() + setOf(entry) + legacyOutputPaths(root, config)
        requireLocalized(resources.none { !it.declaration.directory && it.file in allOutputs }, "error.outputResource")
        val nodes = linkedSetOf<PubspecAssets.Declaration>()
        fun add(key: String, file: Path, declaration: PubspecAssets.Declaration) {
            if (file in allOutputs || config.excluded.any { matchesExcluded(key, it) }) return
            requireLocalized(!Files.isDirectory(file), "error.directorySlash", key)
            if (!Files.isRegularFile(file)) {
                val hasVariants = declaration.kind == PubspecAssets.Kind.ASSET && Files.isDirectory(file.parent) &&
                    Files.newDirectoryStream(file.parent).use { siblings ->
                        siblings.any { directory -> Files.isDirectory(directory) && directory.fileName.toString().matches(MUT_PATTERN) &&
                            Files.isRegularFile(directory.resolve(file.fileName)) }
                    }
                requireLocalized(hasVariants, "error.assetMissing", key)
            }
            nodes.add(declaration.copy(path = key))
        }
        resources.forEach { resource ->
            val declaration = resource.declaration
            val declarationKey = if (declaration.directory) resource.bundleDirectory else declaration.path
            if (config.excluded.any { matchesExcluded(declarationKey, it) }) return@forEach
            if (declaration.directory) {
                requireLocalized(Files.isDirectory(resource.file), "error.assetDirectoryMissing", declaration.path)
                Files.newDirectoryStream(resource.file).use { children ->
                    children.filter { Files.isRegularFile(it) }.forEach { file ->
                        val key = resource.bundleDirectory + file.fileName.toString()
                        add(key, file, declaration)
                    }
                }
            } else add(declaration.path, resource.file, declaration)
        }
        val contents = linkedMapOf<Path, String>()
        outputs.forEach { (kind, output) ->
            contents[output] = AssetsDartTemplate.part(entry, output, config, kind, nodes.filter { it.kind == kind })
        }
        contents[entry] = AssetsDartTemplate.entry(entry, config, outputs.values, nodes.any { it.kind == PubspecAssets.Kind.FONT })
        return contents
    }

    /** Generated parts live under lib/gen in each package, independently of the entry location. */
    fun outputPaths(root: Path, config: AssetsSyncConfig): Map<PubspecAssets.Kind, Path> {
        val entry = validateOutput(root, config.outPath)
        val outputs = PubspecAssets.Kind.values().associateWith { kind ->
            validateOutput(root, "lib/gen/${AssetsDartTemplate.resourceName(kind)}.g.dart")
        }
        requireLocalized(entry.fileName !in outputs.values.map { it.fileName }, "error.entryFilename", config.outPath)
        requireLocalized(outputs.values.toSet().size == outputs.size, "error.outputDistinct")
        return outputs
    }

    /** Only migrate old files bearing our marker and a part-of reference to this entry. */
    fun legacyOutputPaths(root: Path, config: AssetsSyncConfig): Set<Path> {
        val entry = validateOutput(root, config.outPath)
        val current = outputPaths(root, config).values.toSet()
        val entryName = entry.fileName.toString()
        val references = setOf("part of \"${escapeDart(entryName)}\";", "part of '${entryName.replace("'", "\\'")}';")
        return PubspecAssets.Kind.values().map { entry.parent.resolve("${AssetsDartTemplate.resourceName(it)}.g.dart") }
            .filter { path -> path !in current && Files.isRegularFile(path) && Files.newBufferedReader(path).use { reader ->
                reader.readLine() == GENERATED_MARKER && reader.lineSequence().take(8).any { it.trim() in references }
            } }.toSet()
    }

    fun validateClassName(name: String) {
        requireLocalized(identifier.matches(name) && name !in keywords, "error.className", name)
    }

    internal fun fieldName(packageName: String, path: String, extension: Boolean): String {
        val filename = path.substringAfterLast('/')
        val basename = if (extension) filename else filename.substringBeforeLast('.', filename).ifEmpty { filename }
        return dartName("${path.substringBeforeLast('/', packageName)}/$basename")
    }

    internal fun dartName(value: String, prefix: String = "asset"): String {
        val parts = value.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotEmpty() }
        var name = parts.mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar { it.uppercaseChar() } }.joinToString("")
        if (name.isEmpty()) name = prefix
        if (name.first().isDigit() || name in keywords || name in setOf("hashCode", "runtimeType", "toString", "noSuchMethod")) {
            name = prefix + name.replaceFirstChar { it.uppercaseChar() }
        }
        return name
    }

    fun validateOutput(root: Path, relative: String): Path {
        val output = resolvePath(root, relative)
        requireLocalized(output.fileName.toString().endsWith(".dart"), "error.outputDart", relative)
        requireLocalized(!Files.isDirectory(output), "error.outputDirectory", relative)
        return output
    }

    const val GENERATED_MARKER = "// Auto generated by FlutterTools"

    fun validateExistingOutput(output: Path) {
        requireLocalized(!Files.exists(output) || Files.isRegularFile(output) &&
            Files.newBufferedReader(output).use { it.readLine() } == GENERATED_MARKER, "error.outputProtected", output)
    }

    fun matchesExcluded(path: String, pattern: String): Boolean {
        val normalized = pattern.replace('\\', '/')
        if (normalized.endsWith('/')) {
            val directory = normalized.trimEnd('/')
            return path == directory || path.startsWith("$directory/")
        }
        if ('*' !in normalized && '?' !in normalized) {
            if ('/' !in normalized && normalized.startsWith('.')) return path.substringAfterLast('/') == normalized
            return path == normalized || path.startsWith("$normalized/")
        }
        val expression = buildString {
            append('^')
            normalized.forEach { ch -> append(when (ch) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(ch.toString()) }) }
            append('$')
        }
        return Regex(expression).matches(if ('/' in normalized) path else path.substringAfterLast('/'))
    }

    internal fun escapeDart(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r")
    private val identifier = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    private val keywords = setOf("abstract", "as", "assert", "async", "await", "base", "break", "case", "catch", "class", "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum", "export", "extends", "extension", "external", "factory", "false", "final", "finally", "for", "Function", "get", "hide", "if", "implements", "import", "in", "interface", "is", "late", "library", "mixin", "new", "null", "of", "on", "operator", "part", "required", "rethrow", "return", "sealed", "set", "show", "static", "super", "switch", "sync", "this", "throw", "true", "try", "typedef", "var", "void", "when", "while", "with", "yield")
}
