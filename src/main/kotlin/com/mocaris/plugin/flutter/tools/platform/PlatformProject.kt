package com.mocaris.plugin.flutter.tools.platform

import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import java.nio.file.Files
import java.nio.file.Path

enum class ExternalIde(val displayName: String) {
    ANDROID_STUDIO("Android Studio"), XCODE("Xcode"), VISUAL_STUDIO("Visual Studio"), VS_CODE("VS Code")
}

data class PlatformProject(val directory: Path, val target: Path, val ide: ExternalIde) {
    companion object {
        fun detect(directory: Path): PlatformProject? {
            val packageRoot = directory.parent ?: return null
            if (!Files.isDirectory(directory) || !FlutterPackageLocator.isFlutterPackage(packageRoot)) return null
            fun file(name: String) = Files.isRegularFile(directory.resolve(name))
            fun projectFile(vararg extensions: String): Path? = Files.list(directory).use { files ->
                val children = files.toList()
                extensions.firstNotNullOfOrNull { extension ->
                    children.filter { it.fileName.toString().endsWith(extension) &&
                        if (extension in setOf(".xcworkspace", ".xcodeproj")) Files.isDirectory(it) else Files.isRegularFile(it) }
                        .sortedWith(compareBy<Path> { it.fileName.toString() != "Runner$extension" }.thenBy { it.fileName.toString() })
                        .firstOrNull()
                }
            }
            return when (directory.fileName.toString()) {
                "android", ".android" -> if (listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts").any(::file))
                    PlatformProject(directory, directory, ExternalIde.ANDROID_STUDIO) else null
                "ios", ".ios", "macos", "mac" -> projectFile(".xcworkspace", ".xcodeproj")?.let { PlatformProject(directory, it, ExternalIde.XCODE) }
                "windows" -> if (file("CMakeLists.txt")) PlatformProject(directory, projectFile(".sln", ".slnx") ?: directory, ExternalIde.VISUAL_STUDIO) else null
                "linux" -> if (file("CMakeLists.txt")) PlatformProject(directory, directory, ExternalIde.VS_CODE) else null
                "web" -> if (file("index.html")) PlatformProject(directory, directory, ExternalIde.VS_CODE) else null
                else -> null
            }
        }
    }
}
