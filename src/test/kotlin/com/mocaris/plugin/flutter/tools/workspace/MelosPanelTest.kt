package com.mocaris.plugin.flutter.tools.workspace

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTabbedPane

class MelosPanelTest : BasePlatformTestCase() {
    private fun components(root: Component): List<Component> = listOf(root) +
        (root as? Container)?.components.orEmpty().flatMap(::components)

    fun testMelosTabFollowsProjectSettingsAndReplacesHeaderPopup() {
        val panel = com.mocaris.plugin.flutter.tools.toolwindow.FlutterToolsPanel(project)
        Disposer.register(testRootDisposable, panel)
        val tabs = components(panel).filterIsInstance<JTabbedPane>().first()
        assertEquals(message("tab.settings"), tabs.getTitleAt(3))
        assertEquals("Melos", tabs.getTitleAt(4))
        assertTrue(tabs.getComponentAt(4) is MelosPanel)
        assertFalse(components(panel).filterIsInstance<JButton>().any { it.text == "Melos…" })
    }

    fun testConfiguredPanelLoadsScriptsAndMemberList() {
        val path = java.nio.file.Path.of(project.basePath!!)
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, path.toString())
        java.nio.file.Files.createDirectories(path.resolve("packages/core"))
        java.nio.file.Files.writeString(path.resolve("pubspec.yaml"), "name: demo\nworkspace: [packages/core]\nmelos: {scripts: {test: echo test}}\n")
        java.nio.file.Files.writeString(path.resolve("packages/core/pubspec.yaml"), "name: core\nresolution: workspace\n")
        try {
            val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            root.refresh(false, true)
            val member = root.findFileByRelativePath("packages/core")!!
            val panel = MelosPanel(project)
            Disposer.register(testRootDisposable, panel)
            panel.refresh(root, listOf(root, member))
            val buttons = components(panel).filterIsInstance<JButton>()
            PlatformTestUtil.waitWithEventsDispatching("Melos panel load", { buttons.first { it.text == "Bootstrap" }.isEnabled }, 5)
            assertTrue(buttons.first { it.text == "Clean" }.isEnabled)
            assertTrue(components(panel).filterIsInstance<JTextArea>().any { it.text.contains("packages/core") })
            val scripts = components(panel).filterIsInstance<com.intellij.ui.components.JBList<*>>().single()
            assertEquals(1, scripts.model.size)
            val checkboxes = components(panel).filterIsInstance<com.intellij.ui.components.JBCheckBox>()
            val useRoot = checkboxes.single { it.text == "useRootAsPackage" }
            val nested = checkboxes.single { it.text == "discoverNestedWorkspaces" }
            assertFalse(useRoot.isSelected); assertFalse(nested.isSelected)
            val save = buttons.single { it.text == message("melos.saveOptions") }
            useRoot.doClick()
            assertTrue(save.isEnabled)
            assertFalse(buttons.first { it.text == "Bootstrap" }.isEnabled)
            save.doClick()
            PlatformTestUtil.waitWithEventsDispatching("root option saved", {
                panel.refresh(root, listOf(root, member))
                useRoot.isEnabled && !save.isEnabled
            }, 5)
            val first = com.mocaris.plugin.flutter.tools.core.YamlSupport.load(
                com.mocaris.plugin.flutter.tools.core.WorkspaceLayout.read(root.findChild("pubspec.yaml"))!!) as Map<*, *>
            assertEquals(true, (first["melos"] as Map<*, *>)["useRootAsPackage"])
            val dart = path.resolve(".fvm/flutter_sdk/bin/cache/dart-sdk/bin/dart")
            java.nio.file.Files.createDirectories(dart.parent)
            java.nio.file.Files.writeString(dart, "#!/bin/sh\necho 'Dart SDK version: 3.9.2 (stable)'\n")
            assertTrue(dart.toFile().setExecutable(true))
            java.nio.file.Files.createDirectories(path.resolve("packages/core/nested"))
            java.nio.file.Files.writeString(path.resolve("packages/core/nested/pubspec.yaml"), "name: nested\n")
            root.refresh(false, true)
            nested.doClick(); save.doClick()
            PlatformTestUtil.waitWithEventsDispatching("nested option saved", {
                panel.refresh(root, listOf(root, member))
                nested.isEnabled && !save.isEnabled
            }, 8)
            val second = com.mocaris.plugin.flutter.tools.core.YamlSupport.load(
                com.mocaris.plugin.flutter.tools.core.WorkspaceLayout.read(root.findChild("pubspec.yaml"))!!) as Map<*, *>
            assertEquals(listOf("packages/core", "packages/core/nested"), second["workspace"])
            assertEquals(">=3.6.0 <4.0.0", (second["environment"] as Map<*, *>)["sdk"])
            assertEquals(true, (second["melos"] as Map<*, *>)["discoverNestedWorkspaces"])
            assertTrue(nested.isSelected)
            assertTrue(buttons.first { it.text == "Bootstrap" }.isEnabled)
        } finally {
            path.resolve(".fvm").toFile().deleteRecursively()
            path.resolve("packages").toFile().deleteRecursively()
            java.nio.file.Files.deleteIfExists(path.resolve("pubspec.yaml"))
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(path)?.refresh(false, true)
        }
    }

    fun testOptionDefaultsReadPubspecBeforeLegacyConfig() {
        val path = java.nio.file.Path.of(project.basePath!!)
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, path.toString())
        java.nio.file.Files.createDirectories(path)
        java.nio.file.Files.writeString(path.resolve("melos.yaml"), "name: demo\nuseRootAsPackage: true\ndiscoverNestedWorkspaces: true\n")
        try {
            for ((config, expected) in listOf(
                "useRootAsPackage: true, discoverNestedWorkspaces: false" to (true to false),
                "useRootAsPackage: false, discoverNestedWorkspaces: true" to (false to true),
                "" to (false to false)
            )) {
                java.nio.file.Files.writeString(path.resolve("pubspec.yaml"), "name: demo\nmelos: {$config}\n")
                val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
                root.refresh(false, true)
                val panel = MelosPanel(project)
                Disposer.register(testRootDisposable, panel)
                panel.refresh(root, listOf(root))
                val checkboxes = components(panel).filterIsInstance<com.intellij.ui.components.JBCheckBox>()
                val useRoot = checkboxes.single { it.text == "useRootAsPackage" }
                val nested = checkboxes.single { it.text == "discoverNestedWorkspaces" }
                PlatformTestUtil.waitWithEventsDispatching("Melos options loaded", { useRoot.isEnabled }, 5)
                assertEquals(expected.first, useRoot.isSelected)
                assertEquals(expected.second, nested.isSelected)
            }
        } finally {
            java.nio.file.Files.deleteIfExists(path.resolve("pubspec.yaml"))
            java.nio.file.Files.deleteIfExists(path.resolve("melos.yaml"))
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(path)?.refresh(false, true)
        }
    }
}
