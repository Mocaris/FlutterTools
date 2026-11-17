package com.mocaris.plugin.flutter.tools.assets

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ResourceMetadataTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()
    private fun file(path: String) = root.resolve(path).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "resource")
    }
    private fun parse(yaml: String) = PubspecAssets.parse("name: sample\n$yaml")
    private fun generate(yaml: String, config: AssetsSyncConfig = AssetsSyncConfig()) =
        AssetsGenerator.generate(root.toString(), config, "name: sample\n$yaml")
    private fun fonts(faces: String, family: String = "Demo") =
        "flutter:\n  fonts:\n    - family: $family\n      fonts: $faces\n"

    @Test fun metadataUsesPubspecPackageInsteadOfDirectoryName() {
        file("assets/logo.png"); file("shaders/effect.frag")
        val contents = generate("flutter:\n  assets: [assets/]\n  shaders: [shaders/effect.frag]\n")
        assertTrue(contents.getValue(root.resolve("lib/generate/assets.g.dart")).contains("RResourceMeta(\"sample\", \"assets/logo.png\", RResourceKind.assets)"))
        assertTrue(contents.getValue(root.resolve("lib/generate/shaders.g.dart")).contains("RResourceMeta(\"sample\", \"shaders/effect.frag\", RResourceKind.shaders)"))
        assertFalse(contents.getValue(root.resolve("lib/r.dart")).contains("package:flutter"))
    }

    @Test fun externalFileSourceDoesNotReplaceDeclaringPackage() {
        val external = folder.newFolder("external").toPath()
        Files.createDirectories(external.resolve("lib/images"))
        Files.writeString(external.resolve("lib/images/logo.png"), "resource")
        Files.writeString(file(".dart_tool/package_config.json"), """{"packages":[{"name":"icons","rootUri":"${external.toUri()}","packageUri":"lib/"}]}""")
        val resource = parse("flutter:\n  assets: [packages/icons/images/]\n").single()
        assertEquals("sample", resource.packageName)
        assertEquals("icons", resource.sourcePackage)
        val output = generate("flutter:\n  assets: [packages/icons/images/]\n").getValue(root.resolve("lib/generate/assets.g.dart"))
        assertTrue(output.contains("RResourceMeta(\"sample\", \"packages/icons/images/logo.png\", RResourceKind.assets, sourcePackage: \"icons\")"))
    }

    @Test fun changedPubspecNameChangesMetadataWithUnsavedSnapshot() {
        file("assets/logo.png")
        val yaml = "flutter:\n  assets: [assets/logo.png]\n"
        val before = generate(yaml)
        val after = AssetsGenerator.generate(root.toString(), AssetsSyncConfig(), "name: renamed_package\n$yaml")
        assertNotEquals(before, after)
        assertTrue(after.getValue(root.resolve("lib/generate/assets.g.dart")).contains("RResourceMeta(\"renamed_package\","))
    }

    @Test fun invalidPackageNamesDoNotGenerateUnknownMetadata() {
        listOf("flutter: {}", "name: 123", "name: Invalid-Name", "name: ''").forEach { yaml ->
            val error = assertThrows(IllegalArgumentException::class.java) { PubspecAssets.parse(yaml) }
            assertTrue((error as com.mocaris.plugin.flutter.tools.core.LocalizedException).text.key == "error.packageName")
        }
    }

    @Test fun privatePackageNamesArePreserved() {
        val resource = PubspecAssets.parse("name: _internal_package\nflutter:\n  assets: [assets/logo.png]\n").single()
        assertEquals("_internal_package", resource.packageName)
    }

    @Test fun fontsPreserveFamilyWeightStyleAndDefaults() {
        val declarations = parse(fonts("[{asset: fonts/regular.ttf}, {asset: fonts/bold.ttf, weight: 700}, {asset: fonts/italic.ttf, style: italic}]", "Roboto"))
        assertEquals(listOf(400, 700, 400), declarations.map { it.font!!.weight })
        assertEquals(listOf("normal", "normal", "italic"), declarations.map { it.font!!.style })
        assertTrue(declarations.all { it.font!!.family == "Roboto" && it.packageName == "sample" })
    }

    @Test fun duplicatePathsPreserveDifferentFamiliesWeightsAndStyles() {
        file("fonts/shared.ttf")
        val yaml = """
            flutter:
              fonts:
                - family: First
                  fonts:
                    - asset: fonts/shared.ttf
                    - asset: fonts/shared.ttf
                      weight: 700
                    - asset: fonts/shared.ttf
                      style: italic
                - family: Second
                  fonts: [{asset: fonts/shared.ttf}]
        """.trimIndent()
        assertEquals(4, parse(yaml).size)
        val generated = generate(yaml)
        val entry = generated.getValue(root.resolve("lib/r.dart"))
        assertTrue(entry.contains("const RFontFace(super.meta, {"))
        assertFalse(entry.contains(": super(meta)"))
        val output = generated.getValue(root.resolve("lib/generate/fonts.g.dart"))
        assertEquals(4, Regex("RFontFace\\(RResourceMeta").findAll(output).count())
        assertTrue(output.contains("get w400 =>")); assertTrue(output.contains("get w700 =>"))
        assertTrue(output.contains("get w400Italic =>"))
        assertTrue(output.contains("class _RFontsFirst")); assertTrue(output.contains("class _RFontsSecond"))
    }

    @Test fun repeatedFamilyMergesAndDuplicateWeightsKeepAllFiles() {
        file("fonts/latin.ttf"); file("fonts/cjk.ttf")
        val yaml = """
            flutter:
              fonts:
                - family: Demo
                  fonts: [{asset: fonts/latin.ttf}]
                - family: Demo
                  fonts: [{asset: fonts/cjk.ttf}]
        """.trimIndent()
        val output = generate(yaml).getValue(root.resolve("lib/generate/fonts.g.dart"))
        assertEquals(1, Regex("class _RFontsDemo ").findAll(output).count())
        assertEquals(2, Regex("RFontFace\\(RResourceMeta").findAll(output).count())
        assertTrue(output.contains("get w400FontsCjk => faces[0]"))
        assertTrue(output.contains("get w400FontsLatin => faces[1]"))
    }

    @Test fun fontOutputIsStableWhenDeclarationsAreReordered() {
        file("fonts/a.ttf"); file("fonts/b.ttf")
        val a = "{asset: fonts/a.ttf, weight: 700}"
        val b = "{asset: fonts/b.ttf, style: italic}"
        assertEquals(generate(fonts("[$a, $b]")), generate(fonts("[$b, $a]")))
    }

    @Test fun exclusionsRemoveFacesAndEmptyFamilies() {
        file("fonts/a.ttf"); file("fonts/b.ttf")
        val yaml = fonts("[{asset: fonts/a.ttf}, {asset: fonts/b.ttf, weight: 700}]")
        val partial = generate(yaml, AssetsSyncConfig(excluded = setOf("fonts/b.ttf")))
        val fontOutput = partial.getValue(root.resolve("lib/generate/fonts.g.dart"))
        assertTrue(fontOutput.contains("get w400 =>")); assertFalse(fontOutput.contains("fonts/b.ttf"))
        val empty = generate(yaml, AssetsSyncConfig(excluded = setOf("fonts/")))
        assertFalse(empty.getValue(root.resolve("lib/generate/fonts.g.dart")).contains("class _RFontsDemo"))
        assertFalse(empty.getValue(root.resolve("lib/r.dart")).contains("package:flutter"))
    }

    @Test fun invalidFontDefinitionsAreRejectedBeforeGeneration() {
        val invalid = listOf(
            "flutter:\n  fonts: [{fonts: [{asset: fonts/a.ttf}]}]",
            fonts("[{asset: fonts/a.ttf}]", "''"),
            fonts("[{asset: fonts/a.ttf, weight: 450}]"),
            fonts("[{asset: fonts/a.ttf, weight: '400'}]"),
            fonts("[{asset: fonts/a.ttf, weight: 1000}]"),
            fonts("[{asset: fonts/a.ttf, style: bold}]")
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { parse(it) } }
    }

    @Test fun normalizedFamilyNameCollisionsAreRejected() {
        file("fonts/a.ttf")
        val yaml = """
            flutter:
              fonts:
                - family: Demo-Font
                  fonts: [{asset: fonts/a.ttf}]
                - family: Demo Font
                  fonts: [{asset: fonts/a.ttf}]
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { generate(yaml) }
    }

    @Test fun duplicateFontFieldNamesCanIncludeExtensions() {
        file("fonts/a.ttf"); file("fonts/a.otf")
        val yaml = fonts("[{asset: fonts/a.ttf}, {asset: fonts/a.otf}]")
        val output = generate(yaml).getValue(root.resolve("lib/generate/fonts.g.dart"))
        assertTrue(output.contains("get w400FontsAOtf =>")); assertTrue(output.contains("get w400FontsATtf =>"))
    }
    @Test fun shaderExtensionConflictsAreResolvedAutomatically() {
        file("shaders/effect.frag"); file("shaders/effect.glsl")
        val output = generate("flutter:\n  shaders: [shaders/effect.frag, shaders/effect.glsl]\n")
            .getValue(root.resolve("lib/generate/shaders.g.dart"))
        assertTrue(output.contains("RResource shadersEffectFrag ="))
        assertTrue(output.contains("RResource shadersEffectGlsl ="))
    }

}
