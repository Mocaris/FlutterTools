package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.intellij.openapi.actionSystem.*
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager

class CheckPackagesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val versions = project.service<PackageVersionService>()
        val root = FlutterPackageLocator.choosePackage(project, versions.roots, e.getData(CommonDataKeys.VIRTUAL_FILE))
        if (root != null) versions.refresh(root)
        ToolWindowManager.getInstance(project).getToolWindow("FlutterTools")?.show()
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.presentation.text = message("action.FlutterTools.CheckPackages.text")
        e.presentation.description = message("action.FlutterTools.CheckPackages.description")
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
