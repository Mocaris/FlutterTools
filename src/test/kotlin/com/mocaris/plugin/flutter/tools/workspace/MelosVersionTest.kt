package com.mocaris.plugin.flutter.tools.workspace

import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageRelease
import com.mocaris.plugin.flutter.tools.dependencies.sdk.ProjectSdk
import org.junit.Assert.*
import org.junit.Test

class MelosVersionTest {
    private val releases = listOf(
        PackageRelease("8.0.0-dev.1", "", ">=3.6.0 <4.0.0", null),
        PackageRelease("7.4.0", "", ">=3.9.0 <4.0.0", null),
        PackageRelease("7.1.0", "", ">=3.6.0 <4.0.0", null),
        PackageRelease("6.3.0", "", ">=3.0.0 <4.0.0", null))
    private fun sdk(version: String, constraint: String = ">=3.6.0 <4.0.0") = ProjectSdk("app", constraint, null, version, null)

    @Test fun `selects latest stable release allowed by installed dart`() {
        assertEquals("7.1.0", MelosVersion.latest(releases, sdk("3.6.2")))
        assertEquals("7.4.0", MelosVersion.latest(releases.reversed(), sdk("3.10.0")))
    }

    @Test fun `does not choose legacy melos or unknown sdk`() {
        assertThrows(IllegalArgumentException::class.java) { MelosVersion.latest(releases, sdk("3.5.0")) }
        assertThrows(IllegalArgumentException::class.java) { MelosVersion.latest(releases, null) }
        assertThrows(IllegalArgumentException::class.java) { MelosVersion.latest(releases, sdk("3.10.0", ">=3.6.0 <3.9.0")) }
    }
}
