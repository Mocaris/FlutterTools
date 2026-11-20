package com.mocaris.plugin.flutter.tools.dependencies.commands

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RunPubGetActionTest : BasePlatformTestCase() {
    fun testSaveIncludesSelectedPackageAndWorkspaceButPreservesOtherUnsavedFiles() {
        val workspace = myFixture.addFileToProject("pubspec.yaml", "name: workspace\nworkspace: [packages/app]\n").virtualFile
        val member = myFixture.addFileToProject("packages/app/pubspec.yaml", "name: app\nresolution: workspace\n").virtualFile
        val overrides = myFixture.addFileToProject("packages/app/pubspec_overrides.yaml", "resolution: workspace\n").virtualFile
        val unrelated = myFixture.addFileToProject("notes.txt", "original").virtualFile
        val manager = FileDocumentManager.getInstance()
        val edits = mapOf(workspace to "# workspace\n", member to "# app\n", overrides to "# overrides\n", unrelated to "# untouched\n")
        WriteCommandAction.runWriteCommandAction(project) {
            edits.forEach { (file, suffix) -> manager.getDocument(file)!!.insertString(manager.getDocument(file)!!.textLength, suffix) }
        }
        RunPubGetAction().saveConfiguration(member.parent)
        for (file in listOf(workspace, member, overrides)) {
            assertFalse(manager.isDocumentUnsaved(manager.getDocument(file)!!))
            assertTrue(String(file.contentsToByteArray(), file.charset).endsWith(edits.getValue(file)))
        }
        assertTrue(manager.isDocumentUnsaved(manager.getDocument(unrelated)!!))
        assertEquals("original", String(unrelated.contentsToByteArray(), unrelated.charset))
    }
}
