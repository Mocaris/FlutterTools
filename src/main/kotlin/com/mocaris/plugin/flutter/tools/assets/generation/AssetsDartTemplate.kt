package com.mocaris.plugin.flutter.tools.assets.generation

import com.mocaris.plugin.flutter.tools.assets.PubspecAssets.Declaration
import com.mocaris.plugin.flutter.tools.assets.PubspecAssets.Kind
import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized

/** Dart rendering only; resource scanning and output protection stay in the generator. */
internal object AssetsDartTemplate {
    fun resourceName(kind: Kind) = when (kind) {
        Kind.ASSET -> "assets"
        Kind.FONT -> "fonts"
        Kind.SHADER -> "shaders"
    }

    private fun groupClass(config: AssetsSyncConfig, kind: Kind) =
        "_${config.outClass}${resourceName(kind).replaceFirstChar { it.uppercaseChar() }}"

    private fun childClass(parent: String, field: String, used: MutableSet<String>): String {
        val base = parent + field.replaceFirstChar { it.uppercaseChar() }
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) candidate = base + suffix++
        return candidate
    }

    private fun quote(value: String) = "\"${AssetsGenerator.escapeDart(value)}\""

    private fun metaClass(config: AssetsSyncConfig, kind: Kind) =
        config.outClass + if (kind == Kind.FONT) "FontFaceMeta" else "AssetsMeta"

    private fun StringBuilder.appendPackages(config: AssetsSyncConfig, resources: List<Declaration>, packageName: String): Map<String, String> {
        val packages = (resources.map { it.packageName } + resources.mapNotNull { it.sourcePackage })
            .distinct().sorted().mapIndexed { index, name -> name to if (name == packageName) "${config.outClass}.package" else "_package$index" }.toMap()
        packages.forEach { (name, field) ->
            if (name != packageName) appendLine("  static const String $field = ${quote(name)};")
        }
        return packages
    }

    private fun resource(config: AssetsSyncConfig, resource: Declaration, packages: Map<String, String>): String = buildString {
        append("${metaClass(config, resource.kind)}(meta: (package: ${packages.getValue(resource.packageName)}, path: ${quote(resource.path)}, kind: ${quote(resourceName(resource.kind))}, sourcePackage: ${resource.sourcePackage?.let(packages::getValue) ?: "null"})")
        resource.font?.let { font ->
            append(", style: (family: _family, fontWeight: _flutter.FontWeight.w${font.weight}, fontStyle: _flutter.FontStyle.${font.style})")
        }
        append(')')
    }

    private fun fieldNames(names: List<String>) {
        val duplicate = names.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        requireLocalized(duplicate == null, "error.duplicateField", duplicate)
    }

    /** Add extensions only to colliding fields, including collisions introduced by a suffix. */
    private fun resourceFieldNames(
        resources: List<Declaration>, includeExtensions: Boolean,
        occupied: Set<String> = emptySet(),
        name: (Declaration, Boolean) -> String,
    ): List<String> {
        val extensions = BooleanArray(resources.size) { includeExtensions }
        while (true) {
            val names = resources.mapIndexed { index, resource -> name(resource, extensions[index]) }
            val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys + names.filter { it in occupied }
            if (duplicates.isEmpty()) return names
            var expanded = false
            names.forEachIndexed { index, field ->
                if (field in duplicates && !extensions[index]) {
                    extensions[index] = true
                    expanded = true
                }
            }
            requireLocalized(expanded, "error.duplicateField", duplicates.first())
        }
    }

    fun group(config: AssetsSyncConfig, kind: Kind, resources: List<Declaration>, packageName: String) = buildString {
        val group = groupClass(config, kind)
        val classes = mutableSetOf(group)
        if (kind == Kind.FONT) {
            val families = resources.groupBy { it.font!!.family }.toSortedMap()
            val names = families.keys.map { AssetsGenerator.dartName(it.replaceFirstChar { char -> char.lowercaseChar() }, "font") }
            fieldNames(names)
            val types = names.map { childClass(group, it, classes) }
            appendLine("class $group {")
            appendLine("  const $group();")
            names.zip(types).forEach { (name, type) -> appendLine("  final $type $name = const $type();") }
            appendLine("}")
            families.entries.zip(types).forEach { (family, type) ->
                appendLine()
                appendFamily(config, type, family.value, packageName)
            }
        } else if (kind == Kind.ASSET) {
            val tree = AssetDirectory()
            resources.sortedBy { it.path }.forEach { resource ->
                val parts = resource.path.split('/').let { if (it.first() == "assets") it.drop(1) else it }
                var directory = tree
                parts.dropLast(1).forEach { part -> directory = directory.children.getOrPut(part) { AssetDirectory() } }
                directory.resources.add(resource)
            }
            appendAssetDirectory(config, group, tree, classes, packageName)
        } else {
            val sorted = resources.sortedBy { it.path }
            val names = resourceFieldNames(sorted, config.outExtension) { resource, extension ->
                AssetsGenerator.fieldName(resource.packageName, resource.path, extension)
            }
            appendLine("class $group {")
            appendLine("  const $group();")
            val packages = appendPackages(config, sorted, packageName)
            sorted.zip(names).forEach { (resource, name) ->
                appendLine("  final ${metaClass(config, kind)} $name = const ${resource(config, resource, packages)};")
            }
            appendLine("}")
        }
    }

    private class AssetDirectory {
        val children = sortedMapOf<String, AssetDirectory>()
        val resources = mutableListOf<Declaration>()
    }

    private fun StringBuilder.appendAssetDirectory(config: AssetsSyncConfig, group: String, directory: AssetDirectory, classes: MutableSet<String>, packageName: String) {
        val children = directory.children.entries.map { it to AssetsGenerator.dartName(it.key, "directory") }
        fieldNames(children.map { it.second })
        val types = children.map { childClass(group, it.second, classes) }
        val names = resourceFieldNames(directory.resources, config.outExtension, children.map { it.second }.toSet()) { resource, extension ->
            val filename = resource.path.substringAfterLast('/')
            val basename = if (extension) filename else filename.substringBeforeLast('.', filename).ifEmpty { filename }
            AssetsGenerator.dartName(basename)
        }
        appendLine("class $group {")
        appendLine("  const $group();")
        children.zip(types).forEach { (child, type) -> appendLine("  final $type ${child.second} = const $type();") }
        val packages = appendPackages(config, directory.resources, packageName)
        directory.resources.zip(names).forEach { (resource, name) ->
            appendLine("  final ${metaClass(config, Kind.ASSET)} $name = const ${resource(config, resource, packages)};")
        }
        appendLine("}")
        children.zip(types).forEach { (child, type) ->
            appendLine()
            appendAssetDirectory(config, type, child.first.value, classes, packageName)
        }
    }

    private fun StringBuilder.appendFamily(config: AssetsSyncConfig, className: String, resources: List<Declaration>, packageName: String) {
        val sorted = resources.sortedWith(compareBy({ it.font!!.weight }, { it.font!!.style == "italic" }, { it.path }))
        val family = sorted.first().font!!.family
        val bases = sorted.map { "w${it.font!!.weight}" + if (it.font.style == "italic") "Italic" else "" }
        val counts = bases.groupingBy { it }.eachCount()
        val names = resourceFieldNames(sorted, config.outExtension) { resource, extension ->
            val base = "w${resource.font!!.weight}" + if (resource.font.style == "italic") "Italic" else ""
            if (counts.getValue(base) == 1) base else base +
                AssetsGenerator.fieldName(resource.packageName, resource.path, extension).replaceFirstChar { it.uppercaseChar() }
        }
        appendLine("class $className {")
        appendLine("  const $className();")
        val packages = appendPackages(config, sorted, packageName)
        appendLine("  static const String _family = ${quote(family)};")
        appendLine("  String get family => _family;")
        appendLine("  final List<${metaClass(config, Kind.FONT)}> faces = const [")
        sorted.forEach { resource ->
            appendLine("    ${resource(config, resource, packages)},")
        }
        appendLine("  ];")
        names.forEachIndexed { index, name -> appendLine("  ${metaClass(config, Kind.FONT)} get $name => faces[$index];") }
        appendLine("}")
    }

    fun entry(config: AssetsSyncConfig, resources: List<Declaration>, packageName: String): String = buildString {
        appendLine(AssetsGenerator.GENERATED_MARKER)
        appendLine("// dart format: off")
        val hasFonts = resources.any { it.kind == Kind.FONT }
        if (hasFonts) appendLine("import 'package:flutter/painting.dart' as _flutter;")
        appendLine()
        val assetsMeta = metaClass(config, Kind.ASSET)
        val fontFaceMeta = metaClass(config, Kind.FONT)
        appendLine("typedef AssetsMeta = ({String package, String path, String kind, String? sourcePackage,});")
        if (hasFonts) appendLine("typedef FontFaceStyle = ({String family, _flutter.FontWeight fontWeight, _flutter.FontStyle fontStyle,});")
        appendLine()
        appendLine("""
            class $assetsMeta {
              final AssetsMeta meta;

              const $assetsMeta({required this.meta});

              String get package => meta.package;
              String get path => meta.path;
              String get kind => meta.kind;
              String? get sourcePackage => meta.sourcePackage;
              String get fullPath => path.startsWith('packages/') ? path : 'packages/${'$'}{sourcePackage ?? package}/${'$'}path';
            }
        """.trimIndent())
        if (hasFonts) {
            appendLine()
            appendLine("""
                class $fontFaceMeta extends $assetsMeta {
                  final FontFaceStyle style;

                  const $fontFaceMeta({required super.meta, required this.style});

                  String get family => style.family;
                  _flutter.FontWeight get fontWeight => style.fontWeight;
                  _flutter.FontStyle get fontStyle => style.fontStyle;

                  _flutter.TextStyle get textStyle => _flutter.TextStyle(fontFamily: style.family, fontWeight: style.fontWeight, fontStyle: style.fontStyle);
                }
            """.trimIndent())
        }
        appendLine()
        appendLine("abstract class ${config.outClass} {")
        appendLine("  static const String package = ${quote(packageName)};")
        Kind.values().forEach { appendLine("  static const ${resourceName(it)} = ${groupClass(config, it)}();") }
        appendLine("}")
        Kind.values().forEach { kind ->
            appendLine()
            append(group(config, kind, resources.filter { it.kind == kind }, packageName))
        }
        appendLine("// dart format: on")
    }
}
