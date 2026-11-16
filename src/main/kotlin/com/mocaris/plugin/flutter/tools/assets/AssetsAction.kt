package com.mocaris.plugin.flutter.tools.assets

import com.intellij.openapi.actionSystem.*
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.intellij.openapi.components.service
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.FlutterToolsNotifications
import com.intellij.notification.NotificationType

class AssetsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val candidates = FlutterPackageLocator.findPackages(project)
        val root = FlutterPackageLocator.choosePackage(project, candidates, e.getData(CommonDataKeys.VIRTUAL_FILE))
        if (root != null) project.service<AssetsSyncService>().generate(root)
        else {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("FlutterTools")?.show()
            FlutterToolsNotifications.notify(project, if (candidates.isEmpty()) message("package.missing") else message("package.select"), NotificationType.WARNING)
        }
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.presentation.text = message("action.AssetsSync.text")
        e.presentation.description = message("action.AssetsSync.description")
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
