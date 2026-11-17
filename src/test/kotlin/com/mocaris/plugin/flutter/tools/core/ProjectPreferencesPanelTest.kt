package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.toolwindow.ProjectPreferencesPanel
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JSpinner

class ProjectPreferencesPanelTest : LightPlatformCodeInsightFixture4TestCase() {
    private fun components(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::components) else emptyList()

    @Test fun projectOverridesCanBeAppliedResetAndReturnedToGlobalDefaults() {
        val settings = project.service<FlutterToolsSettings>()
        val previous = settings.state.copy()
        val global = service<FlutterToolsGlobalSettings>()
        val globalBefore = global.state.copy()
        settings.loadState(FlutterToolsSettings.Options(useGlobalSettings = true, selectedPackagePath = "/project"))
        val panel = ProjectPreferencesPanel(project)
        try {
            val children = components(panel)
            val inherit = children.filterIsInstance<JCheckBox>().single { it.text == message("settings.useGlobal") }
            val completion = children.filterIsInstance<JCheckBox>().single { it.text == message("settings.resourceCompletion") }
            val delay = children.filterIsInstance<JSpinner>().first()
            assertTrue(inherit.isSelected)
            assertFalse(completion.isEnabled)
            inherit.doClick()
            assertTrue(completion.isEnabled)
            delay.value = 750
            assertEquals(true, settings.state.useGlobalSettings)
            panel.reset()
            assertTrue(inherit.isSelected)
            inherit.doClick()
            delay.value = 750
            panel.apply()
            assertEquals(false, settings.state.useGlobalSettings)
            assertEquals(750, settings.effective().resourceCompletionDelayMillis)
            assertEquals("/project", settings.state.selectedPackagePath)
            assertEquals(globalBefore, global.state)
            inherit.doClick()
            panel.apply()
            assertEquals(true, settings.state.useGlobalSettings)
            assertEquals(global.state.resourceCompletionDelayMillis, settings.effective().resourceCompletionDelayMillis)
        } finally { Disposer.dispose(panel); settings.loadState(previous) }
    }

    @Test fun languageSelectionSynchronizesBetweenSettingsAndToolWindow() {
        val language = service<FlutterToolsLanguageSettings>()
        val previous = language.state.language
        val owner = Disposer.newDisposable()
        val configurable = FlutterToolsConfigurable()
        try {
            language.setLanguage(PluginLanguage.ENGLISH)
            val toolWindow = FlutterToolsLanguageSelector(owner).apply {
                addActionListener { language.setLanguage(selectedItem as PluginLanguage) }
            }
            val selector = components(configurable.createComponent()).filterIsInstance<FlutterToolsLanguageSelector>().single()
            selector.selectedItem = PluginLanguage.CHINESE
            assertEquals(PluginLanguage.ENGLISH, toolWindow.selectedItem)
            configurable.apply()
            assertEquals(PluginLanguage.CHINESE, language.state.language)
            assertEquals(PluginLanguage.CHINESE, toolWindow.selectedItem)
            toolWindow.selectedItem = PluginLanguage.SYSTEM
            assertEquals(PluginLanguage.SYSTEM, selector.selectedItem)
            assertFalse(configurable.isModified)
        } finally { configurable.disposeUIResources(); Disposer.dispose(owner); language.setLanguage(previous) }
    }
}
