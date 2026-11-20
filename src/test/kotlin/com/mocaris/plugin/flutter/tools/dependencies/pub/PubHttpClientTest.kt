package com.mocaris.plugin.flutter.tools.dependencies.pub

import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PubHttpClientTest {
    @Test fun primaryFailuresUseTheMirrorAndRetryPrimaryAfterCooldown() {
        val calls = mutableListOf<String>()
        var time = 0L
        var failPrimary = true
        val client = PubHttpClient({ url ->
            calls.add(url)
            if (url.startsWith(PubHttpClient.PRIMARY) && failPrimary) throw IOException("unavailable")
            "{\"ok\":true}"
        }, { time })
        assertTrue(client.get("/api/search?q=dio&page=1") { it.get("ok").asBoolean })
        assertEquals(listOf(PubHttpClient.PRIMARY, PubHttpClient.MIRROR), calls.map { it.substringBefore("/api/") })
        calls.clear()
        client.get("/api/packages/dio") { it }
        assertEquals(listOf(PubHttpClient.MIRROR + "/api/packages/dio"), calls)
        time = 60_001; failPrimary = false; calls.clear()
        client.get("/api/packages/dio/score") { it }
        assertEquals(listOf(PubHttpClient.PRIMARY + "/api/packages/dio/score"), calls)
    }

    @Test fun invalidPrimaryResponsesAlsoFallBack() {
        for (invalid in listOf("not-json", "{}")) {
            val calls = mutableListOf<String>()
            val client = PubHttpClient({ url -> calls.add(url); if (url.startsWith(PubHttpClient.PRIMARY)) invalid else "{\"version\":\"1.0.0\"}" })
            assertEquals("1.0.0", client.get("/api/packages/demo") { it.get("version").asString })
            assertEquals(2, calls.size)
        }
    }

    @Test fun bothFailuresKeepThePrimaryCauseAndCancellationNeverRetries() {
        val client = PubHttpClient({ throw IOException(it) })
        val failure = assertThrows(IOException::class.java) { client.get("/api/search?q=dio") { it } }
        assertTrue(failure.message!!.startsWith(PubHttpClient.MIRROR))
        assertEquals(1, failure.suppressed.size)
        var calls = 0
        val canceled = PubHttpClient({ calls++; throw CancellationException() })
        assertThrows(CancellationException::class.java) { canceled.get("/api/packages/dio") { it } }
        assertEquals(1, calls)
    }

    @Test fun packageDetailsUseTheLatestNonRetractedStableRelease() {
        val json = JsonParser.parseString("""
            {"name":"demo","latest":{"version":"3.0.0","pubspec":{"description":"retracted"}},"versions":[
              {"version":"1.0.0","pubspec":{"description":"old"}},
              {"version":"2.0.0","pubspec":{"description":"stable","environment":{"sdk":">=3.0.0 <4.0.0"},"repository":"https://example.com/demo"}},
              {"version":"2.1.0-beta.1","pubspec":{"description":"beta"}},
              {"version":"3.0.0","retracted":true,"pubspec":{"description":"retracted"}}
            ]}
        """).asJsonObject
        val info = PubDevClient.parseInfo(json, "demo")
        assertEquals("2.0.0", info.version)
        assertEquals("2.1.0-beta.1", info.metadata.newest)
        assertEquals("stable", info.description)
        assertEquals(">=3.0.0 <4.0.0", info.dartSdk)
        assertEquals("https://example.com/demo", info.repository)
    }
}
