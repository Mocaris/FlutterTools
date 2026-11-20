package com.mocaris.plugin.flutter.tools.assets.generation

import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetsOutputTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()
    private val sdk = "environment: {sdk: ^3.0.0}\n"

    private fun file(path: String, content: String = "resource"): Path = root.resolve(path).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, content)
    }

    private fun generate(config: AssetsSyncConfig = AssetsSyncConfig(), yaml: String = "name: sample") =
        AssetsGenerator.generate(root.toString(), config, sdk + yaml)

    private fun write(contents: Map<Path, String>) {
        val prepared = AssetsOutputWriter.prepare(contents)
        try { AssetsOutputWriter.publish(contents, prepared) }
        finally { AssetsOutputWriter.cleanup(prepared) }
    }

    @Test fun defaultsGenerateAllResourceRecordsInOneEntry() {
        file("assets/logo.png"); file("fonts/demo.ttf"); file("shaders/effect.frag")
        val contents = generate(yaml = """
            name: sample
            flutter:
              assets: [assets/logo.png]
              fonts:
                - family: Demo
                  fonts: [{asset: fonts/demo.ttf}]
              shaders: [shaders/effect.frag]
        """.trimIndent())
        assertEquals(setOf(root.resolve("lib/r.dart")), contents.keys)
        val entry = contents.values.single()
        assertTrue(entry.contains("abstract class R"))
        listOf("assets", "fonts", "shaders").forEach { name ->
            assertTrue(entry.contains("static const $name = _R${name.replaceFirstChar { it.uppercaseChar() }}();"))
        }
        assertFalse(entry.contains("part ")); assertTrue(entry.contains("class RAssetsMeta")); assertTrue(entry.contains("class RFontFaceMeta"))
        assertTrue(entry.contains("logo = const RAssetsMeta(meta: (package: R.package, path: \"assets/logo.png\", kind: \"assets\", sourcePackage: null));"))
        assertTrue(entry.contains("static const String _family = \"Demo\";"))
        assertTrue(entry.contains("String get family => _family;"))
        assertTrue(entry.contains("family: _family, fontWeight: _flutter.FontWeight.w400, fontStyle: _flutter.FontStyle.normal"))
        assertTrue(entry.contains("kind: \"shaders\""))
        write(contents)
        assertEquals(entry, Files.readString(contents.keys.single()))
        assertFalse(Files.exists(root.resolve("lib/generate")))
    }

    @Test fun customEntryAndClassAreRespectedWithoutCreatingAnotherDirectory() {
        val contents = generate(AssetsSyncConfig(outPath = "lib/generated/resources.dart", outClass = "Resources"))
        assertEquals(setOf(root.resolve("lib/generated/resources.dart")), contents.keys)
        assertTrue(contents.values.single().contains("static const assets = _ResourcesAssets();"))
        write(contents)
        assertFalse(Files.exists(root.resolve("lib/generate")))
    }

    @Test fun emptyGroupsReplaceStaleConstants() {
        file("assets/logo.png")
        write(generate(yaml = "name: sample\nflutter:\n  assets: [assets/logo.png]\n"))
        write(generate())
        val entry = Files.readString(root.resolve("lib/r.dart"))
        assertFalse(entry.contains("logo")); assertTrue(entry.contains("const _RAssets();"))
    }

    @Test fun entryRejectsOutsidePathsAndExistingDirectories() {
        Files.createDirectories(root.resolve("lib/directory.dart"))
        for (path in listOf("../outside.dart", "/tmp/outside.dart", "", ".", "lib/directory.dart", "pubspec.yaml")) {
            assertThrows(IllegalArgumentException::class.java) { generate(AssetsSyncConfig(outPath = path)) }
        }
    }

    @Test fun conflictingEntryPreventsReplacement() {
        val contents = generate()
        file("lib/r.dart", "user content")
        assertThrows(IllegalArgumentException::class.java) { write(contents) }
        assertEquals("user content", Files.readString(root.resolve("lib/r.dart")))
    }

    @Test fun conflictCreatedAfterPreparationPreventsReplacement() {
        val contents = generate()
        val prepared = AssetsOutputWriter.prepare(contents)
        try {
            file("lib/r.dart", "user content")
            assertThrows(IllegalArgumentException::class.java) { AssetsOutputWriter.publish(contents, prepared) }
            assertEquals("user content", Files.readString(root.resolve("lib/r.dart")))
        } finally { AssetsOutputWriter.cleanup(prepared) }
    }

    @Test fun failedBatchReplacementRollsBackEarlierOutputs() {
        val contents = generate() + (root.resolve("lib/other.dart") to "${AssetsGenerator.GENERATED_MARKER}\n")
        write(contents)
        val updated = contents.mapValues { (_, content) -> content + "// changed\n" }
        val prepared = AssetsOutputWriter.prepare(updated)
        try {
            Files.delete(prepared.getValue(root.resolve("lib/other.dart")))
            assertThrows(Exception::class.java) { AssetsOutputWriter.publish(updated, prepared) }
            contents.forEach { (path, content) -> assertEquals(content, Files.readString(path)) }
        } finally { AssetsOutputWriter.cleanup(prepared) }
    }

    @Test fun generatedEntryIsExcludedFromDirectoryDeclarations() {
        file("lib/logo.png")
        write(generate())
        val entry = generate(yaml = "name: sample\nflutter:\n  assets: [lib/]\n").values.single()
        assertTrue(entry.contains("lib/logo.png"))
        assertFalse(entry.contains("\"lib/r.dart\""))
    }

    @Test fun explicitEntryResourceIsRejected() {
        write(generate())
        assertThrows(IllegalArgumentException::class.java) {
            generate(yaml = "name: sample\nflutter:\n  assets: [lib/r.dart]\n")
        }
    }

    @Test fun outputAndStagingEventsDoNotTriggerResourceGeneration() {
        val outputs = generate().keys
        assertTrue(AssetsOutputWriter.isOutputEvent(root.resolve("lib/r.dart").toString(), outputs))
        assertTrue(AssetsOutputWriter.isOutputEvent(root.resolve("lib/.fluttertools-123.tmp").toString(), outputs))
        assertFalse(AssetsOutputWriter.isOutputEvent(root.resolve("lib/logo.png").toString(), outputs))
        assertFalse(AssetsOutputWriter.isOutputEvent(root.resolve("other/.fluttertools-123.tmp").toString(), outputs))
    }

    @Test fun generationDoesNotReadOrDeleteUnrelatedFilesOrFormerOutputDirectories() {
        val packageRoot = folder.newFolder("app").toPath()
        val outside = folder.newFolder("outside").toPath()
        val unrelated = outside.resolve("notes.dart")
        Files.writeString(unrelated, "user content")
        Files.createDirectories(packageRoot.resolve("lib"))
        Files.createSymbolicLink(packageRoot.resolve("lib/gen"), outside)
        Files.createSymbolicLink(packageRoot.resolve("lib/generate"), outside)
        val contents = AssetsGenerator.generate(packageRoot.toString(), AssetsSyncConfig(), sdk + "name: sample")
        assertEquals(setOf(packageRoot.resolve("lib/r.dart")), contents.keys)
        write(contents)
        assertEquals("user content", Files.readString(unrelated))
        assertTrue(Files.isSymbolicLink(packageRoot.resolve("lib/gen")))
        assertTrue(Files.isSymbolicLink(packageRoot.resolve("lib/generate")))
    }
    @Test fun metadataClassesAreDeclaredOnceAndReferencedByFieldsAndFonts() {
        file("assets/one.png"); file("assets/two.png"); file("fonts/demo.ttf")
        val text = generate(yaml = "name: sample\nflutter:\n  assets: [assets/]\n  fonts: [{family: Demo, fonts: [{asset: fonts/demo.ttf}]}]\n").values.single()
        for (name in listOf("RAssetsMeta", "RFontFaceMeta")) {
            assertEquals(1, Regex("class $name(?: |\\{)").findAll(text).count())
        }
        assertTrue(text.contains("final RAssetsMeta one = const RAssetsMeta("))
        assertTrue(text.contains("final RAssetsMeta two = const RAssetsMeta("))
        assertTrue(text.contains("final List<RFontFaceMeta> faces = const ["))
        assertTrue(text.contains("RFontFaceMeta get w400 => faces[0];"))
        assertTrue(text.contains("meta:"))
        assertTrue(text.contains("style:"))
        assertTrue(text.contains("String get package => meta.package;"))
        assertEquals(2, Regex("path: \"assets/").findAll(text).count())
        val empty = generate().values.single()
        assertFalse(empty.contains("RFontFaceMeta")); assertFalse(empty.contains("package:flutter"))
    }

    @Test fun metadataMethodsUseTheEntryPrefixAndOnlyImportFlutterForFonts() {
        val plain = generate(AssetsSyncConfig(outClass = "LoginR")).values.single()
        assertTrue(plain.contains("class LoginRAssetsMeta {"))
        assertTrue(plain.contains("const LoginRAssetsMeta({required this.meta});"))
        assertTrue(plain.contains("String get fullPath => path.startsWith('packages/')"))
        assertFalse(plain.contains("FontFaceMeta")); assertFalse(plain.contains("package:flutter"))
        assertTrue(plain.contains("typedef AssetsMeta =")); assertFalse(plain.contains("FontFaceStyle"))
        assertTrue(plain.contains("final AssetsMeta meta;"))
        assertTrue(plain.contains("class _LoginRAssets {"))
        assertFalse(plain.contains("class _LoginRAssetsMeta")); assertFalse(plain.contains("typedef _"))
        assertFalse(plain.contains("extension "))
        file("fonts/demo.ttf")
        val fonts = generate(AssetsSyncConfig(outClass = "LoginR"), "name: sample\nflutter:\n  fonts: [{family: Demo, fonts: [{asset: fonts/demo.ttf}]}]\n").values.single()
        assertTrue(fonts.contains("class LoginRFontFaceMeta extends LoginRAssetsMeta {"))
        assertTrue(fonts.contains("typedef FontFaceStyle =")); assertTrue(fonts.contains("final FontFaceStyle style;"))
        assertTrue(fonts.contains("class _LoginRFontsDemo {")); assertFalse(fonts.contains("class _LoginRFontFaceMeta"))
        assertTrue(fonts.contains("const LoginRFontFaceMeta({"))
        assertTrue(fonts.contains("required super.meta, required this.style"))
        assertEquals(1, Regex("String get fullPath =>").findAll(fonts).count())
        assertEquals(1, Regex("_flutter.TextStyle get textStyle =>").findAll(fonts).count())
        assertTrue(fonts.contains("String get family => style.family;"))
        assertTrue(fonts.contains("_flutter.FontWeight get fontWeight => style.fontWeight;"))
        assertTrue(fonts.contains("_flutter.FontStyle get fontStyle => style.fontStyle;"))
        assertTrue(fonts.contains("fontFamily: style.family,"))
        assertTrue(fonts.contains("fontWeight: style.fontWeight,"))
        assertTrue(fonts.contains("fontStyle: style.fontStyle"))
    }
}
