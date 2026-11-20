package com.mocaris.plugin.flutter.tools.workspace

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.mocaris.plugin.flutter.tools.core.FlutterToolsNotifications
import com.mocaris.plugin.flutter.tools.core.WorkspaceLayout
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.dependencies.commands.FlutterPubGetRunner
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal object MelosCommands {
    data class Command(val name: String, val arguments: List<String>)
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun scripts(config: Map<*, *>): List<Command> =
        (config["scripts"] as? Map<*, *>)?.keys.orEmpty().filterIsInstance<String>()
            .filter { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]*")) }.sorted().map { Command(it, listOf("run", it, "--no-select")) }

    fun arguments(root: Path, config: Map<*, *>, command: Command, windows: Boolean = SystemInfo.isWindows): List<String> {
        val spec = root.resolve("pubspec.yaml").takeIf(Files::isRegularFile)?.let { YamlSupport.load(Files.readString(it)) as? Map<*, *> }
        val local = listOf("dependencies", "dev_dependencies").any { (spec?.get(it) as? Map<*, *>)?.containsKey("melos") == true }
        val selected = dartExecutable(root, config, windows)
        val invocation = if (local) listOf(selected, "run", "melos") else listOf(if (windows) "melos.bat" else "melos")
        val args = invocation + command.arguments
        return if (windows && args.first().endsWith(".bat", true)) listOf("cmd.exe", "/c") + args else args
    }

    fun dartExecutable(root: Path, config: Map<*, *>, windows: Boolean = SystemInfo.isWindows): String {
        val sdk = (config["sdkPath"] as? String)?.let { root.resolve(it).normalize() }
        val filename = if (windows) "dart.exe" else "dart"
        return sdk?.let { base -> listOf(base.resolve("bin/cache/dart-sdk/bin/$filename"), base.resolve("bin/$filename")).firstOrNull(Files::isRegularFile)?.toString() }
            ?: FlutterPubGetRunner.command(root, windows).let { pub ->
                if (!pub.flutter) pub.executable else Path.of(pub.executable).parent?.resolve("cache/dart-sdk/bin/$filename")
                    ?.takeIf(Files::isRegularFile)?.toString() ?: filename
            }
    }

    fun run(project: Project, root: VirtualFile, config: Map<*, *>, command: Command, status: (String) -> Unit = {}) {
        ApplicationManager.getApplication().invokeLater(Runnable {
            if (project.isDisposed || !root.isValid) return@Runnable
            WriteIntentReadAction.run(Runnable { launch(project, root, config, command, status) })
        }, ModalityState.NON_MODAL)
    }

    private fun launch(project: Project, root: VirtualFile, config: Map<*, *>, command: Command, status: (String) -> Unit) {
        fun report(text: String) {
            ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) status(text) }
        }
        val key = "${project.locationHash}:${root.path}"
        if (!running.add(key)) {
            report(message("workspace.running"))
            FlutterToolsNotifications.notify(project, message("workspace.running"), NotificationType.INFORMATION); return
        }
        try {
            val manager = FileDocumentManager.getInstance()
            WorkspaceLayout.configurationNames.forEach { name ->
                root.findChild(name)?.let(manager::getCachedDocument)?.let(manager::saveDocument)
            }
        } catch (e: Exception) {
            running.remove(key)
            Logger.getInstance(MelosCommands::class.java).warn("Cannot save Melos configuration", e)
            report(e.localizedMessage ?: message("error.unknown")); return
        }
        report(message("melos.commandStarted", command.name))
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (project.isDisposed) { running.remove(key); return@executeOnPooledThread }
                val handler = OSProcessHandler(GeneralCommandLine(arguments(Path.of(root.path), config, command))
                    .withWorkDirectory(root.path).withCharset(Charsets.UTF_8).withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE))
                handler.addProcessListener(object : ProcessAdapter() {
                    override fun processTerminated(event: ProcessEvent) {
                        running.remove(key); if (root.isValid) root.refresh(true, true)
                        report(message("workspace.finished", command.name, event.exitCode))
                        FlutterToolsNotifications.notify(project, message("workspace.finished", command.name, event.exitCode),
                            if (event.exitCode == 0) NotificationType.INFORMATION else NotificationType.ERROR)
                    }
                })
                ApplicationManager.getApplication().invokeLater(Runnable {
                    if (project.isDisposed) { handler.destroyProcess(); running.remove(key) }
                    else try {
                        WriteIntentReadAction.run(Runnable {
                            RunContentExecutor(project, handler).withTitle("Melos ${command.name}").withActivateToolWindow(true).run()
                        })
                    } catch (e: Exception) {
                        handler.destroyProcess(); running.remove(key)
                        Logger.getInstance(MelosCommands::class.java).warn("Cannot open Melos console", e)
                        report(e.localizedMessage ?: message("error.unknown"))
                        FlutterToolsNotifications.notify(project, e.localizedMessage ?: message("error.unknown"), NotificationType.ERROR)
                    }
                }, ModalityState.NON_MODAL)
            } catch (e: Exception) {
                running.remove(key); report(e.localizedMessage ?: message("error.unknown"))
                FlutterToolsNotifications.notify(project, e.localizedMessage ?: message("error.unknown"), NotificationType.ERROR)
            }
        }
    }
}
