package com.mocaris.plugin.flutter.tools.dependencies.completion

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LatestSearchTest {
    @Test fun trailingInputIsSearchedAtMostOncePerInterval() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val throttle = LatestSearch(scope)
            val started = CompletableDeferred<Long>()
            val latest = CompletableDeferred<Long>()
            throttle.submit { started.complete(System.nanoTime()) }
            val first = withTimeout(3000) { started.await() }
            val stale = CompletableDeferred<Unit>()
            throttle.submit { stale.complete(Unit) }
            throttle.submit { latest.complete(System.nanoTime()) }
            val last = withTimeout(3000) { latest.await() }
            val interval = (last - first) / 1_000_000
            assertTrue("requests must be throttled", interval >= 190)
            assertTrue("the default interval should be 200ms", interval < 450)
            assertFalse("intermediate input must be skipped", stale.isCompleted)
        } finally { scope.cancel() }
    }

    @Test fun cancelInterruptsOldWorkAndPreventsStalePublication() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val throttle = LatestSearch(scope, 50)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val stale = CompletableDeferred<Unit>()
            throttle.submit {
                started.complete(Unit)
                try { delay(10_000); stale.complete(Unit) }
                finally { cancelled.complete(Unit) }
            }
            withTimeout(3000) { started.await() }
            throttle.cancel()
            withTimeout(3000) { cancelled.await() }
            assertFalse(stale.isCompleted)
        } finally { scope.cancel() }
    }
}
