package com.mocaris.plugin.flutter.tools.assets.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.assets.*
import com.mocaris.plugin.flutter.tools.assets.config.AssetsConfigService
import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import com.mocaris.plugin.flutter.tools.assets.config.DEFAULT_OUT_CLASS
import com.mocaris.plugin.flutter.tools.assets.config.DEFAULT_OUT_PATH
import com.mocaris.plugin.flutter.tools.assets.config.PUBSPEC_FILE_NAME
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localizedTooltip
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.ui.ResultTextArea
import com.mocaris.plugin.flutter.tools.core.ui.SettingsContentPane
import com.mocaris.plugin.flutter.tools.core.ui.SettingsFormPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.event.DocumentEvent

/** Owns resource form state and detects conflicts with externally edited YAML. */
class AssetsConfigurationPanel(private val project: Project) : JBScrollPane() {
    private val configs = project.service<AssetsConfigService>()
    private val assets = project.service<AssetsSyncService>()
    private val resourcePaths = object : JBTextArea(6, 24) {
        override fun getPreferredScrollableViewportSize() = Dimension(super.getPreferredScrollableViewportSize().width,
            getFontMetrics(font).height * rows + insets.top + insets.bottom)
    }.apply { isEditable = false }
    private var pubspecText: String? = null
    private val exclusions = JBTextArea(3, 24)
    private val output = JBTextField(DEFAULT_OUT_PATH)
    private val className = JBTextField(DEFAULT_OUT_CLASS)
    private val extension = JBCheckBox().localized("assets.extension")
    private val watch = JBCheckBox().localized("assets.watch")
    private val source = JBLabel().localized("package.none")
    private val status = ResultTextArea().apply { rows = 3 }.localized("ready")
    private var root: VirtualFile? = null
    private var snapshot: AssetsConfigService.Snapshot? = null
    private var dirty = false
    private var loading = false

