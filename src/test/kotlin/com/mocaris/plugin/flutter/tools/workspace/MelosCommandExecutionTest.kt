package com.mocaris.plugin.flutter.tools.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Run from a raw Swing event, outside the test framework's implicit write-intent action. */
class MelosCommandExecutionTest : BasePlatformTestCase() {
    override fun runInDispatchThread() = false
    override fun setUp() { EdtTestUtil.runInEdtAndWait<Throwable> { super.setUp() } }
    override fun tearDown() { EdtTestUtil.runInEdtAndWait<Throwable> { super.tearDown() } }
    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) { testRunnable.run() }

    fun testBootstrapAndCleanSaveUnsavedConfigurationAndExecuteOnRawEdt() {
        if (com.intellij.openapi.util.SystemInfo.isWindows) return
        val path = Files.createTempDirectory("melos-execution-")
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString(), path.toRealPath().toString())
        val previous = mutableSetOf<com.intellij.execution.ui.RunContentDescriptor>()
        val previousEditors = mutableSetOf<com.intellij.openapi.editor.Editor>()
        EdtTestUtil.runInEdtAndWait<Throwable> {
            previous.addAll(com.intellij.execution.ui.RunContentManager.getInstance(project).allDescriptors)
            previousEditors.addAll(com.intellij.openapi.editor.EditorFactory.getInstance().allEditors)
        }
        try {
            Files.writeString(path.resolve("pubspec.yaml"), "name: before\ndev_dependencies: {melos: ^7.0.0}\n")
            val dart = path.resolve("sdk/bin/dart")
            Files.createDirectories(dart.parent)
            Files.writeString(dart, "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> commands.txt\n")
            assertTrue(dart.toFile().setExecutable(true))
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            EdtTestUtil.runInEdtAndWait<Throwable> {
                root.refresh(false, true)
                val doc = FileDocumentManager.getInstance().getDocument(root.findChild("pubspec.yaml")!!)!!
                WriteCommandAction.runWriteCommandAction(project) { doc.setText("name: after\ndev_dependencies: {melos: ^7.0.0}\n") }
            }
            for (command in listOf("bootstrap", "clean")) {
                val done = CountDownLatch(1)
                val statuses = CopyOnWriteArrayList<String>()
                val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
                SwingUtilities.invokeLater {
                    try {
                        // A modern IDE must not rely on EDT implying write intent.
                        if (System.getProperty("idea.platform.prefix") == "AndroidStudio")
                            assertFalse(ApplicationManager.getApplication().isWriteIntentLockAcquired)
                        MelosCommands.run(project, root, mapOf("sdkPath" to "sdk"), MelosCommands.Command(command, listOf(command))) {
                            statuses.add(it)
                            if (it == com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message("workspace.finished", command, 0) ||
                                it.contains("Access is allowed") || it.contains("Cannot")) done.countDown()
                        }
                    } catch (e: Throwable) { error.set(e); done.countDown() }
                }
                assertTrue("Melos $command: $statuses", done.await(10, TimeUnit.SECONDS))
                error.get()?.let { throw it }
                assertTrue(statuses.toString(), statuses.contains(com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message("workspace.finished", command, 0)))
                assertTrue(Files.readString(path.resolve("commands.txt")).contains("run melos $command"))
            }
            assertTrue(Files.readString(path.resolve("pubspec.yaml")).startsWith("name: after"))
        } finally {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                val manager = com.intellij.execution.ui.RunContentManager.getInstance(project)
                manager.allDescriptors.filter { it !in previous }.forEach {
                    manager.removeRunContent(com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(), it)
                    if (!com.intellij.openapi.util.Disposer.isDisposed(it)) com.intellij.openapi.util.Disposer.dispose(it)
                }
                // The light fixture's RunContentManager may not retain descriptors.
                com.intellij.openapi.editor.EditorFactory.getInstance().allEditors.filter { it !in previousEditors }
                    .mapNotNull { editor -> generateSequence<java.awt.Component>(editor.component) { it.parent }
                        .filterIsInstance<com.intellij.execution.ui.ConsoleView>().firstOrNull() }
                    .distinct().forEach { com.intellij.openapi.util.Disposer.dispose(it) }
            }
            path.toFile().deleteRecursively()
            EdtTestUtil.runInEdtAndWait<Throwable> { LocalFileSystem.getInstance().findFileByNioFile(path)?.refresh(false, true) }
        }
    }
}
