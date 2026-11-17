package com.mocaris.plugin.flutter.tools.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory

class FlutterProjectContextTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun onlySelectedFlutterRootsQualifyForAssetGeneration() {
        val root = folder.root.toPath()
        val module = Files.createDirectories(root.resolve("feature"))
        val lib = Files.createDirectories(module.resolve("lib"))
        val pubspec = module.resolve("pubspec.yaml")
        Files.writeString(pubspec, "name: feature\ndependencies: {flutter: {sdk: flutter}}\n")
        assertTrue(FlutterPackageLocator.isFlutterPackage(module))
        assertFalse(FlutterPackageLocator.isFlutterPackage(root))
        assertFalse(FlutterPackageLocator.isFlutterPackage(lib))
        assertFalse(FlutterPackageLocator.isFlutterPackage(pubspec))

        for (yaml in listOf("name: dart_package", "flutter: {assets: [assets/]}",
            "dependencies: {flutter: {path: ../flutter}}", "dependencies: [")) {
            Files.writeString(pubspec, yaml)
            assertFalse(FlutterPackageLocator.isFlutterPackage(module))
        }
    }

    @Test fun directoryActionsAreMovedToFlutterToolsAndToolsMenuIsPreserved() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse("src/main/resources/META-INF/plugin.xml")
        val xpath = XPathFactory.newInstance().newXPath()
        fun count(expression: String) = xpath.evaluate("count($expression)", xml).toDouble().toInt()
        val group = "/idea-plugin/actions/group[@id='FlutterTools.ProjectViewPopup']"
        assertEquals(1, count("$group[@popup='true']/add-to-group[@group-id='ProjectViewPopupMenu']"))
        assertEquals(1, count("$group/action[@id='FlutterTools.OpenPlatformProject']"))
        assertEquals(1, count("$group/action[@id='FlutterTools.GenerateSelectedAssets']"))
        assertEquals(1, count("$group/action[@id='FlutterTools.RegisterAssets']"))
        assertEquals(0, count("//action[@id='FlutterTools.OpenPlatformProject']/add-to-group[@group-id='RevealGroup']"))
        assertEquals(1, count("/idea-plugin/actions/group[@id='FlutterTools.Mocaris']/action[@id='AssetsSync']"))
    }
}
