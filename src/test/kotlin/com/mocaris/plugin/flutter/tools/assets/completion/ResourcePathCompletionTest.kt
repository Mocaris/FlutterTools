package com.mocaris.plugin.flutter.tools.assets.completion

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.mocaris.plugin.flutter.tools.assets.preview.AssetLineMarkerProvider
import com.mocaris.plugin.flutter.tools.assets.preview.AssetPreviewService
import com.mocaris.plugin.flutter.tools.core.FlutterToolsSettings
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

class ResourcePathCompletionTest : LightPlatformCodeInsightFixture4TestCase() {
    private fun resourceProject(source: String, action: (VirtualFile) -> Unit) {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("fluttertools.test.dart"))
        val path = Files.createTempDirectory("fluttertools-resource-")
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString(), path.toRealPath().toString())
        try {
            Files.createDirectories(path.resolve("assets"))
            Files.writeString(path.resolve("assets/logo.svg"), """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10"/></svg>""")
            Files.writeString(path.resolve("assets/notes.txt"), "notes")
            Files.writeString(path.resolve("pubspec.yaml"), "name: app\nflutter:\n  assets: [assets/]\n")
            Files.writeString(path.resolve("main.dart"), source.replace("<caret>", ""))
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            root.refresh(false, true)
            myFixture.configureFromExistingVirtualFile(root.findChild("main.dart")!!)
            source.indexOf("<caret>").takeIf { it >= 0 }?.let { myFixture.editor.caretModel.moveToOffset(it) }
            assertEquals("Dart", myFixture.file.language.id)
            project.service<FlutterToolsSettings>().state.useGlobalSettings = false
            project.service<FlutterToolsSettings>().state.assetImagePreviews = true
            project.service<AssetPreviewService>().preferencesChanged(true)
            val service = project.service<ResourceCompletionService>()
            PlatformTestUtil.waitWithEventsDispatching("resource index", { service.snapshot(root) != null }, 5)
            action(root)
        } finally { path.toFile().deleteRecursively() }
    }

    @Test fun enterReplacesWholePathAndPreservesQuotesAndSurroundingCode() {
        resourceProject("final path = 'assets/lo<caret>old.svg'; // keep") {
            assertEquals(listOf("assets/logo.svg"), myFixture.completeBasic()!!.map { it.lookupString })
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            myFixture.checkResult("final path = 'assets/logo.svg<caret>'; // keep")
        }
    }

    @Test fun tabPreservesRawStringAndShowsNativePresentation() {
        resourceProject("final path = r\"assets/<caret>old.txt\";") {
            val choices = myFixture.completeBasic()!!
            assertEquals(listOf("assets/logo.svg", "assets/notes.txt"), choices.map { it.lookupString }.sorted())
            val presentation = LookupElementPresentation()
            choices.first().renderElement(presentation)
            assertNotNull(presentation.icon)
            assertTrue(presentation.isItemTextBold)
            assertFalse(presentation.typeText.isNullOrEmpty())
            myFixture.lookup.currentItem = choices.single { it.lookupString.endsWith("notes.txt") }
            myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            myFixture.checkResult("final path = r\"assets/notes.txt<caret>\";")
        }
    }

    @Test fun excludesDirectivesCommentsAndInterpolatedStrings() {
        for (source in listOf("import 'assets/<caret>';", "export 'assets/<caret>';", "part 'assets/<caret>';", "// 'assets/<caret>'", "final path = 'assets/<caret>${'$'}name';")) {
            resourceProject(source) {
                assertNull(ResourceString.find(myFixture.file, myFixture.editor.caretModel.offset))
            }
        }
    }

    private fun markers(): List<LineMarkerInfo<*>> {
        val result = mutableListOf<LineMarkerInfo<*>>()
        AssetLineMarkerProvider().collectSlowLineMarkers(PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }.toList(), result)
        return result
    }

    @Test fun guttersSupportImagesAndOtherFilesButSkipMissingPathsAndRespectToggle() {
        resourceProject("final image =\n  'assets/logo.svg';\nfinal text =\n  r'assets/notes.txt';\nfinal missing = 'assets/missing.png';") {
            val settings = project.service<FlutterToolsSettings>()
            settings.state.assetImagePreviews = true
            val previews = project.service<AssetPreviewService>()
            previews.preferencesChanged(true)
            val icons = markers()
            assertEquals(2, icons.size)
            assertEquals(myFixture.file.text.indexOf("'assets/logo.svg'"), icons[0].element!!.textOffset)
            assertEquals(myFixture.file.text.indexOf("r'assets/notes.txt'"), icons[1].element!!.textOffset)
            assertTrue(icons[0].lineMarkerTooltip!!.contains("assets/logo.svg"))
            assertTrue(icons[1].lineMarkerTooltip!!.contains("assets/notes.txt"))
            assertEquals(0, previews.pendingRequests())
            settings.state.assetImagePreviews = false
            previews.preferencesChanged(false)
            assertTrue(markers().isEmpty())
        }
    }

    @Test fun directoriesAreSelectableAndNestedFileChangesRefreshMatches() {
        resourceProject("final path = 'assets/ne<caret>old';") { root ->
            WriteCommandAction.runWriteCommandAction(project) {
                root.findChild("assets")!!.createChildDirectory(this, "nested").createChildDirectory(this, "deep")
                    .createChildData(this, "data.txt").setBinaryContent("data".toByteArray())
            }
            val service = project.service<ResourceCompletionService>()
            PlatformTestUtil.waitWithEventsDispatching("nested index", { service.snapshot(root)?.items?.containsKey("assets/nested/deep/data.txt") == true }, 5)
            val choices = myFixture.completeBasic()!!
            assertEquals(setOf("assets/nested/", "assets/nested/deep/data.txt"), choices.map { it.lookupString }.toSet())
            val folder = choices.single { it.lookupString == "assets/nested/" }
            val presentation = LookupElementPresentation()
            folder.renderElement(presentation)
            assertSame(com.intellij.icons.AllIcons.Nodes.Folder, presentation.icon)
            assertTrue(presentation.typeText.isNullOrEmpty())
            myFixture.lookup.currentItem = folder
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            myFixture.checkResult("final path = 'assets/nested/<caret>';")
            assertEquals(setOf("assets/nested/deep/", "assets/nested/deep/data.txt"), myFixture.completeBasic()!!.map { it.lookupString }.toSet())
            com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup()
            WriteCommandAction.runWriteCommandAction(project) {
                root.findFileByRelativePath("assets/nested/deep/data.txt")!!.delete(this)
            }
            PlatformTestUtil.waitWithEventsDispatching("nested deletion", { service.snapshot(root)?.items?.containsKey("assets/nested/deep/data.txt") == false }, 5)
        }
    }

    @Test fun previewOffKeepsTextCompletionWithoutIcons() {
        resourceProject("final path = 'assets/<caret>';") {
            project.service<FlutterToolsSettings>().state.assetImagePreviews = false
            project.service<AssetPreviewService>().preferencesChanged(false)
            val choices = myFixture.completeBasic()!!
            assertEquals(2, choices.size)
            choices.forEach { val presentation = LookupElementPresentation(); it.renderElement(presentation); assertNull(presentation.icon) }
        }
    }

    @Test fun automaticMatchingOffPreservesManualCompletion() {
        resourceProject("final path = 'assets/<caret>';") {
            val settings = project.service<FlutterToolsSettings>()
            val previous = settings.state.resourceAutoCompletion
            try {
                settings.state.resourceAutoCompletion = false
                assertEquals(setOf("assets/logo.svg", "assets/notes.txt"), myFixture.completeBasic()!!.map { it.lookupString }.toSet())
            } finally { settings.state.resourceAutoCompletion = previous }
        }
    }

    @Test fun unsavedPubspecAndFileChangesInvalidateTheIndex() {
        resourceProject("final path = 'assets/<caret>';") { root ->
            val index = project.service<ResourceCompletionService>()
            val pubspec = FileDocumentManager.getInstance().getDocument(root.findChild("pubspec.yaml")!!)!!
            WriteCommandAction.runWriteCommandAction(project) { pubspec.setText("name: app\nflutter:\n  assets: [assets/notes.txt]\n") }
            PlatformTestUtil.waitWithEventsDispatching("unsaved pubspec index", { index.snapshot(root)?.items?.keys == setOf("assets/notes.txt") }, 5)
            WriteCommandAction.runWriteCommandAction(project) { root.findChild("assets")!!.findChild("notes.txt")!!.delete(this) }
            PlatformTestUtil.waitWithEventsDispatching("deleted resource", { index.snapshot(root)?.items?.isEmpty() == true }, 5)
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(pubspec))
        }
    }
}
