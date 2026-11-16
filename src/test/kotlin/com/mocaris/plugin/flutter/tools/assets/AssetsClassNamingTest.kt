package com.mocaris.plugin.flutter.tools.assets

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AssetsClassNamingTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()

    @Test fun mainProjectRetainsRIncludingSymlinkAliases() {
        assertEquals("R", AssetsClassNaming.defaultClass(root, root, "name: main_app"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), root)
        assertEquals("R", AssetsClassNaming.defaultClass(alias, root, "name: main_app"))
        assertEquals("R", AssetsClassNaming.defaultClass(root, null, "name: main_app"))
    }

    @Test fun modulesUsePubspecNamesRatherThanFolderNames() {
        val module = root.resolve("different_folder")
        for ((name, expected) in mapOf("feature_login" to "FeatureLoginR", "shared_ui" to "SharedUiR",
            "_internal_package" to "InternalPackageR", "feature_v2" to "FeatureV2R")) {
            assertEquals(expected, AssetsClassNaming.defaultClass(module, root, "name: $name"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssetsClassNaming.defaultClass(module, root, "name: invalid-name")
        }
    }

    @Test fun explicitClassNamesIncludingRArePreserved() {
        for (name in listOf("R", "LoginResources")) {
            val config = AssetsGenerator.parseText("flutter_tools:\n  assets_sync:\n    out_class: $name", "FeatureLoginR")
            assertEquals(name, config.outClass)
        }
        assertEquals("FeatureLoginR", AssetsGenerator.parseText("flutter_tools:\n  assets_sync: {}", "FeatureLoginR").outClass)
    }

    @Test fun defaultConfigurationAndGeneratedPublicTypesUseTheModulePrefix() {
        val module = Files.createDirectories(root.resolve("modules/login"))
        Files.createDirectories(module.resolve("assets"))
        Files.writeString(module.resolve("assets/logo.png"), "resource")
        val pubspec = "name: feature_login\nflutter:\n  assets: [assets/logo.png]"
        val defaultName = AssetsClassNaming.defaultClass(module, root, pubspec)
        val snapshot = AssetsConfigService.Snapshot("flutter_tools.yaml", "", null, false, defaultName)
        val config = snapshot.effectiveConfig
        assertEquals("FeatureLoginR", config.outClass)
        val outputs = AssetsGenerator.generate(module.toString(), config, pubspec)
        val entry = outputs.getValue(module.resolve("lib/r.dart"))
        assertTrue(entry.contains("abstract class FeatureLoginR {"))
        assertTrue(entry.contains("class FeatureLoginRResourceMeta"))
        assertTrue(entry.contains("enum FeatureLoginRResourceKind"))
        assertTrue(outputs.getValue(module.resolve("lib/gen/assets.g.dart")).contains("final FeatureLoginRResource"))
        assertFalse(entry.contains("abstract class R {"))
    }
}
