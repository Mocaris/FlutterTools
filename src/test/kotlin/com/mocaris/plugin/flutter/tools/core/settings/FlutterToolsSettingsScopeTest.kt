package com.mocaris.plugin.flutter.tools.core.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test

class FlutterToolsSettingsScopeTest {
    @Test fun newProjectsFollowGlobalDefaultsButKeepTheirOwnPackageSelection() {
        val first = FlutterToolsSettings()
        val second = FlutterToolsSettings()
        first.state.selectedPackagePath = "/first"
        second.state.selectedPackagePath = "/second"
        val global = FlutterToolsSettings.Options(assetImagePreviews = false, resourceCompletionDelayMillis = 350)
        assertFalse(first.effective(global).assetImagePreviews)
        assertFalse(second.effective(global).assetImagePreviews)
        assertEquals(350, first.effective(global).resourceCompletionDelayMillis)
        assertEquals("/first", first.effective(global).selectedPackagePath)
        assertEquals("/second", second.effective(global).selectedPackagePath)
        assertTrue(first.effective(global.copy(assetImagePreviews = true)).assetImagePreviews)
    }

    @Test fun projectOverridesRemainIndependentOfGlobalChangesAndOtherProjects() {
        val first = FlutterToolsSettings()
        val second = FlutterToolsSettings()
        first.loadState(FlutterToolsSettings.Options(useGlobalSettings = false, cacheMinutes = 30))
        val global = FlutterToolsSettings.Options(cacheMinutes = 240)
        assertEquals(30, first.effective(global).cacheMinutes)
        assertEquals(240, second.effective(global).cacheMinutes)
        assertEquals(30, first.effective(global.copy(cacheMinutes = 60)).cacheMinutes)
        assertEquals(240, global.cacheMinutes)
        first.state.useGlobalSettings = true
        assertEquals(240, first.effective(global).cacheMinutes)
    }

    @Test fun oldCustomProjectSettingsArePreservedWhileDefaultWorkspacesInherit() {
        val custom = FlutterToolsSettings()
        custom.loadState(FlutterToolsSettings.Options(assetImagePreviews = false, cacheMinutes = 15))
        assertEquals(false, custom.state.useGlobalSettings)
        val oldDefault = FlutterToolsSettings()
        oldDefault.loadState(FlutterToolsSettings.Options(selectedPackagePath = "/package"))
        assertEquals(true, oldDefault.state.useGlobalSettings)
        assertEquals("/package", oldDefault.state.selectedPackagePath)
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(custom.state), FlutterToolsSettings.Options::class.java)
        assertEquals(false, restored.useGlobalSettings)
    }

    @Test fun globalPersistenceNeverStoresProjectPackageSelectionOrInheritanceMode() {
        val global = FlutterToolsGlobalSettings()
        global.loadState(FlutterToolsSettings.Options(selectedPackagePath = "/project", useGlobalSettings = false, cacheMinutes = 60))
        assertEquals("", global.state.selectedPackagePath)
        assertNull(global.state.useGlobalSettings)
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(global.state), FlutterToolsSettings.Options::class.java)
        assertEquals(60, restored.cacheMinutes)
    }
}
