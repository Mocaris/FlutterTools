package com.mocaris.plugin.flutter.tools.dependencies.completion

import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.CompletionAutoPopupTestCase
import com.mocaris.plugin.flutter.tools.core.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.core.FlutterToolsIcons

class PubspecPackageAutoPopupTest : CompletionAutoPopupTestCase() {
    fun testTypingDependencyNameAutomaticallyShowsVersionCandidates() {
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            myFixture.configureByText("pubspec.yaml", "name: app\ndependencies:\n  <caret>\n")
            project.service<FlutterToolsSettings>().state.includePrerelease = false
            val service = project.service<PackageCompletionService>()
            val field = PackageCompletionService::class.java.getDeclaredField("cache").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val cache = field.get(service) as MutableMap<String, PackageCompletionService.Snapshot>
            cache["di:false"] = PackageCompletionService.Snapshot(listOf(PackageCandidate("dio", "5.0.0", "2026-01-01T00:00:00Z", 42)))
        }
        type("di")
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            assertNotNull(lookup)
            assertEquals(listOf("dio"), lookup.items.map { it.lookupString })
            val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
            lookup.items.single().renderElement(presentation)
            assertEquals("dio: ^5.0.0", presentation.itemText)
            assertTrue(presentation.isItemTextBold)
            assertSame(FlutterToolsIcons.toolWindow, presentation.icon)
            assertEquals("42", presentation.typeText)
            assertSame(FlutterToolsIcons.heart, presentation.typeIcon)
            assertFalse(presentation.tailText.isNullOrBlank())
        }
    }
}
