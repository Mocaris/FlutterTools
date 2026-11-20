package com.mocaris.plugin.flutter.tools.dependencies.pub

import com.google.gson.JsonParser
import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import com.mocaris.plugin.flutter.tools.dependencies.PubspecDependencyParser
import com.mocaris.plugin.flutter.tools.dependencies.installation.DependencyAdder
import com.mocaris.plugin.flutter.tools.dependencies.sdk.ProjectSdk
import org.junit.Assert.*
import org.junit.Test

class PackageHistoryTest : YamlPsiTest() {
    private fun history() = PubDevClient.parseInfo(JsonParser.parseString("""
        {"name":"demo","latest":{"version":"10.0.0","pubspec":{}},"versions":[
          {"version":"2.0.0","pubspec":{"description":"older","environment":{"sdk":"^3.0.0","flutter":">=3.10.0"}}},
          {"version":"10.0.0","pubspec":{"description":"latest","environment":{"sdk":"^3.10.0","flutter":">=3.38.0"}}},
          {"version":"11.0.0-beta.1","pubspec":{"description":"preview","environment":{"sdk":"^3.10.0"}}},
          {"version":"12.0.0","retracted":true,"pubspec":{}},
          {"version":"invalid","pubspec":{}}
        ]}
    """).asJsonObject, "demo")

    @Test fun historyIsSortedByPubVersionAndExcludesRetractedAndInvalidReleases() {
        val info = history()
        assertEquals(listOf("11.0.0-beta.1", "10.0.0", "2.0.0"), info.versions.map { it.version })
        assertEquals("10.0.0", info.version)
        assertEquals("11.0.0-beta.1", info.metadata.newest)
    }

    @Test fun selectingAnOlderVersionChangesTheDisplayedSdkRequirementsAndComparison() {
        val latest = history()
        val older = latest.forVersion("2.0.0")
        val sdk = ProjectSdk("app", "^3.9.0", ">=3.30.0", "3.9.2", "3.35.0")
        assertEquals(listOf("Dart", "Flutter"), sdk.mismatches(latest))
        assertTrue(sdk.mismatches(older).isEmpty())
        assertEquals("older", older.description)
        assertEquals("^3.0.0", older.dartSdk)
        assertEquals(">=3.10.0", older.flutterSdk)
        assertEquals("10.0.0", latest.version)
    }

    @Test fun insertionAndUpdateUseTheSelectedVersionDespiteMainSdkMismatch() {
        val chosen = history().forVersion("10.0.0")
        val sdk = ProjectSdk("app", "^3.9.0", null, "3.9.2", "3.35.0")
        assertFalse(sdk.mismatches(chosen).isEmpty())
        for (source in listOf("name: app\nenvironment:\n  sdk: ^3.9.0", "name: app\ndependencies:\n  demo: ^1.0.0 # keep")) {
            val updated = DependencyAdder.updatedText(yaml(source), chosen.name, chosen.version!!, "dependencies")
            assertEquals("^10.0.0", PubspecDependencyParser.parse(yaml(updated)).single().constraint)
            if (source.contains("# keep")) assertTrue(updated.contains("# keep"))
        }
        val older = history().forVersion("2.0.0")
        val updated = DependencyAdder.updatedText(yaml("name: app"), older.name, older.version!!, "dependencies")
        assertEquals("^2.0.0", PubspecDependencyParser.parse(yaml(updated)).single().constraint)
    }

    @Test fun prereleaseOnlyPackageHasAnExplicitlySelectableRelease() {
        val info = PubDevClient.parseInfo(JsonParser.parseString("""
            {"name":"demo","latest":{"version":"1.0.0-beta.1","pubspec":{}},"versions":[
              {"version":"1.0.0-beta.1","pubspec":{"environment":{"sdk":"^3.0.0"}}}
            ]}
        """).asJsonObject, "demo")
        assertNull(info.metadata.stable)
        assertEquals("1.0.0-beta.1", info.version)
        assertEquals("^3.0.0", info.dartSdk)
    }

    @Test fun staleOrUnsetVersionSelectionFallsBackToDefaultRelease() {
        val info = history()
        assertEquals(info, info.forVersion(null))
        assertEquals(info, info.forVersion("12.0.0"))
    }
}
