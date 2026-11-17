package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message

class OpenSettingsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        FlutterToolsConfigurable.open(e.project?.takeUnless { it.isDisposed })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = message("action.FlutterTools.OpenSettings.text")
        e.presentation.description = message("action.FlutterTools.OpenSettings.description")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
