package com.mocaris.plugin.flutter.tools.workspace

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.core.WorkspaceLayout
import com.mocaris.plugin.flutter.tools.core.YamlSupport

class WorkspaceConversionTest : BasePlatformTestCase() {
    private fun config(text: String) = YamlSupport.load(text) as Map<*, *>

    fun testConvertsInPlacePreservingResourcesDependenciesAndScripts() {
        val source = "name: demo\npublish_to: none\nenvironment:\n  sdk: '>=3.0.0 <4.0.0' # keep\ndependencies:\n  flutter: {sdk: flutter}\nflutter:\n  assets: [assets/]\nmelos:\n  scripts:\n    test: melos exec -- flutter test\n"
        val root = myFixture.addFileToProject("pubspec.yaml", source).virtualFile.parent
        val asset = myFixture.addFileToProject("assets/a.txt", "resource").virtualFile
        myFixture.addFileToProject("packages/core/pubspec.yaml", "name: core\nenvironment: {sdk: ^3.0.0}\n")
        myFixture.addFileToProject("packages/core/pubspec_overrides.yaml", "resolution: null\ndependency_overrides: {demo: {path: ../..}}\n")
        val plan = WorkspaceConversion.prepare(project, root, "7.4.0")
        assertEquals(1, plan.members)
        val pub = config(plan.after.getValue("pubspec.yaml"))
        assertEquals(listOf("packages/core"), pub["workspace"])
        assertFalse(plan.after.getValue("pubspec.yaml").contains("melos: {"))
        assertFalse(plan.after.getValue("pubspec.yaml").contains("scripts: {"))
        assertEquals(config(source)["flutter"], pub["flutter"])
        assertEquals(config(source)["dependencies"], pub["dependencies"])
        val melos = pub["melos"] as Map<*, *>
        assertEquals(true, melos["useRootAsPackage"])
        assertEquals("melos exec -- flutter test", (melos["scripts"] as Map<*, *>)["test"])
        WorkspaceConversion.apply(project, plan)
        assertTrue(asset.isValid)
        assertEquals("workspace", config(WorkspaceLayout.read(root.findFileByRelativePath("packages/core/pubspec.yaml"))!!)["resolution"])
        assertEquals("workspace", config(WorkspaceLayout.read(root.findFileByRelativePath("packages/core/pubspec_overrides.yaml"))!!)["resolution"])
        assertTrue(WorkspaceConversion.prepare(project, root, "7.4.0").after.isEmpty())
    }

    fun testCreatesAggregateRootAndMigratesLegacyRulesWithoutMovingPackages() {
        val root = myFixture.addFileToProject("melos.yaml", "name: legacy\npackages: [packages/*]\nignore: [packages/private]\nscripts:\n  test: echo test\n").virtualFile.parent
        myFixture.addFileToProject("packages/core/pubspec.yaml", "name: core\n")
        val ignored = myFixture.addFileToProject("packages/private/pubspec.yaml", "name: private\n").virtualFile
        val plan = WorkspaceConversion.prepare(project, root, "7.4.0")
        val pub = config(plan.after.getValue("pubspec.yaml"))
        assertEquals("none", pub["publish_to"])
        assertEquals(listOf("packages/core"), pub["workspace"])
        assertFalse(plan.after.getValue("pubspec.yaml").contains("melos: {"))
        assertFalse(plan.after.getValue("pubspec.yaml").contains("scripts: {"))
        assertFalse(plan.after.containsKey("melos.yaml"))
        assertFalse(plan.after.containsKey("packages/private/pubspec.yaml"))
        assertEquals("echo test", ((pub["melos"] as Map<*, *>)["scripts"] as Map<*, *>)["test"])
        WorkspaceConversion.apply(project, plan)
        assertTrue(ignored.isValid)
        assertEquals("echo test", ((WorkspaceLayout.owner(root)!!.second.melos!!)["scripts"] as Map<*, *>)["test"])
    }

    fun testConversionCanBeUndoneAsOneCommand() {
        val source = "name: demo\nenvironment: {sdk: ^3.0.0}\n"
        val root = myFixture.addFileToProject("pubspec.yaml", source).virtualFile.parent
        myFixture.addFileToProject("packages/core/pubspec.yaml", "name: core\n")
        WorkspaceConversion.apply(project, WorkspaceConversion.prepare(project, root, "7.4.0"))
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        undo.undo(null)
        assertEquals(source, WorkspaceLayout.read(root.findChild("pubspec.yaml")))
        assertEquals("name: core\n", WorkspaceLayout.read(root.findFileByRelativePath("packages/core/pubspec.yaml")))
    }

