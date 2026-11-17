package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.mocaris.plugin.flutter.tools.platform.PlatformProject
import com.mocaris.plugin.flutter.tools.assets.RegisterAssetsAction
import java.nio.file.Path

class FlutterProjectActionGroup : DefaultActionGroup(), DumbAware {
    override fun update(event: AnActionEvent) {
        val directory = ProjectDirectorySelection.selected(event)
        event.presentation.isEnabledAndVisible = event.project != null &&
            (RegisterAssetsAction.selection(event) != null || directory != null && (FlutterPackageLocator.isFlutterPackage(directory) ||
                runCatching { PlatformProject.detect(Path.of(directory.path)) != null }.getOrDefault(false)))
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
