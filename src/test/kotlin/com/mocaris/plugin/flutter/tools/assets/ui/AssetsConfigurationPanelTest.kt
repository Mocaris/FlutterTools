package com.mocaris.plugin.flutter.tools.assets.ui

import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.assets.AssetsSyncService
import com.mocaris.plugin.flutter.tools.assets.config.AssetsConfigService
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.ui.SettingsContentPane
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager

class AssetsConfigurationPanelTest : BasePlatformTestCase() {
    private val paths = listOf("assets/", "assets/fallback/", "assets/fallback/animations/", "assets/fallback/images/",
        "assets/fallback/images/flags/", "assets/fonts/", "assets/i18n/", "assets/pdfjs/", "assets/pdfjs/build/", "assets/pdfjs/viewer/")

    private fun components(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::components)

    private fun layout(component: Component) {
        if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
    }

    private fun withPackage(action: (Path, VirtualFile) -> Unit) {
        val path = Files.createTempDirectory("assets-panel-")
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString(), path.toRealPath().toString())
        try {
            paths.forEach { Files.createDirectories(path.resolve(it)) }
            Files.writeString(path.resolve("pubspec.yaml"), "environment: {sdk: ^3.0.0}\nname: login\nflutter:\n  assets:\n" + paths.joinToString("\n", postfix = "\n") { "    - $it" })
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            root.refresh(false, true)
            action(path, root)
        } finally {
            path.toFile().deleteRecursively()
            LocalFileSystem.getInstance().findFileByNioFile(path)?.refresh(false, true)
        }
    }

    fun testListsStayBoundedAndControlsFitLightDarkAndNarrowWindows() = withPackage { _, root ->
        val panel = AssetsConfigurationPanel(project)
        panel.selectPackage(root)
        val service = project.service<AssetsSyncService>()
        service.generate(root)
        PlatformTestUtil.waitWithEventsDispatching("asset generation before layout", { service.status(root) == message("assets.generated", "lib/r.dart") }, 10)
        panel.refresh()
        val textAreas = components(panel).filterIsInstance<JTextArea>()
        val resources = textAreas.single { it.text.startsWith("assets/") }
        assertEquals(paths.joinToString("\n"), resources.text)
        val surface = SwingUtilities.getAncestorOfClass(SettingsContentPane::class.java, resources) as SettingsContentPane
        val previousLaf = UIManager.getLookAndFeel()
        val bright = JBColor.isBright()
        try {
            for ((theme, laf) in listOf("light" to com.intellij.ide.ui.laf.IntelliJLaf(), "dark" to DarculaLaf())) {
                UIManager.setLookAndFeel(laf); JBColor.setDark(theme == "dark")
                SwingUtilities.updateComponentTreeUI(panel)
                for ((size, width, height) in listOf(Triple("narrow", 320, 760), Triple("wide", 716, 994))) {
                    panel.setSize(JBUI.scale(width), JBUI.scale(height)); repeat(3) { layout(panel) }
                    if (java.lang.Boolean.getBoolean("fluttertools.render.ui")) {
                        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
                        val graphics = image.createGraphics()
                        try { panel.printAll(graphics) } finally { graphics.dispose() }
                        val output = Path.of("build/ui-preview/assets-$theme-$size.png")
                        Files.createDirectories(output.parent); ImageIO.write(image, "png", output.toFile())
                    }
                    assertFalse("$theme/$size outer horizontal scrollbar", panel.horizontalScrollBar.isVisible)
                    assertTrue("$theme/$size resource height ${surface.height}", surface.height in JBUI.scale(60)..JBUI.scale(160))
                    assertTrue(resources.isOpaque)
                    assertTrue(surface.border.getBorderInsets(surface).left > 0)
                    val buttons = components(panel).filterIsInstance<JButton>()
                    val save = buttons.single { it.text == message("assets.save") }
                    val generate = buttons.single { it.text == message("assets.generate") }
                    assertEquals("$theme/$size action left edges", SwingUtilities.convertPoint(save, 0, 0, panel).x,
                        SwingUtilities.convertPoint(generate, 0, 0, panel).x)
                    components(panel).filterIsInstance<JButton>().filter { !it.text.isNullOrEmpty() }.forEach { button ->
                        assertTrue("$theme/$size clips ${button.text}", button.x >= 0 && button.x + button.width <= button.parent.width &&
                            button.y >= 0 && button.y + button.height <= button.parent.height)
                    }
                    components(panel).filterIsInstance<JBCheckBox>().forEach { checkbox ->
                        assertTrue("$theme/$size clips ${checkbox.text}", checkbox.preferredSize.width <= checkbox.width)
                    }
                }
            }
        } finally { UIManager.setLookAndFeel(previousLaf); JBColor.setDark(!bright) }
    }

    fun testSaveReloadConflictAndGenerateKeepTheirBehavior() = withPackage { path, root ->
        val panel = AssetsConfigurationPanel(project)
        panel.selectPackage(root)
        val fields = components(panel).filterIsInstance<JBTextField>()
        assertEquals(2, fields.size)
        val output = fields.single { it.text == "lib/r.dart" }
        val buttons = components(panel).filterIsInstance<JButton>()
        output.text = "lib/kept.dart"
        buttons.single { it.text == message("assets.save") }.doClick()
        assertEquals("lib/kept.dart", project.service<AssetsConfigService>().read(root).effectiveConfig.outPath)
        val document = FileDocumentManager.getInstance().getDocument(root.findChild("flutter_tools.yaml")!!)!!
        output.text = "lib/local.dart"
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("lib/kept.dart", "lib/external.dart")) }
        panel.refresh()
        assertEquals("lib/local.dart", output.text)
        assertTrue(components(panel).filterIsInstance<JTextArea>().any { it.text == message("assets.externalChange") })
        val reload = buttons.single { it.text == message("assets.reload") }
        val previous = TestDialogManager.setTestDialog { Messages.NO }
        try {
            reload.doClick(); assertEquals("lib/local.dart", output.text)
            TestDialogManager.setTestDialog { Messages.YES }
            reload.doClick(); assertEquals("lib/external.dart", output.text)
        } finally { TestDialogManager.setTestDialog(previous) }
        buttons.single { it.text == message("assets.generate") }.doClick()
        val service = project.service<AssetsSyncService>()
        PlatformTestUtil.waitWithEventsDispatching("generated output after reload", { service.status(root) == message("assets.generated", "lib/external.dart") }, 10)
        assertTrue(Files.readString(path.resolve("lib/external.dart")).contains("abstract class LoginR"))
    }
}
