package com.aifieldcam.app.ui.album

/**
 * Tiny path→value memoizer with LRU eviction.
 * Used so album thumbs are decoded once per path while scrolling / rebinding.
 */
class ThumbMemoizer<T>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<String, T>(maxEntries.coerceAtLeast(1), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean =
            size > maxEntries
    }

    /** How many times [loader] actually ran (cache misses). */
    var loadCount: Int = 0
        private set

    fun get(key: String): T? = synchronized(this) { map[key] }

    fun getOrLoad(key: String, loader: () -> T?): T? {
        synchronized(this) {
            map[key]?.let { return it }
        }
        loadCount++
        val value = loader() ?: return null
        synchronized(this) {
            map[key] = value
        }
        return value
    }

    fun clear() = synchronized(this) {
        map.clear()
        loadCount = 0
    }

    fun size(): Int = synchronized(this) { map.size }
}
