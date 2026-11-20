package com.mocaris.plugin.flutter.tools.dependencies

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.PackageActionPresentation
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageVersionService
import com.mocaris.plugin.flutter.tools.toolwindow.FlutterToolsPanel

class CheckPackagesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val versions = project.service<PackageVersionService>()
        val root = FlutterPackageLocator.choosePackage(project, versions.roots.filter { it.isValid }, e.getData(CommonDataKeys.VIRTUAL_FILE))
        if (root == null || versions.isChecking(root)) return
        FlutterToolsPanel.open(project, FlutterToolsPanel.Page.PACKAGES, root) { versions.refresh(root) }
    }
    override fun update(e: AnActionEvent) {
        val service = e.project?.takeUnless { it.isDisposed }?.service<PackageVersionService>()
        PackageActionPresentation.update(e, "FlutterTools.CheckPackages", service?.roots.orEmpty()) { service?.isChecking(it) == true }
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
