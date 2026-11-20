package com.mocaris.plugin.flutter.tools.assets.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.mocaris.plugin.flutter.tools.assets.AssetsSyncService
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.ProjectDirectorySelection
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message

class GenerateSelectedAssetsAction : DumbAwareAction() {
    private fun selectedRoot(event: AnActionEvent) = ProjectDirectorySelection.selected(event)
        ?.takeIf(FlutterPackageLocator::isFlutterPackage)

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && selectedRoot(event) != null
        event.presentation.text = message("action.FlutterTools.GenerateSelectedAssets.text")
        event.presentation.description = message("action.FlutterTools.GenerateSelectedAssets.description")
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = selectedRoot(event) ?: return
        project.service<AssetsSyncService>().generate(root)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
