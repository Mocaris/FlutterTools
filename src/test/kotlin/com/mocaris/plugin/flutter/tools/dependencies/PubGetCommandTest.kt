package com.mocaris.plugin.flutter.tools.dependencies

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class PubGetCommandTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()
    private fun write(path: Path, content: String = "") {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
    private fun spec(text: String) = write(root.resolve("pubspec.yaml"), text)

    @Test fun pureDartUsesDartEvenWithStaleFlutterPackageMetadata() {
        spec("name: cli\nenvironment: {sdk: ^3.9.0}\ndependencies: {http: ^1.0.0}")
        val sdk = root.resolve("sdk with spaces")
        write(sdk.resolve("bin/flutter"))
        write(sdk.resolve("bin/cache/dart-sdk/bin/dart"))
        write(root.resolve(".dart_tool/package_config.json"),
            """{"packages":[{"name":"flutter","rootUri":"${sdk.resolve("packages/flutter").toUri()}"}]}""")
        val command = FlutterPubGetRunner.command(root, false)
        assertFalse(command.flutter)
        assertEquals("Dart pub get", command.title)
        assertEquals(listOf(sdk.resolve("bin/cache/dart-sdk/bin/dart").toString(), "pub", "get"), command.arguments(false))
    }

    @Test fun standaloneDartUsesPathAndWindowsExecutableWithoutCommandShell() {
        spec("name: cli\nenvironment: {sdk: ^3.9.0}")
        assertEquals(listOf("dart", "pub", "get"), FlutterPubGetRunner.command(root, false).arguments(false))
        assertEquals(listOf("dart.exe", "pub", "get"), FlutterPubGetRunner.command(root, true).arguments(true))
    }

    @Test fun flutterDependenciesAndFlutterDevDependenciesUseFlutter() {
        for (text in listOf(
            "name: app\ndependencies: {flutter: {sdk: flutter}}",
            "name: app\ndev_dependencies: {flutter_test: {sdk: flutter}}",
            "name: app\nenvironment: {flutter: '>=3.0.0'}",
        )) {
            spec(text)
            val command = FlutterPubGetRunner.command(root, false)
            assertTrue(command.flutter)
            assertEquals("Flutter pub get", command.title)
            assertEquals(listOf("flutter", "pub", "get"), command.arguments(false))
            assertEquals(listOf("cmd.exe", "/c", "flutter.bat", "pub", "get"),
                FlutterPubGetRunner.command(root, true).arguments(true))
        }
    }

    @Test fun dartUsesItsProjectFvmSdk() {
        spec("name: cli")
        val sdk = root.resolve(".fvm/flutter_sdk")
        write(sdk.resolve("bin/flutter"))
        val dart = sdk.resolve("bin/cache/dart-sdk/bin/dart")
        write(dart)
        assertEquals(dart.toString(), FlutterPubGetRunner.executable(root, false))
        val windowsDart = sdk.resolve("bin/cache/dart-sdk/bin/dart.exe")
        write(windowsDart)
        assertEquals(listOf(windowsDart.toString(), "pub", "get"), FlutterPubGetRunner.command(root, true).arguments(true))
    }

    @Test fun mixedWorkspaceUsesFlutterFromBothDartMemberAndWorkspaceRootBeforePubGet() {
        val dart = root.resolve("packages/cli")
        val flutter = root.resolve("packages/app")
        spec("name: workspace\nworkspace: [packages/cli, packages/app]")
        write(dart.resolve("pubspec.yaml"), "name: cli\nresolution: workspace")
        write(flutter.resolve("pubspec.yaml"), "name: app\nresolution: workspace\ndependencies: {flutter: {sdk: flutter}}")
        write(root.resolve(".fvm/flutter_sdk/bin/flutter"))
        assertTrue(FlutterPubGetRunner.command(dart, false).flutter)
        assertEquals(root.resolve(".fvm/flutter_sdk/bin/flutter").toString(), FlutterPubGetRunner.executable(dart, false))
        assertTrue(FlutterPubGetRunner.command(root, false).flutter)
        write(flutter.resolve("pubspec.yaml"), "name: app\nresolution: workspace")
        val dartSdk = root.resolve(".fvm/flutter_sdk/bin/cache/dart-sdk/bin/dart")
        write(dartSdk)
        assertFalse(FlutterPubGetRunner.command(dart, false).flutter)
        assertEquals(dartSdk.toString(), FlutterPubGetRunner.executable(dart, false))
    }

    @Test fun externalDependencyOverridesDetermineWhetherFlutterIsRequired() {
        spec("name: app\ndependencies: {flutter: {sdk: flutter}}")
        write(root.resolve("pubspec_overrides.yaml"), "dependency_overrides: {flutter: {path: ../local}}")
        assertFalse(FlutterPubGetRunner.command(root, false).flutter)
        spec("name: app\ndependencies: {flutter_test: any}")
        write(root.resolve("pubspec_overrides.yaml"), "dependency_overrides: {flutter_test: {sdk: flutter}}")
        assertTrue(FlutterPubGetRunner.command(root, false).flutter)
    }
}
