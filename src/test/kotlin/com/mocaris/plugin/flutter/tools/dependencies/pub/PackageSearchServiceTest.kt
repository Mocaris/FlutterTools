package com.mocaris.plugin.flutter.tools.dependencies.pub

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class PackageSearchServiceTest : BasePlatformTestCase() {
    private fun info(name: String, version: String) = PubDevClient.parseInfo(JsonParser.parseString("""
        {"name":"$name","latest":{"pubspec":{}},"versions":[{"version":"$version","pubspec":{}}]}
    """).asJsonObject, name)

    fun testChecksSearchAndDiskReuseOneSuccessfulResponseAndKeepItsTimestamp() = runBlocking {
        val name = "cache_test_${System.nanoTime()}"
        val service = PackageSearchService(project)
        val expected = info(name, "1.0.0")
        var calls = 0
        val entry = service.infoEntry(name) { calls++; expected }
        assertEquals(entry, service.cachedInfo(name))
        assertEquals(expected, service.info(name))
        assertEquals(entry, PackageSearchService(project).cachedInfo(name))
        assertEquals(1, calls)
    }

    fun testConcurrentForcedChecksFetchOnceAndFailuresKeepTheSuccessfulResponse() = runBlocking {
        val name = "cache_test_${System.nanoTime()}"
        val service = PackageSearchService(project)
        service.infoEntry(name) { info(name, "1.0.0") }
        delay(2)
        val startedAt = System.currentTimeMillis()
        val calls = AtomicInteger()
        val updated = info(name, "2.0.0")
        val entries = coroutineScope {
            (1..2).map { async {
                service.infoEntry(name, force = true, startedAt = startedAt) { calls.incrementAndGet(); updated }
            } }.awaitAll()
        }
        assertEquals(1, calls.get())
        assertEquals(entries.first(), entries.last())
        delay(2)
        try {
            service.infoEntry(name, force = true) { throw IOException("offline") }
            fail("Expected request failure")
        } catch (_: IOException) { }
        assertEquals(updated, service.info(name))
        assertEquals(entries.first(), service.cachedInfo(name))
    }
}
