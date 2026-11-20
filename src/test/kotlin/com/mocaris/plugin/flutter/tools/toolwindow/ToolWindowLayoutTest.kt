package com.mocaris.plugin.flutter.tools.toolwindow

import com.intellij.ide.ui.laf.IntelliJLaf
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBList
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.text
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsLanguageSelector
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsLanguageSettings
import com.mocaris.plugin.flutter.tools.core.localization.PluginLanguage
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.core.ui.SettingsFormPanel
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageInfo
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageRelease
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageScore
import com.mocaris.plugin.flutter.tools.dependencies.pub.PubDevClient
import com.mocaris.plugin.flutter.tools.dependencies.sdk.SdkRecommendation
import com.mocaris.plugin.flutter.tools.dependencies.ui.DependenciesPanel
import com.mocaris.plugin.flutter.tools.dependencies.ui.search.PackageSearchPanel
import com.mocaris.plugin.flutter.tools.dependencies.ui.search.PackageSearchRenderer
import com.mocaris.plugin.flutter.tools.dependencies.versions.DependencyResult
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageDependency
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageVersionService
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*

class ToolWindowLayoutTest : BasePlatformTestCase() {
    private var previousLaf: LookAndFeel? = null
    override fun setUp() {
        super.setUp()
        previousLaf = UIManager.getLookAndFeel()
        UIManager.setLookAndFeel(IntelliJLaf())
    }
    override fun tearDown() {
        try { super.tearDown() } finally { UIManager.setLookAndFeel(previousLaf) }
    }
    private fun components(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::components)

    private fun layout(component: Component) {
        if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).run { isAccessible = true; get(owner) as T }

