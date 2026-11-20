package com.mocaris.plugin.flutter.tools.dependencies.versions

import com.mocaris.plugin.flutter.tools.dependencies.*
import org.junit.Assert.*
import org.junit.Test

class PackageVersionsTest {
    private fun version(text: String) = PubVersion.parse(text)!!
    @Test fun numericVersionOrdering() { assertTrue(version("1.10.0") > version("1.9.9")); assertTrue(version("2.0.0") > version("1.99.99")) }
    @Test fun prereleaseOrdering() {
        assertTrue(version("1.0.0-beta.10") > version("1.0.0-beta.2"))
        assertTrue(version("1.0.0") > version("1.0.0-rc.1"))
        assertTrue(version("1.0.0-beta") > version("1.0.0-1"))
    }
    @Test fun pubBuildOrdering() { assertTrue(version("1.0.0+10") > version("1.0.0+2")); assertTrue(version("1.0.0+1") > version("1.0.0")) }
    @Test fun caretAllowsCompatibleVersions() {
        assertEquals(true, PubConstraint.allows("^1.2.0", version("1.5.0")))
        assertEquals(false, PubConstraint.allows("^1.2.0", version("2.0.0")))
        assertEquals(false, PubConstraint.allows("^1.2.0", version("1.1.9")))
    }
    @Test fun dartZeroMajorCaretUsesNextMinor() {
        assertEquals(true, PubConstraint.allows("^0.2.1", version("0.2.9")))
        assertEquals(false, PubConstraint.allows("^0.2.1", version("0.3.0")))
        assertEquals(true, PubConstraint.allows("^0.0.1", version("0.0.9")))
        assertEquals(false, PubConstraint.allows("^0.0.1", version("0.1.0")))
    }
    @Test fun rangesFixedAndAny() {
        assertEquals(true, PubConstraint.allows(">=1.0.0 <2.0.0", version("1.5.0")))
        assertEquals(false, PubConstraint.allows(">=1.0.0 <2.0.0", version("2.0.0")))
        assertEquals(false, PubConstraint.allows("1.0.0", version("1.0.1")))
        assertEquals(true, PubConstraint.allows("any", version("9.0.0")))
    }
    @Test fun invalidVersionsAndConstraintsAreNotReportedCompatible() {
        assertNull(PubVersion.parse("01.2.0")); assertNull(PubVersion.parse("v1.0.0"))
        assertNull(PubConstraint.allows("~1.0.0", version("1.0.1")))
        assertNull(PubConstraint.allows(">=1.0.0 garbage", version("1.0.1")))
    }
    @Test fun movingSourceLocationsDoesNotChangeDependencySignature() {
        val dependency = PackageDependency("http", section = "dependencies", constraint = "^1.0.0", source = "pub.dev")
        assertEquals(dependency.signature(), dependency.copy(keyOffset = 200, versionOffset = 250).signature())
        assertNotEquals(dependency.signature(), dependency.copy(source = "git").signature())
    }

    @Test fun upperReleaseBoundaryExcludesItsPrereleasesUnlessExplicitlyRequested() {
        assertEquals(false, PubConstraint.allows("^1.0.0", version("2.0.0-beta.1")))
        assertEquals(false, PubConstraint.allows(">=1.0.0 <2.0.0", version("2.0.0-beta.1")))
        assertEquals(true, PubConstraint.allows(">=2.0.0-beta.1 <2.0.0", version("2.0.0-beta.2")))
    }
}
