package com.mocaris.plugin.flutter.tools.assets.generation

import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetsDirectoryTreeTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()
    private fun file(path: String) = root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, "resource") }
    private fun generate(paths: List<String>, config: AssetsSyncConfig = AssetsSyncConfig()): String {
        val pubspec = "environment: {sdk: ^3.0.0}\nname: sample\nflutter:\n  assets:\n" + paths.joinToString("\n") { "    - $it" }
        return AssetsGenerator.generate(root.toString(), config, pubspec).getValue(root.resolve("lib/r.dart"))
    }

    @Test fun assetsFollowDeclaredDirectoryHierarchyAndLeafFilenames() {
        file("assets/images/tab/ic_tab_home.png"); file("assets/images/home/bg_home.png")
        file("assets/fonts/demo.ttf"); file("assets/anim/loading.json")
        val paths = listOf("assets/images/tab/", "assets/images/home/", "assets/fonts/", "assets/anim/")
        val text = generate(paths)
        assertTrue(text.contains("final _RAssetsImages images = const _RAssetsImages();"))
        assertTrue(text.contains("final _RAssetsImagesTab tab = const _RAssetsImagesTab();"))
        assertTrue(text.contains("final _RAssetsImagesHome home = const _RAssetsImagesHome();"))
        assertTrue(text.contains("RAssetsMeta icTabHome =")); assertTrue(text.contains("RAssetsMeta bgHome ="))
        assertTrue(text.contains("class _RAssetsFonts")); assertTrue(text.contains("class _RAssetsAnim"))
        assertTrue(text.contains("RAssetsMeta demo =")); assertTrue(text.contains("RAssetsMeta loading ="))
        assertTrue(text.contains("\"assets/images/tab/ic_tab_home.png\""))
        assertFalse(text.contains("class _RAssetsAssets"))
        assertEquals(text, generate(paths.reversed()))
    }

    @Test fun sameFilenamesInDifferentDirectoriesDoNotGainExtensions() {
        file("assets/images/home/logo.png"); file("assets/images/tab/logo.svg")
        val text = generate(listOf("assets/images/home/logo.png", "assets/images/tab/logo.svg"))
        assertEquals(2, Regex("RAssetsMeta logo =").findAll(text).count())
        assertFalse(text.contains("RAssetsMeta logoPng")); assertFalse(text.contains("RAssetsMeta logoSvg"))
    }

    @Test fun fileAndDirectoryFieldsCannotConflict() {
        file("assets/images.png"); file("assets/images/logo.png")
        val text = generate(listOf("assets/", "assets/images/"))
        assertTrue(text.contains("final _RAssetsImages images ="))
        assertTrue(text.contains("RAssetsMeta imagesPng ="))
    }

    @Test fun collidingNormalizedDirectoriesFailBeforeOutputChanges() {
        file("assets/a-b/logo.png"); file("assets/a_b/icon.png")
        val output = file("lib/r.dart")
        assertThrows(IllegalArgumentException::class.java) { generate(listOf("assets/a-b/", "assets/a_b/")) }
        assertEquals("resource", Files.readString(output))
    }

    @Test fun customRootsAndDirectoryIdentifiersRemainValidAndShallow() {
        file("resources/my-images/class.png"); file("resources/my-images/deep/hidden.png")
        file("assets/123/logo.png")
        val text = generate(listOf("resources/my-images/", "assets/123/"), AssetsSyncConfig(outClass = "FeatureR"))
        assertTrue(text.contains("class _FeatureRAssetsResourcesMyImages"))
        assertTrue(text.contains("FeatureRAssetsMeta assetClass ="))
        assertTrue(text.contains("class _FeatureRAssetsDirectory123"))
        assertFalse(text.contains("hidden.png"))
    }

    @Test fun removingAllResourcesRemovesTheDirectoryClasses() {
        file("assets/images/logo.png")
        assertTrue(generate(listOf("assets/images/")).contains("class _RAssetsImages"))
        assertFalse(generate(emptyList()).contains("class _RAssetsImages"))
    }

    @Test fun camelCaseClassesRemainUniqueAcrossDifferentDirectoryPaths() {
        file("assets/foo_bar/one.png"); file("assets/foo/bar/two.png")
        val text = generate(listOf("assets/foo_bar/", "assets/foo/bar/"))
        val classes = Regex("(?<!abstract )class ([A-Za-z0-9_]+)").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(classes.size, classes.toSet().size)
        assertTrue(classes.filterNot { it == "RAssetsMeta" }.all { Regex("_[A-Z][A-Za-z0-9]*").matches(it) })
        assertTrue(text.contains("final _RAssetsFooBar fooBar ="))
        assertTrue(text.contains("final _RAssetsFooBar2 bar ="))
        assertEquals(text, generate(listOf("assets/foo/bar/", "assets/foo_bar/")))
    }
    @Test fun metadataClassNamesDoNotCollideWithAnAssetsMetaDirectory() {
        file("assets/meta/logo.png")
        val text = generate(listOf("assets/meta/"))
        assertTrue(text.contains("class RAssetsMeta {"))
        assertTrue(text.contains("class _RAssetsMeta {"))
        assertTrue(text.contains("final _RAssetsMeta meta = const _RAssetsMeta();"))
        val classes = Regex("class ([A-Za-z0-9_]+)").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(classes.size, classes.toSet().size)
    }
    @Test fun onlyTheEntryExposesTheDeclaringPackageIncludingEmptyGroups() {
        file("assets/a/b/logo.png")
        val config = AssetsSyncConfig(outClass = "LoginR")
        for (paths in listOf(listOf("assets/a/b/"), emptyList())) {
            val text = generate(paths, config)
            assertEquals(1, Regex("\"sample\"").findAll(text).count())
            assertTrue(text.contains("static const String package = \"sample\";"))
            assertFalse(text.contains("LoginR._package"))
            val groups = Regex("class (_[A-Za-z0-9]+) \\{(.*?)\\n\\}", RegexOption.DOT_MATCHES_ALL).findAll(text).toList()
            assertEquals(if (paths.isEmpty()) 3 else 5, groups.size)
            assertTrue(groups.all { !it.groupValues[2].contains("String get package") })
        }
        val excluded = generate(listOf("assets/a/b/"), config.copy(excluded = setOf("assets/")))
        assertFalse(excluded.contains("String get package => LoginR."))
        assertTrue(excluded.contains("static const String package = \"sample\";"))
    }
    @Test fun packageNamedFilesAndDirectoriesKeepAUniqueResourceField() {
        file("assets/package.png"); file("assets/package/logo.png")
        val text = generate(listOf("assets/", "assets/package/"))
        assertTrue(text.contains("RAssetsMeta packagePng ="))
        assertTrue(text.contains("final _RAssetsPackage package ="))
        assertFalse(text.contains("RAssetsMeta package ="))
        val single = generate(listOf("assets/package.png"))
        assertTrue(single.contains("RAssetsMeta package ="))
    }

}
