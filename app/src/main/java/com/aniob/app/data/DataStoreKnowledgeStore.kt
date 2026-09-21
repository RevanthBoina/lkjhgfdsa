package com.aniob.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aniob.core.memory.PersistentKnowledgeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.knowledgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "aniob_knowledge")

/**
 * DataStore-backed knowledge vault (UX-2 §3).
 *
 * Answers the user ticks "Remember this answer" for, and any preference the agent should keep
 * across restarts. Replaces [com.aniob.core.memory.InMemorySharedKnowledgeStore], whose contents
 * died with the process.
 *
 * `// SHIM(UX-2): delete when the functional track owns durable knowledge storage.`
 */
class DataStoreKnowledgeStore(private val context: Context) : PersistentKnowledgeStore {

    /** In-memory mirror so the synchronous [AniobSharedKnowledgeStore] reads stay cheap. */
    private val mirror = mutableMapOf<String, String>()

    override fun put(key: String, value: String) {
        mirror[key] = value
        runBlocking { persist(key, value) }
    }

    override fun get(key: String): String? = mirror[key]

    override fun getAll(): Map<String, String> = HashMap(mirror)

    override fun remove(key: String) {
        mirror.remove(key)
        runBlocking { persistRemoval(key) }
    }

    override fun clear() {
        mirror.clear()
        runBlocking {
            context.knowledgeDataStore.edit { it.clear() }
        }
    }

    override suspend fun hydrate(): Map<String, String> = try {
        val prefs = context.knowledgeDataStore.data.first()
        val loaded = prefs.asMap().mapNotNull { (key, value) ->
            (value as? String)?.let { key.name to it }
        }.toMap()
        mirror.putAll(loaded)
        loaded
    } catch (_: Exception) {
        // A corrupt or unreadable store must not block the app; we simply start empty.
        emptyMap()
    }

    override suspend fun persist(key: String, value: String) {
        context.knowledgeDataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun persistRemoval(key: String) {
        context.knowledgeDataStore.edit { it.remove(stringPreferencesKey(key)) }
    }
}
