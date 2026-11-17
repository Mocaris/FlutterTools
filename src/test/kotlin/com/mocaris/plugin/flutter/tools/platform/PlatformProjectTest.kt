package com.mocaris.plugin.flutter.tools.platform

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PlatformProjectTest {
    private fun project(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("fluttertools-platform")
        try { Files.writeString(root.resolve("pubspec.yaml"), "name: app\ndependencies:\n  flutter:\n    sdk: flutter\n"); action(root) }
        finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun directory(root: Path, name: String, marker: String): Path {
        val dir = Files.createDirectories(root.resolve(name))
        Files.writeString(dir.resolve(marker), "")
        return dir
    }

    @Test fun androidGroovyAndKotlinProjectsAreDetected() = project { root ->
        val android = directory(root, "android", "build.gradle")
        assertEquals(ExternalIde.ANDROID_STUDIO, PlatformProject.detect(android)?.ide)
        Files.delete(android.resolve("build.gradle"))
        assertNull(PlatformProject.detect(android))
        Files.writeString(android.resolve("settings.gradle.kts"), "")
        assertEquals(android, PlatformProject.detect(android)?.target)
    }

    @Test fun xcodePrefersWorkspaceAndRunnerAndFallsBackToProject() = project { root ->
        for (name in listOf("ios", "macos", "mac", ".ios")) {
            val dir = Files.createDirectories(root.resolve(name))
            Files.createDirectory(dir.resolve("Runner.xcodeproj"))
            val workspace = Files.createDirectory(dir.resolve("Runner.xcworkspace"))
            Files.createDirectory(dir.resolve("Other.xcworkspace"))
            assertEquals(workspace, PlatformProject.detect(dir)?.target)
            assertEquals(ExternalIde.XCODE, PlatformProject.detect(dir)?.ide)
            Files.delete(workspace)
            Files.delete(dir.resolve("Other.xcworkspace"))
            assertEquals(dir.resolve("Runner.xcodeproj"), PlatformProject.detect(dir)?.target)
        }
    }

    @Test fun windowsOpensSolutionWhenPresentOtherwiseCmakeDirectory() = project { root ->
        val dir = directory(root, "windows", "CMakeLists.txt")
        assertEquals(dir, PlatformProject.detect(dir)?.target)
        val solution = dir.resolve("Runner.sln")
        Files.writeString(solution, "")
        assertEquals(solution, PlatformProject.detect(dir)?.target)
        assertEquals(ExternalIde.VISUAL_STUDIO, PlatformProject.detect(dir)?.ide)
    }

    @Test fun linuxAndWebUseVsCode() = project { root ->
        for ((name, marker) in listOf("linux" to "CMakeLists.txt", "web" to "index.html")) {
            val dir = directory(root, name, marker)
            assertEquals(ExternalIde.VS_CODE, PlatformProject.detect(dir)?.ide)
            assertEquals(dir, PlatformProject.detect(dir)?.target)
        }
    }

    @Test fun flutterModulesCanOpenTheirGeneratedAndroidWrapper() = project { root ->
        val dir = directory(root, ".android", "settings.gradle")
        assertEquals(ExternalIde.ANDROID_STUDIO, PlatformProject.detect(dir)?.ide)
        assertEquals(dir, PlatformProject.detect(dir)?.target)
    }

    @Test fun dartPackagesAndMalformedPubspecsAreNotPlatformProjects() = project { root ->
        val android = directory(root, "android", "build.gradle")
        for (yaml in listOf("name: dart_package", "dependencies: [", "dependencies:\n  flutter: ^1.0.0")) {
            Files.writeString(root.resolve("pubspec.yaml"), yaml)
            assertNull(PlatformProject.detect(android))
        }
    }

    @Test fun arbitraryFoldersNestedDirectoriesAndFilesAreNotPlatformProjects() = project { root ->
        assertNull(PlatformProject.detect(root))
        assertNull(PlatformProject.detect(root.resolve("pubspec.yaml")))
        assertNull(PlatformProject.detect(directory(root, "other", "CMakeLists.txt")))
        val android = Files.createDirectories(root.resolve("android"))
        assertNull(PlatformProject.detect(android))
        assertNull(PlatformProject.detect(directory(android, "web", "index.html")))
        Files.delete(root.resolve("pubspec.yaml"))
        Files.writeString(android.resolve("build.gradle"), "")
        assertNull(PlatformProject.detect(android))
        assertNull(PlatformProject.detect(Path.of("/")))
    }

    @Test fun unsupportedHostsCannotLaunchXcodeOrVisualStudio() {
        assertTrue(ExternalIdeLauncher.supported(ExternalIde.XCODE, DesktopHost.MAC))
        assertFalse(ExternalIdeLauncher.supported(ExternalIde.XCODE, DesktopHost.WINDOWS))
        assertFalse(ExternalIdeLauncher.supported(ExternalIde.VISUAL_STUDIO, DesktopHost.MAC))
        assertTrue(ExternalIdeLauncher.supported(ExternalIde.VISUAL_STUDIO, DesktopHost.WINDOWS))
        assertTrue(ExternalIdeLauncher.supported(ExternalIde.VS_CODE, DesktopHost.LINUX))
    }

    @Test fun macAppBundlesUseOpenAndKeepPathsAsSeparateArguments() {
        val dir = Path.of("/work/app with spaces/android")
        val target = PlatformProject(dir, dir, ExternalIde.ANDROID_STUDIO)
        assertEquals(listOf("/usr/bin/open", "-a", "/Applications/Android Studio.app", dir.toString()),
            ExternalIdeLauncher.command(Path.of("/Applications/Android Studio.app"), target, DesktopHost.MAC))
    }

    @Test fun windowsSolutionAndFolderCommandsHaveDifferentArguments() {
        val dir = Path.of("C:/app with spaces/windows")
        val app = Path.of("C:/Program Files/Visual Studio/devenv.exe")
        assertEquals(listOf(app.toString(), "/OpenFolder", dir.toString()),
            ExternalIdeLauncher.command(app, PlatformProject(dir, dir, ExternalIde.VISUAL_STUDIO), DesktopHost.WINDOWS))
        assertEquals(listOf(app.toString(), dir.resolve("app.sln").toString()),
            ExternalIdeLauncher.command(app, PlatformProject(dir, dir.resolve("app.sln"), ExternalIde.VISUAL_STUDIO), DesktopHost.WINDOWS))
    }

    @Test fun vsCodeLaunchesSelectedDirectoryInANewWindow() {
        val dir = Path.of("/work/app & other/web")
        assertEquals(listOf("/usr/bin/code", "--new-window", dir.toString()),
            ExternalIdeLauncher.command(Path.of("/usr/bin/code"), PlatformProject(dir, dir, ExternalIde.VS_CODE), DesktopHost.LINUX))
    }
}
