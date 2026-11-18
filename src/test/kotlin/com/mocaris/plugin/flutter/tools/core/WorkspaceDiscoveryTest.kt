package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.assets.AssetsSyncService

class WorkspaceDiscoveryTest : BasePlatformTestCase() {
    fun testNativeAndMelosDiscoverOnlyMembersAndRespectUnsavedChanges() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: workspace\nworkspace: ['packages/**']\nmelos: {ignore: ['**/example']}\n").virtualFile.parent
        myFixture.addFileToProject("packages/a/pubspec.yaml", "name: a\nresolution: workspace\n")
        myFixture.addFileToProject("packages/a/example/pubspec.yaml", "name: example\n")
        myFixture.addFileToProject("packages/b/pubspec.yaml", "name: b\nresolution: workspace\n")
        myFixture.addFileToProject("other/pubspec.yaml", "name: other\n")
        fun members() = FlutterPackageLocator.findPackages(project).map { it.path.removePrefix(root.path).trimStart('/') }.toSet()
        assertEquals(setOf("", "packages/a", "packages/b"), members())
        val service = project.service<AssetsSyncService>()
        PlatformTestUtil.waitWithEventsDispatching("workspace discovery", { service.roots.map { it.path }.toSet().contains(root.path + "/packages/b") }, 5)
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(root.findChild("pubspec.yaml")!!)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("name: workspace\nworkspace: [packages/a]\nmelos: {ignore: ['**/example']}\n") }
        assertEquals(setOf("", "packages/a"), members())
        PlatformTestUtil.waitWithEventsDispatching("live workspace discovery", { service.roots.map { it.path }.toSet() == setOf(root.path, root.path + "/packages/a") }, 5)
        assertEquals(root, DartPackageContext.root(root.findFileByRelativePath("packages/a")!!))
    }

    fun testOuterGlobStillIncludesPackagesOutsideNestedMemberDeclarations() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: workspace\nworkspace: ['packages/**']\nmelos: {discoverNestedWorkspaces: true}\n").virtualFile.parent
        myFixture.addFileToProject("packages/server/pubspec.yaml", "name: server\nresolution: workspace\nworkspace: [auth]\n")
        myFixture.addFileToProject("packages/server/auth/pubspec.yaml", "name: auth\nresolution: workspace\n")
        myFixture.addFileToProject("packages/server/example/pubspec.yaml", "name: example\nresolution: workspace\n")
        assertEquals(setOf("", "packages/server", "packages/server/auth", "packages/server/example"),
            FlutterPackageLocator.findPackages(project).map { it.path.removePrefix(root.path).trimStart('/') }.toSet())
    }

    fun testLegacyMelosRulesApplyWithoutAWorkspacePubspec() {
        val root = myFixture.addFileToProject("melos.yaml", "name: legacy\npackages: ['packages/*']\nignore: ['packages/private']\n").virtualFile.parent
        myFixture.addFileToProject("packages/a/pubspec.yaml", "name: a\n")
        myFixture.addFileToProject("packages/private/pubspec.yaml", "name: private\n")
        myFixture.addFileToProject("examples/demo/pubspec.yaml", "name: demo\n")
        assertEquals(listOf(root.path + "/packages/a"), FlutterPackageLocator.findPackages(project).map { it.path })
        assertEquals(root, WorkspaceLayout.owner(root.findFileByRelativePath("packages/a")!!, true)!!.first)
    }
}
