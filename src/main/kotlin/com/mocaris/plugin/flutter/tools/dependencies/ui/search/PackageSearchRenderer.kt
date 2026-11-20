package com.mocaris.plugin.flutter.tools.dependencies.ui.search

import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageInfo
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageScore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import javax.swing.*

/** Padded rows keep package identity distinct from its secondary description. */
internal class PackageSearchRenderer(
    private val info: (String) -> PackageInfo?,
    private val score: (String) -> PackageScore?,
    private val failed: (String) -> Boolean,
) : JPanel(BorderLayout(0, JBUI.scale(6))), ListCellRenderer<String> {
    private val nameLabel = JBLabel()
    // The list handles popup clicks; a label avoids combo-box borders inside a renderer.
    private val version = JBLabel().apply {
        icon = AllIcons.General.ArrowDownSmall
        horizontalTextPosition = SwingConstants.LEFT
        iconTextGap = JBUI.scale(6)
        border = JBUI.Borders.empty(4, 6)
        isOpaque = false
    }
    private val description = JBLabel()
    private val likes = JBLabel().apply { icon = AllIcons.Ide.Like }
    private val points = JBLabel().apply { icon = AllIcons.Nodes.Favorite }
    private val publisher = JBLabel()

    init {
        description.putClientProperty("html.disable", true)
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), JBUI.Borders.empty(10, 14))
        add(JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            add(nameLabel, BorderLayout.CENTER)
            add(version, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(description, BorderLayout.CENTER)
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(likes); add(Box.createHorizontalStrut(JBUI.scale(12))); add(points)
                }, BorderLayout.WEST)
                add(publisher, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(list: JList<out String>, value: String?, index: Int,
                                             isSelected: Boolean, cellHasFocus: Boolean): Component {
        val key = value.orEmpty()
        val data = info(key)
        val rating = score(key)
        val foreground = list.foreground
        val secondary = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        // Blend onto the row background instead of painting translucent Swing components.
        background = if (isSelected) ColorUtil.mix(list.background, list.selectionBackground, 0.3) else list.background
        nameLabel.text = key
        nameLabel.font = list.font.deriveFont(Font.BOLD)
        nameLabel.foreground = if (isSelected) foreground else JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
        version.text = data?.version ?: "—"
        version.font = list.font
        version.foreground = secondary
        version.isEnabled = !data?.versions.isNullOrEmpty()
        version.toolTipText = message("search.chooseVersion", key)
        description.text = data?.description ?: message(if (failed(key)) "search.detailsUnavailable" else "search.loadingDetails")
        description.font = list.font
        description.foreground = secondary
        likes.text = rating?.likes?.toString() ?: "—"
        points.text = "${rating?.points ?: "—"}/${rating?.maxPoints ?: "—"}"
        likes.toolTipText = message("search.likes")
        points.toolTipText = message("search.points")
        publisher.text = rating?.publisher.orEmpty()
        publisher.toolTipText = publisher.text
        for (label in listOf(likes, points, publisher)) {
            label.font = list.font
            label.foreground = secondary
        }
        return this
    }

    /** Only the package name is a link; clicking the rest of the row selects it. */
    fun isTitleAt(point: Point, width: Int, height: Int): Boolean {
        layoutHeader(width, height)
        val bounds = SwingUtilities.convertRectangle(nameLabel.parent, nameLabel.bounds, this)
        return Rectangle(bounds.x, bounds.y, minOf(bounds.width, nameLabel.preferredSize.width), bounds.height).contains(point)
    }

    fun versionBounds(width: Int, height: Int): Rectangle {
        layoutHeader(width, height)
        return SwingUtilities.convertRectangle(version.parent, version.bounds, this)
    }

    private fun layoutHeader(width: Int, height: Int) {
        setBounds(0, 0, width, height)
        doLayout()
        (nameLabel.parent as JPanel).doLayout()
    }
}
