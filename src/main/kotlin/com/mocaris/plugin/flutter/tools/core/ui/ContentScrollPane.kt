package com.mocaris.plugin.flutter.tools.core.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBValue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/** Shared display surface: theme input corners, inner spacing and no outline. */
class ContentScrollPane(private val content: JComponent) : JBScrollPane(content) {
    private val arc = JBValue.UIInteger("Component.arc", 5)

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8)
        viewportBorder = null
        viewport.background = content.background
        content.addPropertyChangeListener("background") { viewport.background = content.background; repaint() }
    }

    override fun isPaintingOrigin() = true
    override fun paint(graphics: Graphics) {
        val canvas = graphics.create() as Graphics2D
        try {
            val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.float, arc.float)
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.color = content.background
            canvas.fill(shape)
            canvas.clip(shape)
            super.paint(canvas)
        } finally { canvas.dispose() }
    }
}
