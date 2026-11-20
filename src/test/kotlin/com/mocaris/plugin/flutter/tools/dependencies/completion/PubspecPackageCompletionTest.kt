package com.mocaris.plugin.flutter.tools.dependencies.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings

class PubspecPackageCompletionTest : BasePlatformTestCase() {
    private fun candidates(vararg items: PackageCandidate) {
        assertTrue("completion contributor registered for ${myFixture.file.language}", CompletionContributor.forLanguage(myFixture.file.language).any { it is PubspecPackageCompletion })
        assertNotNull("dependency context", DependencyCompletionContext.find(myFixture.editor.document.charsSequence, myFixture.editor.caretModel.offset))
        project.service<FlutterToolsSettings>().state.includePrerelease = false
        val service = project.service<PackageCompletionService>()
        val field = PackageCompletionService::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(service) as MutableMap<String, PackageCompletionService.Snapshot>
        cache["dio:false"] = PackageCompletionService.Snapshot(items.toList())
        cache["di:false"] = PackageCompletionService.Snapshot(items.toList())
    }

    fun testSelectAddsVersionAndRetainsComment() {
        myFixture.configureByText("pubspec.yaml", "name: app\ndependencies:\n  dio<caret>: # keep\n")
        candidates(PackageCandidate("dio", "5.0.0"))
        val choices = myFixture.completeBasic()!!
        assertEquals(listOf("dio"), choices.map { it.lookupString })
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("name: app\ndependencies:\n  dio: ^5.0.0<caret> # keep\n")
    }

    fun testMiddleOfNameReplacesWholeKeyAndKeepsConstraint() {
        myFixture.configureByText("pubspec.yaml", "name: app\ndev_dependencies:\n  di<caret>other: ^2.0.0 # keep\n")
        candidates(PackageCandidate("dio", "5.0.0"))
        myFixture.completeBasic()
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("name: app\ndev_dependencies:\n  dio<caret>: ^2.0.0 # keep\n")
    }

    fun testAlreadyDeclaredPackagesAreNotSuggestedAgain() {
        myFixture.configureByText("pubspec.yaml", "name: app\ndependencies:\n  dio: ^5.0.0\ndev_dependencies:\n  di<caret>\n")
        candidates(PackageCandidate("dio", "5.0.0"), PackageCandidate("flutter_dio", "1.0.0"))
        assertEquals(listOf("flutter_dio"), myFixture.completeBasic()!!.map { it.lookupString })
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("name: app\ndependencies:\n  dio: ^5.0.0\ndev_dependencies:\n  flutter_dio: ^1.0.0<caret> \n")
    }

    fun testTabSelectionReplacesNameAndFillsEmptyVersion() {
        myFixture.configureByText("pubspec.yaml", "name: app\ndependencies:\n  di<caret>other:\n")
        candidates(PackageCandidate("dio", "5.0.0"))
        myFixture.completeBasic()
        myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
        myFixture.checkResult("name: app\ndependencies:\n  dio: ^5.0.0<caret>\n")
    }
}
