package com.mocaris.plugin.flutter.tools.assets.preview

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class AssetPreviewServiceTest : BasePlatformTestCase() {
    private fun field(service: AssetPreviewService, name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }

    fun testActivePopupDefersNewThumbnails() {
        val service = project.service<AssetPreviewService>()
        val popup = Job()
        val popupField = field(service, "popupJob")
        popupField.set(service, popup)
        try {
            repeat(20) { assertNull(service.thumbnail(ImageRequest("/unused", "app", "assets/$it.png"))) }
            assertEquals(0, service.pendingRequests())
        } finally { popup.cancel(); popupField.set(service, null) }
    }

    fun testQueuedThumbnailsSkipDecodeAfterInvalidationOrPopupOpening() = runBlocking {
        val service = project.service<AssetPreviewService>()
        val readers = field(service, "readers").get(service) as Semaphore
        @Suppress("UNCHECKED_CAST")
        val store = field(service, "thumbnails").get(service) as ThumbnailStore<ImageRequest, AssetImageLoader.Loaded>
        @Suppress("UNCHECKED_CAST")
        val jobs = field(service, "thumbnailJobs").get(service) as ConcurrentHashMap<ImageRequest, Job>
        val popupField = field(service, "popupJob")
        for (popupOpening in listOf(false, true)) {
            readers.acquire(); readers.acquire()
            var held = true
            val request = ImageRequest("/unused", "app", "assets/$popupOpening.png")
            val popup = Job()
            try {
                service.thumbnail(request)
                val job = jobs.getValue(request)
                if (popupOpening) popupField.set(service, popup) else store.invalidate({ _, _ -> true }, { true })
                readers.release(); readers.release()
                held = false
                withTimeout(5000) { job.join() }
                assertEquals(0, service.pendingRequests())
                assertNull(service.cached(request))
            } finally {
                popup.cancel(); popupField.set(service, null)
                if (held) { readers.release(); readers.release() }
            }
        }
    }
}
