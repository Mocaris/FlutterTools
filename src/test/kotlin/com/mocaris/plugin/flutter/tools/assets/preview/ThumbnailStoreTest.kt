package com.mocaris.plugin.flutter.tools.assets.preview

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ThumbnailStoreTest {
    @Test fun lruEvictionKeepsOnlyTheConfiguredNumberOfBitmaps() {
        val cache = ThumbnailStore<Int, BufferedImage>(3)
        for (key in 1..3) cache.complete(key, cache.begin(key)!!, BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB))
        cache.get(1)
        cache.complete(4, cache.begin(4)!!, BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB))
        assertNull(cache.get(2)); assertNotNull(cache.get(1)); assertEquals(3, cache.entryCount())
    }

    @Test fun queueIsBoundedAndSameResourceIsDeduplicated() {
        val cache = ThumbnailStore<Int, String>(5, 2)
        val first = cache.begin(1)!!
        assertNull(cache.begin(1)); assertNotNull(cache.begin(2)); assertNull(cache.begin(3))
        cache.abandon(1, first)
        assertNotNull(cache.begin(3)); assertEquals(2, cache.pendingCount())
    }

    @Test fun invalidationRejectsLateResultsWithoutFreeingExtraQueueSlots() {
        val cache = ThumbnailStore<Int, String>(5, 1)
        val ticket = cache.begin(1)!!
        cache.invalidate({ _, _ -> true }, { true })
        assertNull(cache.begin(2))
        assertFalse(cache.complete(1, ticket, "stale")); assertNull(cache.get(1))
        val fresh = cache.begin(1)!!
        assertTrue(cache.complete(1, fresh, "fresh")); assertEquals("fresh", cache.get(1))
        cache.invalidate({ key, _ -> key == 1 }, { false })
        assertNull(cache.get(1))
    }

    @Test fun failedResultsAreCachedInsteadOfRepeatedlyEnqueuedByPainting() {
        val cache = ThumbnailStore<Int, String>()
        cache.complete(1, cache.begin(1)!!, "missing")
        repeat(100) { assertNull(cache.begin(1)); assertEquals("missing", cache.get(1)) }
        cache.clear(); assertEquals(0, cache.entryCount())
    }

    @Test fun concurrentRequestsRespectCapacityAndDoNotLoseQueueAccounting() {
        val cache = ThumbnailStore<Int, Int>(32, 4)
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(500) { key -> executor.submit { cache.begin(key)?.let { cache.complete(key, it, key) } } }
        } finally { executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        assertTrue(cache.entryCount() <= 32); assertEquals(0, cache.pendingCount())
    }
}
