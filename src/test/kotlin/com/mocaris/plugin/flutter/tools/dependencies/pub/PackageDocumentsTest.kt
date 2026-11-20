package com.mocaris.plugin.flutter.tools.dependencies.pub

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PackageDocumentsTest {
    @Test fun versionUrlsAreValidatedAndSelectTheRequestedDocument() {
        assertEquals("https://pub.dev/packages/demo/versions/1.2.3/changelog", PackageDocuments.url("demo", "1.2.3", "changelog"))
        assertEquals("https://pub.dev/packages/demo/versions/1.2.3%2B2", PackageDocuments.url("demo", "1.2.3+2", "readme"))
        assertThrows(IllegalArgumentException::class.java) { PackageDocuments.url("../demo", "1.0.0", "readme") }
        assertThrows(IllegalArgumentException::class.java) { PackageDocuments.url("demo", "../1.0.0", "readme") }
    }

    @Test fun previewRemovesActiveMarkupAndRemoteImagesWhileKeepingDocumentation() {
        val source = """<section class="detail-tab-readme-content markdown-body">
            <h1 id="usage">Usage</h1><pre><code>Image.asset('logo.png')</code></pre>
            <a href="/packages/demo/changelog">Changes</a><a href="javascript:alert(1)">bad</a>
            <img src="https://example.invalid/tracking.png"><script>evil()</script><iframe src="https://example.invalid"></iframe>
        </section><section class="detail-tab-changelog-content markdown-body"><h2>1.0.0</h2><p>Fixed images.</p></section>"""
        val readme = PackageDocuments.parse(source, "https://pub.dev/packages/demo", "readme")
        assertTrue(readme.html.contains("Image.asset")); assertTrue(readme.html.contains("https://pub.dev/packages/demo/changelog"))
        for (value in listOf("<img", "<script", "<iframe", "javascript:", "tracking.png", "evil()")) assertFalse(readme.html.contains(value))
        val changes = PackageDocuments.parse(source, readme.url, "changelog")
        assertTrue(changes.html.contains("Fixed images.")); assertFalse(changes.html.contains("Image.asset"))
    }

    @Test fun oversizedContentIsBoundedAndMissingPagesDoNotBecomeSuccessfulCacheEntries() {
        val large = "<section class='detail-tab-readme-content markdown-body'><p>" + "&lt;&amp;&quot;".repeat(100_000) + "</p></section>"
        val document = PackageDocuments.parse(large, "https://pub.dev/packages/demo", "readme")
        assertTrue(document.html.length < PackageDocuments.MAX_CONTENT_CHARS + 20)
        assertThrows(IllegalArgumentException::class.java) { PackageDocuments.parse("<html>Unavailable</html>", document.url, "readme") }
    }

    @Test fun discontinuedStatusAndReplacementSurviveVersionSelection() {
        val info = PubDevClient.parseInfo(JsonParser.parseString("""{"name":"demo","isDiscontinued":true,"replacedBy":"replacement",
            "latest":{"pubspec":{}},"versions":[{"version":"1.0.0","published":"2025-01-01T00:00:00Z","pubspec":{}},{"version":"2.0.0","published":"2026-01-01T00:00:00Z","pubspec":{}}]}""").asJsonObject, "demo")
        assertTrue(info.forVersion("1.0.0").discontinued)
        assertEquals("replacement", info.forVersion("1.0.0").replacedBy)
        assertEquals("2025-01-01T00:00:00Z", info.versions.single { it.version == "1.0.0" }.published)
    }
}
