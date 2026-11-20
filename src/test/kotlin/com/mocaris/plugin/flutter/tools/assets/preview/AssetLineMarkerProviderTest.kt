package com.mocaris.plugin.flutter.tools.assets.preview

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.lang.ParserDefinition
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class AssetLineMarkerProviderTest : YamlPsiTest() {
    @Test fun dartExtensionIsOptionalAndSeparateFromCorePluginRegistration() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse("src/main/resources/META-INF/plugin.xml")
        val depends = xml.getElementsByTagName("depends")
        val dart = (0 until depends.length).map { depends.item(it) }.single { it.textContent == "Dart" }
        assertEquals("true", dart.attributes.getNamedItem("optional").nodeValue)
        val descriptor = Path.of("src/main/resources/META-INF", dart.attributes.getNamedItem("config-file").nodeValue).toFile()
        val extension = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(descriptor)
            .getElementsByTagName("codeInsight.lineMarkerProvider").item(0)
        assertEquals("Dart", extension.attributes.getNamedItem("language").nodeValue)
        assertTrue(extension.attributes.getNamedItem("implementationClass").nodeValue.endsWith("AssetLineMarkerProvider"))
    }

    @Test fun realDartPsiAnchorsMarkersOnResourcePathLinesWithoutSchedulingImageLoads() {
        // Run with an installed Dart jar in the runtime classpath and a matching IDE SDK.
        Assume.assumeTrue(java.lang.Boolean.getBoolean("fluttertools.test.dart"))
        CoreApplicationEnvironment.registerApplicationExtensionPoint(SmartPointerAnchorProvider.EP_NAME, SmartPointerAnchorProvider::class.java)
        val parser = Class.forName("com.jetbrains.lang.dart.DartParserDefinition").getDeclaredConstructor().newInstance() as ParserDefinition
        val type = Class.forName("com.jetbrains.lang.dart.DartFileType").getField("INSTANCE").get(null) as FileType
        environment.environment.registerFileType(type, "dart")
        environment.environment.registerParserDefinition(parser)
        val directory = object : LightVirtualFile("feature") {
            override fun isDirectory() = true
            override fun getPath() = "/preview-test/feature"
            override fun findChild(name: String): VirtualFile? = if (name == "pubspec.yaml") LightVirtualFile(name, "name: feature") else null
        }
        val text = AssetsGenerator.GENERATED_MARKER + "\n" + """
            class _FeatureRAssets {
              const _FeatureRAssets();
              final FeatureRResource logo = const FeatureRResource(
                FeatureRResourceMeta(
                  "feature",
                  "assets/logo.png", FeatureRResourceKind.assets),
              );
              // final FeatureRResource fake = const FeatureRResource(FeatureRResourceMeta("feature", "fake.png", FeatureRResourceKind.assets));
              final FeatureRResource notes = const FeatureRResource(FeatureRResourceMeta("feature", "notes.txt", FeatureRResourceKind.assets));
            }
        """.trimIndent()
        val virtual = object : LightVirtualFile("assets.g.dart", type, text) {
            override fun getParent() = directory
            override fun getPath() = "/preview-test/feature/assets.g.dart"
        }
        val file = PsiManager.getInstance(environment.project).findFile(virtual)!!
        val settings = FlutterToolsSettings().apply { state.useGlobalSettings = false }
        settings.state.assetImagePreviews = false
        environment.project.registerService(FlutterToolsSettings::class.java, settings)
        val service = AssetPreviewService(environment.project)
        environment.project.registerService(AssetPreviewService::class.java, service)
        Disposer.register(environment.parentDisposable, service)
        val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toList()
        val markers = mutableListOf<com.intellij.codeInsight.daemon.LineMarkerInfo<*>>()
        AssetLineMarkerProvider().collectSlowLineMarkers(leaves, markers)
        assertTrue(markers.isEmpty())
        settings.state.assetImagePreviews = true
        AssetLineMarkerProvider().collectSlowLineMarkers(leaves, markers)
        assertEquals(2, markers.size)
        assertEquals(text.indexOf("\"assets/logo.png\""), markers.first().element!!.textOffset)
        assertTrue(markers.first().element!!.textOffset > text.indexOf("logo ="))
        assertEquals(0, service.pendingRequests())
        assertNull(service.cached(ImageRequest(directory.path, "feature", "assets/logo.png")))
        assertTrue(markers.first().lineMarkerTooltip!!.contains("assets/logo.png"))
        settings.state.assetImagePreviews = false
        markers.clear()
        AssetLineMarkerProvider().collectSlowLineMarkers(leaves, markers)
        assertTrue(markers.isEmpty())
        assertNull(service.thumbnail(ImageRequest(directory.path, "feature", "assets/logo.png")))
    }
}
