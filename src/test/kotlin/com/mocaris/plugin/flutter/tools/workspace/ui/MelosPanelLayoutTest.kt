package com.mocaris.plugin.flutter.tools.workspace.ui

import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.UIManager

class MelosPanelLayoutTest : BasePlatformTestCase() {
    private fun components(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::components)

    private fun layout(component: Component) {
        if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
    }

    private fun capture(panel: MelosPanel, name: String) {
        if (!java.lang.Boolean.getBoolean("fluttertools.render.ui")) return
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { panel.printAll(graphics) } finally { graphics.dispose() }
        val output = Path.of("build/ui-preview/$name.png")
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())
    }

    fun testScriptSurfaceAndCompactLayoutInLightAndDarkThemes() {
        val path = Path.of(project.basePath!!)
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString())
        Files.createDirectories(path.resolve("modules/login"))
        Files.writeString(path.resolve("pubspec.yaml"), "name: flutter_test_app\nworkspace: [modules/login]\nmelos: {useRootAsPackage: true, scripts: {analyze: dart analyze, test: dart test}}\n")
        Files.writeString(path.resolve("modules/login/pubspec.yaml"), "name: login\nresolution: workspace\n")
        val previousLaf = UIManager.getLookAndFeel()
        val bright = JBColor.isBright()
        try {
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            root.refresh(false, true)
            val panel = MelosPanel(project)
            Disposer.register(testRootDisposable, panel)
            panel.refresh(root, listOf(root, root.findFileByRelativePath("modules/login")!!))
            val list = components(panel).filterIsInstance<JBList<*>>().single()
            PlatformTestUtil.waitWithEventsDispatching("Melos layout state", { list.model.size == 2 }, 5)
            list.selectedIndex = 0
            val field = components(panel).filterIsInstance<JBTextField>().single()
            field.text = "/Users/zovey.zou-zd/DevSpace/flutter_test_app"
            for ((theme, laf) in listOf("light" to com.intellij.ide.ui.laf.IntelliJLaf(), "dark" to DarculaLaf())) {
                UIManager.setLookAndFeel(laf)
                JBColor.setDark(theme == "dark")
                SwingUtilities.updateComponentTreeUI(panel)
                val surface = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, list) as JBScrollPane
                assertTrue(list.isOpaque)
                assertEquals(UIUtil.getTextFieldBackground(), list.background)
                assertEquals(list.background, surface.viewport.background)
                assertTrue(surface.border.getBorderInsets(surface).left > 0)
                for ((size, width, height) in listOf(Triple("narrow", 320, 760), Triple("wide", 712, 996))) {
                    panel.setSize(JBUI.scale(width), JBUI.scale(height))
                    repeat(3) { layout(panel) }
                    capture(panel, "melos-$theme-$size")
                    val outer = components(panel).filterIsInstance<JBScrollPane>().first()
                    assertFalse("$theme/$size should scroll vertically", outer.horizontalScrollBar.isVisible)
                    assertTrue("$theme/$size script surface is ${surface.height}px", surface.height in JBUI.scale(60)..JBUI.scale(180))
                    val buttons = components(panel).filterIsInstance<JButton>().filter { !it.text.isNullOrEmpty() }
                    buttons.forEach { button ->
                        assertTrue("$theme/$size clips ${button.text}", button.x >= 0 && button.x + button.width <= button.parent.width && button.y >= 0 && button.y + button.height <= button.parent.height)
                    }
                    val run = buttons.single { it.text == message("melos.runScript") }
                    val runBottom = SwingUtilities.convertPoint(run, Point(0, run.height), panel).y
                    val scriptTop = SwingUtilities.convertPoint(surface, Point(0, 0), panel).y
                    assertTrue("Run action should sit beside its script list", scriptTop - runBottom in 0..JBUI.scale(16))

                }
            }
            val empty = MelosPanel(project)
            Disposer.register(testRootDisposable, empty)
            empty.setSize(JBUI.scale(320), JBUI.scale(760)); repeat(3) { layout(empty) }
            val emptyList = components(empty).filterIsInstance<JBList<*>>().single()
            assertEquals(0, emptyList.model.size)
            assertTrue(emptyList.isOpaque)
            assertFalse(components(empty).filterIsInstance<JButton>().single { it.text == message("melos.runScript") }.isEnabled)
            capture(empty, "melos-dark-empty")
        } finally {
            UIManager.setLookAndFeel(previousLaf); JBColor.setDark(!bright)
            path.resolve("modules").toFile().deleteRecursively()
            Files.deleteIfExists(path.resolve("pubspec.yaml"))
            LocalFileSystem.getInstance().findFileByNioFile(path)?.refresh(false, true)
        }
    }
}
