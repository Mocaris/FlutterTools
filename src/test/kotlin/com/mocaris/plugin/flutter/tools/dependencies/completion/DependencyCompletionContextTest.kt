package com.mocaris.plugin.flutter.tools.dependencies.completion

import org.junit.Assert.*
import org.junit.Test

class DependencyCompletionContextTest {
    private fun context(source: String): DependencyCompletionContext? = DependencyCompletionContext.find(source.replace("|", ""), source.indexOf('|'))

    @Test fun findsIncompleteDependencyKeysAndWholeReplacementRange() {
        for (section in listOf("dependencies", "dev_dependencies")) {
            val key = context("name: app\n$section: # packages\n  flutter:\n    sdk: flutter\n  shared_pre|ferences: ^2.0.0 # keep")!!
            assertEquals("shared_pre", key.prefix)
            assertEquals("shared_preferences", key.name)
            assertEquals(18, key.end - key.start)
            assertNotNull(context("$section:\n    di|"))
            assertNotNull(context("$section:\n  di| # todo"))
        }
    }

    @Test fun excludesValuesCommentsSourcesAndUnrelatedYaml() {
        for (source in listOf("dependencies:\n  d|", "dependencies:\n  dio: ^5|", "dependencies:\n  # di|",
            "flutter:\n  assets:\n    - di|", "dependency_overrides:\n  di|", "dependencies: {di|: any}",
            "dependencies:\n  flutter:\n    sd|k: flutter", "dependencies:\n  lo|cal:\n    path: ../local",
            "description: >\n  dependencies:\n    di|", "dependencies:\n  package:\n    hosted:\n      na|me: dio",
            "dependencies:\n  dio: |\n    ve|rsion", "dependencies:\n  dio:\n    version: any\n  flutter:\n    sdk: flutter\nflutter:\n  fo|nts:")) {
            assertNull(source, context(source))
        }
    }

    @Test fun insertingVersionsPreservesConstraintsCommentsAndYamlSpacing() {
        fun edited(suffix: String): String {
            val edit = DependencyCompletionContext.versionEdit(suffix, "1.2.3") ?: return suffix
            return suffix.substring(0, edit.first) + edit.second + suffix.substring(edit.first)
        }
        assertEquals(": ^1.2.3 ", edited(""))
        assertEquals(": ^1.2.3", edited(":"))
        assertEquals(": ^1.2.3", edited(": "))
        assertEquals(": ^1.2.3 # keep", edited(": # keep"))
        assertEquals(": ^1.2.3   # keep", edited("  # keep"))
        for (suffix in listOf(": ^2.0.0 # keep", ": any", ": null", ": {path: ../local}", ": '>=1.0.0 <2.0.0'")) assertEquals(suffix, edited(suffix))
    }

    @Test fun relatedPackageNamesCanMatchInsideTheName() {
        val matcher = PackageNameMatcher("dio")
        assertTrue(matcher.prefixMatches("dio")); assertTrue(matcher.prefixMatches("flutter_dio"))
        assertFalse(matcher.prefixMatches("http"))
        assertTrue(matcher.cloneWithPrefix("http").prefixMatches("http_parser"))
    }
}
