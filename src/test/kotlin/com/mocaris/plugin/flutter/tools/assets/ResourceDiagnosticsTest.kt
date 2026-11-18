package com.mocaris.plugin.flutter.tools.assets

import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import org.jetbrains.yaml.psi.YAMLSequenceItem

class ResourceDiagnosticsTest : YamlPsiTest() {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()
    private fun issues(source: String) = ResourceDiagnostics.inspect(yaml(source), root, AssetsSyncConfig())
    private fun file(path: String) { val value = root.resolve(path); Files.createDirectories(value.parent); Files.writeString(value, "sample") }

    @Test fun reportsIndependentMissingPathsFontErrorsAndDuplicateDeclarations() {
        file("font.ttf")
        val source = """
            name: app
            flutter:
              assets: [missing.png, missing.png]
              fonts:
                - family: Demo
                  fonts:
                    - asset: font.ttf
                      weight: 450
                      style: bold
              shaders: [missing.frag]
        """
        val issues = issues(source)
        assertEquals(2, issues.count { it.message.key == "error.assetMissing" })
        assertTrue(issues.filter { it.message.key == "error.assetMissing" }.all { it.selectResource })
        assertEquals(1, issues.count { it.message.key == "inspection.duplicate" })
        assertEquals("400", issues.single { it.message.key == "error.fontWeight" }.replacement)
        assertEquals("normal", issues.single { it.message.key == "error.fontStyle" }.replacement)
        assertEquals("450", issues.single { it.message.key == "error.fontWeight" }.element.text)
    }

    @Test fun offersDirectorySlashFixAndAcceptsLogicalResolutionVariants() {
        Files.createDirectories(root.resolve("images")); file("images/2.0x/logo.png")
        val issues = issues("name: app\nflutter:\n  assets: [images, images/logo.png]\n")
        assertEquals(1, issues.size)
        assertEquals("'images/'", issues.single().replacement)
    }

    @Test fun conditionalAssetsAndDistinctFontFacesAreNotDuplicates() {
        file("a.png"); file("f.ttf")
        val issues = issues("""
            name: app
            flutter:
              assets:
                - path: a.png
                  flavors: [dev]
                - path: a.png
                  flavors: [prod]
              fonts:
                - family: Demo
                  fonts:
                    - asset: f.ttf
                    - asset: f.ttf
                      weight: 700
        """)
        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test fun collisionsInScannedDirectoryAreReportedOnTheResourceSection() {
        file("assets/a-b.png"); file("assets/a_b.png")
        val issues = issues("name: app\nflutter:\n  assets: [assets/]\n")
        assertEquals("error.duplicateField", issues.single().message.key)
        assertEquals("assets", issues.single().element.text)
    }

    @Test fun externalResourcesShareResolutionAndRefreshMetadataOnTheNextInspection() {
        file("dep/lib/a.png"); file("dep/lib/b.png")
        val config = root.resolve(".dart_tool/package_config.json")
        Files.createDirectories(config.parent)
        Files.writeString(config, """{"packages":[{"name":"dep","rootUri":"../dep","packageUri":"lib/"}]}""")
        val source = "name: app\nflutter:\n  assets: [packages/dep/a.png, packages/dep/b.png]\n"
        assertTrue(issues(source).isEmpty())
        Files.writeString(config, """{"packages":[]}""")
        assertEquals(2, issues(source).count { it.message.key == "error.packageConfig" })
    }

    @Test fun invalidOutputPathsStillReportEachDeclarationAndTheConfigurationError() {
        file("a.png"); file("b.png")
        val result = ResourceDiagnostics.inspect(yaml("name: app\nflutter:\n  assets: [a.png, b.png]"), root,
            AssetsSyncConfig(outPath = "../r.dart"))
        assertEquals(3, result.count { it.message.key == "error.pathInside" })
    }

    @Test fun removingFlowDuplicatesKeepsOtherItemsAndComments() {
        for (paths in listOf("[a.png, a.png, b.png]", "[a.png, b.png, a.png]", "[a.png, a.png]")) {
            val duplicate = issues("name: app\nflutter:\n  assets: $paths # keep\n").single { it.remove }
            val updated = ResourceFix.removeDuplicate(duplicate.element as YAMLSequenceItem)
            val flutter = (YamlSupport.load(updated) as Map<*, *>)["flutter"] as Map<*, *>
            assertEquals(if (paths.contains("b.png")) listOf("a.png", "b.png") else listOf("a.png"), flutter["assets"])
            assertTrue(updated.contains("# keep"))
        }
    }

    @Test fun removingBlockMappingDuplicatePreservesEmbeddedAndTrailingComments() {
        val source = """
            name: app
            description: Keep me
            flutter:
              assets:
                - path: a.png
                  flavors: [dev]
                - path: a.png # inline
                  # embedded
                  flavors: [dev]
                - b.png # next
        """
        val duplicate = issues(source).single { it.remove }
        val updated = ResourceFix.removeDuplicate(duplicate.element as YAMLSequenceItem)
        for (comment in listOf("# inline", "# embedded", "# next", "Keep me")) assertTrue(updated, updated.contains(comment))
        val flutter = (YamlSupport.load(updated) as Map<*, *>)["flutter"] as Map<*, *>
        assertEquals(2, (flutter["assets"] as List<*>).size)
    }

    @Test fun removingFlowDuplicateKeepsCommentsBetweenItems() {
        val source = "name: app\nflutter:\n  assets: [a.png, a.png, # keep between\n    b.png]\n"
        val duplicate = issues(source).single { it.remove }
        val updated = ResourceFix.removeDuplicate(duplicate.element as YAMLSequenceItem)
        val flutter = (YamlSupport.load(updated) as Map<*, *>)["flutter"] as Map<*, *>
        assertEquals(listOf("a.png", "b.png"), flutter["assets"])
        assertTrue(updated.contains("# keep between"))
    }

    @Test fun malformedSectionsAndQuotedFontWeightAreFlaggedWithoutCrashing() {
        file("f.ttf")
        val issues = issues("""
            name: app
            flutter:
              assets: bad
              fonts:
                - family: Demo
                  fonts:
                    - asset: f.ttf
                      weight: '400'
              shaders: [42]
        """)
        assertTrue(issues.any { it.message.key == "error.pubspecList" })
        assertTrue(issues.any { it.message.key == "error.fontWeight" })
        assertTrue(issues.any { it.message.key == "error.pubspecPaths" })
    }
}
