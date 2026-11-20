package com.mocaris.plugin.flutter.tools.dependencies.ui

import com.mocaris.plugin.flutter.tools.core.localization.LocalizedText
import com.mocaris.plugin.flutter.tools.dependencies.*
import com.mocaris.plugin.flutter.tools.dependencies.installation.DependencyVersionEditor
import com.mocaris.plugin.flutter.tools.dependencies.versions.DependencyResult
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageDependency
import org.junit.Assert.*
import org.junit.Test

class DependencyUpdatePopupTest {
    private fun result(latest: String? = "1.0.0", installed: String? = "1.0.0", error: Boolean = false, source: String = "pub.dev") =
        DependencyResult(PackageDependency("http", section = "dependencies", constraint = "^1.0.0", source = source),
            installed, latest, LocalizedText("ready"), error)

    @Test fun upToDateAndFailedChecksStillOfferBothDocumentsWithoutUpgrades() {
        for (result in listOf(result(), result(error = true), result(latest = null))) {
            assertFalse(DependencyVersionEditor.updateAvailable(result))
            val choices = DependencyUpdatePopup.choices(result)
            assertEquals(listOf("readme", "changelog"), choices.map { it.document })
            assertTrue(choices.all { it.version == "1.0.0" })
        }
    }

    @Test fun upgradeActionsRemainAlongsideDocuments() {
        val choices = DependencyUpdatePopup.choices(result(latest = "2.0.0"))
        assertEquals(2, choices.count { it.document == null })
        assertEquals(listOf(false, true), choices.filter { it.document == null }.map { it.pubGet })
        assertEquals(listOf("readme", "changelog"), choices.mapNotNull { it.document })
        assertTrue(DependencyUpdatePopup.choices(result(source = "path")).isEmpty())
    }
}
