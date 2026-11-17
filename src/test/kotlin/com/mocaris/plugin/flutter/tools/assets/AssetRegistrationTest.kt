package com.mocaris.plugin.flutter.tools.assets

import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AssetRegistrationTest : YamlPsiTest() {
    @get:Rule val folder = TemporaryFolder()
    private fun registered(text: String) = PubspecAssets.parse(text).filter { it.kind == PubspecAssets.Kind.ASSET }.map { it.path }

    @Test fun appendsAssetsWithoutChangingCommentsFontsOrConditionalDeclarations() {
        val source = """
            name: app
            dependencies: {flutter: {sdk: flutter}} # dependency
            flutter:
              assets:
                - assets/logo.png # logo
                - path: assets/conditional.png
                  flavors: [dev] # flavor
              fonts:
                - family: Demo
                  fonts: [{asset: fonts/demo.ttf, weight: 500}] # font
        """.trimIndent()
        val updated = AssetRegistration.updatedText(yaml(source), listOf("assets/icon.svg", "assets/logo.png"))
        assertEquals(listOf("assets/logo.png", "assets/conditional.png", "assets/icon.svg"), registered(updated))
        for (comment in listOf("# dependency", "# logo", "# flavor", "# font")) assertTrue(updated.contains(comment))
        assertTrue(updated.contains("flavors: [dev]"))
        assertTrue(updated.contains("fonts: [{asset: fonts/demo.ttf, weight: 500}]"))
        assertEquals(updated, AssetRegistration.updatedText(yaml(updated), listOf("assets/icon.svg")))
    }

    @Test fun directoriesCoverOnlyTheirDirectFilesAndResolutionVariants() {
        val source = "name: app\nflutter:\n  assets: [assets/images/]"
        val updated = AssetRegistration.updatedText(yaml(source), listOf("assets/images/logo.png", "assets/images/2.0x/logo.png", "assets/images/nested/logo.png"))
        assertEquals(listOf("assets/images/", "assets/images/nested/logo.png"), registered(updated))
        val batch = AssetRegistration.updatedText(yaml("name: app"), listOf("assets/logo.png", "assets/", "assets/"))
        assertEquals(listOf("assets/"), registered(batch))
        val explicitVariant = "name: app\nflutter: {assets: [assets/2.0x/logo.png]}"
        assertEquals(listOf("assets/2.0x/logo.png", "assets/logo.png"),
            registered(AssetRegistration.updatedText(yaml(explicitVariant), listOf("assets/logo.png"))))
    }

    @Test fun createsAndExtendsEmptyBlockAndFlowMappingsWithQuotedPaths() {
        val sources = listOf("name: app", "{name: app}", "name: app\nflutter:", "{name: app, flutter: null}",
            "name: app\nflutter: null # flutter", "name: app\nflutter: {}", "name: app\nflutter:\n  assets: # assets", "name: app\nflutter: {assets: null}", "name: app\nflutter: {assets: []}",
            "name: app\nflutter: {assets: [assets/old.png,], uses-material-design: true}")
        for (source in sources) {
            val updated = AssetRegistration.updatedText(yaml(source), listOf("assets/it's logo.png", "assets/2.0x/icon.png"))
            assertTrue(source, registered(updated).containsAll(listOf("assets/it's logo.png", "assets/icon.png")))
            if (source.contains("# assets")) assertTrue(updated.contains("# assets"))
        }
    }

    @Test fun rootDirectoryCoverageAndInvalidPathsAreHandledWithoutChangingSource() {
        val source = "name: app\nflutter: {assets: ['./']}"
        assertEquals(source, AssetRegistration.updatedText(yaml(source), listOf("logo.png")))
        for (path in listOf("../outside.png", "/outside.png", "")) {
            assertThrows(IllegalArgumentException::class.java) { AssetRegistration.updatedText(yaml(source), listOf(path)) }
        }
    }

    @Test fun recursiveDirectoriesRegisterNestedResourcesAndDeduplicateOverlappingSelections() {
        val root = folder.root.toPath()
        Files.createDirectories(root.resolve("assets/images/icons/deep"))
        Files.createDirectories(root.resolve("assets/images/2.0x"))
        Files.createDirectories(root.resolve("assets/images/icons/3.0x"))
        Files.writeString(root.resolve("assets/images/logo.png"), "base")
        Files.writeString(root.resolve("assets/images/icons/icon.png"), "icon")
        Files.writeString(root.resolve("assets/images/icons/deep/note.txt"), "note")
        val paths = AssetRegistration.recursivePaths(root, listOf("assets/images/", "assets/images/icons/"))
        assertEquals(listOf("assets/images/", "assets/images/icons/", "assets/images/icons/deep/"), paths)
        val source = "name: app\nflutter:\n  assets:\n    - assets/images/ # keep"
        val updated = AssetRegistration.updatedText(yaml(source), paths)
        assertEquals(paths, registered(updated))
        assertTrue(updated.contains("# keep"))
        assertEquals(updated, AssetRegistration.updatedText(yaml(updated), paths))
        // The existing shallow path request continues to add only the selected directory.
        assertEquals(listOf("assets/images/"),
            registered(AssetRegistration.updatedText(yaml("name: app"), listOf("assets/images/"))))
    }

    @Test fun recursiveScanStopsAtNestedPackagesAndDoesNotFollowSymbolicLinks() {
        val root = folder.root.toPath()
        Files.createDirectories(root.resolve("assets/ordinary"))
        Files.createDirectories(root.resolve("assets/package/resources"))
        Files.writeString(root.resolve("assets/package/pubspec.yaml"), "name: nested")
        val external = folder.newFolder("outside").toPath()
        Files.createDirectories(external.resolve("nested"))
        Files.createSymbolicLink(root.resolve("assets/link"), external)
        assertEquals(listOf("assets/", "assets/ordinary/"), AssetRegistration.recursivePaths(root, listOf("assets/")))
    }

    @Test fun recursiveScanCanBeCanceledAndRejectsFilesAndOutsidePaths() {
        val root = folder.root.toPath()
        Files.createDirectories(root.resolve("assets/deep"))
        Files.writeString(root.resolve("assets/note.txt"), "note")
        var checks = 0
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            AssetRegistration.recursivePaths(root, listOf("assets/")) {
                if (++checks > 2) throw com.intellij.openapi.progress.ProcessCanceledException()
            }
        }
        for (path in listOf("assets/note.txt", "../outside/", "/outside/")) {
            assertThrows(IllegalArgumentException::class.java) { AssetRegistration.recursivePaths(root, listOf(path)) }
        }
    }
}
