package com.mocaris.plugin.flutter.tools.dependencies.usage

import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageDependency
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DependencyUsageScanTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()
    private fun file(name: String, source: String): Path = root.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, source) }
    private fun dependency(name: String, section: String = "dependencies", source: String = "pub.dev") = PackageDependency(name, section = section, constraint = "any", source = source)
    private fun scan() = DependencyUsageScan.scan(root, Files::readString)

    @Test fun scanIsPackageScopedAndIncludesTestsAndToolsButNotGeneratedRegistration() {
        file("lib/main.dart", "import 'package:used/api.dart';")
        file("test/main_test.dart", "import 'package:test_used/api.dart';")
        file("tool/setup.dart", "export 'package:tool_used/api.dart';")
        file("packages/other/pubspec.yaml", "name: other")
        file("packages/other/lib/main.dart", "import 'package:nested_only/api.dart';")
        file(".dart_tool/main.dart", "import 'package:generated_only/api.dart';")
        file("build/main.dart", "import 'package:generated_only/api.dart';")
        file("lib/generated_plugin_registrant.dart", "import 'package:generated_only/api.dart';")
        val result = scan()
        for (name in listOf("used", "test_used", "tool_used")) assertEquals(false, result.unused(dependency(name)))
        for (name in listOf("nested_only", "generated_only", "unused")) assertEquals(true, result.unused(dependency(name)))
        assertNull(result.unused(dependency("build_runner", "dev_dependencies")))
        assertNull(result.unused(dependency("flutter", source = "sdk")))
        assertEquals(false, result.unused(dependency("alias").copy(packageName = "used")))
        assertEquals(true, result.unused(dependency("local_unused", source = "path")))
    }

    @Test fun incrementalUpdatesReadOnlyChangedFilesAndConsiderUnsavedText() {
        val first = file("lib/a.dart", "import 'package:first/a.dart';")
        val second = file("lib/b.dart", "import 'package:second/b.dart';")
        val initial = scan()
        val reads = mutableListOf<Path>()
        val updated = DependencyUsageScan.update(root, initial, setOf(first), { reads.add(it); "import 'package:edited/a.dart';" })
        assertEquals(listOf(first), reads)
        assertEquals(true, updated.unused(dependency("first")))
        assertEquals(false, updated.unused(dependency("edited")))
        assertEquals(false, updated.unused(dependency("second")))
        Files.delete(second)
        val deleted = DependencyUsageScan.update(root, updated, setOf(second), { error("deleted files must not be read") })
        assertEquals(true, deleted.unused(dependency("second")))
    }

    @Test fun UnreadableLargeOrIncompleteSourcesNeverProduceUnusedWarnings() {
        val path = file("lib/main.dart", "import 'package:foo/foo.dart'")
        assertNull(scan().unused(dependency("foo")))
        Files.writeString(path, " ".repeat(DependencyUsageScan.MAX_FILE_BYTES.toInt() + 1))
        val reads = mutableListOf<Path>()
        val large = DependencyUsageScan.scan(root, { reads.add(it); Files.readString(it) })
        assertTrue(reads.isEmpty())
        assertNull(large.unused(dependency("foo")))
        assertNull(DependencyUsageSnapshot(emptyMap(), false).unused(dependency("foo")))
    }

    @Test fun newSourcesOutsideThePackageAndCancelledScansAreNotAccepted() {
        val nested = file("nested/pubspec.yaml", "name: nested").parent.resolve("lib/file.dart")
        assertFalse(DependencyUsageScan.source(root, nested))
        assertFalse(DependencyUsageScan.source(root, root.parent.resolve("outside.dart")))
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            DependencyUsageScan.scan(root, Files::readString) { throw java.util.concurrent.CancellationException() }
        }
    }
}
