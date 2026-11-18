package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.mocaris.plugin.flutter.tools.assets.*
import com.mocaris.plugin.flutter.tools.assets.completion.ResourcePathIndex
import com.mocaris.plugin.flutter.tools.assets.preview.*
import com.mocaris.plugin.flutter.tools.dependencies.FlutterPubGetRunner
import com.mocaris.plugin.flutter.tools.dependencies.search.ProjectSdkReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceContextTest : YamlPsiTest() {
    @get:Rule val folder = TemporaryFolder()
    private val base get() = folder.root.toPath()
    private val workspace get() = base.resolve("workspace")
    private val member get() = workspace.resolve("packages/app")
    private val sdk get() = base.resolve("sdk with spaces")
    private val config get() = workspace.resolve(".dart_tool/package_config.json")

    private class Directory(private val location: Path, private val parentDirectory: VirtualFile? = null) : LightVirtualFile(location.fileName.toString()) {
        val children = mutableMapOf<String, VirtualFile>()
        override fun isDirectory() = true
        override fun getPath() = location.toString()
        override fun findChild(name: String) = children[name]
        override fun getParent() = parentDirectory
    }
    private fun file(path: Path) = object : LightVirtualFile(path.fileName.toString(), PlainTextFileType.INSTANCE, Files.readString(path)) {
        override fun getPath() = path.toString()
    }
    private fun write(path: Path, text: String) { Files.createDirectories(path.parent); Files.writeString(path, text) }
    private fun setupWorkspace(): VirtualFile {
        write(workspace.resolve("pubspec.yaml"), "name: workspace\nworkspace: [packages/app]\n")
        write(member.resolve("pubspec.yaml"), "name: app\nresolution: workspace\nenvironment: {sdk: ^3.9.0}\nflutter: {assets: [packages/icons/logo.png]}\n")
        write(sdk.resolve("bin/flutter"), "executable")
        Files.createDirectories(sdk.resolve("packages/flutter"))
        write(sdk.resolve("bin/cache/flutter.version.json"), """{"frameworkVersion":"3.35.0","dartSdkVersion":"3.9.2"}""")
        write(base.resolve("icons/lib/logo.png"), "image")
        write(config, """{"generatorVersion":"3.9.2","packages":[{"name":"flutter","rootUri":"${sdk.resolve("packages/flutter").toUri()}"},{"name":"icons","rootUri":"${base.resolve("icons").toUri()}","packageUri":"lib/"}]}""")
        val root = Directory(workspace)
        root.children["pubspec.yaml"] = file(workspace.resolve("pubspec.yaml"))
        val dartTool = Directory(config.parent, root)
        dartTool.children["package_config.json"] = file(config)
        root.children[".dart_tool"] = dartTool
        val packages = Directory(member.parent, root)
        val app = Directory(member, packages)
        app.children["pubspec.yaml"] = file(member.resolve("pubspec.yaml"))
        return app
    }

    @Test fun sharedConfigResolvesGenerationCompletionAndPreviewPaths() {
        setupWorkspace()
        assertEquals(config, DartPackageContext.packageConfig(member))
        val text = Files.readString(member.resolve("pubspec.yaml"))
        val outputs = AssetsGenerator.generate(member.toString(), AssetsSyncConfig(), text)
        assertTrue(outputs.values.any { it.contains("packages/icons/logo.png") })
        assertEquals(base.resolve("icons/lib/logo.png"), ResourcePathIndex.collect(member, text).items.getValue("packages/icons/logo.png").file)
        val request = ImageRequest(member.toString(), "app", "packages/icons/logo.png")
        val location = AssetImageLoader.resolve(request, PubspecAssets.resolver(member))
        assertEquals(base.resolve("icons/lib/logo.png"), location.file)
    }

    @Test fun memberSdkUsesSharedMetadataButRetainsMemberEnvironment() {
        val app = setupWorkspace()
        assertEquals(config.toString(), DartPackageContext.packageConfig(app)!!.path)
        val actual = ProjectSdkReader.read(app)!!
        assertEquals("app", actual.name)
        assertEquals("^3.9.0", actual.dart)
        assertEquals("3.9.2", actual.dartVersion)
        assertEquals("3.35.0", actual.flutterVersion)
        assertEquals(sdk.resolve("bin/flutter").toString(), FlutterPubGetRunner.executable(member, false))
    }

    @Test fun workspaceFvmAppliesToMembersAndMemberFvmTakesPrecedence() {
        val app = setupWorkspace()
        val workspaceFvm = workspace.resolve(".fvm/flutter_sdk")
        write(workspaceFvm.resolve("bin/flutter"), "executable")
        write(workspaceFvm.resolve("bin/cache/flutter.version.json"), """{"frameworkVersion":"3.36.0","dartSdkVersion":"3.10.0"}""")
        assertEquals(workspaceFvm.resolve("bin/flutter").toString(), FlutterPubGetRunner.executable(member, false))
        assertEquals("3.36.0", ProjectSdkReader.read(app)!!.flutterVersion)
        write(member.resolve(".fvm/flutter_sdk/bin/flutter"), "executable")
        assertEquals(member.resolve(".fvm/flutter_sdk/bin/flutter").toString(), FlutterPubGetRunner.executable(member, false))
    }

    @Test fun independentNestedPackageNeverBorrowsAnAncestorConfig() {
        setupWorkspace()
        write(member.resolve("pubspec.yaml"), "name: independent\n")
        assertEquals(member.resolve(".dart_tool/package_config.json"), DartPackageContext.packageConfig(member))
        assertEquals("dart", FlutterPubGetRunner.executable(member, false))
        write(member.resolve("pubspec_overrides.yaml"), "resolution: workspace\n")
        assertEquals(config, DartPackageContext.packageConfig(member))
        write(member.resolve("pubspec_overrides.yaml"), "resolution: null\n")
        assertEquals(member.resolve(".dart_tool/package_config.json"), DartPackageContext.packageConfig(member))
    }

    @Test fun nestedWorkspacesShareTheOutermostResolutionContext() {
        setupWorkspace()
        write(workspace.resolve("pubspec.yaml"), "name: workspace\nworkspace: [packages/app, packages/server]\n")
        val server = workspace.resolve("packages/server")
        val auth = server.resolve("auth")
        write(server.resolve("pubspec.yaml"), "name: server\nresolution: workspace\nworkspace: [auth]\n")
        write(auth.resolve("pubspec.yaml"), "name: auth\nresolution: workspace\n")
        assertEquals(workspace, DartPackageContext.root(auth))
        assertEquals(config, DartPackageContext.packageConfig(auth))
        val rootDirectory = Directory(workspace)
        rootDirectory.children["pubspec.yaml"] = file(workspace.resolve("pubspec.yaml"))
        val serverDirectory = Directory(server, rootDirectory)
        serverDirectory.children["pubspec.yaml"] = file(server.resolve("pubspec.yaml"))
        val authDirectory = Directory(auth, serverDirectory)
        authDirectory.children["pubspec.yaml"] = file(auth.resolve("pubspec.yaml"))
        assertEquals(rootDirectory, DartPackageContext.root(authDirectory))
    }

    @Test fun sharedMetadataInvalidatesMembersAndResolvedImages() {
        setupWorkspace()
        val request = ImageRequest(member.toString(), "app", "packages/icons/logo.png")
        val info = AssetImageLoader.Info(base.resolve("icons/lib/logo.png"), status = AssetImageLoader.Status.READY)
        for (path in listOf(config, config.parent, workspace.resolve("pubspec.yaml"), member.resolve("pubspec_overrides.yaml"))) {
            assertTrue(DartPackageContext.metadataAffected(member, path))
            assertTrue(ImageInvalidation.affected(request, info, path))
        }
        assertTrue(DartPackageContext.sdkAffected(member, workspace.resolve(".fvm/flutter_sdk/bin/cache/flutter.version.json")))
        assertFalse(DartPackageContext.metadataAffected(member, workspace.resolve("packages/other/.dart_tool/package_config.json")))
        assertFalse(DartPackageContext.sdkAffected(member, base.resolve("other/.fvm/flutter_sdk/bin/flutter")))
        assertFalse(ImageInvalidation.affected(request, info, base.resolve("other/pubspec.yaml")))
    }
}
