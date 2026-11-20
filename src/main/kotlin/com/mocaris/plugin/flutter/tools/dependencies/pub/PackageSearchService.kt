package com.mocaris.plugin.flutter.tools.dependencies.pub

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

@Service(Service.Level.PROJECT)
class PackageSearchService(private val project: Project) {
    private data class Cached(val value: Any, val time: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val network = Semaphore(4)
    private val documents = Semaphore(2)
    private val localCache = PubLocalCache.forProject(project)

    suspend fun search(query: String, page: Int) = cached("search:${query.trim()}:$page", PackageSearchPage::class.java) { PubDevClient.search(query, page) }.value
    suspend fun info(name: String) = infoEntry(name).value
    suspend fun score(name: String) = cached("score:$name", PackageScore::class.java) { PubDevClient.score(name) }.value
    suspend fun document(name: String, version: String, tab: String) = documents.withPermit {
        cached("doc:$name:$version:$tab", PackageDocument::class.java) { PackageDocuments.fetch(name, version, tab) }.value
    }

    internal suspend fun infoEntry(name: String, force: Boolean = false, startedAt: Long = System.currentTimeMillis(),
                                   fetch: () -> PackageInfo = { PubDevClient.info(name) }): PubLocalCache.Entry<PackageInfo> =
        cached("info:3:$name", PackageInfo::class.java, force, startedAt, fetch)

    internal fun peekInfo(name: String): PubLocalCache.Entry<PackageInfo>? = cache["info:3:$name"]?.let {
        PubLocalCache.Entry(it.value as PackageInfo, it.time)
    }

    internal suspend fun cachedInfo(name: String): PubLocalCache.Entry<PackageInfo>? = locks.getOrPut("info:3:$name") { Mutex() }.withLock {
        peekInfo(name) ?: runInterruptible(Dispatchers.IO) { localCache.read("info:3:$name", PackageInfo::class.java) }
            ?.also { remember("info:3:$name", it.value, it.time) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, type: Class<T>, force: Boolean = false,
                                       startedAt: Long = System.currentTimeMillis(), fetch: () -> T): PubLocalCache.Entry<T> = locks.getOrPut(key) { Mutex() }.withLock {
        val minutes = project.service<FlutterToolsSettings>().effective().cacheMinutes
        fun usable(entry: PubLocalCache.Entry<T>) = if (force) entry.time in startedAt..System.currentTimeMillis() else entry.fresh(minutes)
        cache[key]?.let { PubLocalCache.Entry(it.value as T, it.time) }?.takeIf(::usable)?.let { return@withLock it }
        if (!force) {
            val stored = runInterruptible(Dispatchers.IO) { localCache.read(key, type) }
            stored?.takeIf(::usable)?.let {
                remember(key, it.value, it.time)
                return@withLock it
            }
        }
        val value = network.withPermit { runInterruptible(Dispatchers.IO) { fetch() } }
        currentCoroutineContext().ensureActive()
        val time = System.currentTimeMillis()
        remember(key, value, time)
        runInterruptible(Dispatchers.IO) { localCache.write(key, value, time) }
        PubLocalCache.Entry(value, time)
    }

    private fun remember(key: String, value: Any, time: Long) = synchronized(cache) {
        if (!cache.containsKey(key) && cache.size >= 128) cache.entries.minByOrNull { it.value.time }?.let { cache.remove(it.key) }
        if (!cache.containsKey(key) && key.startsWith("doc:") && cache.keys.count { it.startsWith("doc:") } >= 16) {
            cache.entries.filter { it.key.startsWith("doc:") }.minByOrNull { it.value.time }?.let { cache.remove(it.key) }
        }
        cache[key] = Cached(value, time)
    }
}
