package com.mocaris.plugin.flutter.tools.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.mocaris.plugin.flutter.tools.assets.ui.AssetsConfigurationPanel
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.dependencies.ui.DependenciesPanel
import com.mocaris.plugin.flutter.tools.dependencies.ui.search.PackageSearchPanel
import com.mocaris.plugin.flutter.tools.workspace.ui.MelosPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JTabbedPane

class ToolWindowNavigationTest : BasePlatformTestCase() {
    private fun components(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::components)

    fun testNavigationKeepsTargetAndCancelDoesNotDiscardEditedAssetSettings() {
        val first = myFixture.addFileToProject("first/pubspec.yaml", "name: first\n").virtualFile.parent
        val second = myFixture.addFileToProject("second/pubspec.yaml", "name: second\n").virtualFile.parent
        val panel = FlutterToolsPanel(project)
        Disposer.register(testRootDisposable, panel)
        val tabs = components(panel).filterIsInstance<JTabbedPane>().single()
        val destinations = mapOf(FlutterToolsPanel.Page.ASSETS to AssetsConfigurationPanel::class.java,
            FlutterToolsPanel.Page.PACKAGES to DependenciesPanel::class.java,
            FlutterToolsPanel.Page.SEARCH to PackageSearchPanel::class.java,
            FlutterToolsPanel.Page.SETTINGS to ProjectPreferencesPanel::class.java,
            FlutterToolsPanel.Page.MELOS to MelosPanel::class.java)
        for ((page, type) in destinations) {
            assertTrue(panel.showPage(page, first))
            assertTrue(type.isInstance(tabs.selectedComponent))
            assertEquals(first.path, project.service<FlutterToolsSettings>().state.selectedPackagePath)
        }
        panel.showPage(FlutterToolsPanel.Page.ASSETS, first)
        val output = components(tabs.getComponentAt(0)).filterIsInstance<JBTextField>().single { it.text == "lib/r.dart" }
        output.text = "lib/keep.dart"
        val previous = TestDialogManager.setTestDialog { Messages.CANCEL }
        try {
            assertFalse(panel.showPage(FlutterToolsPanel.Page.PACKAGES, second))
            assertTrue(tabs.selectedComponent is AssetsConfigurationPanel)
            assertEquals(first.path, project.service<FlutterToolsSettings>().state.selectedPackagePath)
            assertEquals("lib/keep.dart", output.text)
        } finally { TestDialogManager.setTestDialog(previous) }
    }
}
