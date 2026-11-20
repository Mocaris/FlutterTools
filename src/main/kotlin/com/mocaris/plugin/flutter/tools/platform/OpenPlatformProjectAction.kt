package com.mocaris.plugin.flutter.tools.platform

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.mocaris.plugin.flutter.tools.core.FlutterToolsNotifications
import com.mocaris.plugin.flutter.tools.core.ProjectDirectorySelection
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import java.nio.file.Path

class OpenPlatformProjectAction : DumbAwareAction() {
    private fun selected(event: AnActionEvent): PlatformProject? {
        val file = ProjectDirectorySelection.selected(event) ?: return null
        return runCatching { PlatformProject.detect(Path.of(file.path)) }.getOrNull()
    }

    override fun update(event: AnActionEvent) {
        val target = selected(event)
        event.presentation.isEnabledAndVisible = event.project != null && target != null
        if (target == null) return
        event.presentation.text = message("platform.openIn", target.ide.displayName)
        event.presentation.isEnabled = ExternalIdeLauncher.supported(target.ide, DesktopHost.current())
        event.presentation.description = message(if (event.presentation.isEnabled) "platform.openDescription" else "platform.unsupportedHost", target.ide.displayName)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val target = selected(event) ?: return
        val host = DesktopHost.current()
        if (!ExternalIdeLauncher.supported(target.ide, host)) return
        val settings = service<ExternalIdeSettings>()
        try {
            val saved = settings.state.paths[target.ide.name]?.let { runCatching { Path.of(it) }.getOrNull() }
                ?.takeIf { ExternalIdeLauncher.validApplication(it, host) }
            val application = saved ?: ExternalIdeLauncher.locate(target.ide, host) ?: run {
                val descriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().apply {
                    title = message("platform.selectApplication", target.ide.displayName)
                    description = message("platform.applicationMissing", target.ide.displayName)
                }
                val initial = if (host == DesktopHost.MAC) LocalFileSystem.getInstance().findFileByPath("/Applications") else null
                val chosen = FileChooser.chooseFile(descriptor, project, initial) ?: return
                Path.of(chosen.path).also {
                    require(ExternalIdeLauncher.validApplication(it, host)) { message("platform.invalidApplication", target.ide.displayName) }
                }
            }
            settings.state.paths[target.ide.name] = application.toString()
            ApplicationManager.getApplication().executeOnPooledThread {
                try { ExternalIdeLauncher.launch(ExternalIdeLauncher.command(application, target, host), target.directory) }
                catch (error: Exception) {
                    if (!project.isDisposed) FlutterToolsNotifications.notify(project, message("platform.openFailed", target.ide.displayName, error.localizedMessage), NotificationType.ERROR)
                }
            }
        } catch (error: Exception) {
            FlutterToolsNotifications.notify(project, message("platform.openFailed", target.ide.displayName, error.localizedMessage), NotificationType.ERROR)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
