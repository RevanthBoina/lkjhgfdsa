package com.aniob.core.memory

/**
 * Persistent shared-knowledge store (UX-2 seam).
 *
 * [AniobSharedKnowledgeStore] has a purely in-memory default, so anything the user taught the
 * agent died with the process. This interface adds the persistence contract the app layer
 * implements over DataStore, keeping core-agent free of Android imports.
 *
 * `// SHIM(UX-2): the app-side DataStoreKnowledgeStore satisfies this until the functional
 * track owns durable knowledge storage.`
 */
interface PersistentKnowledgeStore : AniobSharedKnowledgeStore {
    /** Loads every entry once at cold start; safe to call repeatedly. */
    suspend fun hydrate(): Map<String, String>

    /** Flushes a single entry to durable storage. */
    suspend fun persist(key: String, value: String)

    /** Removes a single entry from durable storage. */
    suspend fun persistRemoval(key: String)
}
