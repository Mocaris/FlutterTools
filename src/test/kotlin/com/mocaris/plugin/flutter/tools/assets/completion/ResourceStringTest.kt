package com.mocaris.plugin.flutter.tools.assets.completion

import org.junit.Assert.*
import org.junit.Test

class ResourceStringTest {
    @Test fun parsesNormalRawAndTripleStringsWithExactReplacementRange() {
        for (text in listOf("'assets/a.png'", "\"assets/a.png\"", "r'assets/a.png'", "'''assets/a.png'''")) {
            val string = ResourceString.parse(text, 10)!!
            assertEquals("assets/a.png", string.value)
            assertEquals("assets/", string.prefix(string.start + 7))
            assertEquals("assets/a.png", text.substring(string.start - 10, string.end - 10))
        }
        assertEquals("assets/", ResourceString.parse("'assets/")!!.value)
    }

    @Test fun rejectsInterpolationAndMultilineValuesAndSafelyEscapesInsertedPaths() {
        assertNull(ResourceString.parse("'assets/${'$'}name.png'"))
        assertNull(ResourceString.parse("'''assets/\na.png'''"))
        val string = ResourceString.parse("'assets/it\\'s.png'")!!
        assertEquals("assets/it's.png", string.value)
        assertEquals("assets/it\\'s-\\${'$'}a.png", string.encode("assets/it's-${'$'}a.png"))
        assertEquals("assets/it'", string.prefix(string.start + 11))
        assertNull(string.prefix(string.start + 10))
        assertNull(ResourceString.parse("r'assets/'")!!.encode("assets/it's.png"))
        assertEquals("assets/${'$'}a.png", ResourceString.parse("r'assets/${'$'}a.png'")!!.value)
    }
    @Test fun escapedPathsDecodeUnicodeAndRejectMalformedOrExcessiveInput() {
        assertEquals("assets/logo.png", ResourceString.parse("'assets/lo\\x67o.png'")!!.value)
        assertEquals("assets/logo.png", ResourceString.parse("'assets/lo\\u0067o.png'")!!.value)
        assertNull(ResourceString.parse("'assets/\\xZZ.png'"))
        assertNull(ResourceString.parse("'assets/\\u006.png'"))
        assertNull(ResourceString.parse("'" + "\\\"".repeat(100_000)))
    }

}
