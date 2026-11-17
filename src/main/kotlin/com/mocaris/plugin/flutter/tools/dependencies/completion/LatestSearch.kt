package com.mocaris.plugin.flutter.tools.dependencies.completion

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** At most one search starts per interval; newer input cancels stale work and keeps the latest query. */
internal class LatestSearch(private val scope: CoroutineScope, private val intervalMillis: Long = 200) {
    private val gate = Mutex()
    private var lastStart = 0L
    private var job: Job? = null

    @Synchronized fun submit(search: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            gate.withLock {
                val remaining = intervalMillis - (System.nanoTime() - lastStart) / 1_000_000
                if (remaining > 0) delay(remaining)
                ensureActive()
                lastStart = System.nanoTime()
            }
            search()
        }
    }
    @Synchronized fun cancel() { job?.cancel(); job = null }
}
