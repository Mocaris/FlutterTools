package com.mocaris.plugin.flutter.tools.dependencies.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.ui.SettingsContentPane
import com.mocaris.plugin.flutter.tools.core.ui.ResultTextArea
import com.mocaris.plugin.flutter.tools.dependencies.*
import com.mocaris.plugin.flutter.tools.dependencies.versions.DependencyResult
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageVersionService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Dependency preferences and cached results; HTTP work stays in PackageVersionService. */
class DependenciesPanel(private val project: Project) : JPanel(BorderLayout(0, 8)) {
    private val versions = project.service<PackageVersionService>()
    private val columns = listOf("packages.column.package", "packages.column.type", "packages.column.constraint", "packages.column.resolved", "packages.column.latest", "packages.column.recommended", "packages.column.status")
    private val status = ResultTextArea().apply { rows = 2 }.localized("ready")
    private val model = object : DefaultTableModel(columns.map { message(it) }.toTypedArray(), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private var displayedResults: List<DependencyResult> = emptyList()
    private val table = object : JBTable(model) {
        override fun getScrollableTracksViewportWidth() = parent != null && parent.width >= minimumSize.width
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val column = columnAtPoint(event.point)
            return displayedResults.getOrNull(if (row >= 0) convertRowIndexToModel(row) else -1)?.let { result ->
                listOfNotNull(if (column >= 0) getValueAt(row, column)?.toString() else null, result.status, result.recommendation.version?.let { message("sdk.recommended", it) }
                    ?: message("sdk.${result.recommendation.status.name.lowercase()}" )).joinToString(" · ")
            }
        }
    }
    private var root: VirtualFile? = null
    private var visibleState: PackageVersionService.PackageState? = null

    init {
        border = JBUI.Borders.empty(12)
        val preferences = JPanel(BorderLayout(8, 0)).apply {
            add(TitledSeparator().apply { label.localized("packages.dependencies") }, BorderLayout.CENTER)
            add(JButton().localized("packages.refresh").apply { addActionListener { root?.let { versions.refresh(it) } } }, BorderLayout.EAST)
        }
        add(preferences, BorderLayout.NORTH)
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.setAutoCreateRowSorter(true)
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.emptyText.text = message("packages.empty")
        table.setDefaultRenderer(Object::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
                super.getTableCellRendererComponent(table, value, selected, focused, row, column)
                border = JBUI.Borders.empty(4, 8)
                val result = displayedResults.getOrNull(table.convertRowIndexToModel(row))
                if (!selected) foreground = if (column == 0 && result?.dependency?.isPubDev == true)
                    JBColor.namedColor("Link.activeForeground", JBColor.BLUE) else table.foreground
                return this
            }
        })
        sizeColumns()
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
                val row = table.rowAtPoint(event.point)
                if (row < 0) return
                val result = displayedResults.getOrNull(table.convertRowIndexToModel(row)) ?: return
                if (table.columnAtPoint(event.point) == 0 && result.dependency.isPubDev) BrowserUtil.browse("https://pub.dev/packages/${result.dependency.packageName}")
                if (table.columnAtPoint(event.point) in listOf(4, 5)) root?.let { DependencyUpdatePopup.show(project, it, result, event) }
            }
        })
        add(JBScrollPane(table).apply {
            border = JBUI.Borders.customLine(JBColor.border())
            setColumnHeaderView(table.tableHeader)
        }, BorderLayout.CENTER)
        add(SettingsContentPane(status), BorderLayout.SOUTH)
    }
    private fun sizeColumns() {
        table.rowHeight = table.getFontMetrics(table.font).height + JBUI.scale(10)
        val widths = listOf(160, 60, 100, 80, 80, 140, 200)
        table.columnModel.columns.toList().forEachIndexed { index, column ->
            val header = table.tableHeader.getFontMetrics(table.tableHeader.font).stringWidth(message(columns[index])) + JBUI.scale(20)
            column.minWidth = maxOf(JBUI.scale(if (index == 0) 120 else if (index == 6) 140 else 56), header)
            column.preferredWidth = maxOf(column.minWidth, JBUI.scale(widths[index]))
        }
    }
    fun updateLanguage() {
        FlutterToolsBundle.update(this)
        FlutterToolsBundle.update(status.componentPopupMenu)
        columns.forEachIndexed { index, key -> table.columnModel.getColumn(index).headerValue = message(key) }
        table.emptyText.text = message("packages.empty")
        sizeColumns()
        table.tableHeader.repaint()
        visibleState = null
        refresh()
    }
    fun selectPackage(value: VirtualFile?) { root = value; visibleState = null; refresh() }
    fun refresh() {
        val current = root ?: return
        val state = versions.state(current)
        status.localized("error.details", state.message)
        if (state != visibleState) {
            visibleState = state; displayedResults = state.results
            model.rowCount = 0
            state.results.forEach { result -> model.addRow(arrayOf(result.dependency.name,
                when (result.dependency.section) { "dev_dependencies" -> message("packages.type.dev"); "dependencies" -> message("packages.type.direct"); else -> message("packages.type.override") },
                result.dependency.constraint, result.installed ?: "—", result.latest ?: "—", result.recommendation.version ?: message("sdk.${result.recommendation.status.name.lowercase()}"), result.status)) }
        }
    }
}
