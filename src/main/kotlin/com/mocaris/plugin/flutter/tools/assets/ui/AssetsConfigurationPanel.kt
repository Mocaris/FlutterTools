package com.mocaris.plugin.flutter.tools.assets.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.*
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBValue
import com.mocaris.plugin.flutter.tools.assets.*
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.ui.ResultTextArea
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localizedTooltip
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import java.awt.FlowLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*
import javax.swing.plaf.basic.BasicHTML
import javax.swing.text.View
import javax.swing.event.DocumentEvent

/** Owns resource form state and detects conflicts with externally edited YAML. */
class AssetsConfigurationPanel(private val project: Project) : JBScrollPane() {
    private val configs = project.service<AssetsConfigService>()
    private val assets = project.service<AssetsSyncService>()
    private val resourcePaths = JBTextArea(6, 24).apply { isEditable = false }
    private var pubspecText: String? = null
    private val exclusions = JBTextArea(4, 24)
    private val output = JBTextField(DEFAULT_OUT_PATH)
    private val generateDir = JBTextField(DEFAULT_GENERATE_DIR)
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
        val form = JPanel(GridBagLayout())
        row(form, "assets.source", source)
        row(form, "assets.resources", contentPane(resourcePaths), true)
        row(form, "", button("assets.editPubspec") {
            root?.findChild(PUBSPEC_FILE_NAME)?.let { com.intellij.openapi.fileEditor.OpenFileDescriptor(project, it).navigate(true) }
        })
        exclusions.localizedTooltip("assets.exclusionsTip")
        row(form, "assets.excluded", contentPane(exclusions))
        output.localizedTooltip("assets.entryTip")
        row(form, "assets.entry", output)
        generateDir.localizedTooltip("assets.generateDirTip")
        row(form, "assets.generateDir", generateDir)
        row(form, "assets.class", className)
        row(form, "", extension)
        row(form, "", watch)
        row(form, "", buttons(button("assets.save") { save() }, button("assets.reload") {
            if (!dirty || Messages.showYesNoDialog(project, message("assets.reloadQuestion"), message("assets.reloadTitle"), message("button.discard"), message("button.cancel"), null) == Messages.YES) load()
        }, button("assets.generate") {
            if (!dirty || save()) root?.let { assets.generate(it) }
        }))
        row(form, "assets.result", contentPane(status).apply {
            minimumSize = Dimension(0, preferredSize.height)
        })
        setViewportView(form)
        val listener = object : DocumentAdapter() { override fun textChanged(event: DocumentEvent) { if (!loading) dirty = true } }
        listOf(exclusions, output, generateDir, className).forEach { it.document.addDocumentListener(listener) }
        extension.addActionListener { if (!loading) dirty = true }
        watch.addActionListener { if (!loading) dirty = true }
    }

    private fun showError(value: String) = Messages.showDialog(project, value, "FlutterTools", arrayOf(message("button.ok")), 0, Messages.getErrorIcon())

    private fun button(text: String, action: () -> Unit) = JButton().localized(text).apply { addActionListener { action() } }
    private fun buttons(vararg components: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { components.forEach { add(it) } }
    private fun contentPane(component: JComponent) = object : JBScrollPane(component) {
        private val arc = JBValue.UIInteger("Component.arc", 5)
        override fun isPaintingOrigin() = true
        override fun paint(graphics: Graphics) {
            val canvas = graphics.create() as Graphics2D
            try {
                val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.float, arc.float)
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                canvas.color = component.background
                canvas.fill(shape)
                canvas.clip(shape)
                super.paint(canvas)
            } finally { canvas.dispose() }
        }
    }.apply {
        isOpaque = false
        border = JBUI.Borders.empty(8)
        viewportBorder = null
    }
    private fun row(form: JPanel, label: String, component: JComponent, grow: Boolean = false) {
        val index = form.componentCount / 2
        val title = object : JLabel() {
            override fun setText(text: String?) {
                super.setText(if (text.isNullOrEmpty()) "" else "<html>${StringUtil.escapeXmlEntities(text)}</html>")
            }
            override fun getPreferredSize(): Dimension {
                val width = JBUI.scale(100)
                (getClientProperty(BasicHTML.propertyKey) as? View)?.setSize(width.toFloat(), 0f)
                return Dimension(width, super.getPreferredSize().height)
            }
            override fun getMinimumSize() = preferredSize
        }.apply { if (label.isNotEmpty()) localized(label) }
        form.add(title, GridBagConstraints().apply { gridx = 0; gridy = index; anchor = GridBagConstraints.NORTHWEST; insets = Insets(5, 0, 5, 8) })
        form.add(component, GridBagConstraints().apply { gridx = 1; gridy = index; weightx = 1.0; if (grow) weighty = 1.0; fill = if (grow) GridBagConstraints.BOTH else GridBagConstraints.HORIZONTAL; insets = Insets(5, 0, 5, 0) })
    }
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
            generateDir.text = config.generateDir
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
                exclusions.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet(), generateDir.text.trim())
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
