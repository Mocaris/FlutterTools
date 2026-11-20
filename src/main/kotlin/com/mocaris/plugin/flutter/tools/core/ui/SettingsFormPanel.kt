package com.mocaris.plugin.flutter.tools.core.ui

import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable

/** Settings keep their natural height; surplus space belongs below the form. */
class SettingsFormPanel : JPanel(GridBagLayout()), Scrollable {
    private var row = 0

    fun addRow(component: JComponent, gap: Int = 6) = add(component, GridBagConstraints().apply {
        gridx = 0; gridy = row++; weightx = 1.0; anchor = GridBagConstraints.NORTHWEST
        fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insets(gap, 0, 0, 0)
    })

    fun heading(key: String, gap: Int = 16) = addRow(TitledSeparator().apply { label.localized(key) }, gap)

    fun finish() = add(JPanel(), GridBagConstraints().apply { gridy = row; weighty = 1.0; fill = GridBagConstraints.BOTH })

    override fun getPreferredScrollableViewportSize() = preferredSize
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height
}

/** Outlined input surfaces retain their viewport height even in narrow forms. */
class SettingsContentPane(private val content: JComponent) : JBScrollPane(content) {
    init {
        content.isOpaque = true
        content.border = JBUI.Borders.empty(8)
        border = JBUI.Borders.customLine(JBColor.border())
        viewportBorder = null; viewport.background = content.background
        content.addPropertyChangeListener("background") { viewport.background = content.background }
    }

    override fun getMinimumSize() = Dimension(0, preferredSize.height)
}
