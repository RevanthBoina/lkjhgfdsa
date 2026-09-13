package com.aniob.core.safety

/**
 * Vault abstraction for secure storage of credentials, API keys, and session scores.
 * Pure JVM interface (zero android.* imports in core-agent).
 */
interface AniobVault {
    fun saveKey(alias: String, secret: String)
    fun getKey(alias: String): String?
    fun removeKey(alias: String)
    fun clear()
}

/**
 * Thread-safe memory vault fallback.
 */
class AniobMemoryVault : AniobVault {
    private val storage = mutableMapOf<String, String>()

    @Synchronized
    override fun saveKey(alias: String, secret: String) {
        storage[alias] = secret
    }

    @Synchronized
    override fun getKey(alias: String): String? = storage[alias]

    @Synchronized
    override fun removeKey(alias: String) {
        storage.remove(alias)
    }

    @Synchronized
    override fun clear() {
        storage.clear()
    }
}
