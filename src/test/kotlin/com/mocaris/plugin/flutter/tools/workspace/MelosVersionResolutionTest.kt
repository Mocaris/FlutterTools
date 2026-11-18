package com.mocaris.plugin.flutter.tools.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.dependencies.search.PackageRelease
import java.nio.file.Files
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking

class MelosVersionResolutionTest : BasePlatformTestCase() {
    fun testActualWorkspaceSdkWinsOverOldMetadataWithoutWritingConfiguration() {
        if (com.intellij.openapi.util.SystemInfo.isWindows) return
        val path = Files.createTempDirectory("melos-sdk-")
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString(), path.toRealPath().toString())
        try {
            val source = "name: demo\nenvironment: {sdk: '>=3.6.0 <4.0.0'}\n"
            Files.writeString(path.resolve("pubspec.yaml"), source)
            val sdk = path.resolve(".fvm/flutter_sdk/bin/cache")
            Files.createDirectories(sdk.resolve("dart-sdk/bin"))
            Files.writeString(sdk.resolve("flutter.version.json"), """{"dartSdkVersion":"3.10.0","frameworkVersion":"3.38.0"}""")
            val dart = sdk.resolve("dart-sdk/bin/dart")
            Files.writeString(dart, "#!/bin/sh\necho 'Dart SDK version: 3.6.2 (stable)'\n")
            assertTrue(dart.toFile().setExecutable(true))
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            root.refresh(false, true)
            val job = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MelosVersion.resolve(project, root) {
                    listOf(PackageRelease("7.4.0", "", ">=3.9.0 <4.0.0", null),
                        PackageRelease("7.1.0", "", ">=3.6.0 <4.0.0", null))
                } }
            })
            PlatformTestUtil.waitWithEventsDispatching("Melos SDK resolution", { job.isDone }, 8)
            assertEquals("7.1.0", job.get())
            assertEquals(source, Files.readString(path.resolve("pubspec.yaml")))
            val check = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                MelosVersion.supportsWorkspaceGlobs(root)
            })
            PlatformTestUtil.waitWithEventsDispatching("glob SDK check", { check.isDone }, 8)
            assertFalse(check.get())
            assertEquals(source, Files.readString(path.resolve("pubspec.yaml")))
        } finally {
            path.toFile().deleteRecursively()
            LocalFileSystem.getInstance().findFileByNioFile(path)?.refresh(false, true)
        }
    }
}
