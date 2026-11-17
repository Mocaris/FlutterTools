package com.mocaris.plugin.flutter.tools.platform

import com.intellij.openapi.components.*
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class DesktopHost { MAC, WINDOWS, LINUX;
    companion object {
        fun current() = when { SystemInfo.isMac -> MAC; SystemInfo.isWindows -> WINDOWS; else -> LINUX }
    }
}

@Service(Service.Level.APP)
@State(name = "FlutterToolsExternalIdeSettings", storages = [Storage("flutterToolsExternalIdes.xml")])
class ExternalIdeSettings : PersistentStateComponent<ExternalIdeSettings.Options> {
    class Options { var paths: MutableMap<String, String> = mutableMapOf() }
    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) { options = state }
}

object ExternalIdeLauncher {
    fun supported(ide: ExternalIde, host: DesktopHost) = when (ide) {
        ExternalIde.XCODE -> host == DesktopHost.MAC
        ExternalIde.VISUAL_STUDIO -> host == DesktopHost.WINDOWS
        else -> true
    }

    fun validApplication(path: Path, host: DesktopHost): Boolean = if (host == DesktopHost.MAC && path.toString().endsWith(".app")) {
        Files.isDirectory(path) && Files.isRegularFile(path.resolve("Contents/Info.plist"))
    } else Files.isRegularFile(path) && if (host == DesktopHost.WINDOWS) path.toString().endsWith(".exe", true) else Files.isExecutable(path)

    fun locate(ide: ExternalIde, host: DesktopHost): Path? {
        val candidates = mutableListOf<Path>()
        fun add(base: String?, relative: String) { if (!base.isNullOrBlank()) candidates.add(Path.of(base).resolve(relative)) }
        when (host) {
            DesktopHost.MAC -> {
                val app = when (ide) { ExternalIde.ANDROID_STUDIO -> "Android Studio.app"; ExternalIde.XCODE -> "Xcode.app"; ExternalIde.VS_CODE -> "Visual Studio Code.app"; else -> return null }
                add("/Applications", app); add(System.getProperty("user.home"), "Applications/$app")
            }
            DesktopHost.WINDOWS -> when (ide) {
                ExternalIde.ANDROID_STUDIO -> {
                    add(System.getenv("ProgramFiles"), "Android/Android Studio/bin/studio64.exe")
                    add(System.getenv("LOCALAPPDATA"), "Programs/Android Studio/bin/studio64.exe")
                }
                ExternalIde.VS_CODE -> {
                    add(System.getenv("LOCALAPPDATA"), "Programs/Microsoft VS Code/Code.exe")
                    add(System.getenv("ProgramFiles"), "Microsoft VS Code/Code.exe")
                }
                ExternalIde.VISUAL_STUDIO -> for (base in listOf("ProgramFiles", "ProgramFiles(x86)")) {
                    val root = System.getenv(base)?.let { Path.of(it).resolve("Microsoft Visual Studio") } ?: continue
                    if (Files.isDirectory(root)) Files.newDirectoryStream(root).use { years ->
                        for (year in years.sortedDescending()) if (Files.isDirectory(year)) Files.newDirectoryStream(year).use { editions ->
                            editions.sorted().forEach { candidates.add(it.resolve("Common7/IDE/devenv.exe")) }
                        }
                    }
                }
                ExternalIde.XCODE -> return null
            }
            DesktopHost.LINUX -> when (ide) {
                ExternalIde.ANDROID_STUDIO -> {
                    add("/opt", "android-studio/bin/studio.sh")
                    add(System.getProperty("user.home"), "android-studio/bin/studio.sh")
                }
                ExternalIde.VS_CODE -> add("/usr/share", "code/code")
                else -> return null
            }
        }
        val binaries = when (ide) {
            ExternalIde.ANDROID_STUDIO -> if (host == DesktopHost.WINDOWS) listOf("studio64.exe") else listOf("studio", "studio.sh", "android-studio")
            ExternalIde.VS_CODE -> if (host == DesktopHost.WINDOWS) listOf("Code.exe") else listOf("code")
            ExternalIde.VISUAL_STUDIO -> listOf("devenv.exe")
            ExternalIde.XCODE -> emptyList()
        }
        System.getenv("PATH")?.split(java.io.File.pathSeparator)?.filter { it.isNotBlank() }?.forEach { base -> binaries.forEach { add(base, it) } }
        return candidates.firstOrNull { validApplication(it, host) }
    }

    fun command(application: Path, project: PlatformProject, host: DesktopHost): List<String> {
        require(supported(project.ide, host))
        if (host == DesktopHost.MAC && application.toString().endsWith(".app")) return listOf("/usr/bin/open", "-a", application.toString(), project.target.toString())
        return when (project.ide) {
            ExternalIde.VISUAL_STUDIO -> if (project.target == project.directory) listOf(application.toString(), "/OpenFolder", project.target.toString())
                else listOf(application.toString(), project.target.toString())
            ExternalIde.VS_CODE -> listOf(application.toString(), "--new-window", project.target.toString())
            else -> listOf(application.toString(), project.target.toString())
        }
    }

    fun launch(command: List<String>, directory: Path) {
        val process = ProcessBuilder(command).directory(directory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() != 0) error("Process exited with code ${process.exitValue()}")
    }
}
