package com.mocaris.plugin.flutter.tools.assets.preview

/** Icons retain keys rather than bitmaps. Eviction therefore releases decoded images even with many markers. */
internal class ThumbnailStore<K, V>(private val capacity: Int = 512, private val maxPending: Int = 16) {
    private data class Flight(val id: Long, var valid: Boolean = true)
    private val entries = LinkedHashMap<K, V>(16, .75f, true)
    private val pending = mutableMapOf<K, Flight>()
    private var nextId = 0L

    @Synchronized fun get(key: K): V? = entries[key]
    @Synchronized fun begin(key: K): Long? {
        if (entries.containsKey(key) || pending.containsKey(key) || pending.size >= maxPending) return null
        return (++nextId).also { pending[key] = Flight(it) }
    }
    @Synchronized fun complete(key: K, id: Long, value: V): Boolean {
        val flight = pending[key]?.takeIf { it.id == id } ?: return false
        pending.remove(key)
        if (!flight.valid) return false
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return true
    }
    @Synchronized fun abandon(key: K, id: Long) { if (pending[key]?.id == id) pending.remove(key) }
    @Synchronized fun invalidate(match: (K, V) -> Boolean, pendingMatch: (K) -> Boolean): Set<K> {
        val affected = entries.entries.filter { match(it.key, it.value) }.map { it.key }.toMutableSet()
        affected.forEach(entries::remove)
        // Keep running/waiting jobs counted until completion, so invalidations cannot grow the queue.
        pending.filterKeys(pendingMatch).forEach { (key, flight) -> flight.valid = false; affected.add(key) }
        return affected
    }
    @Synchronized fun clear() { entries.clear(); pending.values.forEach { it.valid = false } }
    @Synchronized fun entryCount() = entries.size
    @Synchronized fun pendingCount() = pending.size
}
