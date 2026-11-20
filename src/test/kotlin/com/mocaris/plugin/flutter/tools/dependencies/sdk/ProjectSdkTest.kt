package com.mocaris.plugin.flutter.tools.dependencies.sdk

import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageInfo
import com.mocaris.plugin.flutter.tools.dependencies.pub.PubDevClient
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubConstraint
import org.junit.Assert.*
import org.junit.Test

class ProjectSdkTest {
    private fun info(dart: String? = ">=3.0.0 <4.0.0", flutter: String? = null) =
        PackageInfo("demo", PubDevClient.Metadata("1.0.0", "1.0.0"), "1.0.0", "", dart, flutter, null, null, null)

    @Test fun mainDartSdkRejectsNewerPackageEvenWhenDeclaredRangesOverlap() {
        val main = ProjectSdk("app", "^3.9.0", null, "3.9.2", "3.35.0")
        assertEquals(listOf("Dart"), main.mismatches(info("^3.10.0")))
        assertTrue(main.mismatches(info(">=2.14.0 <4.0.0")).isEmpty())
    }

    @Test fun flutterRequirementIsCheckedAsWellAsDart() {
        val main = ProjectSdk("app", "^3.9.0", ">=3.30.0", "3.9.2", "3.35.0")
        assertEquals(listOf("Flutter"), main.mismatches(info(flutter = ">=3.38.0")))
        assertEquals(listOf("Dart", "Flutter"), main.mismatches(info("^3.10.0", ">=3.38.0")))
        assertTrue(main.mismatches(info(flutter = ">=3.30.0")).isEmpty())
    }

    @Test fun disjointMainConstraintsRejectPackagesWithoutResolvedSdkFiles() {
        val main = ProjectSdk("app", ">=2.17.0 <3.0.0", ">=3.0.0 <3.10.0", null, null)
        assertEquals(listOf("Dart", "Flutter"), main.mismatches(info("^3.0.0", ">=3.20.0")))
    }

    @Test fun missingFlutterRequirementDoesNotRequireFlutterSdkDetection() {
        assertTrue(ProjectSdk("app", "^3.0.0", null, "3.9.2", null).mismatches(info()).isEmpty())
    }

    @Test fun unresolvedSdkDoesNotInventAMismatchForOverlappingConstraints() {
        assertTrue(ProjectSdk("app", "^3.9.0", null, null, null).mismatches(info("^3.10.0")).isEmpty())
    }

    @Test fun mainProjectRequirementStillAppliesWhenResolvedSdkIsOutsideIt() {
        val main = ProjectSdk("app", ">=3.0.0 <3.9.0", null, "3.10.0", null)
        assertEquals(listOf("Dart"), main.mismatches(info("^3.10.0")))
    }

    @Test fun intervalComparisonHandlesCaretExactAndExclusiveBounds() {
        assertEquals(true, PubConstraint.overlaps("^3.9.0", ">=3.10.0 <4.0.0"))
        assertEquals(false, PubConstraint.overlaps("^2.0.0", "^3.0.0"))
        assertEquals(false, PubConstraint.overlaps(">=3.0.0 <3.9.0", ">=3.9.0"))
        assertEquals(true, PubConstraint.overlaps(">=3.0.0 <=3.9.0", ">=3.9.0"))
        assertEquals(true, PubConstraint.overlaps("3.9.0", "^3.0.0"))
        assertEquals(false, PubConstraint.overlaps("3.9.0", "<3.9.0"))
        assertEquals(true, PubConstraint.overlaps("any", "^3.0.0"))
        assertEquals(true, PubConstraint.overlaps(">= 3.0.0<4.0.0", "^3.9.0"))
        assertEquals(false, PubConstraint.overlaps(">=3.10.0 <3.0.0", "any"))
    }

    @Test fun unsupportedConstraintRemainsUnknown() {
        assertNull(PubConstraint.overlaps("broken", "^3.0.0"))
        assertNull(PubConstraint.overlaps("^3.0.0", ">=three"))
    }
}
