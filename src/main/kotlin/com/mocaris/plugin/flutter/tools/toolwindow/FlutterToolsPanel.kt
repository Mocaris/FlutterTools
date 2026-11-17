package com.mocaris.plugin.flutter.tools.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.mocaris.plugin.flutter.tools.assets.ui.AssetsConfigurationPanel
import com.mocaris.plugin.flutter.tools.dependencies.ui.DependenciesPanel
import com.mocaris.plugin.flutter.tools.dependencies.ui.search.PackageSearchPanel
import com.mocaris.plugin.flutter.tools.assets.AssetsSyncService
import com.mocaris.plugin.flutter.tools.core.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.FlutterToolsLanguageSettings
import com.mocaris.plugin.flutter.tools.core.PluginLanguage
import com.mocaris.plugin.flutter.tools.core.LanguageListener
import com.mocaris.plugin.flutter.tools.core.FlutterToolsLanguageSelector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/** Coordinates package selection only; each feature owns its own page. */
class FlutterToolsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val resourceGeneration = project.service<AssetsSyncService>()
    private val settings = project.service<FlutterToolsSettings>()
    private val languages = FlutterToolsLanguageSelector(this)
    private val languageSettings = ApplicationManager.getApplication().getService(FlutterToolsLanguageSettings::class.java)
    private val tabs = JBTabbedPane()
    private val preferences = ProjectPreferencesPanel(project)
    private val packages = ComboBox<VirtualFile>()
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
        packages.preferredSize = Dimension(JBUI.scale(220), packages.preferredSize.height)
        packages.minimumSize = Dimension(JBUI.scale(80), packages.preferredSize.height)
        packages.maximumSize = Dimension(JBUI.scale(240), packages.preferredSize.height)
        languages.selectedItem = languageSettings.state.language
        languages.addActionListener { languageSettings.setLanguage(languages.selectedItem as PluginLanguage) }
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel().localized("package.label"))
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(packages)
            add(Box.createHorizontalStrut(JBUI.scale(12)))
            add(JBLabel().localized("language.label"))
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(languages)
            add(Box.createHorizontalGlue())
        }, BorderLayout.NORTH)
        tabs.addTab(message("tab.assets"), assets); tabs.addTab(message("tab.packages"), dependencies)
        tabs.addTab(message("tab.search"), search)
        tabs.addTab(message("tab.settings"), preferences)
        add(tabs, BorderLayout.CENTER)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LanguageListener.TOPIC, LanguageListener {
            languages.selectedItem = languageSettings.state.language
            FlutterToolsBundle.update(this)
            tabs.setTitleAt(0, message("tab.assets")); tabs.setTitleAt(1, message("tab.packages"))
            tabs.setTitleAt(2, message("tab.search")); tabs.setTitleAt(3, message("tab.settings"))
            preferences.updateLanguage()
            assets.updateLanguage(); dependencies.updateLanguage()
            search.updateLanguage()
            languages.repaint(); packages.repaint()
            revalidate(); repaint()
        })
        packages.addActionListener { if (!loading) select(packages.selectedItem as? VirtualFile) }
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
        search.refresh()
    }
    override fun dispose() { timer.stop(); search.dispose(); com.intellij.openapi.util.Disposer.dispose(preferences) }
}
