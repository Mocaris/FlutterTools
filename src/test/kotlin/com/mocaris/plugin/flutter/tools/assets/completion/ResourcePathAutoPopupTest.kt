package com.mocaris.plugin.flutter.tools.assets.completion

import com.intellij.openapi.components.service
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CompletionAutoPopupTestCase
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Files

@RunWith(JUnit4::class)
class ResourcePathAutoPopupTest : CompletionAutoPopupTestCase() {
    @Test fun typingSlashAutomaticallyShowsDeclaredResourcePaths() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("fluttertools.test.dart"))
        val path = Files.createTempDirectory("fluttertools-resource-popup-")
        try {
            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                VfsRootAccess.allowRootAccess(testRootDisposable, path.toString(), path.toRealPath().toString())
                Files.createDirectories(path.resolve("assets/nested"))
                Files.writeString(path.resolve("assets/nested/notes.txt"), "notes")
                Files.writeString(path.resolve("pubspec.yaml"), "name: app\nflutter:\n  assets: [assets/]\n")
                Files.writeString(path.resolve("main.dart"), "final path = '';\n")
                val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
                root.refresh(false, true)
                myFixture.configureFromExistingVirtualFile(root.findChild("main.dart")!!)
                myFixture.editor.caretModel.moveToOffset(14)
                val service = project.service<ResourceCompletionService>()
                PlatformTestUtil.waitWithEventsDispatching("resource index", { service.snapshot(root) != null }, 5)
            }
            type("assets/")
            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                PlatformTestUtil.waitWithEventsDispatching("resource autopopup", { lookup != null && lookup.items.any { it.lookupString == "assets/nested/" } }, 5)
                assertEquals(setOf("assets/nested/", "assets/nested/notes.txt"), lookup.items.map { it.lookupString }.toSet())
                lookup.currentItem = lookup.items.single { it.lookupString == "assets/nested/" }
                myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
                PlatformTestUtil.waitWithEventsDispatching("directory continuation", {
                    lookup != null && lookup.items.map { it.lookupString } == listOf("assets/nested/notes.txt")
                }, 5)
            }
        } finally { path.toFile().deleteRecursively() }
    }
}
