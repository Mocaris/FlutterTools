package com.mocaris.plugin.flutter.tools.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message

open class OpenToolWindowAction(
    private val page: FlutterToolsPanel.Page? = null,
    private val actionId: String = "FlutterTools.OpenToolWindow",
) : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project?.takeUnless { it.isDisposed } ?: return
        FlutterToolsPanel.open(project, page)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.isDisposed == false
        e.presentation.text = message("action.$actionId.text")
        e.presentation.description = message("action.$actionId.description")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class SearchPackagesAction : OpenToolWindowAction(FlutterToolsPanel.Page.SEARCH, "FlutterTools.SearchPackages")
class OpenWorkspaceAction : OpenToolWindowAction(FlutterToolsPanel.Page.MELOS, "FlutterTools.OpenWorkspace")
class OpenProjectSettingsAction : OpenToolWindowAction(FlutterToolsPanel.Page.SETTINGS, "FlutterTools.OpenProjectSettings")