    private fun root(): VirtualFile {
        project.service<FlutterToolsSettings>().state.apply { useGlobalSettings = false; automaticChecks = false }
        val path = Path.of(project.basePath!!)
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString())
        Files.createDirectories(path.resolve("modules/login"))
        Files.createDirectories(path.resolve(".dart_tool"))
        Files.writeString(path.resolve("pubspec.yaml"), "name: flutter_test_app\nworkspace: [modules/login]\nenvironment:\n  sdk: ^3.9.2\n")
        Files.writeString(path.resolve("modules/login/pubspec.yaml"), "name: login\nresolution: workspace\nenvironment:\n  sdk: ^3.9.2\n")
        Files.writeString(path.resolve(".dart_tool/package_config.json"), """{"configVersion":2,"packages":[],"generatorVersion":"3.9.2"}""")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        root.refresh(false, true)
        return root.findFileByRelativePath("modules/login")!!
    }

    private fun capture(panel: JComponent, name: String) {
        if (!java.lang.Boolean.getBoolean("fluttertools.render.ui")) return
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { panel.printAll(graphics) } finally { graphics.dispose() }
        val path = Path.of("build/ui-preview/$name.png")
        Files.createDirectories(path.parent); ImageIO.write(image, "png", path.toFile())
    }

    private fun checkControls(panel: JComponent) {
        components(panel).filterIsInstance<AbstractButton>().filter {
            !it.text.isNullOrEmpty() && it.isVisible && generateSequence(it.parent) { parent -> parent.parent }.takeWhile { parent -> parent !== panel }.all { parent -> parent.isVisible }
        }.forEach { button ->
            assertTrue("clipped ${button.text}", button.x >= 0 && button.x + button.width <= button.parent.width &&
                button.y >= 0 && button.y + button.height <= button.parent.height)
            assertTrue("label clipped ${button.text}", button.width >= button.preferredSize.width)
        }
    }

    private fun sampleResults(root: VirtualFile) {
        val rows = listOf(
            DependencyResult(PackageDependency("cupertino_icons", section = "dependencies", constraint = "^1.0.8", source = "pub.dev"),
                "1.0.9", "2.0.0", text("packages.changeConstraint"), recommendation = SdkRecommendation.Result("1.0.9", SdkRecommendation.Status.COMPATIBLE)),
            DependencyResult(PackageDependency("flutter_lints", section = "dev_dependencies", constraint = "^5.0.0", source = "pub.dev"),
                "5.0.0", "6.0.0", text("packages.changeConstraint"), recommendation = SdkRecommendation.Result("6.0.0", SdkRecommendation.Status.COMPATIBLE)))
        val service = project.service<PackageVersionService>()
        field<MutableMap<String, PackageVersionService.PackageState>>(service, "states")[root.path] =
            PackageVersionService.PackageState(root, rows, text("packages.complete"))
    }

    private fun selectResult(panel: PackageSearchPanel) {
        val releases = listOf(PackageRelease("1.2.0", "A composable, cross-platform HTTP client for Dart.", "^3.0.0", null),
            PackageRelease("1.1.0", "A composable HTTP client.", "^3.0.0", null))
        field<MutableMap<String, PackageInfo>>(panel, "infos")["http"] = PackageInfo("http", PubDevClient.Metadata("1.2.0", "1.2.0", releases),
            "1.2.0", releases.first().description, "^3.0.0", null, null, null, null, releases)
        field<DefaultListModel<String>>(panel, "model").addElement("http")
        field<JList<String>>(panel, "results").selectedIndex = 0
        panel.refresh()
    }

    private fun themes(action: (String) -> Unit) {
        val previous = UIManager.getLookAndFeel()
        val bright = JBColor.isBright()
        val languages = service<FlutterToolsLanguageSettings>()
        val language = languages.state.language
        try {
            languages.setLanguage(PluginLanguage.ENGLISH)
            for ((name, laf) in listOf("light" to IntelliJLaf(), "dark" to DarculaLaf())) {
                UIManager.setLookAndFeel(laf); JBColor.setDark(name == "dark")
                action(name)
            }
        } finally { languages.setLanguage(language); UIManager.setLookAndFeel(previous); JBColor.setDark(!bright) }
    }

    fun testPackageHeaderAndDependencyTableFitWideAndNarrowWindows() {
        val root = root()
        sampleResults(root)
        themes { theme ->
            val panel = FlutterToolsPanel(project)
            Disposer.register(testRootDisposable, panel)
            try {
                panel.showPage(FlutterToolsPanel.Page.PACKAGES, root)
                panel.addNotify()
                val tabs = components(panel).filterIsInstance<JTabbedPane>().single()
                val page = tabs.selectedComponent as DependenciesPanel
                val table = components(page).filterIsInstance<JTable>().single()
                assertEquals(2, table.rowCount)
                assertEquals(JTabbedPane.WRAP_TAB_LAYOUT, tabs.tabLayoutPolicy)
                for ((size, width, height) in listOf(Triple("narrow", 320, 760), Triple("wide", 820, 1067))) {
                    panel.setSize(width, height); repeat(4) { layout(panel) }
                    capture(panel, "packages-$theme-$size")
                    checkControls(page)
                    val selector = components(panel).filterIsInstance<FlutterToolsLanguageSelector>().single()
                    val header = selector.parent
                    components(header).filterIsInstance<JComboBox<*>>().forEach {
                        assertTrue("$theme/$size selector clipped", it.width >= 150 && it.x + it.width <= header.width)
                    }
                    val surface = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, table) as JBScrollPane
                    assertEquals(size == "narrow", surface.horizontalScrollBar.isVisible)
                    assertTrue(table.rowHeight >= table.getFontMetrics(table.font).height + 8)
                    table.columnModel.columns.toList().forEach {
                        assertTrue("header clipped ${it.headerValue}", it.width >= table.tableHeader.getFontMetrics(table.tableHeader.font).stringWidth(it.headerValue.toString()) + 16)
                    }
                }
                val cell = table.getCellRect(0, 6, true)
                val event = MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, cell.x + 2, cell.y + 2, 0, 0, 0, false, MouseEvent.NOBUTTON)
                assertTrue(table.getToolTipText(event).contains(message("packages.changeConstraint")))
            } finally { panel.removeNotify(); Disposer.dispose(panel) }
        }
    }

    fun testSearchEmptyAndSelectedLayoutsKeepAllSettingsReachable() {
        val root = root()
        themes { theme ->
            val panel = FlutterToolsPanel(project)
            Disposer.register(testRootDisposable, panel)
            try {
                panel.showPage(FlutterToolsPanel.Page.SEARCH, root)
                panel.addNotify()
                val page = components(panel).filterIsInstance<PackageSearchPanel>().single()
                for (selected in listOf(false, true)) {
                    if (selected) selectResult(page)
                    for ((size, width, height) in listOf(Triple("narrow", 320, 760), Triple("wide", 831, 1070))) {
                        panel.setSize(width, height); repeat(4) { layout(panel) }
                        capture(panel, "search-${if (selected) "selected" else "empty"}-$theme-$size")
                        checkControls(page)
                        val details = components(page).filterIsInstance<SettingsFormPanel>().single()
                        val surface = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, details) as JBScrollPane
                        assertFalse("settings must scroll vertically", surface.horizontalScrollBar.isVisible)
                        if (selected) {
                            assertTrue(components(page).filterIsInstance<JButton>().single { it.text == message("search.add") }.isEnabled)
                            val button = field<JButton>(page, "recommend")
                            val bounds = SwingUtilities.convertRectangle(button.parent, button.bounds, details)
                            details.scrollRectToVisible(bounds)
                            assertTrue("compatible version action unreachable", surface.viewport.viewRect.intersects(bounds))
                            capture(panel, "search-selected-$theme-$size-sdk")
                        }
                    }
                }
            } finally { panel.removeNotify(); Disposer.dispose(panel) }
        }
    }

    fun testSearchSelectionAndDependencyTypeStillControlTheWrittenDependency() {
        val root = root()
        val panel = PackageSearchPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.selectPackage(root)
        assertFalse(field<JButton>(panel, "addButton").isEnabled)
        assertFalse(field<JBScrollPane>(panel, "noticeScroll").isVisible)
        components(panel).filterIsInstance<JBTextField>().single().postActionEvent()
        assertTrue(components(panel).filterIsInstance<JTextArea>().any { it.text == message("search.queryTip") })
        selectResult(panel)
        field<MutableMap<String, String>>(panel, "chosenVersions")["http"] = "1.1.0"
        field<JComboBox<String>>(panel, "section").selectedItem = "dev_dependencies"
        panel.refresh()
        val details = field<JTextArea>(panel, "releaseDetails").text
        assertTrue(details.contains(message("search.selectedVersion", "1.1.0")))
        assertTrue(details.contains(message("search.latestVersion", "1.2.0")))
        field<JButton>(panel, "addButton").doClick()
        val document = FileDocumentManager.getInstance().getDocument(root.findChild("pubspec.yaml")!!)!!
        assertTrue(document.text.contains("dev_dependencies:\n  http: ^1.1.0"))
        field<JComboBox<String>>(panel, "section").selectedItem = "dependencies"
        assertFalse(field<JButton>(panel, "addButton").isEnabled)
        assertTrue(field<JBScrollPane>(panel, "noticeScroll").isVisible)
    }

    fun testLongSelectedRowsStayInsideTheListAndPreserveFullReleaseDetails() {
        val root = root()
        val description = "A reactive caching and data-binding framework. Riverpod makes working with asynchronous code a breeze and offers a flexible way to manage state."
        val names = listOf("riverpod", "hooks_riverpod", "riverbloc", "flutter_riverpod", "riverpod_annotation", "riverpod_generator",
            "riverpod_lint", "riverpod_test", "riverpod_hooks", "riverpod_devtools")
        val releases = listOf(PackageRelease("3.5.0", description, "^3.12.0", null), PackageRelease("3.3.2", description, "^3.9.0", null))
        themes { theme ->
            val panel = FlutterToolsPanel(project)
            Disposer.register(testRootDisposable, panel)
            try {
                panel.showPage(FlutterToolsPanel.Page.SEARCH, root)
                panel.addNotify()
                val page = components(panel).filterIsInstance<PackageSearchPanel>().single()
                for (name in names) {
                    field<MutableMap<String, PackageInfo>>(page, "infos")[name] = PackageInfo(name, PubDevClient.Metadata("3.5.0", "3.5.0", releases),
                        "3.5.0", description, "^3.12.0", null, null, null, null, releases)
                    field<MutableMap<String, PackageScore>>(page, "scores")[name] = PackageScore(4045, 160, 160, "dash-overflow.net")
                    field<DefaultListModel<String>>(page, "model").addElement(name)
                }
                field<JBTextField>(page, "query").text = "riverpod"
                page.javaClass.getDeclaredField("currentQuery").apply { isAccessible = true }.set(page, "riverpod")
                field<JTextArea>(page, "status").text = message("search.complete", names.size)
                val list = field<JBList<String>>(page, "results")
                list.selectedIndex = 0
                page.refresh()
                for ((size, width, height) in listOf(Triple("narrow", 320, 760), Triple("reference", 611, 1060))) {
                    panel.setSize(width, height); repeat(4) { layout(panel) }
                    if (size == "reference") {
                        val top = components(panel).filterIsInstance<JTabbedPane>().single().y
                        panel.setSize(width, 964 + top); repeat(4) { layout(panel) }
                        if (java.lang.Boolean.getBoolean("fluttertools.render.ui")) Files.writeString(Path.of("build/ui-preview/search-long-reference-crop.txt"), top.toString())
                    }
                    list.selectedIndex = 0
                    page.refresh(); repeat(4) { layout(panel) }
                    capture(panel, "search-long-$theme-$size")
                    assertFalse(list.expandableItemsHandler.isEnabled)
                    assertTrue(list.expandableItemsHandler.expandedItems.isEmpty())
                    val surface = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, list) as JBScrollPane
                    assertFalse(surface.horizontalScrollBar.isVisible)
                    assertTrue(list.width <= surface.viewport.width)
                    val bounds = list.getCellBounds(0, 0)
                    assertTrue("compact row height ${bounds.height}", bounds.height <= 110)
                    val renderer = field<PackageSearchRenderer>(page, "renderer")
                    renderer.getListCellRendererComponent(list, names.first(), 0, true, false)
                    val version = renderer.versionBounds(bounds.width, bounds.height)
                    assertTrue("version outside row", version.x >= 0 && version.x + version.width <= bounds.width)
                    assertFalse(renderer.isTitleAt(java.awt.Point(version.x + 2, version.y + 2), bounds.width, bounds.height))
                    val details = field<JTextArea>(page, "releaseDetails")
                    assertTrue(details.lineWrap)
                    assertTrue(details.text.contains(description))
                    assertTrue(details.text.contains("dash-overflow.net"))
                    assertTrue(details.text.contains(message("search.compareDart", "^3.12.0")))
                    checkControls(page)
                    val form = components(page).filterIsInstance<SettingsFormPanel>().single()
                    val settings = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, form) as JBScrollPane
                    val release = field<JPanel>(page, "releasePanel")
                    form.scrollRectToVisible(SwingUtilities.convertRectangle(release.parent, release.bounds, form))
                    capture(panel, "search-long-$theme-$size-details")
                    val detailSurface = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, details) as JBScrollPane
                    assertFalse("release details must wrap", detailSurface.horizontalScrollBar.isVisible)
                    list.selectedIndex = 1
                    page.refresh()
                    assertTrue(details.text.startsWith("hooks_riverpod\n"))
                    list.selectedIndex = 0
                    page.refresh()
                    settings.viewport.viewPosition = java.awt.Point(0, 0)
                }
            } finally { panel.removeNotify(); Disposer.dispose(panel) }
        }
    }

    fun testLanguageSelectorKeepsThePackageAndUnsavedAssetEdits() {
        val root = root()
        val languages = service<FlutterToolsLanguageSettings>()
        val previous = languages.state.language
        val panel = FlutterToolsPanel(project)
        Disposer.register(testRootDisposable, panel)
        try {
            panel.showPage(FlutterToolsPanel.Page.ASSETS, root)
            val tabs = components(panel).filterIsInstance<JTabbedPane>().single()
            val output = components(tabs.selectedComponent).filterIsInstance<JBTextField>().single { it.text == "lib/r.dart" }
            output.text = "lib/local.dart"
            components(panel).filterIsInstance<FlutterToolsLanguageSelector>().single().selectedItem = PluginLanguage.CHINESE
            assertEquals(PluginLanguage.CHINESE, languages.state.language)
            assertEquals(root.path, project.service<FlutterToolsSettings>().state.selectedPackagePath)
            assertEquals("lib/local.dart", output.text)
            panel.showPage(FlutterToolsPanel.Page.SEARCH, root)
            panel.addNotify()
            panel.setSize(320, 760); repeat(4) { layout(panel) }
            checkControls(tabs.selectedComponent as JComponent)
            capture(panel, "search-empty-chinese-narrow")
            assertTrue(components(tabs.selectedComponent).filterIsInstance<JButton>().any { it.text == "查看文档" })
        } finally { panel.removeNotify(); Disposer.dispose(panel); languages.setLanguage(previous) }
    }
}
