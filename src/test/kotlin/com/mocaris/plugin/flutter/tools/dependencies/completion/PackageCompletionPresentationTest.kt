package com.mocaris.plugin.flutter.tools.dependencies.completion

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Locale

class PackageCompletionPresentationTest {
    @Test fun releaseAgeUsesLocalizedUnitsAndHandlesUnavailableDates() {
        val now = Instant.parse("2026-11-17T12:00:00Z")
        fun age(seconds: Long, locale: Locale = Locale.ENGLISH) = PackageCompletionPresentation.age(now.minusSeconds(seconds).toString(), now, locale)
        assertEquals("just now", age(10))
        assertEquals("1 minute ago", age(60))
        assertEquals("2 hours ago", age(7200))
        assertEquals("19 days ago", age(19 * 86400L))
        assertEquals("3 months ago", age(90 * 86400L))
        assertEquals("3 years ago", age(1095 * 86400L))
        assertEquals("19 天前", age(19 * 86400L, Locale.SIMPLIFIED_CHINESE))
        assertEquals("just now", age(-60))
        for (value in listOf(null, "", "invalid")) assertEquals("—", PackageCompletionPresentation.age(value, now, Locale.ENGLISH))
    }
}