    init {
        border = JBUI.Borders.empty(12)
        viewportBorder = null
        val form = SettingsFormPanel()
        form.addRow(JPanel(BorderLayout(8, 0)).apply {
            add(TitledSeparator().apply { label.localized("assets.resources"); labelFor = resourcePaths }, BorderLayout.CENTER)
            add(button("assets.editPubspec") {
                root?.findChild(PUBSPEC_FILE_NAME)?.let { com.intellij.openapi.fileEditor.OpenFileDescriptor(project, it).navigate(true) }
            }, BorderLayout.EAST)
        }, 0)
        form.addRow(SettingsContentPane(resourcePaths).apply {
            // Keep the last visible row whole when declarations extend past the viewport.
            resourcePaths.border = JBUI.Borders.empty(8, 8, 0, 8)
        })
        form.heading("assets.generationSettings")
        form.addRow(JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel().localized("assets.source"), BorderLayout.WEST)
            add(source, BorderLayout.CENTER)
        })
        exclusions.localizedTooltip("assets.exclusionsTip")
        output.localizedTooltip("assets.entryTip")
        val fields = JPanel(GridBagLayout())
        listOf("assets.entry" to output, "assets.class" to className).forEachIndexed { index, (key, field) ->
            fields.add(JBLabel().localized(key).apply { labelFor = field }, GridBagConstraints().apply {
                gridx = 0; gridy = index; anchor = GridBagConstraints.WEST; insets = JBUI.insets(3, 0, 3, 8)
            })
            fields.add(field, GridBagConstraints().apply {
                gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insets(3, 0)
            })
        }
        form.addRow(fields)
        form.addRow(JBLabel().localized("assets.excluded").apply { labelFor = exclusions })
        form.addRow(SettingsContentPane(exclusions))
        form.addRow(extension, 10); form.addRow(watch, 0)
        form.addRow(buttons(button("assets.save") { save() }, button("assets.reload") {
            if (!dirty || Messages.showYesNoDialog(project, message("assets.reloadQuestion"), message("assets.reloadTitle"), message("button.discard"), message("button.cancel"), null) == Messages.YES) load()
        }), 10)
        form.addRow(buttons(button("assets.generate") {
            if (!dirty || save()) root?.let { assets.generate(it) }
        }))
        form.heading("assets.result")
        form.addRow(SettingsContentPane(status))
        form.finish()
        setViewportView(form)
        val listener = object : DocumentAdapter() { override fun textChanged(event: DocumentEvent) { if (!loading) dirty = true } }
        listOf(exclusions, output, className).forEach { it.document.addDocumentListener(listener) }
        extension.addActionListener { if (!loading) dirty = true }
        watch.addActionListener { if (!loading) dirty = true }
    }

    private fun showError(value: String) = Messages.showDialog(project, value, "FlutterTools", arrayOf(message("button.ok")), 0, Messages.getErrorIcon())

    private fun button(text: String, action: () -> Unit) = JButton().localized(text).apply { addActionListener { action() } }
    private fun buttons(vararg components: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { components.forEach { add(it) } }
    fun canSwitchPackage(): Boolean {
        if (!dirty) return true
        val answer = Messages.showYesNoCancelDialog(project, message("assets.switchQuestion"), "FlutterTools", message("button.save"), message("button.discard"), message("button.cancel"), null)
        return answer != Messages.CANCEL && (answer != Messages.YES || save())
    }
    fun selectPackage(value: VirtualFile?) { root = value; dirty = false; snapshot = null; pubspecText = null; load(); refreshResources() }
    private fun load() {
        val current = root ?: return
        loading = true
        try {
            val value = configs.read(current)
            snapshot = value
            val config = value.effectiveConfig
            exclusions.text = config.excluded.joinToString("\n")
            output.text = config.outPath; className.text = config.outClass
            extension.isSelected = config.outExtension; watch.isSelected = config.watcher
            if (value.config == null) source.localized("assets.newConfig") else source.localized("error.details", value.path.substringAfterLast('/'))
            dirty = false; status.localized("ready")
        } catch (e: Exception) { snapshot = null; source.localized("assets.invalidConfig"); status.localized("error.details", e) }
        finally { loading = false }
    }
    private fun save(): Boolean {
        val current = root ?: return false
        val original = snapshot ?: run { showError(message("assets.fixConfig")); return false }
        return try {
            val config = AssetsSyncConfig(output.text.trim(), className.text.trim(), watch.isSelected, extension.isSelected,
                exclusions.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet())
            configs.save(current, original, config)
            load(); status.localized("assets.saved"); true
        } catch (e: Exception) { showError(message("error.details", e)); false }
    }
    private fun refreshResources() {
        val current = root ?: run { resourcePaths.text = message("package.none"); return }
        try {
            val text = configs.readPubspec(current)
            if (text == pubspecText) return
            val declarations = PubspecAssets.parse(text)
            resourcePaths.text = declarations.joinToString("\n") { declaration ->
                when (declaration.kind) {
                    PubspecAssets.Kind.ASSET -> declaration.path
                    PubspecAssets.Kind.FONT -> message("assets.font", declaration.path, declaration.font?.family, declaration.font?.weight, message("font.${declaration.font?.style}"))
                    PubspecAssets.Kind.SHADER -> message("assets.shader", declaration.path)
                }
            }.ifEmpty { message("assets.noResources") }
            pubspecText = text
        } catch (e: Exception) { resourcePaths.text = message("error.details", e) }
    }

    fun updateLanguage() {
        FlutterToolsBundle.update(this)
        FlutterToolsBundle.update(status.componentPopupMenu)
        pubspecText = null
        refresh()
    }

    fun refresh() {
        refreshResources()
        val current = root ?: return
        try {
            val latest = configs.read(current)
            if (latest != snapshot) {
                if (dirty) status.localized("assets.externalChange") else load()
            } else if (!dirty) status.localized("error.details", assets.status(current))
        } catch (e: Exception) { status.localized("error.details", e) }
    }
}
