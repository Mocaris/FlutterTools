package com.mocaris.plugin.flutter.tools.assets.generation

import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import com.mocaris.plugin.flutter.tools.assets.config.DEFAULT_EXCLUDE
import com.mocaris.plugin.flutter.tools.assets.config.DEFAULT_OUT_CLASS
import com.mocaris.plugin.flutter.tools.assets.config.DEFAULT_OUT_PATH
import com.mocaris.plugin.flutter.tools.assets.config.MUT_PATTERN
import com.mocaris.plugin.flutter.tools.assets.config.PUBSPEC_FILE_NAME
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.errorLocalized
import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized
import com.google.gson.JsonParser
import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubConstraint
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubVersion
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Pure parsing and generation; IDE document writes are owned by the project service. */
object AssetsGenerator {
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
        return generate(root, config, PubspecAssets.resolve(root, PubspecAssets.parse(text)), text)
    }

    fun generate(root: Path, config: AssetsSyncConfig, resources: List<PubspecAssets.Resolved>, pubspecText: String): Map<Path, String> {
        val spec = YamlSupport.load(pubspecText) as? Map<*, *> ?: errorLocalized("error.configMapping")
        validateLanguageVersion(root, spec)
        validateClassName(config.outClass)
        val allOutputs = outputPaths(root, config)
        val entry = allOutputs.single()
        requireLocalized(resources.none { !it.declaration.directory && it.file in allOutputs }, "error.outputResource")
        val nodes = collect(config, resources, allOutputs).map { it.declaration }
        return mapOf(entry to AssetsDartTemplate.entry(config, nodes, PubspecAssets.packageName(spec)))
    }

    private fun validateLanguageVersion(root: Path, spec: Map<*, *>) {
        val sdk = (spec["environment"] as? Map<*, *>)?.get("sdk") as? String
        requireLocalized(sdk != null && PubConstraint.overlaps(sdk, "<3.0.0") == false &&
            PubConstraint.overlaps(sdk, ">=3.0.0") == true, "error.resourceLanguage")
        val config = DartPackageContext.packageConfig(root)
        if (Files.isRegularFile(config)) {
            val json = JsonParser.parseString(Files.readString(config)).asJsonObject
            val language = json.getAsJsonArray("packages")?.firstOrNull { it.asJsonObject.get("name")?.asString == spec["name"] }
                ?.asJsonObject?.get("languageVersion")?.asString
            requireLocalized(language == null || PubVersion.parse("$language.0")?.let { it.major >= 3 } == true,
                "error.resourceLanguage")
        }
    }

    /** Shared Flutter scanning semantics for generation and inspections. */
    fun collect(root: Path, config: AssetsSyncConfig, resources: List<PubspecAssets.Resolved>): List<PubspecAssets.Resolved> =
        collect(config, resources, outputPaths(root, config))

    internal fun collect(config: AssetsSyncConfig, resources: List<PubspecAssets.Resolved>, allOutputs: Set<Path>): List<PubspecAssets.Resolved> {
        val excluded = exclusionMatcher(config.excluded)
        val nodes = linkedMapOf<PubspecAssets.Declaration, PubspecAssets.Resolved>()
        fun add(key: String, file: Path, declaration: PubspecAssets.Declaration) {
            if (file in allOutputs || excluded(key)) return
            requireLocalized(!Files.isDirectory(file), "error.directorySlash", key)
            if (!Files.isRegularFile(file)) {
                val hasVariants = declaration.kind == PubspecAssets.Kind.ASSET && Files.isDirectory(file.parent) &&
                    Files.newDirectoryStream(file.parent).use { siblings ->
                        siblings.any { directory -> Files.isDirectory(directory) && directory.fileName.toString().matches(MUT_PATTERN) &&
                            Files.isRegularFile(directory.resolve(file.fileName)) }
                    }
                requireLocalized(hasVariants, "error.assetMissing", key)
            }
            val logical = declaration.copy(path = key)
            nodes[logical] = PubspecAssets.Resolved(logical, file)
        }
        resources.forEach { resource ->
            val declaration = resource.declaration
            val declarationKey = if (declaration.directory) resource.bundleDirectory else declaration.path
            if (excluded(declarationKey)) return@forEach
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
        return nodes.values.toList()
    }

    fun outputPaths(root: Path, config: AssetsSyncConfig): Set<Path> = setOf(validateOutput(root, config.outPath))

    fun validateClassName(name: String) {
        requireLocalized(identifier.matches(name) && name !in keywords && name !in setOf("String", "List", "Object", "_flutter", "package", "AssetsMeta", "FontFaceStyle"), "error.className", name)
    }

    internal fun fieldName(packageName: String, path: String, extension: Boolean): String {
        val filename = path.substringAfterLast('/')
        val basename = if (extension) filename else filename.substringBeforeLast('.', filename).ifEmpty { filename }
        return dartName("${path.substringBeforeLast('/', packageName)}/$basename")
    }

    internal fun dartName(value: String, prefix: String = "asset"): String {
        val parts = value.split(nameSeparator).filter { it.isNotEmpty() }
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

    fun matchesExcluded(path: String, pattern: String): Boolean = compileExclusion(pattern)(path)

    /** Compile once per scan; keep matching behavior shared with the public single-pattern helper. */
    internal fun exclusionMatcher(patterns: Set<String>): (String) -> Boolean {
        val matchers = patterns.map(::compileExclusion)
        return { path -> matchers.any { it(path) } }
    }

    private fun compileExclusion(pattern: String): (String) -> Boolean {
        val normalized = pattern.replace('\\', '/')
        if (normalized.endsWith('/')) {
            val directory = normalized.trimEnd('/')
            return { path -> path == directory || path.startsWith("$directory/") }
        }
        if ('*' !in normalized && '?' !in normalized) {
            if ('/' !in normalized && normalized.startsWith('.')) return { path -> path.substringAfterLast('/') == normalized }
            return { path -> path == normalized || path.startsWith("$normalized/") }
        }
        val expression = buildString {
            append('^')
            normalized.forEach { ch -> append(when (ch) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(ch.toString()) }) }
            append('$')
        }
        val regex = Regex(expression)
        return if ('/' in normalized) { path -> regex.matches(path) }
            else { path -> regex.matches(path.substringAfterLast('/')) }
    }

    internal fun escapeDart(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r")
    private val identifier = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    private val nameSeparator = Regex("[^a-zA-Z0-9]+")
    private val keywords = setOf("abstract", "as", "assert", "async", "await", "base", "break", "case", "catch", "class", "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum", "export", "extends", "extension", "external", "factory", "false", "final", "finally", "for", "Function", "get", "hide", "if", "implements", "import", "in", "interface", "is", "late", "library", "mixin", "new", "null", "of", "on", "operator", "part", "required", "rethrow", "return", "sealed", "set", "show", "static", "super", "switch", "sync", "this", "throw", "true", "try", "typedef", "var", "void", "when", "while", "with", "yield")
}
