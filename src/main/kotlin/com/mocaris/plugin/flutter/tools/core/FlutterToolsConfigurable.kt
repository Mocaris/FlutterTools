package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localized
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Application defaults; project overrides are edited in the Tool Window. */
class FlutterToolsConfigurable : SearchableConfigurable {
    companion object {
        const val ID = "fluttertools.settings"
        fun open(project: Project?) = ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, FlutterToolsConfigurable::class.java)
    }

    private var form: FlutterToolsPreferencesForm? = null
    private var languages: FlutterToolsLanguageSelector? = null
    private var panel: JPanel? = null
    private var lifetime: Disposable? = null
    override fun getId() = ID
    override fun getDisplayName() = "FlutterTools"
    override fun createComponent(): JComponent {
        panel?.let { return it }
        val owner = Disposer.newDisposable("FlutterTools global settings")
        lifetime = owner
        val preferences = FlutterToolsPreferencesForm("settings.globalScope").also { form = it }
        val language = FlutterToolsLanguageSelector(owner).also { languages = it }
        return JPanel(BorderLayout(0, 8)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(JBLabel().localized("language.label"))
                add(language)
            }, BorderLayout.NORTH)
            add(preferences.panel, BorderLayout.CENTER)
            com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(owner)
                .subscribe(LanguageListener.TOPIC, LanguageListener {
                    FlutterToolsBundle.update(this)
                    revalidate(); repaint()
                })
            panel = this
            reset()
        }
    }

    override fun isModified(): Boolean {
        val current = form ?: return false
        val settings = service<FlutterToolsGlobalSettings>()
        return current.hasPendingNumberEdit() || current.options(settings.state) != settings.state ||
            languages?.selectedItem != service<FlutterToolsLanguageSettings>().state.language
    }

    override fun apply() {
        val current = form ?: return
        current.commitNumbers()
        val settings = service<FlutterToolsGlobalSettings>()
        val next = current.options(settings.state)
        if (next != settings.state) settings.apply(next)
        service<FlutterToolsLanguageSettings>().setLanguage(languages?.selectedItem as PluginLanguage)
    }

    override fun reset() {
        form?.reset(service<FlutterToolsGlobalSettings>().state.normalized())
        languages?.selectedItem = service<FlutterToolsLanguageSettings>().state.language
    }

    override fun disposeUIResources() {
        lifetime?.let(Disposer::dispose)
        lifetime = null; form = null; languages = null; panel = null
    }
}
