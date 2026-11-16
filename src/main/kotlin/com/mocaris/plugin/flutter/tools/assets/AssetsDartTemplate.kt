package com.mocaris.plugin.flutter.tools.assets

import com.mocaris.plugin.flutter.tools.core.requireLocalized
import com.mocaris.plugin.flutter.tools.assets.PubspecAssets.Declaration
import com.mocaris.plugin.flutter.tools.assets.PubspecAssets.Kind
import java.nio.file.Path

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

    private fun meta(config: AssetsSyncConfig, resource: Declaration): String = buildString {
        append("${config.outClass}ResourceMeta(${quote(resource.packageName)}, ${quote(resource.path)}, ${config.outClass}ResourceKind.${resourceName(resource.kind)}")
        resource.sourcePackage?.let { append(", sourcePackage: ${quote(it)}") }
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

    fun part(entry: Path, output: Path, config: AssetsSyncConfig, kind: Kind, resources: List<Declaration>) = buildString {
        appendLine(AssetsGenerator.GENERATED_MARKER)
        appendLine("// dart format: off")
        appendLine("part of ${quote(output.parent.relativize(entry).toString().replace('\\', '/'))};")
        appendLine()
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
                appendFamily(config, type, family.value)
            }
        } else if (kind == Kind.ASSET) {
            val tree = AssetDirectory()
            resources.sortedBy { it.path }.forEach { resource ->
                val parts = resource.path.split('/').let { if (it.first() == "assets") it.drop(1) else it }
                var directory = tree
                parts.dropLast(1).forEach { part -> directory = directory.children.getOrPut(part) { AssetDirectory() } }
                directory.resources.add(resource)
            }
            appendAssetDirectory(config, group, tree, classes)
        } else {
            val sorted = resources.sortedBy { it.path }
            val names = resourceFieldNames(sorted, config.outExtension) { resource, extension ->
                AssetsGenerator.fieldName(resource.packageName, resource.path, extension)
            }
            appendLine("class $group {")
            appendLine("  const $group();")
            sorted.zip(names).forEach { (resource, name) ->
                appendLine("  final ${config.outClass}Resource $name = const ${config.outClass}Resource(${meta(config, resource)});")
            }
            appendLine("}")
        }
        appendLine("// dart format: on")
    }

    private class AssetDirectory {
        val children = sortedMapOf<String, AssetDirectory>()
        val resources = mutableListOf<Declaration>()
    }

    private fun StringBuilder.appendAssetDirectory(config: AssetsSyncConfig, group: String, directory: AssetDirectory, classes: MutableSet<String>) {
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
        directory.resources.zip(names).forEach { (resource, name) ->
            appendLine("  final ${config.outClass}Resource $name = const ${config.outClass}Resource(${meta(config, resource)});")
        }
        appendLine("}")
        children.zip(types).forEach { (child, type) ->
            appendLine()
            appendAssetDirectory(config, type, child.first.value, classes)
        }
    }

    private fun StringBuilder.appendFamily(config: AssetsSyncConfig, className: String, resources: List<Declaration>) {
        val sorted = resources.sortedWith(compareBy({ it.font!!.weight }, { it.font!!.style == "italic" }, { it.path }))
        val family = sorted.first().font!!.family
        val bases = sorted.map { "w${it.font!!.weight}" + if (it.font.style == "italic") "Italic" else "" }
        val counts = bases.groupingBy { it }.eachCount()
        val names = resourceFieldNames(sorted, config.outExtension) { resource, extension ->
            val base = "w${resource.font!!.weight}" + if (resource.font.style == "italic") "Italic" else ""
            if (counts.getValue(base) == 1) base else base +
                AssetsGenerator.fieldName(resource.packageName, resource.path, extension).replaceFirstChar { it.uppercaseChar() }
        }
        val faceType = "${config.outClass}FontFace"
        appendLine("class $className {")
        appendLine("  const $className();")
        appendLine("  final String family = ${quote(family)};")
        appendLine("  final String package = ${quote(sorted.first().packageName)};")
        appendLine("  final List<$faceType> faces = const [")
        sorted.forEach { resource ->
            val font = resource.font!!
            appendLine("    $faceType(${meta(config, resource)}, family: ${quote(family)}, fontWeight: _flutter.FontWeight.w${font.weight}, fontStyle: _flutter.FontStyle.${font.style}),")
        }
        appendLine("  ];")
        names.forEachIndexed { index, name -> appendLine("  $faceType get $name => faces[$index];") }
        appendLine("}")
    }

    fun entry(entry: Path, config: AssetsSyncConfig, parts: Collection<Path>, hasFonts: Boolean): String {
        val type = config.outClass
        return buildString {
            appendLine(AssetsGenerator.GENERATED_MARKER)
            appendLine("// dart format: off")
            if (hasFonts) appendLine("import 'package:flutter/painting.dart' as _flutter;")
            parts.forEach { appendLine("part ${quote(entry.parent.relativize(it).toString().replace('\\', '/'))};") }
            appendLine()
            appendLine("abstract class $type {")
            Kind.values().forEach { appendLine("  static const ${resourceName(it)} = ${groupClass(config, it)}();") }
            appendLine("}")
            appendLine()
            appendLine("""
                enum ${type}ResourceKind { assets, fonts, shaders }

                class ${type}ResourceMeta {
                  const ${type}ResourceMeta(this.package, this.path, this.kind, {this.sourcePackage});
                  final String package;
                  final String path;
                  final ${type}ResourceKind kind;
                  final String? sourcePackage;
                }

                class ${type}Resource {
                  const ${type}Resource(this.meta);
                  final ${type}ResourceMeta meta;
                  String get path => meta.path;
                }
            """.trimIndent())
            if (hasFonts) {
                appendLine()
                appendLine("""
                    class ${type}FontFace extends ${type}Resource {
                      const ${type}FontFace(super.meta, {
                        required this.family,
                        required this.fontWeight,
                        this.fontStyle = _flutter.FontStyle.normal,
                      });
                      final String family;
                      final _flutter.FontWeight fontWeight;
                      final _flutter.FontStyle fontStyle;
                    }
                """.trimIndent())
            }
            appendLine("// dart format: on")
        }
    }
}
