package com.mocaris.plugin.flutter.tools.dependencies.ui.search

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.FlutterToolsSettings
import com.intellij.openapi.application.ReadAction
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.ui.ResultTextArea
import com.mocaris.plugin.flutter.tools.dependencies.*
import com.mocaris.plugin.flutter.tools.dependencies.search.*
import com.mocaris.plugin.flutter.tools.dependencies.ui.PackageDocumentationDialog
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ActionEvent
import javax.swing.*

class PackageSearchPanel(private val project: Project) : JPanel(BorderLayout(0, 8)), Disposable {
    private val service = project.service<PackageSearchService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val query = JBTextField()
    private val model = DefaultListModel<String>()
    private val results = object : JBList<String>(model) {
        override fun getScrollableTracksViewportWidth() = true
    }
    private val infos = mutableMapOf<String, PackageInfo>()
    private val chosenVersions = mutableMapOf<String, String>()
    private val scores = mutableMapOf<String, PackageScore>()
    private val infoFailures = mutableSetOf<String>()
    private val scoreFailures = mutableSetOf<String>()
    private fun displayedInfo(name: String) = infos[name]?.forVersion(chosenVersions[name])
    private val renderer = PackageSearchRenderer(::displayedInfo, { scores[it] }, { it in infoFailures })
    private fun textArea(rows: Int) = ResultTextArea().apply {
        this.rows = rows
        margin = JBUI.insets(6, 12)
        font = UIUtil.getLabelFont()
        addPropertyChangeListener("UI") { font = UIUtil.getLabelFont() }
    }
    private val sdkSummary = textArea(4)
    private val notice = textArea(3)
    private val noticeScroll = JBScrollPane(notice).apply { border = JBUI.Borders.empty() }
    private val status = textArea(1).localized("search.ready")
    private val target = JBLabel()
    private val section = JComboBox(arrayOf("dependencies", "dev_dependencies"))
    private val pubGet = JBCheckBox().localized("search.pubGet")
    private val addButton = button("search.add") { install() }
    private val documentation = button("docs.details") {
        selected?.let { PackageDocumentationDialog(project, it.name, it.version).show() }
    }
    private val recommend = button("sdk.useRecommended") {
        val info = results.selectedValue?.let { infos[it] } ?: return@button
        val sdk = root?.let { ReadAction.compute<ProjectSdk?, RuntimeException> { ProjectSdkReader.read(it) } }
        SdkRecommendation.recommend(info.versions, sdk, project.service<FlutterToolsSettings>().effective().includePrerelease).version?.let { chosenVersions[info.name] = it; results.repaint(); refresh() }
    }
    private val recommendation = textArea(2)
    private val more = button("search.more") { search(true) }
    private val retry = button("search.retry") { retryAction?.invoke() ?: retrySelected() }
    private var root: VirtualFile? = null
    private val selected get() = results.selectedValue?.let(::displayedInfo)
    private var page = 0
    private var currentQuery = ""
    private var hasMore = false
    private var busy = false
    @Volatile private var generation = 0L
    private var searchJob: Job? = null
    private var retryJob: Job? = null
    private var retryAction: (() -> Unit)? = null

