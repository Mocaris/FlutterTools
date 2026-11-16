package com.mocaris.plugin.flutter.tools.dependencies

import com.google.gson.JsonParser
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.FlutterToolsNotifications
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Runs without a shell on Unix; output and termination are exposed in the IDE Run window. */
object FlutterPubGetRunner {
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun executable(root: Path, windows: Boolean = SystemInfo.isWindows): String {
        val filename = if (windows) "flutter.bat" else "flutter"
        val fvm = root.resolve(".fvm/flutter_sdk/bin/$filename")
        if (Files.isRegularFile(fvm)) return fvm.toString()
        val config = root.resolve(".dart_tool/package_config.json")
        if (Files.isRegularFile(config)) {
            val sdk = runCatching {
                val packages = JsonParser.parseString(Files.readString(config)).asJsonObject.getAsJsonArray("packages")
                val flutter = packages.firstOrNull { it.asJsonObject.get("name")?.asString == "flutter" }?.asJsonObject
                flutter?.get("rootUri")?.asString?.let {
                    val uri = config.toUri().resolve(it)
                    if (uri.scheme != "file") null else Path.of(uri).normalize().parent?.parent?.resolve("bin/$filename")
                }
            }.getOrNull()
            if (sdk != null && Files.isRegularFile(sdk)) return sdk.toString()
        }
        return filename
    }

    fun run(project: Project, root: VirtualFile) {
        val key = "${project.locationHash}:${root.path}"
        if (!running.add(key)) {
            FlutterToolsNotifications.notify(project, message("packages.pubGetRunning"), NotificationType.INFORMATION)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (project.isDisposed) { running.remove(key); return@executeOnPooledThread }
                val executable = executable(Path.of(root.path))
                val arguments = if (SystemInfo.isWindows) listOf("cmd.exe", "/c", executable, "pub", "get") else listOf(executable, "pub", "get")
                val command = GeneralCommandLine(arguments).withWorkDirectory(root.path).withCharset(Charsets.UTF_8)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                val handler = OSProcessHandler(command)
                handler.addProcessListener(object : ProcessAdapter() {
                    override fun processTerminated(event: ProcessEvent) {
                        running.remove(key)
                        FlutterToolsNotifications.notify(project, message(if (event.exitCode == 0) "packages.pubGetComplete" else "packages.pubGetFailed", event.exitCode),
                            if (event.exitCode == 0) NotificationType.INFORMATION else NotificationType.ERROR)
                        root.refresh(true, true)
                    }
                })
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) { handler.destroyProcess(); running.remove(key) }
                    else RunContentExecutor(project, handler).withTitle("Flutter pub get").withActivateToolWindow(true).run()
                }
            } catch (e: Exception) {
                running.remove(key)
                FlutterToolsNotifications.notify(project, e.localizedMessage ?: message("error.unknown"), NotificationType.ERROR)
            }
        }
    }
}
