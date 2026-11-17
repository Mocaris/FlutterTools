package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import org.junit.Assert.*
import org.junit.Test

class DependencyAdderTest : YamlPsiTest() {
    private fun add(source: String, section: String = "dependencies") = DependencyAdder.updatedText(yaml(source), "dio", "5.0.0", section)
    private fun added(text: String, section: String = "dependencies") = PubspecDependencyParser.parse(yaml(text))
        .single { it.name == "dio" && it.section == section }

    @Test fun missingEmptyAndFlowSectionsAcceptOneHostedDependency() {
        for (source in listOf("name: app", "name: app\ndependencies:\n", "name: app\ndependencies: {}",
            "{name: app, dependencies: {}}", "name: app\ndependencies: {http: ^1.0.0,}")) {
            val text = add(source)
            assertEquals("^5.0.0", added(text).constraint)
            assertTrue(text.endsWith('\n'))
        }
    }

    @Test fun insertionPreservesCommentsAndNestedSiblingSettings() {
        val source = """
            # header
            name: app
            dependencies:
              http: ^1.0.0 # keep http
            flutter:
              assets:
                - assets/ # keep assets
        """.trimIndent()
        val text = add(source)
        assertEquals("^5.0.0", added(text).constraint)
        assertTrue(text.contains("http: ^1.0.0 # keep http"))
        assertTrue(text.contains("flutter:\n  assets:\n    - assets/ # keep assets"))
        assertTrue(text.startsWith("# header"))
    }

    @Test fun existingPublicDependenciesUpdateInPlaceWithQuotesAndComments() {
        val text = add("name: app\ndependencies:\n  dio: '^4.0.0' # keep")
        assertEquals("^5.0.0", added(text).constraint)
        assertTrue(text.contains("dio: '^5.0.0' # keep"))
        assertEquals(1, Regex("dio:").findAll(text).count())
        val hosted = add("name: app\ndependencies:\n  dio: {hosted: https://pub.dev, version: '4.0.0'}")
        assertEquals("5.0.0", added(hosted).constraint)
        assertTrue(hosted.contains("hosted: https://pub.dev"))
    }

    @Test fun dependencySectionsDoNotCreateDuplicatesOrMoveExistingEntries() {
        val text = add("name: app", "dev_dependencies")
        assertEquals("dev_dependencies", added(text, "dev_dependencies").section)
        assertThrows(IllegalArgumentException::class.java) { add(text) }
    }

    @Test fun localGitSdkCustomHostedAliasesAndOverridesAreNotReplaced() {
        for (value in listOf("{path: ../dio}", "{git: https://example.com/dio}", "{sdk: flutter}",
            "{hosted: https://private.example.com, version: any}")) {
            assertThrows(IllegalArgumentException::class.java) { add("name: app\ndependencies:\n  dio: $value") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            add("name: app\ndependencies:\n  dio: any\ndependency_overrides:\n  dio: ^4.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            add("name: app\ndependencies:\n  network: {hosted: {name: dio, url: https://pub.dev}, version: any}")
        }
        val override = yaml("dependency_overrides: {dio: ^4.0.0}")
        assertThrows(IllegalArgumentException::class.java) {
            DependencyAdder.updatedText(yaml("name: app"), "dio", "5.0.0", "dependencies", override)
        }
    }

    @Test fun updateAfterExternalOverrideReplacementEditsTheOriginalDependency() {
        val file = yaml("name: app\ndependencies: {dio: '^4.0.0'}\ndependency_overrides: {dio: {path: ../dio}}")
        val external = yaml("dependency_overrides: {}")
        val current = DependencyAdder.existing(file, "dio", external)!!
        assertFalse(current.overridden)
        val updated = DependencyAdder.updatedText(file, "dio", "5.0.0", "dependencies", external)
        assertTrue(updated.contains("dio: '^5.0.0'"))
        assertTrue(updated.contains("dio: {path: ../dio}"))
        assertEquals("^5.0.0", PubspecDependencyParser.parse(yaml(updated), external).single().constraint)
    }

    @Test fun invalidYamlAndNamesFailBeforeProducingAnEdit() {
        assertThrows(IllegalArgumentException::class.java) { add("name: app\ndependencies: [broken") }
        assertThrows(IllegalArgumentException::class.java) { DependencyAdder.updatedText(yaml("name: app"), "dio\nother", "5.0.0", "dependencies") }
        assertThrows(IllegalArgumentException::class.java) { DependencyAdder.updatedText(yaml("name: app"), "dio", "5.0", "dependencies") }
    }

    @Test fun explicitlySelectedPrereleaseCanBeAdded() {
        val text = DependencyAdder.updatedText(yaml("name: app"), "dio", "5.0.0-beta.1", "dependencies")
        assertEquals("^5.0.0-beta.1", added(text).constraint)
    }
}
