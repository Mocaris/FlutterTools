package com.mocaris.plugin.flutter.tools.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message

class OpenToolWindowAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project?.takeUnless { it.isDisposed } ?: return
        ToolWindowManager.getInstance(project).getToolWindow("FlutterTools")?.activate(null)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.isDisposed == false
        e.presentation.text = message("action.FlutterTools.OpenToolWindow.text")
        e.presentation.description = message("action.FlutterTools.OpenToolWindow.description")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
