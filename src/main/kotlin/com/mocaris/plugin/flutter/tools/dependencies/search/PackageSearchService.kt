package com.mocaris.plugin.flutter.tools.dependencies.search

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.mocaris.plugin.flutter.tools.core.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.dependencies.PubDevClient
import com.mocaris.plugin.flutter.tools.dependencies.PubLocalCache
import com.mocaris.plugin.flutter.tools.dependencies.PackageDocuments
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
class PackageSearchService(private val project: Project) {
    private data class Cached(val value: Any, val time: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val network = Semaphore(4)
    private val documents = Semaphore(2)
    private val localCache = PubLocalCache.forProject(project)

    suspend fun search(query: String, page: Int) = cached("search:${query.trim()}:$page", PackageSearchPage::class.java) { PubDevClient.search(query, page) }
    suspend fun info(name: String) = cached("info:3:$name", PackageInfo::class.java) { PubDevClient.info(name) }
    suspend fun score(name: String) = cached("score:$name", PackageScore::class.java) { PubDevClient.score(name) }
    suspend fun document(name: String, version: String, tab: String) = documents.withPermit {
        cached("doc:$name:$version:$tab", PackageDocument::class.java) { PackageDocuments.fetch(name, version, tab) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, type: Class<T>, fetch: () -> T): T = locks.getOrPut(key) { Mutex() }.withLock {
        val minutes = project.service<FlutterToolsSettings>().effective().cacheMinutes
        cache[key]?.takeIf { PubLocalCache.Entry(it.value, it.time).fresh(minutes) }?.let { return@withLock it.value as T }
        val stored = runInterruptible(Dispatchers.IO) { localCache.read(key, type) }
        stored?.takeIf { it.fresh(minutes) }?.let {
            remember(key, it.value, it.time)
            return@withLock it.value
        }
        val value = network.withPermit { runInterruptible(Dispatchers.IO) { fetch() } }
        currentCoroutineContext().ensureActive()
        val time = System.currentTimeMillis()
        remember(key, value, time)
        runInterruptible(Dispatchers.IO) { localCache.write(key, value, time) }
        value
    }

    private fun remember(key: String, value: Any, time: Long) = synchronized(cache) {
        if (!cache.containsKey(key) && cache.size >= 128) cache.entries.minByOrNull { it.value.time }?.let { cache.remove(it.key) }
        if (!cache.containsKey(key) && key.startsWith("doc:") && cache.keys.count { it.startsWith("doc:") } >= 16) {
            cache.entries.filter { it.key.startsWith("doc:") }.minByOrNull { it.value.time }?.let { cache.remove(it.key) }
        }
        cache[key] = Cached(value, time)
    }
}
