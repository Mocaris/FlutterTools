package com.mocaris.plugin.flutter.tools.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class MelosCommandsTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun scriptsUseSeparateArgumentsAndBuiltInCommandsAreAlwaysAvailable() {
        val choices = MelosCommands.choices(mapOf("scripts" to mapOf("test:unit" to "dart test", "bad & command" to "ignored")))
        assertEquals(listOf("bootstrap", "clean", "test:unit"), choices.map { it.name })
        assertEquals(listOf("run", "test:unit", "--no-select"), choices.last().arguments)
        assertEquals(listOf("melos", "bootstrap"), MelosCommands.arguments(folder.root.toPath(), emptyMap<Any, Any>(), choices.first(), false))
    }

    @Test fun localMelosUsesConfiguredDartSdkAndWindowsGlobalMelosUsesBatchWrapper() {
        val root = folder.root.toPath()
        Files.writeString(root.resolve("pubspec.yaml"), "name: workspace\ndev_dependencies: {melos: ^7.0.0}")
        val dart = root.resolve("sdk/bin/cache/dart-sdk/bin/dart")
        Files.createDirectories(dart.parent); Files.writeString(dart, "")
        val args = MelosCommands.arguments(root, mapOf("sdkPath" to "sdk"), MelosCommands.Command("clean", listOf("clean")), false)
        assertEquals(listOf(dart.toString(), "run", "melos", "clean"), args)
        Files.delete(root.resolve("pubspec.yaml"))
        assertEquals(listOf("cmd.exe", "/c", "melos.bat", "clean"), MelosCommands.arguments(root, emptyMap<Any, Any>(), MelosCommands.Command("clean", listOf("clean")), true))
    }
}
