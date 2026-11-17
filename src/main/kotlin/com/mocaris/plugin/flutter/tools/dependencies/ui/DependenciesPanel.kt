package com.mocaris.plugin.flutter.tools.dependencies.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.ui.ResultTextArea
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.dependencies.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel

/** Dependency preferences and cached results; HTTP work stays in PackageVersionService. */
class DependenciesPanel(private val project: Project) : JPanel(BorderLayout(0, 8)) {
    private val versions = project.service<PackageVersionService>()
    private val columns = listOf("packages.column.package", "packages.column.type", "packages.column.constraint", "packages.column.resolved", "packages.column.latest", "packages.column.recommended", "packages.column.status")
    private val status = ResultTextArea().localized("ready")
    private val model = object : DefaultTableModel(columns.map { message(it) }.toTypedArray(), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private var displayedResults: List<DependencyResult> = emptyList()
    private val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            return displayedResults.getOrNull(if (row >= 0) convertRowIndexToModel(row) else -1)?.let { result ->
                listOf(result.status, result.recommendation.version?.let { message("sdk.recommended", it) }
                    ?: message("sdk.${result.recommendation.status.name.lowercase()}" )).joinToString(" · ")
            }
        }
    }
    private var root: VirtualFile? = null
    private var visibleState: PackageVersionService.PackageState? = null

    init {
        val preferences = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton().localized("packages.refresh").apply { addActionListener { root?.let { versions.refresh(it) } } })
        }
        add(preferences, BorderLayout.NORTH)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setAutoCreateRowSorter(true)
        table.columnModel.columns.toList().forEachIndexed { index, column -> column.preferredWidth = if (index == 6) 350 else 120 }
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
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(JBScrollPane(status), BorderLayout.SOUTH)
    }
    fun updateLanguage() {
        FlutterToolsBundle.update(this)
        FlutterToolsBundle.update(status.componentPopupMenu)
        columns.forEachIndexed { index, key -> table.columnModel.getColumn(index).headerValue = message(key) }
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
