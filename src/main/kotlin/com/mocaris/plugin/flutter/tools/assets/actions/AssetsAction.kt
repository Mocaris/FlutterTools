package com.mocaris.plugin.flutter.tools.assets.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.mocaris.plugin.flutter.tools.assets.AssetsSyncService
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.PackageActionPresentation
import com.mocaris.plugin.flutter.tools.toolwindow.FlutterToolsPanel

class AssetsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<AssetsSyncService>()
        val candidates = service.roots.filter { it.isValid }
        val root = FlutterPackageLocator.choosePackage(project, candidates, e.getData(CommonDataKeys.VIRTUAL_FILE))
        if (root == null || service.isGenerating(root)) return
        FlutterToolsPanel.open(project, FlutterToolsPanel.Page.ASSETS, root) { service.generate(root) }
    }
    override fun update(e: AnActionEvent) {
        val service = e.project?.takeUnless { it.isDisposed }?.service<AssetsSyncService>()
        PackageActionPresentation.update(e, "AssetsSync", service?.roots.orEmpty()) { service?.isGenerating(it) == true }
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
