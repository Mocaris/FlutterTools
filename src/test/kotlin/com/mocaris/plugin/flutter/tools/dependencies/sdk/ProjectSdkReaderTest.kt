package com.mocaris.plugin.flutter.tools.dependencies.sdk

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.yaml.YAMLFileType
import org.junit.Assert.*
import org.junit.Test

class ProjectSdkReaderTest : YamlPsiTest() {
    private class Directory(private val path: Path, private val children: Map<String, VirtualFile>) : LightVirtualFile(path.fileName.toString()) {
        override fun isDirectory() = true
        override fun getPath() = path.toString()
        override fun findChild(name: String) = children[name]
    }
    private fun root(path: Path, config: String? = null): VirtualFile {
        val spec = LightVirtualFile("pubspec.yaml", YAMLFileType.YML,
            "name: main_app\nenvironment:\n  sdk: ^3.9.0\n  flutter: '>=3.30.0'\n")
        val children = mutableMapOf<String, VirtualFile>("pubspec.yaml" to spec)
        if (config != null) {
            val file = object : LightVirtualFile("package_config.json", com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, config) {
                override fun getPath() = path.resolve(".dart_tool/package_config.json").toString()
            }
            children[".dart_tool"] = Directory(path.resolve(".dart_tool"), mapOf("package_config.json" to file))
        }
        return Directory(path, children)
    }
    private fun temporary(action: (Path) -> Unit) {
        val folder = Files.createTempDirectory("fluttertools-sdk-test")
        try { action(folder) }
        finally { Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
    }

    @Test fun mainRequirementsAreAvailableWithoutPubGet() = temporary { folder ->
        val sdk = ProjectSdkReader.read(root(folder))!!
        assertEquals("main_app", sdk.name)
        assertEquals("^3.9.0", sdk.dart)
        assertEquals(">=3.30.0", sdk.flutter)
        assertNull(sdk.dartVersion)
        assertNull(sdk.flutterVersion)
    }

    @Test fun fvmMetadataProvidesBothSdkVersionsInsteadOfModuleMetadata() = temporary { folder ->
        val cache = folder.resolve(".fvm/flutter_sdk/bin/cache")
        Files.createDirectories(cache)
        Files.writeString(cache.resolve("flutter.version.json"),
            """{"frameworkVersion":"3.35.0","dartSdkVersion":"3.9.2 (stable)"}""")
        val sdk = ProjectSdkReader.read(root(folder, """{"generatorVersion":"3.8.0","packages":[]}"""))!!
        assertEquals("3.9.2", sdk.dartVersion)
        assertEquals("3.35.0", sdk.flutterVersion)
    }

    @Test fun mainPackageConfigResolvesFlutterSdkWithRelativeRootUri() = temporary { folder ->
        val cache = folder.resolve("sdk with spaces/bin/cache")
        Files.createDirectories(cache)
        Files.writeString(cache.resolve("flutter.version.json"), """{"frameworkVersion":"3.35.0"}""")
        val config = """{"generatorVersion":"3.9.2","packages":[{"name":"flutter","rootUri":"../sdk%20with%20spaces/packages/flutter"}]}"""
        val sdk = ProjectSdkReader.read(root(folder, config))!!
        assertEquals("3.9.2", sdk.dartVersion)
        assertEquals("3.35.0", sdk.flutterVersion)
    }
}
