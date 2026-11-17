package com.mocaris.plugin.flutter.tools.dependencies.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.LanguageListener
import com.mocaris.plugin.flutter.tools.dependencies.PackageDocuments
import com.mocaris.plugin.flutter.tools.dependencies.search.PackageSearchService
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.event.HyperlinkEvent

/** Documents load on demand; closing the viewer cancels requests and releases rendered HTML. */
class PackageDocumentationDialog(private val project: Project, private val name: String, version: String? = null,
                                 initialTab: String = "readme") : DialogWrapper(project, false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val service = project.service<PackageSearchService>()
    private val versions = JComboBox<String>()
    private val status = JBLabel().localized("docs.loading")
    private val tabs = JBTabbedPane()
    private val kinds = listOf("readme", "changelog")
    private val editors = kinds.map { JEditorPane("text/html", "").apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        border = JBUI.Borders.empty(8)
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                if (event.description?.startsWith('#') == true) scrollToReference(event.description.removePrefix("#"))
                else event.url?.takeIf { it.protocol in setOf("https", "http") }?.let { BrowserUtil.browse(it) }
            }
        }
    } }
    private val jobs = mutableMapOf<Int, Job>()
    private val loaded = mutableMapOf<Int, String>()
    @Volatile private var closed = false
    private val content = JPanel(BorderLayout(0, 8)).apply {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(520))
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JBLabel().localized("docs.version")); add(versions)
                add(JButton().localized("docs.openBrowser").apply { addActionListener {
                    BrowserUtil.browse(PackageDocuments.url(name, versions.selectedItem as? String, kinds[tabs.selectedIndex]))
                } })
            }, BorderLayout.NORTH)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        kinds.forEachIndexed { index, kind -> tabs.addTab(message("docs.$kind"), JBScrollPane(editors[index])) }
        add(tabs, BorderLayout.CENTER)
    }

    init {
        title = message("docs.title", name)
        isModal = false
        version?.let { versions.addItem(it) }
        tabs.selectedIndex = kinds.indexOf(initialTab).coerceAtLeast(0)
        init()
        setCancelButtonText(message("docs.close"))
        tabs.addChangeListener { load() }
        versions.addActionListener {
            jobs.values.forEach { it.cancel() }; jobs.clear(); loaded.clear()
            editors.forEach { it.text = "" }
            load()
        }
        project.messageBus.connect(disposable).subscribe(LanguageListener.TOPIC, LanguageListener {
            ApplicationManager.getApplication().invokeLater {
                if (!closed) {
                    title = message("docs.title", name); setCancelButtonText(message("docs.close"))
                    FlutterToolsBundle.update(content)
                    kinds.forEachIndexed { index, kind -> tabs.setTitleAt(index, message("docs.$kind")) }
                }
            }
        })
        load()
        scope.launch {
            try {
                val info = service.info(name)
                ApplicationManager.getApplication().invokeLater {
                    if (closed) return@invokeLater
                    val selected = versions.selectedItem as? String ?: info.version
                    versions.model = DefaultComboBoxModel((listOfNotNull(selected) + info.versions.map { it.version }).distinct().toTypedArray())
                    versions.selectedItem = selected
                    if (info.discontinued) status.localized(if (info.replacedBy == null) "docs.discontinued" else "docs.replaced", info.replacedBy ?: "")
                    else status.localized("docs.active")
                    load()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ApplicationManager.getApplication().invokeLater { if (!closed) status.localized("docs.failed", e) } }
        }
    }

    private fun load() {
        val version = versions.selectedItem as? String ?: return
        val index = tabs.selectedIndex
        val key = "$version:${kinds[index]}"
        if (loaded[index] == key || jobs[index]?.isActive == true) return
        editors[index].text = "<html>" + StringUtil.escapeXmlEntities(message("docs.loading")) + "</html>"
        jobs[index] = scope.launch {
            try {
                val document = service.document(name, version, kinds[index])
                ApplicationManager.getApplication().invokeLater {
                    if (closed || versions.selectedItem != version) return@invokeLater
                    editors[index].text = "<html><body>" + document.html.ifBlank { StringUtil.escapeXmlEntities(message("docs.empty")) } + "</body></html>"
                    editors[index].caretPosition = 0
                    loaded[index] = key
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ApplicationManager.getApplication().invokeLater {
                if (!closed && versions.selectedItem == version) editors[index].text = "<html>" + StringUtil.escapeXmlEntities(message("docs.failed", e)) + "</html>"
            } }
        }
    }
    override fun createCenterPanel(): JComponent = content
    override fun createActions(): Array<Action> = arrayOf(cancelAction)
    override fun dispose() {
        closed = true; scope.cancel(); editors.forEach { it.text = "" }; super.dispose()
    }
}
