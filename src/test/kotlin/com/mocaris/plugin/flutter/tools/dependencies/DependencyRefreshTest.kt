package com.mocaris.plugin.flutter.tools.dependencies

import com.intellij.testFramework.LightVirtualFile
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.text
import org.junit.Assert.*
import org.junit.Test

class DependencyRefreshTest {
    private val http = PackageDependency("http", section = "dependencies", constraint = "^1.0.0", source = "pub.dev")
    private val root = LightVirtualFile("app")
    private fun state(valid: Boolean = true) = PackageVersionService.PackageState(root,
        listOf(DependencyResult(http, null, "3.0.0", text("packages.complete"))), valid = valid)

    @Test fun changingAConstraintRetainsTheLatestHintAndUsesTheLiveDependency() {
        val edited = http.copy(constraint = "^2.0.0", keyOffset = 30, versionOffset = 60)
        val result = state().resultFor(edited)!!
        assertEquals("3.0.0", result.latest)
        assertEquals(edited, result.dependency)
        assertTrue(DependencyVersionEditor.updateAvailable(result))
        val upToDate = state().resultFor(edited.copy(constraint = "^3.0.0"))!!
        assertEquals("3.0.0", upToDate.latest)
        assertFalse(DependencyVersionEditor.updateAvailable(upToDate))
    }

    @Test fun aPreviouslyInvalidSnapshotDoesNotHideHintsForValidLiveDependencies() {
        assertEquals("3.0.0", state(valid = false).resultFor(http.copy(constraint = "^2.0.0"))?.latest)
    }

    @Test fun cachedResultsAreNotReusedForLocalSourcesOrDifferentHostedPackages() {
        val state = state()
        for (source in listOf("path", "git", "sdk", "custom hosted")) {
            assertNull(state.resultFor(http.copy(source = source)))
        }
        assertNull(state.resultFor(http.copy(packageName = "another_package")))
        assertNull(state.resultFor(http.copy(name = "another_name")))
        assertNull(state.resultFor(http.copy(section = "dev_dependencies")))
    }

    @Test fun onlyChangedHostedDependenciesAreSelectedForMetadataRefresh() {
        val dio = http.copy(name = "dio", packageName = "dio")
        val previous = listOf(http.signature(), dio.signature())
        val dependencies = listOf(http.copy(constraint = "^2.0.0"), dio.copy(keyOffset = 50, versionOffset = 100))
        assertEquals(setOf("http"), DependencyRefreshPlan.changedPackages(dependencies, previous))
        assertTrue(DependencyRefreshPlan.changedPackages(listOf(dio, http), previous).isEmpty())
    }

    @Test fun newPackagesAreCheckedAndLocalDependenciesAreExcluded() {
        val dio = http.copy(name = "dio", packageName = "dio")
        val local = http.copy(name = "local", packageName = "local", source = "path")
        assertEquals(setOf("dio"), DependencyRefreshPlan.changedPackages(listOf(http, dio, local), listOf(http.signature())))
        assertEquals(setOf("http", "dio"), DependencyRefreshPlan.changedPackages(listOf(http, dio, local), null))
    }
}
