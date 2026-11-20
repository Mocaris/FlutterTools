package com.mocaris.plugin.flutter.tools.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.assets.AssetsSyncService
import com.mocaris.plugin.flutter.tools.assets.ui.AssetsConfigurationPanel
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsLanguageSelector
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsLanguageSettings
import com.mocaris.plugin.flutter.tools.core.localization.LanguageListener
import com.mocaris.plugin.flutter.tools.core.localization.PluginLanguage
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.dependencies.ui.DependenciesPanel
import com.mocaris.plugin.flutter.tools.dependencies.ui.search.PackageSearchPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/** Coordinates package selection only; each feature owns its own page. */
class FlutterToolsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    enum class Page { ASSETS, PACKAGES, SEARCH, SETTINGS, MELOS }

    companion object {
        fun open(project: Project, page: Page? = null, target: VirtualFile? = null, onShown: () -> Unit = {}) {
            val window = ToolWindowManager.getInstance(project).getToolWindow("FlutterTools") ?: return
            window.activate {
                if (project.isDisposed) return@activate
                val panel = window.contentManager.contents.firstNotNullOfOrNull { it.component as? FlutterToolsPanel }
                if (panel?.showPage(page, target) == true) onShown()
            }
        }
    }
    private val resourceGeneration = project.service<AssetsSyncService>()
    private val settings = project.service<FlutterToolsSettings>()
    private val languages = FlutterToolsLanguageSelector(this)
    private val languageSettings = ApplicationManager.getApplication().getService(FlutterToolsLanguageSettings::class.java)
    private val tabs = JBTabbedPane()
    private val preferences = ProjectPreferencesPanel(project)
    private val packages = ComboBox<VirtualFile>()
    private val melos = com.mocaris.plugin.flutter.tools.workspace.ui.MelosPanel(project)
    private val assets = AssetsConfigurationPanel(project)
    private val dependencies = DependenciesPanel(project)
    private val search = PackageSearchPanel(project)
    private var root: VirtualFile? = null
    private var loading = false
    private val timer = Timer(1000) { refresh() }
    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        packages.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean): java.awt.Component {
                val label = super.getListCellRendererComponent(list, value, index, selected, focused) as JLabel
                label.text = (value as? VirtualFile)?.let { file -> project.basePath?.let { file.path.removePrefix(it).trimStart('/').ifEmpty { file.name } } ?: file.path } ?: message("package.discovering")
                return label
            }
        }
        packages.minimumSize = Dimension(0, packages.preferredSize.height)
        languages.selectedItem = languageSettings.state.language
        languages.addActionListener { languageSettings.setLanguage(languages.selectedItem as PluginLanguage) }
        add(JPanel().apply {
            layout = GridBagLayout()
            border = JBUI.Borders.empty(4, 4, 8, 4)
            listOf("package.label" to packages, "language.label" to languages).forEachIndexed { row, (key, selector) ->
                add(JBLabel().localized(key).apply { labelFor = selector }, GridBagConstraints().apply {
                    gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = JBUI.insets(3, 0, 3, 8)
                })
                add(selector, GridBagConstraints().apply {
                    gridx = 1; gridy = row; weightx = 1.0; anchor = GridBagConstraints.WEST
                    fill = if (row == 0) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE; insets = JBUI.insets(3, 0)
                })
            }
        }, BorderLayout.NORTH)
        tabs.addTab(message("tab.assets"), assets); tabs.addTab(message("tab.packages"), dependencies)
        tabs.addTab(message("tab.search"), search)
        tabs.addTab(message("tab.settings"), preferences)
        tabs.addTab("Melos", melos)
        add(tabs, BorderLayout.CENTER)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LanguageListener.TOPIC, LanguageListener {
            languages.selectedItem = languageSettings.state.language
            FlutterToolsBundle.update(this)
            tabs.setTitleAt(0, message("tab.assets")); tabs.setTitleAt(1, message("tab.packages"))
            tabs.setTitleAt(2, message("tab.search")); tabs.setTitleAt(3, message("tab.settings"))
            preferences.updateLanguage()
            melos.updateLanguage()
            assets.updateLanguage(); dependencies.updateLanguage()
            search.updateLanguage()
            languages.repaint(); packages.repaint()
            revalidate(); repaint()
        })
        packages.addActionListener { if (!loading) select(packages.selectedItem as? VirtualFile) }
        tabs.addChangeListener { if (tabs.selectedComponent === search) search.refresh() }
        refresh(); timer.start()
    }
    private fun select(next: VirtualFile?) {
        if (next?.path == root?.path) return
        if (root?.isValid == true && !assets.canSwitchPackage()) {
            loading = true; packages.selectedItem = root; loading = false; return
        }
        root = next
        packages.toolTipText = next?.path
        settings.state.selectedPackagePath = next?.path.orEmpty()
        assets.selectPackage(next); dependencies.selectPackage(next)
        search.selectPackage(next)
    }
    internal fun showPage(page: Page?, target: VirtualFile? = null): Boolean {
        if (target != null && !target.isValid) return false
        if (target == null) refresh()
        if (target != null && root?.path != target.path) {
            select(target)
            if (root?.path != target.path) return false
            loading = true
            try {
                if ((0 until packages.itemCount).none { packages.getItemAt(it).path == target.path }) packages.addItem(target)
                packages.selectedItem = target
            } finally { loading = false }
        }
        page?.let { tabs.selectedIndex = it.ordinal }
        when (page) {
            Page.ASSETS -> assets.refresh()
            Page.PACKAGES -> dependencies.refresh()
            Page.SEARCH -> { search.refresh(); search.focusQuery() }
            Page.MELOS -> melos.refresh(root, resourceGeneration.roots.filter { it.isValid })
            else -> Unit
        }
        return true
    }
    private fun refresh() {
        if (project.isDisposed) return
        val available = resourceGeneration.roots.filter { it.isValid }
        if ((0 until packages.itemCount).map { packages.getItemAt(it).path } != available.map { it.path }) {
            loading = true
            packages.removeAllItems(); available.forEach { packages.addItem(it) }
            packages.selectedItem = available.firstOrNull { it.path == (root?.path ?: settings.state.selectedPackagePath) } ?: available.firstOrNull()
            loading = false
            select(packages.selectedItem as? VirtualFile)
        }
        assets.refresh(); dependencies.refresh()
        if (search.isShowing) search.refresh()
        melos.refresh(root, available)
    }
    override fun dispose() { timer.stop(); search.dispose(); com.intellij.openapi.util.Disposer.dispose(preferences); melos.dispose() }
}
