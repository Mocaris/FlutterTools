package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class FlutterToolsPreferencesForm(scopeKey: String) {
    val panel = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(12) }
    private val previews = JBCheckBox().localized("assets.previewImages")
    private val completion = JBCheckBox().localized("settings.resourceCompletion")
    private val completionDelay = JSpinner(SpinnerNumberModel(100, 0, 5000, 50))
    private val hints = JBCheckBox().localized("settings.versionHints")
    private val automatic = JBCheckBox().localized("packages.automatic")
    private val checkDelay = JSpinner(SpinnerNumberModel(1000, 0, 10000, 100))
    private val cache = JSpinner(SpinnerNumberModel(720, 1, 1440, 1))
    private val prerelease = JBCheckBox().localized("packages.prerelease")
    private var row = 0

    init {
        add(JBLabel().localized(scopeKey))
        add(JBLabel().localized("settings.assets"))
        add(previews)
        add(completion)
        field("settings.completionDelay", completionDelay)
        add(JBLabel().localized("settings.dependencies"))
        add(hints)
        add(automatic)
        field("settings.checkDelay", checkDelay)
        field("packages.cache", cache)
        add(JBLabel().localized("settings.timingTip"))
        add(prerelease)
        panel.add(JPanel(), GridBagConstraints().apply {
            gridy = row; gridwidth = 2; weighty = 1.0; fill = GridBagConstraints.BOTH
        })
        completion.addActionListener { updateEnabled() }
        automatic.addActionListener { updateEnabled() }
    }

    private fun add(component: JComponent) {
        panel.add(component, GridBagConstraints().apply {
            gridx = 0; gridy = row++; gridwidth = 2; weightx = 1.0
            anchor = GridBagConstraints.WEST; insets = JBUI.insets(4, 0)
        })
    }

    private fun field(key: String, spinner: JSpinner) {
        panel.add(JBLabel().localized(key), GridBagConstraints().apply {
            gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = JBUI.insets(4, 20, 4, 12)
        })
        panel.add(spinner, GridBagConstraints().apply {
            gridx = 1; gridy = row++; anchor = GridBagConstraints.WEST; insets = JBUI.insets(4, 0)
        })
    }

    fun options(previous: FlutterToolsSettings.Options): FlutterToolsSettings.Options {
        return previous.copy(
            assetImagePreviews = previews.isSelected,
            resourceAutoCompletion = completion.isSelected,
            resourceCompletionDelayMillis = completionDelay.value as Int,
            dependencyVersionHints = hints.isSelected,
            automaticChecks = automatic.isSelected,
            dependencyCheckDelayMillis = checkDelay.value as Int,
            cacheMinutes = cache.value as Int,
            includePrerelease = prerelease.isSelected,
        ).normalized()
    }

    fun hasPendingNumberEdit() = listOf(completionDelay, checkDelay, cache).any { spinner ->
        val field = (spinner.editor as JSpinner.NumberEditor).textField
        field.text != field.formatter.valueToString(spinner.value)
    }

    fun commitNumbers() {
        try { listOf(completionDelay, checkDelay, cache).forEach { it.commitEdit() } }
        catch (_: java.text.ParseException) { throw ConfigurationException(message("settings.invalidNumber")) }
    }

    fun reset(options: FlutterToolsSettings.Options) {
        previews.isSelected = options.assetImagePreviews
        completion.isSelected = options.resourceAutoCompletion
        completionDelay.value = options.resourceCompletionDelayMillis
        hints.isSelected = options.dependencyVersionHints
        automatic.isSelected = options.automaticChecks
        checkDelay.value = options.dependencyCheckDelayMillis
        cache.value = options.cacheMinutes
        prerelease.isSelected = options.includePrerelease
        updateEnabled()
    }

    fun updateEnabled() {
        completionDelay.isEnabled = completion.isSelected
        checkDelay.isEnabled = automatic.isSelected
    }
}
