package com.mocaris.plugin.flutter.tools.assets

import com.google.gson.JsonParser
import com.mocaris.plugin.flutter.tools.assets.config.MUT_PATTERN
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.errorLocalized
import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized
import java.nio.file.Files
import java.nio.file.Path

/** Resource declarations are owned by Flutter's pubspec, never by generator settings. */
object PubspecAssets {
    enum class Kind { ASSET, FONT, SHADER }
    data class Font(val family: String, val weight: Int = 400, val style: String = "normal")
    data class Declaration(val path: String, val packageName: String, val kind: Kind = Kind.ASSET, val font: Font? = null) {
        val directory get() = path.endsWith('/')
        val sourcePackage get() = path.takeIf { it.startsWith("packages/") }?.split('/')?.getOrNull(1)
    }
    data class Resolved(val declaration: Declaration, val file: Path) {
        val watchPath: Path get() = if (declaration.directory) file else file.parent
        // Flutter derives directory entries from normalized paths relative to the asset base.
        val bundleDirectory: String get() {
            val normalized = Path.of(declaration.path).normalize().toString().replace('\\', '/').trimEnd('/')
            return if (normalized.isEmpty()) "" else "$normalized/"
        }

        fun affectedBy(path: String): Boolean {
            val changed = Path.of(path).toAbsolutePath().normalize()
            if (file.startsWith(changed)) return true // declaration or its ancestor removed/moved
            if (declaration.directory) return changed.parent == file
            return changed == file || declaration.kind == Kind.ASSET && (
                changed.parent == file.parent && changed.fileName.toString().matches(MUT_PATTERN) ||
                changed.fileName == file.fileName && changed.parent?.parent == file.parent &&
                    changed.parent.fileName.toString().matches(MUT_PATTERN))
        }
    }

    internal fun packageName(root: Map<*, *>): String {
        val name = root["name"]
        requireLocalized(name is String && Regex("[a-z_][a-z0-9_]*").matches(name), "error.packageName")
        return name
    }

    fun parse(text: String): List<Declaration> {
        val root = YamlSupport.load(text) as? Map<*, *> ?: errorLocalized("error.pubspecMapping")
        val packageName = packageName(root)
        val flutter = root["flutter"] ?: return emptyList()
        requireLocalized(flutter is Map<*, *>, "error.flutterMapping")
        val result = mutableListOf<Declaration>()
        fun entries(value: Any?, section: String): List<*> {
            if (value == null) return emptyList<Any>()
            requireLocalized(value is List<*>, "error.pubspecList", section)
            return value
        }
        fun path(value: Any?, section: String): String {
            requireLocalized(value is String && value.isNotBlank(), "error.pubspecPaths", section)
            return value.replace('\\', '/')
        }
        entries(flutter["assets"], "flutter.assets").forEach { entry ->
            result.add(Declaration(path(if (entry is Map<*, *>) entry["path"] else entry, "flutter.assets"), packageName))
        }
        entries(flutter["fonts"], "flutter.fonts").forEach { font ->
            requireLocalized(font is Map<*, *>, "error.fontMappings")
            val family = font["family"]
            requireLocalized(family is String && family.isNotBlank(), "error.fontFamily")
            entries(font["fonts"], "flutter.fonts.fonts").forEach { face ->
                requireLocalized(face is Map<*, *>, "error.fontEntries")
                val asset = path(face["asset"], "flutter.fonts.fonts.asset")
                requireLocalized(!asset.endsWith('/'), "error.fontPath", asset)
                val weight = face["weight"] ?: 400
                requireLocalized(weight is Int && weight in 100..900 && weight % 100 == 0, "error.fontWeight")
                val style = face["style"] ?: "normal"
                requireLocalized(style == "normal" || style == "italic", "error.fontStyle")
                result.add(Declaration(asset, packageName, Kind.FONT, Font(family, weight, style as String)))
            }
        }
        entries(flutter["shaders"], "flutter.shaders").forEach {
            val asset = path(it, "flutter.shaders")
            requireLocalized(!asset.endsWith('/'), "error.shaderPath", asset)
            result.add(Declaration(asset, packageName, Kind.SHADER))
        }
        return result.distinct()
    }

    fun resolve(root: Path, declarations: List<Declaration>): List<Resolved> = declarations.map(resolver(root))

    /** Reuse parsed package metadata while resolving multiple images; callers invalidate on config changes. */
    internal fun resolver(root: Path): (Declaration) -> Resolved {
        val packages by lazy {
            val config = DartPackageContext.packageConfig(root)
            requireLocalized(Files.isRegularFile(config), "error.packageGet")
            JsonParser.parseString(Files.readString(config)).asJsonObject.getAsJsonArray("packages")
                .associate { it.asJsonObject.let { pkg -> pkg.get("name").asString to pkg } }
        }
        return { declaration ->
            val local = AssetsGenerator.resolvePath(root, declaration.path, allowRoot = declaration.directory)
            if (!declaration.path.startsWith("packages/") || Files.exists(local)) Resolved(declaration, local)
            else {
                val components = declaration.path.split('/', limit = 3)
                requireLocalized(components.size == 3 && components[1].isNotEmpty(), "error.packageAsset", declaration.path)
                val config = DartPackageContext.packageConfig(root)
                val pkg = packages[components[1]]
                    ?: errorLocalized("error.packageConfig", components[1])
                val packageRoot = config.toUri().resolve(pkg.get("rootUri").asString)
                val base = java.net.URI(packageRoot.toString().trimEnd('/') + "/").resolve(pkg.get("packageUri")?.asString ?: "lib/")
                requireLocalized(base.scheme == "file", "error.packageLocation", base)
                Resolved(declaration, if (components[2].isEmpty() && declaration.directory) Path.of(base) else AssetsGenerator.resolvePath(Path.of(base), components[2]))
            }
        }
    }
}
