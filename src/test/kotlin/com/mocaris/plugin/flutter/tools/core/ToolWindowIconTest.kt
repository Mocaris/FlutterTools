package com.mocaris.plugin.flutter.tools.core

import com.intellij.util.SVGLoader
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class ToolWindowIconTest {
    @Test fun toolWindowRegistersAnExistingIconBeforeContentCreation() {
        val xml = Files.newInputStream(Path.of("src/main/resources/META-INF/plugin.xml"))
        val plugin = xml.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val toolWindow = plugin.getElementsByTagName("toolWindow").item(0)
        val path = toolWindow.attributes.getNamedItem("icon").nodeValue
        assertEquals("/icons/flutterTools.svg", path)
        assertNotNull(javaClass.getResource(path))
        assertNotNull(javaClass.getResource(path.replace(".svg", "_dark.svg")))
    }

    @Test fun bothThemeIconsLoadAndPaintAtSixteenPixels() {
        listOf("flutterTools", "flutterTools_dark").forEach { name ->
            val url = javaClass.getResource("/icons/$name.svg")!!
            val icon = SVGLoader.load(url, 1f)
            assertEquals(16, icon.getWidth(null)); assertEquals(16, icon.getHeight(null))
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { graphics.drawImage(icon, 0, 0, null) } finally { graphics.dispose() }
            assertTrue((0 until 16).sumOf { x -> (0 until 16).count { y -> image.getRGB(x, y).ushr(24) > 0 } } > 20)
            val preview = Path.of("build/offline/icons/$name.png")
            Files.createDirectories(preview.parent)
            ImageIO.write(image, "png", preview.toFile())
        }
    }
}
