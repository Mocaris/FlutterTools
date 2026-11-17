package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JSpinner

class FlutterToolsConfigurableTest : LightPlatformCodeInsightFixture4TestCase() {
    private fun components(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::components) else emptyList()

    @Test fun globalApplyResetAndReopenDoNotModifyProjectSettings() {
        val projectSettings = project.service<FlutterToolsSettings>()
        val projectBefore = projectSettings.state.copy()
        val settings = service<FlutterToolsGlobalSettings>()
        val previous = settings.state.copy()
        settings.loadState(FlutterToolsSettings.Options(automaticChecks = false, selectedPackagePath = "/selected/package"))
        val configurable = FlutterToolsConfigurable()
        try {
            val children = components(configurable.createComponent())
            val completion = children.filterIsInstance<JCheckBox>().single { it.text == message("settings.resourceCompletion") }
            val hints = children.filterIsInstance<JCheckBox>().single { it.text == message("settings.versionHints") }
            val numbers = children.filterIsInstance<JSpinner>()
            assertFalse(configurable.isModified)
            assertFalse(numbers[1].isEnabled)
            completion.doClick()
            hints.doClick()
            numbers[0].value = 450
            assertFalse(numbers[0].isEnabled)
            assertTrue(configurable.isModified)
            // The settings form has no effect before Apply.
            assertTrue(settings.state.resourceAutoCompletion)
            configurable.reset()
            assertTrue(completion.isSelected)
            assertTrue(numbers[0].isEnabled)
            assertFalse(configurable.isModified)
            completion.doClick()
            hints.doClick()
            numbers[0].value = 450
            configurable.apply()
            assertFalse(settings.state.resourceAutoCompletion)
            assertFalse(settings.state.dependencyVersionHints)
            assertEquals(450, settings.state.resourceCompletionDelayMillis)
            assertEquals("", settings.state.selectedPackagePath)
            assertEquals(projectBefore, projectSettings.state)
            assertFalse(configurable.isModified)
            configurable.disposeUIResources()
            val reopened = components(configurable.createComponent()).filterIsInstance<JCheckBox>()
            assertFalse(reopened.single { it.text == message("settings.resourceCompletion") }.isSelected)
        } finally { configurable.disposeUIResources(); settings.loadState(previous) }
    }

    @Test fun uncommittedNumericEditsAreAppliedAndInvalidTextDoesNotChangeSettings() {
        val projectSettings = project.service<FlutterToolsSettings>()
        val projectBefore = projectSettings.state.copy()
        val settings = service<FlutterToolsGlobalSettings>()
        val previous = settings.state.copy()
        settings.loadState(FlutterToolsSettings.Options(automaticChecks = false))
        val configurable = FlutterToolsConfigurable()
        try {
            val number = components(configurable.createComponent()).filterIsInstance<JSpinner>().first()
            val field = (number.editor as JSpinner.NumberEditor).textField
            field.text = "650"
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals(650, settings.state.resourceCompletionDelayMillis)
            field.text = "invalid"
            assertTrue(configurable.isModified)
            try { configurable.apply(); fail("Invalid number should be rejected") }
            catch (_: ConfigurationException) { /* IDE displays the localized validation error. */ }
            assertEquals(650, settings.state.resourceCompletionDelayMillis)
        } finally { configurable.disposeUIResources(); settings.loadState(previous) }
    }
}
