package com.aniob.core.memory

/**
 * Persistent cross-chat Key-Value knowledge store.
 * Stores user preferences, discovered app state paths, and stable selectors without
 * polluting short-term context.
 * Pure JVM interface with in-memory thread-safe default implementation.
 */
interface AniobSharedKnowledgeStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun getAll(): Map<String, String>
    fun remove(key: String)
    fun clear()
}

class InMemorySharedKnowledgeStore : AniobSharedKnowledgeStore {
    private val store = mutableMapOf<String, String>()

    @Synchronized
    override fun put(key: String, value: String) {
        store[key] = value
    }

    @Synchronized
    override fun get(key: String): String? = store[key]

    @Synchronized
    override fun getAll(): Map<String, String> = HashMap(store)

    @Synchronized
    override fun remove(key: String) {
        store.remove(key)
    }

    @Synchronized
    override fun clear() {
        store.clear()
    }
}
