package com.mocaris.plugin.flutter.tools.assets.generation

import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetsGeneratorTest {
    @get:Rule val folder = TemporaryFolder()
    private fun asset(path: String) = File(folder.root, path).apply { parentFile.mkdirs(); writeText("asset") }
    private fun config(extension: Boolean = false, excluded: Set<String> = emptySet()) = AssetsSyncConfig("lib/r.dart", "R", outExtension = extension, excluded = excluded)

    private fun generateText(projectPath: String, config: AssetsSyncConfig, pubspec: String? = null) =
        AssetsGenerator.generate(projectPath, config, pubspec).values.joinToString("\n")

    @Before fun defaultPubspec() { pubspec("assets/") }
    private fun pubspec(vararg paths: String) {
        File(folder.root, "pubspec.yaml").writeText("environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  assets:\n" + paths.joinToString("\n", postfix = "\n") { "    - '${it.replace("'", "''")}'" })
    }

    @Test fun deterministicOutputAndDensityVariants() {
        asset("assets/z.png"); asset("assets/a.png"); asset("assets/2.0x/a.png"); asset("assets/3x/a.png")
        val text = generateText(folder.root.path, config())
        assertTrue(text.indexOf("RAssetsMeta a =") < text.indexOf("RAssetsMeta z ="))
        assertEquals(1, Regex("RAssetsMeta a").findAll(text).count())
        assertFalse(text.contains("2.0x"))
        assertTrue(text.contains("abstract class R"))
    }
    @Test fun conflictingExtensionsAreAddedAutomaticallyWithoutTouchingOutput() {
        asset("assets/logo.png"); asset("assets/logo.svg"); asset("assets/banner.png")
        val output = asset("lib/r.dart").apply { writeText("existing content") }
        val text = generateText(folder.root.path, config())
        assertTrue(text.contains("RAssetsMeta logoPng =")); assertTrue(text.contains("RAssetsMeta logoSvg ="))
        assertTrue(text.contains("RAssetsMeta banner =")); assertFalse(text.contains("RAssetsMeta bannerPng ="))
        assertEquals("existing content", output.readText())
        val explicit = generateText(folder.root.path, config(true))
        assertTrue(explicit.contains("RAssetsMeta bannerPng ="))
    }

    @Test fun extensionSuffixesCannotClashWithExistingFields() {
        asset("assets/logo.png"); asset("assets/logo.svg"); asset("assets/logo_png.jpg")
        val text = generateText(folder.root.path, config())
        assertTrue(text.contains("RAssetsMeta logoPng ="))
        assertTrue(text.contains("RAssetsMeta logoSvg ="))
        assertTrue(text.contains("RAssetsMeta logoPngJpg ="))
        pubspec("assets/logo_png.jpg", "assets/logo.svg", "assets/logo.png")
        assertEquals(text, generateText(folder.root.path, config()))
    }

    @Test fun normalizationCollisionsStillFailWithoutReplacingOutput() {
        asset("assets/a-b.png"); asset("assets/a_b.png")
        val output = asset("lib/r.dart").apply { writeText("existing content") }
        val error = assertThrows(IllegalArgumentException::class.java) { generateText(folder.root.path, config()) }
        assertEquals("error.duplicateField", (error as com.mocaris.plugin.flutter.tools.core.localization.LocalizedException).text.key)
        assertEquals("existing content", output.readText())
    }

    @Test fun renamedAndRemovedAssetsDisappearOnNextGeneration() {
        val file = asset("assets/old.png")
        assertTrue(generateText(folder.root.path, config()).contains("RAssetsMeta old ="))
        file.renameTo(File(file.parentFile, "new.png"))
        val renamed = generateText(folder.root.path, config())
        assertTrue(renamed.contains("RAssetsMeta assetNew =")); assertFalse(renamed.contains("RAssetsMeta old ="))
        File(file.parentFile, "new.png").delete()
        assertFalse(generateText(folder.root.path, config()).contains("final RAssetsMeta "))
    }
    @Test fun globsAndDirectoryBoundaries() {
        assertTrue(AssetsGenerator.matchesExcluded("assets/icon/logo.png", "assets/icon/"))
        assertFalse(AssetsGenerator.matchesExcluded("assets/icon_other/logo.png", "assets/icon/"))
        assertTrue(AssetsGenerator.matchesExcluded("assets/a-dark-icon.png", "a*icon.png"))
        assertTrue(AssetsGenerator.matchesExcluded("assets/.DS_Store", ".DS_Store"))
        assertFalse(AssetsGenerator.matchesExcluded("assets/foo.DS_Store", ".DS_Store"))
    }
    @Test fun exclusionRulesKeepLiteralGlobAndDirectorySemanticsDuringCollection() {
        val paths = listOf("assets/keep.png", "assets/a.tmp", "assets/file[1].png", "assets/.DS_Store",
            "assets/cache/hidden.png", "assets/cache_other/visible.png", "assets/icon1.svg", "assets/icon12.svg")
        paths.forEach { asset(it) }
        pubspec(*paths.toTypedArray())
        val rules = setOf("*.tmp", "file[1].*", ".DS_Store", "assets\\cache/", "icon?.svg")
        val resources = PubspecAssets.resolve(folder.root.toPath(), PubspecAssets.parse(File(folder.root, "pubspec.yaml").readText()))
        assertEquals(listOf("assets/keep.png", "assets/cache_other/visible.png", "assets/icon12.svg"),
            AssetsGenerator.collect(folder.root.toPath(), config(excluded = rules), resources).map { it.declaration.path })
    }
    @Test fun excludedFilesAndGeneratedOutputAreOmitted() {
        asset("assets/a.png"); asset("assets/.DS_Store"); asset("assets/r.dart")
        val text = generateText(folder.root.path, config(excluded = setOf(".DS_Store")).copy(outPath = "assets/r.dart"))
        assertTrue(text.contains("RAssetsMeta a =")); assertFalse(text.contains("DS_Store")); assertFalse(text.contains("RAssetsMeta r ="))
    }
    @Test fun invalidDensityDirectoryIsNotMerged() {
        asset("assets/2a0x/a.png")
        pubspec("assets/2a0x/a.png")
        assertTrue(generateText(folder.root.path, config()).contains("assets/2a0x/a.png"))
    }
    @Test fun dollarQuotesAndBackslashesAreEscaped() {
        asset("assets/a\$b\"c.png")
        assertTrue(generateText(folder.root.path, config()).contains("a\\\$b\\\"c.png"))
    }
    @Test fun unsafePathsAndInvalidClassNamesAreRejected() {
        asset("assets/a.png")
        assertThrows(IllegalArgumentException::class.java) { generateText(folder.root.path, config().copy(outPath = "../r.dart")) }
        assertThrows(IllegalArgumentException::class.java) { generateText(folder.root.path, config().copy(outClass = "class")) }
        pubspec("missing/")
        assertThrows(IllegalArgumentException::class.java) { generateText(folder.root.path, config()) }
    }
    @Test fun coreTypesAndGeneratedNamesCannotBeUsedAsTheOutputClass() {
        asset("assets/logo.png")
        val output = asset("lib/r.dart").apply { writeText("existing content") }
        for (name in listOf("String", "List", "Object", "_flutter", "package", "AssetsMeta", "FontFaceStyle")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                generateText(folder.root.path, config().copy(outClass = name))
            }
            assertEquals("error.className", (error as com.mocaris.plugin.flutter.tools.core.localization.LocalizedException).text.key)
            assertEquals("existing content", output.readText())
        }
        assertTrue(generateText(folder.root.path, config().copy(outClass = "StringsR")).contains("abstract class StringsR"))
    }

    @Test fun configurationTypesAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { AssetsGenerator.parseText("flutter_tools:\n  assets_sync:\n    excluded: [123]\n") }
        assertThrows(IllegalArgumentException::class.java) { AssetsGenerator.parseText("flutter_tools:\n  assets_sync:\n    sync_path: [assets]\n    watch: 123\n") }
    }

    @Test fun subdirectoriesRequireTheirOwnPubspecDeclaration() {
        asset("assets/root.png"); asset("assets/icons/nested.png")
        val before = generateText(folder.root.path, config())
        assertTrue(before.contains("assets/root.png")); assertFalse(before.contains("nested.png"))
        pubspec("assets/", "assets/icons/")
        assertTrue(generateText(folder.root.path, config()).contains("assets/icons/nested.png"))
    }
    @Test fun declaredMissingBaseImageUsesItsVariants() {
        asset("assets/2.0x/logo.png"); asset("assets/3.0x/logo.png")
        pubspec("assets/logo.png")
        val text = generateText(folder.root.path, config())
        assertTrue(text.contains("assets/logo.png")); assertFalse(text.contains("2.0x"))
        assertFalse(generateText(folder.root.path, config(), "environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  assets: [assets/]\n").contains("logo.png"))
    }
    @Test fun explicitlyDeclaredVariantAndOrdinaryFileKeepTheirExactKeys() {
        asset("assets/2.0x/logo.png"); asset("assets/2.0x/data.json")
        pubspec("assets/2.0x/logo.png", "assets/2.0x/data.json")
        val text = generateText(folder.root.path, config())
        assertTrue(text.contains("assets/2.0x/logo.png")); assertTrue(text.contains("assets/2.0x/data.json"))
        assertFalse(text.contains("\"assets/logo.png\"")); assertFalse(text.contains("\"assets/data.json\""))
    }
    @Test fun variantsAreResolvedWithoutAnExtensionAllowlist() {
        asset("assets/2.0x/data.json")
        pubspec("assets/data.json")
        assertTrue(generateText(folder.root.path, config()).contains("\"assets/data.json\""))
        pubspec("assets/missing.json")
        assertThrows(IllegalArgumentException::class.java) { generateText(folder.root.path, config()) }
    }
    @Test fun fontAndShaderPathsComeFromTheirPubspecSections() {
        asset("fonts/Roboto.ttf"); asset("shaders/effect.frag"); asset("assets/logo.png")
        val yaml = """
            environment: {sdk: ^3.0.0}
            name: sample
            flutter:
              assets:
                - path: assets/logo.png
                  flavors: [production]
              fonts:
                - family: Roboto
                  fonts:
                    - asset: fonts/Roboto.ttf
                      weight: 400
              shaders:
                - shaders/effect.frag
        """.trimIndent()
        val text = generateText(folder.root.path, config(), yaml)
        assertTrue(text.contains("fonts/Roboto.ttf")); assertTrue(text.contains("shaders/effect.frag")); assertTrue(text.contains("assets/logo.png"))
    }
    @Test fun generatorSettingsCannotOverrideThePubspecPaths() {
        asset("assets/right.png"); asset("other/wrong.png")
        val oldConfig = AssetsGenerator.parseText("flutter_tools:\n  assets_sync:\n    sync_path: [other]\n")
        val text = generateText(folder.root.path, oldConfig)
        assertTrue(text.contains("assets/right.png")); assertFalse(text.contains("wrong.png"))
    }
    @Test fun unsavedPubspecSnapshotIsUsedForGeneration() {
        asset("assets/saved.png"); asset("other/edited.png")
        val text = generateText(folder.root.path, config(), "environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  assets: [other/]\n")
        assertTrue(text.contains("other/edited.png")); assertFalse(text.contains("assets/saved.png"))
    }
    @Test fun singleImageDeclarationWatchesItsParentForVariantChanges() {
        asset("assets/2.0x/logo.png")
        val resources = PubspecAssets.resolve(folder.root.toPath(), PubspecAssets.parse("environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  assets: [assets/logo.png]\n"))
        assertEquals(File(folder.root, "assets").toPath(), resources.single().watchPath)
    }
    @Test fun externalPackageResourceUsesPackageConfigAndPreservesBundleKey() {
        val library = folder.newFolder("external_package")
        val image = File(library, "lib/images/logo.png").apply { parentFile.mkdirs(); writeText("asset") }
        asset(".dart_tool/package_config.json").writeText("""{"configVersion":2,"packages":[{"name":"icons","rootUri":"${library.toURI().toString().trimEnd('/')}","packageUri":"lib/"}]}""")
        pubspec("packages/icons/images/")
        val text = generateText(folder.root.path, config())
        assertTrue(text.contains("packages/icons/images/logo.png"))
        assertTrue(image.exists())
    }
    @Test fun noDeclaredResourcesGeneratesAnEmptyContainer() {
        val text = generateText(folder.root.path, config(), "environment: {sdk: ^3.0.0}\nname: sample\n")
        assertTrue(text.contains("abstract class R")); assertFalse(text.contains("final RAssetsMeta "))
    }

    @Test fun fontAndShaderDeclarationsCannotBeDirectories() {
        assertThrows(IllegalArgumentException::class.java) { PubspecAssets.parse("environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  shaders: [shaders/]\n") }
        assertThrows(IllegalArgumentException::class.java) { PubspecAssets.parse("environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  fonts:\n    - family: Demo\n      fonts:\n        - asset: fonts/\n") }
    }
    @Test fun directoryKeysAreNormalizedAndDeduplicated() {
        asset("assets/logo.png")
        pubspec("./assets/", "assets/")
        val text = generateText(folder.root.path, config())
        assertFalse(text.contains("./assets/"))
        assertEquals(1, Regex("RAssetsMeta logo").findAll(text).count())
    }

    @Test fun extensionPunctuationProducesValidFields() {
        asset("assets/logo.foo-bar"); asset("assets/.hidden-file")
        val text = generateText(folder.root.path, config(true))
        assertTrue(text.contains("RAssetsMeta logoFooBar ="))
        assertTrue(text.contains("RAssetsMeta hiddenFile ="))
    }

    @Test fun outputCannotOverwriteConfigurationOrExplicitResources() {
        assertThrows(IllegalArgumentException::class.java) {
            generateText(folder.root.path, config().copy(outPath = "pubspec.yaml"))
        }
        asset("lib/input.dart")
        pubspec("lib/input.dart")
        assertThrows(IllegalArgumentException::class.java) {
            generateText(folder.root.path, config().copy(outPath = "lib/input.dart"))
        }
    }

    @Test fun existingOutputMustBelongToTheGenerator() {
        val output = asset("lib/r.dart")
        assertThrows(IllegalArgumentException::class.java) { AssetsGenerator.validateExistingOutput(output.toPath()) }
        assertEquals("asset", output.readText())
        output.writeText(AssetsGenerator.GENERATED_MARKER + "\nabstract class R {}\n")
        AssetsGenerator.validateExistingOutput(output.toPath())
        output.delete()
        AssetsGenerator.validateExistingOutput(output.toPath())
    }

    @Test fun singleFileWatchIncludesOnlyItsFileAndVariants() {
        asset("assets/logo.png")
        val resource = PubspecAssets.resolve(folder.root.toPath(), PubspecAssets.parse("environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  assets: [assets/logo.png]\n")).single()
        fun affected(path: String) = resource.affectedBy(File(folder.root, path).path)
        assertTrue(affected("assets/logo.png"))
        assertTrue(affected("assets/2.0x/logo.png"))
        assertTrue(affected("assets/2.0x"))
        assertTrue(affected("assets"))
        assertFalse(affected("assets/unrelated.png"))
        assertFalse(affected("assets/icons/logo.png"))
        assertFalse(affected("assets/2.0x/unrelated.png"))
    }

    @Test fun directoryWatchIgnoresOrdinaryNestedFiles() {
        asset("assets/logo.png")
        val resource = PubspecAssets.resolve(folder.root.toPath(), PubspecAssets.parse("environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  assets: [assets/]\n")).single()
        assertTrue(resource.affectedBy(File(folder.root, "assets/logo.png").path))
        assertTrue(resource.affectedBy(folder.root.path))
        assertFalse(resource.affectedBy(File(folder.root, "assets/icons/logo.png").path))
    }

    @Test fun packageRootCanBeDeclaredAsAnAssetDirectory() {
        asset("logo.png")
        pubspec("./")
        val text = generateText(folder.root.path, config())
        assertTrue(text.contains("\"logo.png\""))
        assertFalse(text.contains("\"/logo.png\""))
        assertThrows(IllegalArgumentException::class.java) {
            AssetsGenerator.resolvePath(folder.root.toPath(), "./")
        }
    }

    @Test fun fontAndShaderFilesDoNotUseResolutionFallback() {
        asset("fonts/2.0x/demo.ttf")
        assertThrows(IllegalArgumentException::class.java) {
            generateText(folder.root.path, config(), "environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  fonts:\n    - family: Demo\n      fonts: [{asset: fonts/demo.ttf}]\n")
        }
        asset("shaders/2.0x/demo.frag")
        assertThrows(IllegalArgumentException::class.java) {
            generateText(folder.root.path, config(), "environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  shaders: [shaders/demo.frag]\n")
        }
    }

    @Test fun generationRequiresDart3LanguageBeforeReplacingOutput() {
        asset("assets/logo.png")
        val output = asset("lib/r.dart").apply { writeText(AssetsGenerator.GENERATED_MARKER + "\nold output\n") }
        for (constraint in listOf("^2.19.0", ">=2.12.0 <4.0.0", "<3.0.0", "any", "invalid")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                AssetsGenerator.generate(folder.root.path, config(), "name: sample\nenvironment: {sdk: '$constraint'}\n")
            }
            assertEquals("error.resourceLanguage", (error as com.mocaris.plugin.flutter.tools.core.localization.LocalizedException).text.key)
            assertTrue(output.readText().contains("old output"))
        }
        assertThrows(IllegalArgumentException::class.java) { AssetsGenerator.generate(folder.root.path, config(), "name: sample") }
        for (constraint in listOf("^3.0.0", ">=3.0.0 <4.0.0", "3.9.0")) {
            assertTrue(AssetsGenerator.generate(folder.root.path, config(), "name: sample\nenvironment: {sdk: '$constraint'}\n").values.single().contains("abstract class R"))
        }
        asset(".dart_tool/package_config.json").writeText("""{"packages":[{"name":"sample","languageVersion":"2.19"}]}""")
        assertThrows(IllegalArgumentException::class.java) { generateText(folder.root.path, config()) }
        asset(".dart_tool/package_config.json").writeText("""{"packages":[{"name":"sample","languageVersion":"3.0"}]}""")
        assertTrue(generateText(folder.root.path, config()).contains("abstract class R"))
    }

}
