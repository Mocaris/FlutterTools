package com.mocaris.plugin.flutter.tools.assets.inspection

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsDartTemplate
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.text
import com.mocaris.plugin.flutter.tools.core.localization.LocalizedException
import com.mocaris.plugin.flutter.tools.core.localization.LocalizedText
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.yaml.psi.*

/** Editor diagnostics retain PSI locations and collect independent errors instead of failing at the first one. */
object ResourceDiagnostics {
    data class Issue(val element: PsiElement, val message: LocalizedText, val replacement: String? = null, val remove: Boolean = false, val selectResource: Boolean = false)

    fun inspect(file: YAMLFile, root: Path, config: AssetsSyncConfig): List<Issue> {
        val top = file.documents.singleOrNull()?.topLevelValue as? YAMLMapping ?: return emptyList()
        val entry = top.getKeyValueByKey("flutter") ?: return emptyList()
        val issues = mutableListOf<Issue>()
        fun issue(element: PsiElement, key: String, vararg args: Any?, replacement: String? = null, remove: Boolean = false) {
            issues.add(Issue(element, text(key, *args), replacement, remove))
        }
        val flutter = entry.value as? YAMLMapping ?: return listOf(Issue(entry, text("error.flutterMapping")))
        val packageName = (top.getKeyValueByKey("name")?.value as? YAMLScalar)?.textValue ?: "app"
        val resolve = PubspecAssets.resolver(root)
        // Scope metadata reuse to this inspection so subsequent edits still revalidate paths.
        val allOutputs by lazy { AssetsGenerator.excludedOutputPaths(root, config) }
        val scanConfig = config.copy(excluded = emptySet())
        val resources = mutableListOf<PubspecAssets.Resolved>()
        val seen = mutableSetOf<Pair<PubspecAssets.Kind, Any>>()
        fun paths(items: List<YAMLSequenceItem>, kind: PubspecAssets.Kind, font: PubspecAssets.Font? = null) {
            items.forEach { item ->
                val mapping = item.value as? YAMLMapping
                val scalar = (if (mapping != null) mapping.getKeyValueByKey(if (kind == PubspecAssets.Kind.FONT) "asset" else "path")?.value else item.value) as? YAMLScalar
                if (scalar == null || scalar.textValue.isBlank() || runCatching { YamlSupport.load(scalar.text) }.getOrNull() !is String || kind == PubspecAssets.Kind.SHADER && mapping != null || kind == PubspecAssets.Kind.FONT && mapping == null) {
                    issue(item, "error.pubspecPaths", "flutter.${AssetsDartTemplate.resourceName(kind)}")
                    return@forEach
                }
                val path = scalar.textValue.replace('\\', '/')
                // Compare the full declaration: conditional assets and distinct font faces are not duplicates.
                val semantic = item.value?.let(::semantic)
                if (semantic != null && !seen.add(kind to if (kind == PubspecAssets.Kind.FONT) listOf(path, font) else if (semantic is Map<*, *> && semantic.keys == setOf("path")) path else semantic)) {
                    issue(item, "inspection.duplicate", path, remove = true)
                    return@forEach
                }
                val declaration = PubspecAssets.Declaration(path, packageName, kind, font)
                if (kind != PubspecAssets.Kind.ASSET && declaration.directory) {
                    issue(scalar, if (kind == PubspecAssets.Kind.FONT) "error.fontPath" else "error.shaderPath", path)
                    return@forEach
                }
                try {
                    val resolved = resolve(declaration)
                    if (!declaration.directory && Files.isDirectory(resolved.file)) {
                        issue(scalar, "error.directorySlash", path,
                            replacement = if (kind == PubspecAssets.Kind.ASSET) quote("$path/") else null)
                    } else {
                        resources.addAll(AssetsGenerator.collect(scanConfig, listOf(resolved), allOutputs))
                    }
                } catch (e: ProcessCanceledException) { throw e }
                catch (e: Exception) {
                    val message = if (e is LocalizedException) e.text else text("error.details", e)
                    issues.add(Issue(scalar, message, selectResource = message.key in setOf("error.assetMissing", "error.assetDirectoryMissing")))
                }
            }
        }
        for (kind in PubspecAssets.Kind.values()) {
            val section = flutter.getKeyValueByKey(AssetsDartTemplate.resourceName(kind)) ?: continue
            val sequence = section.value as? YAMLSequence
            if (sequence == null) { issue(section, "error.pubspecList", "flutter.${section.keyText}"); continue }
            if (kind != PubspecAssets.Kind.FONT) { paths(sequence.items, kind); continue }
            sequence.items.forEach { item ->
                val family = item.value as? YAMLMapping
                if (family == null) { issue(item, "error.fontMappings"); return@forEach }
                val familyName = (family.getKeyValueByKey("family")?.value as? YAMLScalar)?.textValue
                if (familyName.isNullOrBlank()) issue(family.getKeyValueByKey("family") ?: item, "error.fontFamily")
                val fontsEntry = family.getKeyValueByKey("fonts")
                val fonts = fontsEntry?.value as? YAMLSequence
                if (fonts == null) { issue(fontsEntry ?: item, "error.pubspecList", "flutter.fonts.fonts"); return@forEach }
                fonts.items.forEach { face ->
                    val faceMapping = face.value as? YAMLMapping ?: return@forEach
                    val weight = faceMapping.getKeyValueByKey("weight")
                    val scalarWeight = weight?.value as? YAMLScalar
                    val value = scalarWeight?.textValue?.toIntOrNull()
                    if (weight != null && (value == null || value !in 100..900 || value % 100 != 0 || scalarWeight.text.firstOrNull() in listOf('\'', '"'))) {
                        issue(weight.value ?: weight, "error.fontWeight", replacement = if (scalarWeight != null) "400" else null)
                    }
                    val style = faceMapping.getKeyValueByKey("style")
                    val scalarStyle = style?.value as? YAMLScalar
                    if (style != null && scalarStyle?.textValue !in listOf("normal", "italic")) {
                        issue(style.value ?: style, "error.fontStyle", replacement = if (scalarStyle != null) "normal" else null)
                    }
                }
                // Each face keeps its own weight/style while sharing the original family name.
                fonts.items.forEach { face ->
                    val single = face.value as? YAMLMapping
                    val font = PubspecAssets.Font(familyName ?: "", (single?.getKeyValueByKey("weight")?.value as? YAMLScalar)?.textValue?.toIntOrNull() ?: 400,
                        (single?.getKeyValueByKey("style")?.value as? YAMLScalar)?.textValue ?: "normal")
                    paths(listOf(face), PubspecAssets.Kind.FONT, font)
                }
            }
        }
        // Render only validated resources; report generator naming collisions before output is touched.
        try {
            AssetsGenerator.validateClassName(config.outClass)
            val outputs = AssetsGenerator.outputPaths(root, config)
            val excluded = AssetsGenerator.exclusionMatcher(config.excluded)
            val declarations = resources.map { it.declaration }.distinct().filterNot { excluded(it.path) }.groupBy { it.kind }
            outputs.forEach { (kind, output) ->
                try {
                    AssetsDartTemplate.part(root.resolve(config.outPath), output, config, kind,
                        declarations[kind].orEmpty())
                } catch (e: LocalizedException) {
                    issues.add(Issue(flutter.getKeyValueByKey(AssetsDartTemplate.resourceName(kind))?.key ?: entry, e.text))
                }
            }
        } catch (e: LocalizedException) { issues.add(Issue(entry.key ?: entry, e.text)) }
        return issues
    }

    private fun semantic(value: YAMLValue): Any? = when (value) {
        is YAMLMapping -> value.keyValues.associate { it.keyText to it.value?.let(::semantic) }
        is YAMLSequence -> value.items.map { it.value?.let(::semantic) }
        is YAMLScalar -> value.textValue
        else -> null
    }

    private fun quote(value: String) = "'${value.replace("'", "''")}'"
}
