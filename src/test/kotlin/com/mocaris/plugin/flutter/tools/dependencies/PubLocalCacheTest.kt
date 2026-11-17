package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.core.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.dependencies.search.PackageInfo
import com.mocaris.plugin.flutter.tools.dependencies.search.PackageRelease
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PubLocalCacheTest {
    private fun temporary(action: (Path) -> Unit) {
        val dir = Files.createTempDirectory("fluttertools-pub-cache-test")
        try { action(dir) }
        finally { Files.walk(dir).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private val metadata = PubDevClient.Metadata("1.0.0", "2.0.0-beta.1")

    @Test fun successfulMetadataSurvivesAnewCacheInstance() = temporary { dir ->
        val time = System.currentTimeMillis() - 1000
        PubLocalCache(dir).write("metadata:demo", metadata, time)
        val restored = PubLocalCache(dir).read("metadata:demo", PubDevClient.Metadata::class.java)!!
        assertEquals(metadata, restored.value)
        assertEquals(time, restored.time)
        assertTrue(restored.fresh(720))
    }

    @Test fun defaultLifetimeIsTwelveHoursAndCustomSettingsRemainIntact() {
        assertEquals(720, FlutterToolsSettings.Options().cacheMinutes)
        val cached = PubLocalCache.Entry(metadata, 1000)
        assertTrue(cached.fresh(720, 1000 + 12 * 60 * 60 * 1000L - 1))
        assertFalse(cached.fresh(720, 1000 + 12 * 60 * 60 * 1000L))
        assertFalse(cached.fresh(720, 999))
        val settings = FlutterToolsSettings()
        settings.loadState(FlutterToolsSettings.Options(cacheMinutes = 15))
        assertEquals(15, settings.state.cacheMinutes)
    }

    @Test fun refreshOverwritesOnlyTheCorrespondingCacheAndTimestamp() = temporary { dir ->
        val cache = PubLocalCache(dir)
        val time = System.currentTimeMillis() - 1000
        cache.write("metadata:demo", metadata, time)
        cache.write("metadata:other", metadata, time)
        val newer = PubDevClient.Metadata("3.0.0", "3.0.0")
        cache.write("metadata:demo", newer, time + 500)
        assertEquals(newer, PubLocalCache(dir).read("metadata:demo", PubDevClient.Metadata::class.java)?.value)
        assertEquals(time + 500, cache.read("metadata:demo", PubDevClient.Metadata::class.java)?.time)
        assertEquals(metadata, cache.read("metadata:other", PubDevClient.Metadata::class.java)?.value)
        Files.list(dir).use { files -> assertEquals(2L, files.count()) }
    }

    @Test fun corruptUnknownSchemaAndFutureDatedCacheEntriesAreIgnored() = temporary { dir ->
        val cache = PubLocalCache(dir)
        cache.write("metadata:demo", metadata, System.currentTimeMillis() - 1000)
        val file = Files.list(dir).use { it.findFirst().get() }
        Files.writeString(file, "broken json")
        assertNull(cache.read("metadata:demo", PubDevClient.Metadata::class.java))
        Files.writeString(file, """{"schema":2,"time":1,"value":{}}""")
        assertNull(cache.read("metadata:demo", PubDevClient.Metadata::class.java))
        cache.write("metadata:demo", metadata, System.currentTimeMillis() + 60_000)
        assertNull(cache.read("metadata:demo", PubDevClient.Metadata::class.java))
        cache.write("metadata:demo", metadata, System.currentTimeMillis() - 1000)
        assertEquals(metadata, cache.read("metadata:demo", PubDevClient.Metadata::class.java)?.value)
    }

    @Test fun packageHistoryAndSdkRequirementsAreRestoredForSearch() = temporary { dir ->
        val info = PackageInfo("demo", metadata, "1.0.0", "latest", "^3.9.0", ">=3.35.0", null, null, null,
            listOf(PackageRelease("0.9.0", "older", "^3.0.0", null)))
        PubLocalCache(dir).write("info:demo", info, System.currentTimeMillis() - 1000)
        val restored = PubLocalCache(dir).read("info:demo", PackageInfo::class.java)!!.value
        assertEquals(info, restored)
        assertEquals("^3.0.0", restored.forVersion("0.9.0").dartSdk)
    }

    @Test fun queryKeysCannotEscapeTheCacheDirectoryAndNamespacesStaySeparate() = temporary { dir ->
        val cache = PubLocalCache(dir)
        cache.write("search:../../path?q=hello/world:1", metadata, System.currentTimeMillis() - 1000)
        cache.write("metadata:demo", metadata, System.currentTimeMillis() - 1000)
        assertNull(cache.read("info:demo", PubDevClient.Metadata::class.java))
        Files.list(dir).use { files -> assertTrue(files.allMatch { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }) }
    }

    @Test fun concurrentUpdatesLeaveACompleteEntryAndNoTemporaryFiles() = temporary { dir ->
        val threads = (1..8).map { number -> Thread {
            PubLocalCache(dir).write("metadata:demo", PubDevClient.Metadata("$number.0.0", "$number.0.0"), System.currentTimeMillis() - 1000)
        }.also { it.start() } }
        threads.forEach { it.join() }
        val value = PubLocalCache(dir).read("metadata:demo", PubDevClient.Metadata::class.java)!!.value
        assertEquals(value.stable, value.newest)
        assertNotNull(PubVersion.parse(value.stable!!))
        Files.list(dir).use { files -> assertEquals(1L, files.count()) }
    }
}