    fun testRejectsMissingNativeMemberAndUpgradesHostedMelos() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: demo\nworkspace: [missing]\n").virtualFile.parent
        try { WorkspaceConversion.prepare(project, root, "7.4.0"); fail("missing member") } catch (_: IllegalArgumentException) { }
        val doc = FileDocumentManager.getInstance().getDocument(root.findChild("pubspec.yaml")!!)!!
        WriteCommandAction.runWriteCommandAction(project) {
            doc.setText("name: demo\ndev_dependencies:\n  melos:\n    hosted: https://pub.dev\n    version: ^6.0.0\n")
        }
        val pub = config(WorkspaceConversion.prepare(project, root, "7.4.0").after.getValue("pubspec.yaml"))
        val melos = (pub["dev_dependencies"] as Map<*, *>)["melos"] as Map<*, *>
        assertEquals("^7.4.0", melos["version"])
        assertEquals("https://pub.dev", melos["hosted"])
    }

    fun testBothWorkspaceAndMelosStayBlockYamlIncludingOverrides() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: demo\nworkspace: [packages/a]\nmelos: {scripts: {test: echo test}}\n").virtualFile.parent
        myFixture.addFileToProject("packages/a/pubspec.yaml", "name: a\n")
        myFixture.addFileToProject("pubspec_overrides.yaml", "workspace: [packages/a]\n# keep overrides\n")
        val plan = WorkspaceConversion.prepare(project, root, "7.4.0")
        val pub = plan.after.getValue("pubspec.yaml")
        assertTrue(pub.contains("workspace:\n  - packages/a"))
        assertTrue(pub.contains("melos:\n  scripts:\n    test: echo test"))
        val overrides = plan.after.getValue("pubspec_overrides.yaml")
        assertTrue(overrides.contains("workspace:\n  - packages/a"))
        assertTrue(overrides.contains("# keep overrides"))
        WorkspaceConversion.apply(project, plan)
        assertTrue(WorkspaceConversion.prepare(project, root, "7.4.0").after.isEmpty())
    }

    fun testSettingsToggleGlobMembersOverridesAndRootWithoutChangingDependenciesOrScripts() {
        val source = "name: demo\nenvironment: {sdk: ^3.11.0}\ndev_dependencies: {melos: ^8.0.0}\nworkspace: [packages/a, apps/demo]\nmelos:\n  useRootAsPackage: true\n  scripts:\n    test: echo test # keep script\n"
        val root = myFixture.addFileToProject("pubspec.yaml", source).virtualFile.parent
        myFixture.addFileToProject("packages/a/pubspec.yaml", "name: a\n")
        myFixture.addFileToProject("packages/a/nested/pubspec.yaml", "name: nested\n")
        myFixture.addFileToProject("packages/b/pubspec.yaml", "name: b\n")
        myFixture.addFileToProject("apps/demo/pubspec.yaml", "name: app\n")
        myFixture.addFileToProject("pubspec_overrides.yaml", "workspace: [packages/a, apps/demo]\n")
        val glob = WorkspaceConversion.prepare(project, root, null, WorkspaceConversion.Options(false, true))
        val pub = config(glob.after.getValue("pubspec.yaml"))
        assertEquals(listOf("packages/**", "apps/demo"), pub["workspace"])
        assertEquals(false, (pub["melos"] as Map<*, *>)["useRootAsPackage"])
        assertEquals(true, (pub["melos"] as Map<*, *>)["discoverNestedWorkspaces"])
        assertEquals(config(source)["dev_dependencies"], pub["dev_dependencies"])
        assertTrue(glob.after.getValue("pubspec.yaml").contains("test: echo test # keep script"))
        assertEquals(4, glob.members)
        assertEquals("workspace", config(glob.after.getValue("packages/b/pubspec.yaml"))["resolution"])
        assertEquals(listOf("packages/**", "apps/demo"), config(glob.after.getValue("pubspec_overrides.yaml"))["workspace"])
        WorkspaceConversion.apply(project, glob)
        assertTrue(WorkspaceConversion.prepare(project, root, null, WorkspaceConversion.Options(false, true)).after.isEmpty())
        val explicit = WorkspaceConversion.prepare(project, root, null, WorkspaceConversion.Options(true, false))
        val restored = config(explicit.after.getValue("pubspec.yaml"))
        assertEquals(listOf("apps/demo", "packages/a", "packages/a/nested", "packages/b"), restored["workspace"])
        assertEquals(false, (restored["melos"] as Map<*, *>)["discoverNestedWorkspaces"])
        assertEquals(true, (restored["melos"] as Map<*, *>)["useRootAsPackage"])
    }

    fun testGlobSdkConstraintRejectsIncompatibleRangesBeforeWrites() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: demo\nenvironment: {sdk: '>=3.6.0 <3.11.0'}\n").virtualFile.parent
        try {
            WorkspaceConversion.prepare(project, root, "8.0.0", WorkspaceConversion.Options(true, true))
            fail("glob requires Dart 3.11")
        } catch (_: IllegalArgumentException) { }
        assertFalse(WorkspaceLayout.read(root.findChild("pubspec.yaml"))!!.contains("workspace:"))
    }

    fun testNestedDiscoveryExpandsPathsForOlderSdkAndKeepsOverridesConsistent() {
        val source = "name: demo\nenvironment: {sdk: '>=3.6.0 <3.11.0'}\nworkspace: [packages/a]\nmelos: {}\n"
        val root = myFixture.addFileToProject("pubspec.yaml", source).virtualFile.parent
        myFixture.addFileToProject("packages/a/pubspec.yaml", "name: a\n")
        myFixture.addFileToProject("packages/a/nested/pubspec.yaml", "name: nested\n")
        myFixture.addFileToProject("packages/b/pubspec.yaml", "name: b\n")
        myFixture.addFileToProject("pubspec_overrides.yaml", "workspace: [packages/a]\n")
        val options = WorkspaceConversion.Options(false, true)
        val plan = WorkspaceConversion.prepare(project, root, null, options, workspaceGlobs = false)
        val pub = config(plan.after.getValue("pubspec.yaml"))
        assertEquals(listOf("packages/a", "packages/a/nested", "packages/b"), pub["workspace"])
        assertEquals(config(source)["environment"], pub["environment"])
        assertEquals(true, (pub["melos"] as Map<*, *>)["discoverNestedWorkspaces"])
        assertEquals(pub["workspace"], config(plan.after.getValue("pubspec_overrides.yaml"))["workspace"])
        assertEquals("workspace", config(plan.after.getValue("packages/a/nested/pubspec.yaml"))["resolution"])
        WorkspaceConversion.apply(project, plan)
        assertTrue(WorkspaceConversion.prepare(project, root, null, options, workspaceGlobs = false).after.isEmpty())
    }

    fun testRejectsIncompatibleMembersWithoutPartialChanges() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: demo\n").virtualFile.parent
        myFixture.addFileToProject("packages/old/pubspec.yaml", "name: old\nenvironment: {sdk: '>=2.12.0 <3.0.0'}\n")
        try { WorkspaceConversion.prepare(project, root, "7.4.0"); fail("incompatible SDK") } catch (_: IllegalArgumentException) { }
        assertEquals("name: demo\n", WorkspaceLayout.read(root.findChild("pubspec.yaml")))
    }

    fun testRejectsStalePlansAndDuplicateNames() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: demo\n").virtualFile.parent
        val plan = WorkspaceConversion.prepare(project, root, "7.4.0")
        val document = FileDocumentManager.getInstance().getDocument(root.findChild("pubspec.yaml")!!)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("name: changed\n") }
        try { WorkspaceConversion.apply(project, plan); fail("stale plan") } catch (_: IllegalArgumentException) { }
        assertEquals("name: changed\n", document.text)
        myFixture.addFileToProject("packages/a/pubspec.yaml", "name: changed\n")
        try { WorkspaceConversion.prepare(project, root, "7.4.0"); fail("duplicate names") } catch (_: IllegalArgumentException) { }
    }

    fun testNativeMembershipAndConfigurationTakePrecedenceOverLegacy() {
        val root = myFixture.addFileToProject("pubspec.yaml", "name: demo\nworkspace: [packages/a]\nmelos: {scripts: {test: echo native}}\n").virtualFile.parent
        myFixture.addFileToProject("melos.yaml", "packages: [packages/*]\nscripts: {test: echo legacy}\n")
        myFixture.addFileToProject("packages/a/pubspec.yaml", "name: a\n")
        myFixture.addFileToProject("packages/b/pubspec.yaml", "name: b\n")
        val plan = WorkspaceConversion.prepare(project, root, "7.4.0")
        assertEquals(1, plan.members)
        assertFalse(plan.after.containsKey("packages/b/pubspec.yaml"))
        assertEquals("echo native", (WorkspaceLayout.owner(root)!!.second.melos!!["scripts"] as Map<*, *>)["test"])
    }
}
