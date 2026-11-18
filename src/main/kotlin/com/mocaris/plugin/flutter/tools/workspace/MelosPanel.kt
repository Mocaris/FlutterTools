package com.mocaris.plugin.flutter.tools.workspace

import com.mocaris.plugin.flutter.tools.core.ui.ContentScrollPane
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.*
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.core.WorkspaceLayout
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.ui.ResultTextArea
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

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
    private val scripts = JBList<MelosCommands.Command>().apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean) =
                super.getListCellRendererComponent(list, (value as? MelosCommands.Command)?.name, index, selected, focused)
                    .also { border = JBUI.Borders.empty() }
        }
    }
    private val convert = JButton().localized("melos.convert")
    private val useRoot = JBCheckBox("useRootAsPackage").apply { toolTipText = message("melos.useRootTip") }
    private val nested = JBCheckBox("discoverNestedWorkspaces").apply { toolTipText = message("melos.nestedTip") }
    private val save = JButton().localized("melos.saveOptions")
    private val edit = JButton().localized("melos.edit")
    private val bootstrap = JButton("Bootstrap")
    private val clean = JButton("Clean")
    private val run = JButton().localized("melos.runScript")

    init {
        updateScriptStyle()
        status.addPropertyChangeListener { event ->
            when (event.propertyName) {
                "UI", "font", "background", "foreground" -> updateScriptStyle()
            }
        }
        val form = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        fun section(key: String, content: JComponent) {
            form.add(JBLabel().localized(key)); form.add(Box.createVerticalStrut(JBUI.scale(4)))
            form.add(content); form.add(Box.createVerticalStrut(JBUI.scale(10)))
        }
        section("melos.root", location)
        form.add(useRoot); form.add(nested)
        form.add(Box.createVerticalStrut(JBUI.scale(6)))
        form.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(save); add(edit); add(convert)})
        form.add(Box.createVerticalStrut(JBUI.scale(10)))
        section("melos.members", ContentScrollPane(members))
        form.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(bootstrap); add(clean) })
        form.add(Box.createVerticalStrut(JBUI.scale(10)))
        section("melos.scripts", ContentScrollPane(scripts).apply { preferredSize = java.awt.Dimension(240, 120) })
        form.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(run) })
        section("melos.result", ContentScrollPane(status))
        form.components.filterIsInstance<JComponent>().forEach { it.alignmentX = LEFT_ALIGNMENT }
        add(JBScrollPane(form).apply { border = JBUI.Borders.empty(); viewportBorder = null }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            for ((key, url) in listOf("melos.pub" to "https://pub.dev/packages/melos",
                "melos.website" to "https://melos.invertase.dev/")) {
                add(LinkLabel.create("") { BrowserUtil.browse(url) }.localized(key).apply { toolTipText = url })
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
        scripts.font = status.font
        scripts.background = status.background
        scripts.foreground = status.foreground
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
                state = value; location.text = value?.root?.path.orEmpty()
                renderMembers()
                val previous = scripts.selectedValue?.name
                scripts.setListData(value?.rules?.melos?.let(MelosCommands::choices)?.filter { it.arguments.first() == "run" }?.toTypedArray() ?: emptyArray())
                (0 until scripts.model.size).firstOrNull { scripts.model.getElementAt(it).name == previous }?.let { scripts.selectedIndex = it }
                updateActions()
                if (actionResult == null && !converting) status.localized(if (value?.rules?.melos != null) "melos.configured" else "melos.notConfigured")
            }
        }
    }

    fun updateLanguage() {
        actionResult = null; FlutterToolsBundle.update(this); stamp = null
        useRoot.toolTipText = message("melos.useRootTip"); nested.toolTipText = message("melos.nestedTip"); renderMembers()
    }
    override fun dispose() { scope.cancel() }
}