    init {
        query.toolTipText = message("search.queryTip")
        query.addActionListener { search(false) }
        add(JPanel(BorderLayout(6, 0)).apply {
            add(query, BorderLayout.CENTER)
            add(button("search.search") { search(false) }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        results.cellRenderer = renderer
        results.addListSelectionListener { if (!it.valueIsAdjusting) refresh() }
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                if (versionAt(event.point)) {
                    showVersions(results.locationToIndex(event.point))
                } else if (titleAt(event.point)) {
                    val index = results.locationToIndex(event.point)
                    BrowserUtil.browse("https://pub.dev/packages/${model.getElementAt(index)}")
                }
            }
            override fun mouseMoved(event: MouseEvent) {
                results.cursor = Cursor.getPredefinedCursor(if (titleAt(event.point) || versionAt(event.point)) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            }
            override fun mouseExited(event: MouseEvent) { results.cursor = Cursor.getDefaultCursor() }
        }
        results.addMouseListener(mouse)
        results.addMouseMotionListener(mouse)
        results.inputMap.put(KeyStroke.getKeyStroke("alt DOWN"), "chooseVersion")
        results.actionMap.put("chooseVersion", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) { showVersions(results.selectedIndex) }
        })
        section.addActionListener { refresh() }
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JBScrollPane(results), BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { add(more); add(retry); add(recommend) })
                add(JPanel(BorderLayout(0, 6)).apply {
                    border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
                    add(JPanel(BorderLayout()).apply {
                        add(JBScrollPane(sdkSummary).apply { border = JBUI.Borders.empty() }, BorderLayout.NORTH)
                        add(JBScrollPane(recommendation).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
                    }, BorderLayout.NORTH)
                    add(noticeScroll, BorderLayout.CENTER)
                    add(JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(JPanel(FlowLayout(FlowLayout.LEFT, 12, 4)).apply { add(target) })
                        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { add(section); add(addButton); add(pubGet); add(documentation) })
                    }, BorderLayout.SOUTH)
                })
            }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        add(JBScrollPane(status).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
        }, BorderLayout.SOUTH)
        refresh()
    }

    private fun titleAt(point: Point): Boolean {
        val index = results.locationToIndex(point)
        if (index < 0) return false
        val bounds = results.getCellBounds(index, index) ?: return false
        if (!bounds.contains(point)) return false
        renderer.getListCellRendererComponent(results, model.getElementAt(index), index, results.isSelectedIndex(index), false)
        return renderer.isTitleAt(Point(point.x - bounds.x, point.y - bounds.y), bounds.width, bounds.height)
    }

    private fun versionAt(point: Point): Boolean {
        val index = results.locationToIndex(point)
        if (index < 0) return false
        val bounds = results.getCellBounds(index, index) ?: return false
        if (!bounds.contains(point) || infos[model.getElementAt(index)]?.versions.isNullOrEmpty()) return false
        renderer.getListCellRendererComponent(results, model.getElementAt(index), index, results.isSelectedIndex(index), false)
        return renderer.versionBounds(bounds.width, bounds.height).contains(Point(point.x - bounds.x, point.y - bounds.y))
    }

    private fun showVersions(index: Int) {
        if (index !in 0 until model.size) return
        val name = model.getElementAt(index)
        val info = displayedInfo(name) ?: return
        if (info.versions.isEmpty()) return
        results.selectedIndex = index
        val cell = results.getCellBounds(index, index) ?: return
        renderer.getListCellRendererComponent(results, name, index, true, false)
        val combo = renderer.versionBounds(cell.width, cell.height)
        val token = generation
        JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<PackageRelease>(message("search.chooseVersion", name), info.versions) {
            init { defaultOptionIndex = info.versions.indexOfFirst { it.version == info.version }.coerceAtLeast(0) }
            override fun getTextFor(value: PackageRelease) = value.version
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(value: PackageRelease, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
                if (token == generation && scope.isActive && infos[name]?.versions?.any { it.version == value.version } == true) {
                    chosenVersions[name] = value.version
                    results.repaint()
                    refresh()
                }
            }
        }).show(RelativePoint(results, Point(cell.x + combo.x, cell.y + combo.y + combo.height)))
    }

    private fun button(key: String, action: () -> Unit) = JButton().localized(key).apply { addActionListener { action() } }
    private fun ui(token: Long, action: () -> Unit) = ApplicationManager.getApplication().invokeLater {
        if (!project.isDisposed && scope.isActive && token == generation) action()
    }

    private suspend fun loadRow(name: String, token: Long) = coroutineScope {
        launch {
            try {
                val info = service.info(name)
                ui(token) { infos[name] = info; infoFailures.remove(name); results.repaint(); refresh() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ui(token) { infoFailures.add(name); results.repaint(); refresh() } }
        }
        launch {
            try {
                val score = service.score(name)
                ui(token) { scores[name] = score; scoreFailures.remove(name); results.repaint(); refresh() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ui(token) { scoreFailures.add(name); results.repaint(); refresh() } }
        }
    }

    private fun search(append: Boolean) {
        val text = if (append) currentQuery else query.text.trim()
        if (text.isBlank()) { status.localized("search.queryTip"); return }
        if (append && (busy || !hasMore)) return
        searchJob?.cancel()
        retryJob?.cancel()
        val token = if (append) generation else ++generation
        val nextPage = if (append) page + 1 else 1
        busy = true; retryAction = null; retry.isEnabled = false; more.isEnabled = false
        status.localized("search.searching", text)
        searchJob = scope.launch {
            try {
                val result = service.search(text, nextPage)
                ui(token) {
                    if (!append) { model.clear(); infos.clear(); chosenVersions.clear(); scores.clear(); infoFailures.clear(); scoreFailures.clear() }
                    currentQuery = text; page = nextPage; hasMore = result.hasMore
                    result.names.filter { !model.contains(it) }.forEach(model::addElement)
                    if (results.selectedIndex < 0 && !model.isEmpty) results.selectedIndex = 0
                    refresh()
                }
                coroutineScope { result.names.map { name -> launch { loadRow(name, token) } }.joinAll() }
                ui(token) { busy = false; status.localized(if (model.isEmpty) "search.empty" else "search.complete", model.size); refresh() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ui(token) { busy = false; status.localized("search.failed", e); retryAction = { search(append) }; refresh() }
            }
        }
    }

    fun selectPackage(value: VirtualFile?) { root = value; refresh() }
    private fun retrySelected() {
        val name = results.selectedValue ?: return
        val token = generation
        retryJob?.cancel()
        retryJob = scope.launch { loadRow(name, token) }
    }
    fun updateLanguage() {
        FlutterToolsBundle.update(this)
        for (area in listOf(sdkSummary, recommendation, notice, status)) FlutterToolsBundle.update(area.componentPopupMenu)
        query.toolTipText = message("search.queryTip")
        results.repaint(); refresh()
    }

    fun refresh() {
        if (project.isDisposed) return
        val current = root?.takeIf { it.isValid }
        val targetName = current?.let { file ->
            project.basePath?.let { base -> if (file.path == base) file.name else file.path.removePrefix("$base/") } ?: file.name
        }
        target.localized("search.target", targetName ?: message("package.none"))
        target.toolTipText = current?.path
        target.foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        notice.foreground = JBColor.namedColor("Label.errorForeground", JBColor(0xB83E35, 0xFF7A70))
        more.isEnabled = hasMore && !busy
        addButton.isEnabled = false
        documentation.isEnabled = selected != null
        addButton.localized("search.add")
        val name = results.selectedValue
        val rowFailed = name in infoFailures || name in scoreFailures
        retry.isEnabled = !busy && (retryAction != null || rowFailed)
        try {
            val info = selected
            val existing = if (current != null && info != null) DependencyInstaller.context(project, current, info.name).dependency else null
            val targetSdk = current?.let { ReadAction.compute<ProjectSdk?, RuntimeException> { ProjectSdkReader.read(it) } }
            val recommended = SdkRecommendation.recommend(name?.let { infos[it]?.versions }.orEmpty(), targetSdk, project.service<FlutterToolsSettings>().effective().includePrerelease)
            recommendation.localized(if (recommended.version == null) "sdk.${recommended.status.name.lowercase()}" else "sdk.recommended", recommended.version)
            recommend.isEnabled = recommended.version != null && recommended.version != info?.version
            val warning = sdkWarning(info)
            val reason = when {
                name == null -> message("search.selectPackage")
                info == null -> message(if (name in infoFailures) "search.detailsUnavailable" else "search.loadingDetails")
                current == null -> message("package.none")
                info.version == null -> message("search.noVersions")
                existing != null && (!existing.isPubDev || existing.overridden || existing.name != info.name) ->
                    message("search.conflict", info.name, if (existing.overridden) "override" else existing.source)
                existing != null && existing.section != section.selectedItem -> message("search.otherSection", info.name, existing.section)
                else -> null
            }
            addButton.localized(if (existing == null) "search.add" else "search.update")
            addButton.isEnabled = reason == null
            addButton.toolTipText = reason
            notice.localized("error.details", listOfNotNull(reason, warning).joinToString("\n"))
            noticeScroll.isVisible = reason != null || warning != null
            if (reason == null) notice.foreground = JBColor.namedColor("Label.warningForeground", JBColor(0x8B6508, 0xE2B450))
        } catch (e: Exception) {
            notice.localized("search.failed", e)
            noticeScroll.isVisible = true
            addButton.toolTipText = notice.text
        }
        revalidate()
    }

    /** SDK checks are advisory and never change whether a dependency can be written. */
    private fun sdkWarning(info: PackageInfo?): String? = try {
        val sdk = ProjectSdkReader.read(project)
        sdkSummary.localized("error.details", if (sdk == null) message("search.mainMissing") else
            message("search.mainSdk", sdk.name, sdk.dart ?: message("search.unrestricted"),
                sdk.dartVersion ?: message("search.unknownVersion"), sdk.flutter ?: message("search.unrestricted"),
                sdk.flutterVersion ?: message("search.unknownVersion")))
        val mismatch = if (info != null) sdk?.mismatches(info).orEmpty() else emptyList()
        when {
            sdk == null -> message("search.mainMissing")
            info == null -> null
            mismatch.isNotEmpty() -> message("search.mainSdkMismatch", info.name, info.version, mismatch.joinToString(" / "))
            info.dartSdk != null && sdk.dartVersion == null || info.flutterSdk != null && sdk.flutterVersion == null -> message("search.sdkUnknown")
            else -> null
        }
    } catch (e: Exception) {
        sdkSummary.localized("search.failed", e)
        message("search.failed", e)
    }

    private fun install() {
        refresh()
        if (!addButton.isEnabled) return
        val current = root?.takeIf { it.isValid } ?: return
        val info = selected ?: return
        val version = info.version ?: return
        try {
            DependencyInstaller.add(project, current, info.name, version, section.selectedItem as String, pubGet.isSelected)
            status.localized("search.added", info.name, current.path)
            refresh()
        } catch (e: Exception) { status.localized("search.failed", e) }
    }

    override fun dispose() { scope.cancel() }
}
