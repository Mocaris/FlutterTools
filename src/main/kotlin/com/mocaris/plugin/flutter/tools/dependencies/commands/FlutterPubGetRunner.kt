package com.mocaris.plugin.flutter.tools.dependencies.commands

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
import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import com.mocaris.plugin.flutter.tools.core.FlutterToolsNotifications
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Runs without a shell on Unix; output and termination are exposed in the IDE Run window. */
object FlutterPubGetRunner {
    private val running = ConcurrentHashMap.newKeySet<String>()
    fun isRunning(project: Project, root: VirtualFile): Boolean = running.contains("${project.locationHash}:${root.path}")

    data class Command(val executable: String, val flutter: Boolean) {
        val title get() = if (flutter) "Flutter pub get" else "Dart pub get"
        fun arguments(windows: Boolean = SystemInfo.isWindows): List<String> =
            if (windows && executable.endsWith(".bat", ignoreCase = true))
                listOf("cmd.exe", "/c", executable, "pub", "get")
            else listOf(executable, "pub", "get")
    }

    private fun mapping(root: Path, name: String): Map<*, *>? = root.resolve(name)
        .takeIf(Files::isRegularFile)?.let { YamlSupport.load(Files.readString(it)) as? Map<*, *> }

    private fun requiresFlutter(root: Path): Boolean {
        val spec = mapping(root, "pubspec.yaml") ?: return false
        if ((spec["environment"] as? Map<*, *>)?.get("flutter") != null) return true
        val external = mapping(root, "pubspec_overrides.yaml")
        val overrides = (if (external?.containsKey("dependency_overrides") == true)
            external["dependency_overrides"] else spec["dependency_overrides"]) as? Map<*, *>
        val dependencies = listOf("dependencies", "dev_dependencies").flatMap { section ->
            (spec[section] as? Map<*, *>)?.entries?.map { overrides?.get(it.key) ?: it.value }.orEmpty()
        } + overrides?.values.orEmpty()
        return dependencies.any { (it as? Map<*, *>)?.get("sdk") == "flutter" }
    }

    fun command(root: Path, windows: Boolean = SystemInfo.isWindows): Command {
        val context = DartPackageContext.root(root)
        val config = DartPackageContext.packageConfig(root)
        val recordedFlutterSdk = if (Files.isRegularFile(config)) runCatching {
            DartPackageContext.flutterSdkRoot(config, JsonParser.parseString(Files.readString(config)).asJsonObject)
        }.getOrNull() else null
        // One pub resolution covers every workspace member, including Flutter packages.
        val members = (mapping(context, "pubspec.yaml")?.get("workspace") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val workspaceRequiresFlutter = members.isNotEmpty() &&
            (recordedFlutterSdk != null || requiresFlutter(context) ||
                members.any { requiresFlutter(context.resolve(it)) })
        val flutter = requiresFlutter(root) || workspaceRequiresFlutter ||
            !Files.isRegularFile(root.resolve("pubspec.yaml")) && recordedFlutterSdk != null
        val filename = if (flutter) { if (windows) "flutter.bat" else "flutter" }
            else if (windows) "dart.exe" else "dart"
        val sdkRoots = DartPackageContext.flutterSdkRoots(root, context, recordedFlutterSdk)
        val executable = sdkRoots.asSequence().flatMap { sdk ->
            if (flutter) sequenceOf(sdk.resolve("bin/$filename"))
            else sequenceOf(sdk.resolve("bin/cache/dart-sdk/bin/$filename"),
                sdk.resolve(if (windows) "bin/dart.bat" else "bin/dart"))
        }.firstOrNull(Files::isRegularFile)?.toString() ?: filename
        return Command(executable, flutter)
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
                val pubGet = command(Path.of(root.path))
                val command = GeneralCommandLine(pubGet.arguments()).withWorkDirectory(root.path).withCharset(Charsets.UTF_8)
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
                    else RunContentExecutor(project, handler).withTitle(pubGet.title).withActivateToolWindow(true).run()
                }
            } catch (e: Exception) {
                running.remove(key)
                FlutterToolsNotifications.notify(project, e.localizedMessage ?: message("error.unknown"), NotificationType.ERROR)
            }
        }
    }
}
