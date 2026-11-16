package com.mocaris.plugin.flutter.tools.assets

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import org.junit.Assert.*
import org.junit.Test

class AssetsConfigServiceTest : YamlPsiTest() {
    private class PackageDirectory(name: String) : LightVirtualFile(name) {
        val files = mutableListOf<VirtualFile>()
        override fun isDirectory() = true
        override fun getPath() = "/flutter_config_test/$name"
        override fun getChildren() = files.toTypedArray()
        override fun findChild(name: String) = files.firstOrNull { it.name == name }
        fun file(name: String, text: String): LightVirtualFile {
            val parent = this
            return object : LightVirtualFile(name, text) {
                override fun getParent(): VirtualFile = parent
                override fun getPath() = "${parent.path}/$name"
            }.also { files.add(it) }
        }
    }

    @Test fun existingStandaloneConfigurationAppliesOnFirstReadAndOverridesPubspecSettings() {
        val root = PackageDirectory("main")
        root.file(PUBSPEC_FILE_NAME, "name: demo\nflutter_tools:\n  assets_sync:\n    out_class: PubspecR")
        root.file(TOOLS_FILE_NAME, """
            flutter_tools:
              assets_sync:
                excluded:
                  - '*.tmp'
                out_path: lib/resources.dart
                out_class: Resources
                out_extension: true
                watch: true
        """.trimIndent())
        val snapshot = AssetsConfigService(environment.project).read(root)
        assertEquals("${root.path}/$TOOLS_FILE_NAME", snapshot.path)
        assertEquals(AssetsSyncConfig("lib/resources.dart", "Resources", true, true, setOf("*.tmp")), snapshot.effectiveConfig)
    }

    @Test fun packagesLoadTheirOwnSettingsAndRefreshAfterConfigurationReplacement() {
        val service = AssetsConfigService(environment.project)
        val first = PackageDirectory("first")
        first.file(PUBSPEC_FILE_NAME, "name: first")
        val config = first.file(TOOLS_FILE_NAME, "flutter_tools:\n  assets_sync:\n    out_class: FirstR")
        val second = PackageDirectory("second")
        second.file(PUBSPEC_FILE_NAME, "name: second")
        second.file(TOOLS_FILE_NAME, "flutter_tools:\n  assets_sync:\n    out_class: SecondR")
        assertEquals("FirstR", service.read(first).effectiveConfig.outClass)
        assertEquals("SecondR", service.read(second).effectiveConfig.outClass)
        first.files.remove(config)
        first.file(TOOLS_FILE_NAME, "flutter_tools:\n  assets_sync:\n    out_class: UpdatedR")
        assertEquals("UpdatedR", service.read(first).effectiveConfig.outClass)
        assertEquals("SecondR", service.read(second).effectiveConfig.outClass)
    }

    @Test fun addingAndRemovingStandaloneConfigurationChangesTheAppliedSource() {
        val service = AssetsConfigService(environment.project)
        val root = PackageDirectory("main")
        root.file(PUBSPEC_FILE_NAME, "name: demo\nflutter_tools:\n  assets_sync:\n    out_class: PubspecR")
        assertEquals("PubspecR", service.read(root).effectiveConfig.outClass)
        val config = root.file(TOOLS_FILE_NAME, "flutter_tools:\n  assets_sync:\n    out_class: StandaloneR")
        assertEquals("StandaloneR", service.read(root).effectiveConfig.outClass)
        root.files.remove(config)
        assertEquals("PubspecR", service.read(root).effectiveConfig.outClass)
    }

    @Test fun malformedStandaloneConfigurationDoesNotSilentlyApplyPubspecSettings() {
        val root = PackageDirectory("main")
        root.file(PUBSPEC_FILE_NAME, "name: demo\nflutter_tools:\n  assets_sync:\n    out_class: PubspecR")
        root.file(TOOLS_FILE_NAME, "flutter_tools: [broken")
        assertThrows(IllegalArgumentException::class.java) { AssetsConfigService(environment.project).read(root) }
    }
}
