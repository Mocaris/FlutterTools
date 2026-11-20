package com.mocaris.plugin.flutter.tools.dependencies.usage

import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.dependencies.inspection.PubspecInlayHints
import com.mocaris.plugin.flutter.tools.dependencies.pub.PubDevClient
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageDependency
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageVersionService
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.jetbrains.yaml.psi.YAMLKeyValue

class DependencyUsageServiceTest : BasePlatformTestCase() {
    private fun hints(): List<String> {
        val texts = mutableListOf<String>()
        val sink = Proxy.newProxyInstance(InlayHintsSink::class.java.classLoader, arrayOf(InlayHintsSink::class.java)) { _, method, args ->
            if (method.name == "addInlineElement" && args?.getOrNull(2) is InlayPresentation) texts.add(args[2].toString())
            null
        } as InlayHintsSink
        val collector = PubspecInlayHints().getCollectorFor(myFixture.file, myFixture.editor, NoSettings(), sink)!!
        PsiTreeUtil.collectElements(myFixture.file) { it is YAMLKeyValue }.forEach { collector.collect(it, myFixture.editor, sink) }
        return texts
    }

    fun testUnusedHintSharesTheLatestVersionPresentation() {
        val path = Files.createTempDirectory("fluttertools-usage-")
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString(), path.toRealPath().toString())
        try {
            Files.writeString(path.resolve("pubspec.yaml"), "name: app\ndependencies:\n  runtime: ^1.0.0\n")
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            root.refresh(false, true)
            myFixture.configureFromExistingVirtualFile(root.findChild("pubspec.yaml")!!)
            val versions = project.service<PackageVersionService>()
            val type = PackageVersionService::class.java.declaredClasses.single { it.simpleName == "Cached" }
            val cached = type.getDeclaredConstructor(PubDevClient.Metadata::class.java, Throwable::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(PubDevClient.Metadata("2.0.0", "2.0.0"), null, System.currentTimeMillis())
            val field = PackageVersionService::class.java.getDeclaredField("cache").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(versions) as MutableMap<String, Any>)["runtime"] = cached
            val usage = project.service<DependencyUsageService>()
            usage.ensure(root)
            val dependency = PackageDependency("runtime", section = "dependencies", constraint = "^1.0.0", source = "pub.dev")
            PlatformTestUtil.waitWithEventsDispatching("usage scan", { usage.unused(root, dependency) == true }, 5000)
            assertEquals(listOf(message("packages.latestHint", "2.0.0") + " " + message("packages.unusedHint")), hints())
        } finally { path.toFile().deleteRecursively() }
    }
    fun testUnusedHintAppearsWithoutVersionMetadataAndTracksUnsavedImports() {
        val path = Files.createTempDirectory("fluttertools-usage-")
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString(), path.toRealPath().toString())
        try {
            Files.writeString(path.resolve("pubspec.yaml"), "name: app\ndependencies:\n  used: {path: ../used}\n  unused: {path: ../unused}\ndev_dependencies:\n  generator: {path: ../generator}\n")
            Files.writeString(path.resolve("main.dart"), "import 'package:used/api.dart';\nvoid main() {}")
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            root.refresh(false, true)
            myFixture.configureFromExistingVirtualFile(root.findChild("pubspec.yaml")!!)
            val usage = project.service<DependencyUsageService>()
            usage.ensure(root)
            val used = PackageDependency("used", section = "dependencies", constraint = "any", source = "path")
            val unused = used.copy(name = "unused", packageName = "unused")
            PlatformTestUtil.waitWithEventsDispatching("usage scan", { usage.unused(root, unused) == true }, 5000)
            assertEquals(false, usage.unused(root, used))

            assertEquals(listOf(message("packages.unusedHint")), hints())
            val document = FileDocumentManager.getInstance().getDocument(root.findChild("main.dart")!!)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText("import 'package:unused/api.dart';\nvoid main() {}") }
            PlatformTestUtil.waitWithEventsDispatching("unsaved usage update", { usage.unused(root, used) == true }, 5000)
            assertEquals(false, usage.unused(root, unused))
            assertEquals(listOf(message("packages.unusedHint")), hints())
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        } finally { path.toFile().deleteRecursively() }
    }
}
