package com.mocaris.plugin.flutter.tools.dependencies.sdk

import com.google.gson.JsonParser
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageRelease
import com.mocaris.plugin.flutter.tools.dependencies.pub.PubDevClient
import org.junit.Assert.*
import org.junit.Test

class SdkRecommendationTest {
    private val sdk = ProjectSdk("feature", "^3.0.0", null, "3.9.2", "3.35.0")
    private fun release(version: String, dart: String? = "^3.0.0", flutter: String? = null) = PackageRelease(version, "", dart, flutter)
    private val releases = listOf(release("1.0.0"), release("3.0.0", "^3.10.0"), release("2.0.0", flutter = ">=3.30.0"))

    @Test fun recommendsHighestCompatibleReleaseRatherThanLatestPublished() {
        assertEquals("2.0.0", SdkRecommendation.recommend(releases, sdk).version)
        assertEquals(SdkRecommendation.Status.COMPATIBLE, SdkRecommendation.recommend(releases, sdk).status)
    }
    @Test fun flutterRequirementCanRejectAReleaseEvenWhenDartMatches() {
        val result = SdkRecommendation.recommend(listOf(release("2.0.0", flutter = ">=3.38.0"), release("1.0.0")), sdk)
        assertEquals("1.0.0", result.version)
    }
    @Test fun missingActualSdkAndUnsupportedConstraintsRemainUnconfirmed() {
        for (actual in listOf(null, sdk.copy(dartVersion = null))) {
            assertEquals(SdkRecommendation.Status.UNKNOWN, SdkRecommendation.recommend(releases, actual).status)
        }
        assertEquals(SdkRecommendation.Status.UNKNOWN, SdkRecommendation.recommend(listOf(release("1.0.0", "broken")), sdk).status)
        assertEquals(SdkRecommendation.Status.UNKNOWN, SdkRecommendation.recommend(listOf(release("2.0.0", null), release("1.0.0")), sdk).status)
    }
    @Test fun prereleasesAreOptInAndNoCompatibleReleaseIsExplicit() {
        val versions = releases + release("4.0.0-beta.1")
        assertEquals("2.0.0", SdkRecommendation.recommend(versions, sdk).version)
        assertEquals("4.0.0-beta.1", SdkRecommendation.recommend(versions, sdk, true).version)
        val result = SdkRecommendation.recommend(listOf(release("1.0.0", "^4.0.0")), sdk)
        assertNull(result.version); assertEquals(SdkRecommendation.Status.INCOMPATIBLE, result.status)
    }
    @Test fun destinationEnvironmentStillAppliesAndMissingFlutterVersionIsUnknown() {
        assertEquals(SdkRecommendation.Status.INCOMPATIBLE, SdkRecommendation.recommend(listOf(release("1.0.0")), sdk.copy(dart = "^2.0.0")).status)
        assertEquals(SdkRecommendation.Status.UNKNOWN, SdkRecommendation.recommend(listOf(release("1.0.0", flutter = ">=3.0.0")), sdk.copy(flutterVersion = null)).status)
    }
    @Test fun persistedReleaseRequirementsKeepTheirRecommendation() {
        val folder = java.nio.file.Files.createTempDirectory("recommendation-cache")
        try {
            val cache = com.mocaris.plugin.flutter.tools.dependencies.pub.PubLocalCache(folder)
            val metadata = PubDevClient.Metadata("3.0.0", "3.0.0", releases)
            cache.write("metadata:demo", metadata, System.currentTimeMillis())
            val restored = cache.read("metadata:demo", PubDevClient.Metadata::class.java)!!.value
            assertEquals("2.0.0", SdkRecommendation.recommend(restored.releases, sdk).version)
        } finally { folder.toFile().deleteRecursively() }
    }

    @Test fun dependencyMetadataKeepsReleaseRequirementsAndExcludesRetractedVersions() {
        val info = PubDevClient.parseInfo(JsonParser.parseString("""
            {"name":"demo","latest":{"pubspec":{}},"versions":[
              {"version":"1.0.0","pubspec":{"environment":{"sdk":"^3.0.0"}}},
              {"version":"2.0.0","pubspec":{"environment":{"sdk":"^3.10.0"}}},
              {"version":"3.0.0","retracted":true,"pubspec":{"environment":{"sdk":"^3.0.0"}}}
            ]}
        """).asJsonObject, "demo")
        assertEquals(info.versions, info.metadata.releases)
        assertEquals("2.0.0", info.metadata.stable)
        assertEquals("1.0.0", SdkRecommendation.recommend(info.metadata.releases, sdk).version)
    }
}
