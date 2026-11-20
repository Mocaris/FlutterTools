package com.mocaris.plugin.flutter.tools.workspace.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.*
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mocaris.plugin.flutter.tools.core.WorkspaceLayout
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.ui.ResultTextArea
import com.mocaris.plugin.flutter.tools.core.ui.SettingsFormPanel
import com.mocaris.plugin.flutter.tools.core.ui.SettingsContentPane
import com.mocaris.plugin.flutter.tools.workspace.MelosCommands
import com.mocaris.plugin.flutter.tools.workspace.WorkspaceConversion
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import kotlinx.coroutines.*

/** Metadata refreshes are coalesced in the background; commands use the displayed workspace root. */
class MelosPanel(private val project: Project) : JPanel(BorderLayout(0, 8)), Disposable {
    private data class State(val root: VirtualFile, val rules: WorkspaceLayout.Rules?, val members: List<String>,
                             val modern: Boolean, val options: WorkspaceConversion.Options)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var stamp: Any? = null
    private var state: State? = null
    private var converting = false
    private var actionResult: String? = null
    private var optionsDirty = false
    private val location = object : JBTextField() {
        override fun getMaximumSize() = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply { isEditable = false }
    private val status = ResultTextArea().apply { rows = 3 }.localized("ready")
    private val members = JBTextArea(4, 24).apply { isEditable = false }
    private val scripts = object : JBList<MelosCommands.Command>() {
        override fun getPreferredScrollableViewportSize() = Dimension(super.getPreferredScrollableViewportSize().width,
            (getFontMetrics(font).height + JBUI.scale(8)) * visibleRowCount + insets.top + insets.bottom)
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 4
        isOpaque = true
        emptyText.text = message("melos.noScripts")
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean) =
                super.getListCellRendererComponent(list, (value as? MelosCommands.Command)?.name, index, selected, focused)
                    .also { border = JBUI.Borders.empty(4, 8) }
        }
    }
    private val convert = JButton().localized("melos.convert")
    private val useRoot = JBCheckBox().localized("melos.useRoot").apply { toolTipText = message("melos.useRootTip") }
    private val nested = JBCheckBox().localized("melos.nested").apply { toolTipText = message("melos.nestedTip") }
    private val save = JButton().localized("melos.saveOptions")
    private val edit = JButton().localized("melos.edit")
    private val bootstrap = JButton("Bootstrap")
    private val clean = JButton("Clean")
    private val run = JButton().localized("melos.runScript")

    init {
        updateScriptStyle()
        scripts.addPropertyChangeListener("UI") { updateScriptStyle() }
        members.addPropertyChangeListener("UI") { updateScriptStyle() }
        border = JBUI.Borders.empty(12)
        val form = SettingsFormPanel()
        form.heading("melos.configuration", 0)
        form.addRow(JBLabel().localized("melos.root").apply { labelFor = this@MelosPanel.location })
        form.addRow(location)
        form.addRow(useRoot, 10); form.addRow(nested, 0)
        form.addRow(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(save); add(Box.createHorizontalStrut(JBUI.scale(8))); add(edit)
        }, 10)
        form.addRow(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(convert) })
        form.heading("melos.members")
        form.addRow(SettingsContentPane(members))
        form.heading("melos.tasks")
        form.addRow(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(bootstrap); add(Box.createHorizontalStrut(JBUI.scale(8))); add(clean)
        })
        form.addRow(JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel().localized("melos.scripts").apply { labelFor = scripts }, BorderLayout.CENTER)
            add(run, BorderLayout.EAST)
        }, 10)
        form.addRow(SettingsContentPane(scripts))
        form.heading("melos.result")
        form.addRow(SettingsContentPane(status))
        form.finish()
        add(JBScrollPane(form).apply { border = JBUI.Borders.empty(); viewportBorder = null }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            for ((key, url) in listOf("melos.pub" to "https://pub.dev/packages/melos",
                "melos.website" to "https://melos.invertase.dev/")) {
                add(LinkLabel<Any>("", null, { _, _ -> BrowserUtil.browse(url) }).localized(key).apply { toolTipText = url })
            }
        }, BorderLayout.SOUTH)
        convert.addActionListener { updateOptions(true) }
        save.addActionListener { updateOptions(false) }
        listOf(useRoot, nested).forEach { checkbox -> checkbox.addActionListener {
            optionsDirty = true; renderMembers(); updateActions()
        } }
        edit.addActionListener { ApplicationManager.getApplication().invokeLater(Runnable {
            WriteIntentReadAction.run(Runnable { state?.let { current ->
                val file = current.root.findChild("pubspec.yaml")
                val spec = WorkspaceLayout.read(file)
                val native = WorkspaceLayout.parse(spec, WorkspaceLayout.read(current.root.findChild("pubspec_overrides.yaml")), null)
                val target = if (native?.melos != null) file else current.root.findChild("melos.yaml") ?: file
                target?.let { OpenFileDescriptor(project, it).navigate(true) }
            } })
        }, ModalityState.NON_MODAL) }
        bootstrap.addActionListener { execute(MelosCommands.Command("bootstrap", listOf("bootstrap"))) }
        clean.addActionListener { execute(MelosCommands.Command("clean", listOf("clean"))) }
        run.addActionListener { scripts.selectedValue?.let(::execute) }
        scripts.addListSelectionListener { updateActions() }
        updateActions()
    }

    private fun updateScriptStyle() {
        scripts.font = members.font
        scripts.background = UIUtil.getTextFieldBackground()
        scripts.foreground = UIUtil.getTextFieldForeground()
    }

    private fun selectedOptions() = WorkspaceConversion.Options(useRoot.isSelected, nested.isSelected)

    private fun updateOptions(conversion: Boolean) {
        val current = state ?: return
        val options = selectedOptions()
        converting = true; updateActions()
        status.text = message("melos.converting")
        val finished: (String) -> Unit = { result ->
            converting = false; actionResult = result; status.text = result; stamp = null; updateActions()
        }
        if (conversion) WorkspaceConversion.convert(project, current.root, options, finished)
        else WorkspaceConversion.saveOptions(project, current.root, options, finished)
    }

    private fun updateActions() {
        val ready = state != null && !converting
        convert.isEnabled = ready
        save.isEnabled = ready && state?.modern == true && optionsDirty
        useRoot.isEnabled = ready; nested.isEnabled = ready
        edit.isEnabled = ready && state?.rules != null
        bootstrap.isEnabled = ready && !optionsDirty && state?.rules?.melos != null
        clean.isEnabled = bootstrap.isEnabled
        run.isEnabled = bootstrap.isEnabled && scripts.selectedValue != null
    }

    private fun renderMembers() {
        members.text = state?.members?.filter { it.isNotEmpty() || useRoot.isSelected }
            ?.joinToString("\n") { it.ifEmpty { message("melos.rootMember") } }.orEmpty()
    }

    private fun execute(command: MelosCommands.Command) {
        state?.let { current -> current.rules?.melos?.let { MelosCommands.run(project, current.root, it, command) { result -> actionResult = result; status.text = result } } }
    }

    fun refresh(selected: VirtualFile?, available: List<VirtualFile>) {
        if (project.isDisposed || !scope.isActive) return
        val next = listOf(selected?.path, available.map { it.path }, VirtualFileManager.getInstance().modificationCount,
            PsiModificationTracker.getInstance(project).modificationCount)
        if (stamp == next || stamp != null && !isShowing) return
        stamp = next; job?.cancel()
        job = scope.launch {
            val result = runCatching { ReadAction.compute<State?, RuntimeException> {
                val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: selected ?: return@compute null
                val owner = WorkspaceLayout.owner(base, true) ?: WorkspaceLayout.owner(base)
                val root = owner?.first ?: base
                val modern = WorkspaceLayout.parse(WorkspaceLayout.read(root.findChild("pubspec.yaml")),
                    WorkspaceLayout.read(root.findChild("pubspec_overrides.yaml")), null)
                val configured = modern?.members != null && modern.melos != null
                val config = (modern?.melos ?: owner?.second?.melos).orEmpty()
                State(root, owner?.second, available.filter { it.path == root.path || it.path.startsWith("${root.path}/") }
                    .map { it.path.removePrefix(root.path).trimStart('/') }, configured,
                    WorkspaceConversion.Options(config["useRootAsPackage"] == true,
                        config["discoverNestedWorkspaces"] == true))
            }
            }
            ensureActive()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !scope.isActive || stamp != next) return@invokeLater
                if (result.isFailure) {
                    stamp = null; status.text = result.exceptionOrNull()?.localizedMessage ?: message("error.unknown")
                    return@invokeLater
                }
                val value = result.getOrNull()
                val sameRoot = state?.root?.path == value?.root?.path
                if (!sameRoot || !optionsDirty || value?.options == selectedOptions()) {
                    optionsDirty = false
                    useRoot.isSelected = value?.options?.useRootAsPackage == true
                    nested.isSelected = value?.options?.discoverNestedWorkspaces == true
                }
                state = value; location.text = value?.root?.path.orEmpty(); location.toolTipText = value?.root?.path
                renderMembers()
                val previous = scripts.selectedValue?.name
                scripts.setListData(value?.rules?.melos?.let(MelosCommands::scripts)?.toTypedArray() ?: emptyArray())
                (0 until scripts.model.size).firstOrNull { scripts.model.getElementAt(it).name == previous }?.let { scripts.selectedIndex = it }
                updateActions()
                if (actionResult == null && !converting) status.localized(if (value?.rules?.melos != null) "melos.configured" else "melos.notConfigured")
            }
        }
    }

    fun updateLanguage() {
        actionResult = null; FlutterToolsBundle.update(this); stamp = null
        scripts.emptyText.text = message("melos.noScripts")
        useRoot.toolTipText = message("melos.useRootTip"); nested.toolTipText = message("melos.nestedTip"); renderMembers()
    }
    override fun dispose() { scope.cancel() }
}
