package com.mocaris.plugin.flutter.tools.assets

import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.text
import java.nio.file.Files
import java.nio.file.Path

class AssetsOutputVfsTest : BasePlatformTestCase() {
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("fluttertools-output-vfs-").toRealPath()
    }

    override fun tearDown() {
        try { FileUtil.delete(root.toFile()) }
        finally { super.tearDown() }
    }

    private fun contents(config: AssetsSyncConfig = AssetsSyncConfig()) =
        AssetsGenerator.generate(root.toString(), config, "name: sample")

    private fun directory(path: String): VirtualFile {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(path))!!
        VfsUtil.markDirtyAndRefresh(false, false, true, file)
        file.children // Populate the same cached listing that the Project view uses.
        return file
    }

    private fun write(contents: Map<Path, String>) {
        val prepared = AssetsOutputWriter.prepare(contents)
        try { AssetsOutputWriter.publish(contents, prepared) }
        finally { AssetsOutputVfs.finish(prepared, contents.keys) }
    }

    fun testNewOutputsReplaceVisibleTemporaryEntriesImmediately() {
        val contents = contents()
        val prepared = AssetsOutputWriter.prepare(contents)
        val generated = directory(DEFAULT_GENERATE_DIR)
        assertEquals(3, generated.children.count { it.name.startsWith(".fluttertools-") })
        try { AssetsOutputWriter.publish(contents, prepared) }
        finally { AssetsOutputVfs.finish(prepared, contents.keys) }
        assertEquals(setOf("assets.g.dart", "fonts.g.dart", "shaders.g.dart"), generated.children.map { it.name }.toSet())
        assertNotNull(directory("lib").findChild("r.dart"))
    }

    fun testUnchangedGenerationCleansTemporaryFilesBeforeRefreshingListings() {
        val contents = contents()
        write(contents)
        val prepared = AssetsOutputWriter.prepare(contents)
        val generated = directory(DEFAULT_GENERATE_DIR)
        val entryDirectory = directory("lib")
        assertEquals(3, generated.children.count { it.name.startsWith(".fluttertools-") })
        try {
            AssetsOutputWriter.publish(contents, prepared)
            assertTrue(prepared.values.all(Files::exists)) // Identical outputs leave staging files for cleanup.
        } finally { AssetsOutputVfs.finish(prepared, contents.keys) }
        assertTrue(prepared.values.none(Files::exists))
        assertFalse(generated.children.any { it.name.startsWith(".fluttertools-") })
        assertFalse(entryDirectory.children.any { it.name.startsWith(".fluttertools-") })
    }

    fun testMigratedPartsDisappearFromTheLoadedOldDirectory() {
        write(contents(AssetsSyncConfig(generateDir = "lib/gen")))
        val oldDirectory = directory("lib/gen")
        assertEquals(3, oldDirectory.children.size)
        val contents = contents()
        val obsolete = AssetsGenerator.legacyOutputPaths(root, AssetsSyncConfig())
        val prepared = AssetsOutputWriter.prepare(contents)
        val generated = directory(DEFAULT_GENERATE_DIR)
        try { AssetsOutputWriter.publish(contents, prepared, obsolete) }
        finally { AssetsOutputVfs.finish(prepared, contents.keys + obsolete) }
        assertTrue(oldDirectory.children.isEmpty())
        assertEquals(3, generated.children.size)
        assertFalse(generated.children.any { it.name.startsWith(".fluttertools-") })
        assertTrue(VfsUtilCore.loadText(directory("lib").findChild("r.dart")!!).contains("generate/assets.g.dart"))
    }

    fun testFailedPublicationRefreshesRolledBackOutputsAndTemporaryDeletions() {
        val originals = contents()
        write(originals)
        val prepared = AssetsOutputWriter.prepare(originals.mapValues { (_, value) -> value + "// changed\n" })
        val generated = directory(DEFAULT_GENERATE_DIR)
        val entryDirectory = directory("lib")
        Files.delete(prepared.getValue(root.resolve("$DEFAULT_GENERATE_DIR/fonts.g.dart")))
        try {
            org.junit.Assert.assertThrows(Exception::class.java) { AssetsOutputWriter.publish(originals.mapValues { (_, value) -> value + "// changed\n" }, prepared) }
        } finally { AssetsOutputVfs.finish(prepared, originals.keys) }
        assertFalse(generated.children.any { it.name.startsWith(".fluttertools-") })
        assertFalse(entryDirectory.children.any { it.name.startsWith(".fluttertools-") })
        originals.forEach { (path, value) ->
            assertEquals(value, VfsUtilCore.loadText(LocalFileSystem.getInstance().findFileByNioFile(path)!!))
        }
    }

    fun testManualGenerationReportsCompletionAfterTheDirectoryIsRefreshed() {
        Files.writeString(root.resolve(PUBSPEC_FILE_NAME), "name: sample\n")
        Files.writeString(root.resolve(TOOLS_FILE_NAME), "flutter_tools:\n  assets_sync:\n    out_class: R\n")
        val packageRoot = directory("")
        val service = project.service<AssetsSyncService>()
        service.generate(packageRoot)
        PlatformTestUtil.waitWithEventsDispatching("resource generation and VFS refresh", {
            service.status(packageRoot) == text("assets.generated", DEFAULT_OUT_PATH).toString()
        }, 10)
        val generated = packageRoot.findFileByRelativePath(DEFAULT_GENERATE_DIR)!!
        assertEquals(setOf("assets.g.dart", "fonts.g.dart", "shaders.g.dart"), generated.children.map { it.name }.toSet())
        assertFalse(packageRoot.findChild("lib")!!.children.any { it.name.startsWith(".fluttertools-") })
    }
}
