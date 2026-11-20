package com.mocaris.plugin.flutter.tools.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.mocaris.plugin.flutter.tools.core.*
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsConfigurable
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsGlobalSettings
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsPreferences
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsPreferencesForm
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.core.settings.PreferencesListener
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** Project overrides never modify application defaults or the global language. */
class ProjectPreferencesPanel(private val project: Project) : JPanel(BorderLayout(0, 8)), Disposable {
    private val settings = project.service<FlutterToolsSettings>()
    private val inherited = JBCheckBox().localized("settings.useGlobal")
    private val form = FlutterToolsPreferencesForm("settings.projectScope")
    private var dirty = false
    private var resetting = false

    init {
        add(JPanel(BorderLayout(0, 8)).apply {
            add(inherited, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JButton().localized("settings.openGlobal").apply {
                    addActionListener { FlutterToolsConfigurable.open(project) }
                })
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(form.panel), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton().localized("settings.applyProject").apply { addActionListener {
                try { apply() }
                catch (e: ConfigurationException) { Messages.showErrorDialog(project, e.message, "FlutterTools") }
            } })
            add(JButton().localized("settings.resetProject").apply { addActionListener { reset() } })
        }, BorderLayout.SOUTH)
        inherited.addActionListener {
            dirty = true
            if (inherited.isSelected) form.reset(service<FlutterToolsGlobalSettings>().state)
            updateEnabled()
        }
        observe(form.panel)
        project.messageBus.connect(this).subscribe(PreferencesListener.TOPIC, PreferencesListener {
            if (!dirty) reset()
        })
        reset()
    }

    private fun observe(component: Component) {
        when (component) {
            is javax.swing.AbstractButton -> component.addActionListener { if (!resetting) dirty = true }
            is javax.swing.JSpinner -> {
                component.addChangeListener { if (!resetting) dirty = true }
                (component.editor as javax.swing.JSpinner.NumberEditor).textField.document
                    .addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                        override fun textChanged(event: javax.swing.event.DocumentEvent) { if (!resetting) dirty = true }
                    })
            }
        }
        if (component is Container) component.components.forEach(::observe)
    }

    fun apply() {
        form.commitNumbers()
        val next = form.options(settings.state).copy(useGlobalSettings = inherited.isSelected)
        settings.loadState(next)
        dirty = false
        FlutterToolsPreferences.changed(project)
        reset()
    }

    fun reset() {
        resetting = true
        try {
            inherited.isSelected = settings.state.useGlobalSettings == true
            form.reset(settings.effective())
            updateEnabled()
            dirty = false
        } finally { resetting = false }
    }

    private fun updateEnabled() {
        fun enable(component: Component) {
            component.isEnabled = !inherited.isSelected
            if (component is Container) component.components.forEach(::enable)
        }
        enable(form.panel)
        if (!inherited.isSelected) form.updateEnabled()
    }

    fun updateLanguage() { FlutterToolsBundle.update(this); revalidate(); repaint() }
    override fun dispose() { }
}
