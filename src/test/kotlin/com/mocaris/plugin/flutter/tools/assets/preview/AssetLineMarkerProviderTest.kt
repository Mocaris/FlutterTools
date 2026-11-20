package com.mocaris.plugin.flutter.tools.assets.preview

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.ParserDefinition
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.assets.completion.ResourceCompletionService
import com.mocaris.plugin.flutter.tools.assets.completion.ResourceNavigationHandler
import com.mocaris.plugin.flutter.tools.assets.completion.ResourceString
import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import java.nio.file.Files
import java.lang.reflect.Proxy
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class AssetLineMarkerProviderTest : BasePlatformTestCase() {
    fun testDartExtensionIsOptionalAndSeparateFromCorePluginRegistration() {
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

    fun testGeneratedAndOrdinaryDartUseSamePreviewAndNavigation() {
        if (!java.lang.Boolean.getBoolean("fluttertools.test.dart")) return
        val parser = Class.forName("com.jetbrains.lang.dart.DartParserDefinition").getDeclaredConstructor().newInstance() as ParserDefinition
        val type = Class.forName("com.jetbrains.lang.dart.DartFileType").getField("INSTANCE").get(null) as FileType
        val language = parser.fileNodeType.language
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(language, parser)
        Disposer.register(testRootDisposable) {
            LanguageParserDefinitions.INSTANCE.removeExplicitExtension(language, parser)
        }
        val path = Files.createTempDirectory("resource-preview-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString())
        try {
            val paths = listOf("assets/logo.png", "assets/notes.txt", "fonts/demo.ttf", "shaders/effect.frag")
            paths.forEach { resource ->
                val file = path.resolve(resource)
                Files.createDirectories(file.parent); Files.writeString(file, "resource")
            }
            val pubspec = "name: feature\nenvironment: {sdk: ^3.0.0}\nflutter:\n  assets: [assets/]\n  fonts: [{family: Demo, fonts: [{asset: fonts/demo.ttf}]}]\n  shaders: [shaders/effect.frag]\n"
            Files.writeString(path.resolve("pubspec.yaml"), pubspec)
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            root.refresh(false, true)
            val settings = project.service<FlutterToolsSettings>().apply {
                state.useGlobalSettings = false; state.assetImagePreviews = true
            }
            val completion = project.service<ResourceCompletionService>()
            PlatformTestUtil.waitWithEventsDispatching("resource index", { completion.snapshot(root) != null }, 10)
            val generated = AssetsGenerator.generate(path.toString(), AssetsSyncConfig(outClass = "FeatureR"), pubspec).values.single()
            val ordinary = """
                import 'assets/logo.png';
                // 'assets/logo.png'
                final missing = 'assets/missing.png';
                final interpolated = 'assets/${'$'}name.png';
                final logo = r'assets/logo.png';
                final notes = '''assets/notes.txt''';
                final font = "fonts/dem\x6f.ttf";
                final shader = 'shaders/effect.frag';
            """.trimIndent()
            fun file(name: String, text: String): PsiFile {
                val virtual = object : LightVirtualFile(name, type, text) {
                    override fun getParent() = root
                    override fun getPath() = "${root.path}/$name"
                }
                return PsiManager.getInstance(project).findFile(virtual)!!
            }
            fun markers(file: PsiFile): List<LineMarkerInfo<*>> = mutableListOf<LineMarkerInfo<*>>().also {
                AssetLineMarkerProvider().collectSlowLineMarkers(PsiTreeUtil.collectElements(file) { it.firstChild == null }.toList(), it)
            }
            val files = listOf(file("resources.dart", generated), file("ordinary.dart", ordinary),
                file("formatted.dart", generated.removePrefix(AssetsGenerator.GENERATED_MARKER)))
            val service = project.service<AssetPreviewService>()
            val found = files.map { file -> markers(file).also { result ->
                assertEquals(paths.size, result.size)
                paths.forEach { resource -> assertEquals(1, result.count { it.lineMarkerTooltip!!.contains(resource) }) }
            } }
            assertEquals(0, service.pendingRequests())
            assertNull(service.cached(ImageRequest(root.path, "feature", "assets/logo.png")))
            settings.state.assetImagePreviews = false
            val editor = Proxy.newProxyInstance(Editor::class.java.classLoader, arrayOf(Editor::class.java)) { _, _, _ ->
                throw UnsupportedOperationException("Path navigation must not access an editor")
            } as Editor
            files.zip(found).forEach { (file, result) ->
                assertTrue(markers(file).isEmpty())
                result.forEach { marker ->
                    val literal = ResourceString.literal(marker.element)!!
                    val string = ResourceString.parse(literal.text, literal.textOffset)!!
                    val target = ResourceNavigationHandler().getGotoDeclarationTargets(marker.element, string.start, editor)!!.single()
                    assertEquals(path.resolve(string.value).toString(), target.containingFile.virtualFile.path)
                }
            }
            assertEquals(0, service.pendingRequests())
        } finally { path.toFile().deleteRecursively() }
    }
}
