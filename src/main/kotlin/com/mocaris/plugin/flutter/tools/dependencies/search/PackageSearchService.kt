package com.mocaris.plugin.flutter.tools.dependencies.search

import com.intellij.openapi.components.Service
import com.mocaris.plugin.flutter.tools.dependencies.PubDevClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class PackageSearchService {
    private data class Cached(val value: Any, val time: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val network = Semaphore(4)

    suspend fun search(query: String, page: Int) = cached("search:${query.trim()}:$page") { PubDevClient.search(query, page) }
    suspend fun info(name: String) = cached("info:$name") { PubDevClient.info(name) }
    suspend fun score(name: String) = cached("score:$name") { PubDevClient.score(name) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, fetch: () -> T): T = locks.getOrPut(key) { Mutex() }.withLock {
        cache[key]?.takeIf { System.currentTimeMillis() - it.time < 300_000 }?.let { return@withLock it.value as T }
        val value = network.withPermit { runInterruptible(Dispatchers.IO) { fetch() } }
        currentCoroutineContext().ensureActive()
        if (cache.size >= 128) cache.entries.minByOrNull { it.value.time }?.let { cache.remove(it.key) }
        cache[key] = Cached(value, System.currentTimeMillis())
        value
    }
}
