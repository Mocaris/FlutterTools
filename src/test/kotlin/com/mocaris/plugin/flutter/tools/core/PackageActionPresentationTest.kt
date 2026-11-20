package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings

class PackageActionPresentationTest : BasePlatformTestCase() {
    private fun event(file: VirtualFile? = null) = AnActionEvent.createFromDataContext("MainMenu", Presentation(),
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build())

    fun testEditorPackageTakesPrecedenceAndAmbiguousTargetsAreDisabled() {
        val first = myFixture.addFileToProject("first/pubspec.yaml", "name: first\n").virtualFile.parent
        val second = myFixture.addFileToProject("second/pubspec.yaml", "name: second\n").virtualFile.parent
        val roots = listOf(first, second)
        val settings = project.service<FlutterToolsSettings>()
        settings.state.selectedPackagePath = second.path
        val editor = event(first.findChild("pubspec.yaml"))
        PackageActionPresentation.update(editor, "AssetsSync", roots) { false }
        assertTrue(editor.presentation.isEnabled)
        assertTrue(editor.presentation.description!!.contains(first.path))
        val selected = event()
        PackageActionPresentation.update(selected, "AssetsSync", roots) { false }
        assertTrue(selected.presentation.description!!.contains(second.path))
        settings.state.selectedPackagePath = ""
        PackageActionPresentation.update(selected, "AssetsSync", roots) { false }
        assertFalse(selected.presentation.isEnabled)
        assertEquals(message("package.select"), selected.presentation.description)
        PackageActionPresentation.update(selected, "AssetsSync", emptyList()) { false }
        assertEquals(message("package.missing"), selected.presentation.description)
    }

    fun testRunningCommandIsDisabledAndShowsItsTarget() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: app\n").virtualFile.parent
        val event = event()
        PackageActionPresentation.update(event, "FlutterTools.RunPubGet", listOf(root)) { true }
        assertFalse(event.presentation.isEnabled)
        assertTrue(event.presentation.text!!.contains(FlutterPackageLocator.displayName(project, root)))
        assertEquals(message("action.packageRunning", root.path), event.presentation.description)
    }
}
