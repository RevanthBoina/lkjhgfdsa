package com.aniob.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for the OmniRoute API key.
 *
 * Previously the key lived in plaintext `SharedPreferences("aniob_prefs")` under
 * `omniroute_api_key`. That file is world-readable by any process running as the app's UID and is
 * included verbatim in bug reports and device backups, so a live cloud credential sat on disk in the
 * clear. The `.env` / `BuildConfig.OMNIROUTE_API_KEY` path that `app/build.gradle.kts` still wires
 * up was never read by a single line of Kotlin — grepping `OMNIROUTE_API_KEY` and `BuildConfig.`
 * across all `.kt` sources returns zero hits — so it gave the impression of a secret-injection
 * mechanism while the plaintext path was the only one that ran. A key baked into BuildConfig would
 * also be trivially extractable, since release builds keep `isMinifyEnabled = true` but string
 * constants survive minification.
 *
 * Uses Jetpack Security (Tink): an AES256-GCM `MasterKey` in the Android Keystore wraps
 * AES256-SIV key encryption and AES256-GCM value encryption.
 *
 * Failure policy: if the keystore is unavailable (some emulators, rooted devices, a corrupted
 * keystore after an OS update) this degrades to `null` and the caller disables the cloud provider,
 * rather than crashing at startup or silently falling back to plaintext. Falling back to plaintext
 * would reintroduce exactly the exposure this class removes.
 */
class AniobSecretStore(context: Context) {

    private val appContext = context.applicationContext

    /** Non-secret UI preferences stay in the original plaintext file. */
    private val plainPrefs: SharedPreferences =
        appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                SECRET_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Logged, not rethrown: a missing keystore must not make the app unstartable.
            Log.e(TAG, "EncryptedSharedPreferences unavailable; cloud provider will stay disabled: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** True when the encrypted store could be created. */
    val isAvailable: Boolean get() = encryptedPrefs != null

    /**
     * Returns the stored key, migrating a legacy plaintext value on first read.
     *
     * Migration is one-way and destructive: the plaintext copy is removed immediately after it is
     * copied into the encrypted file, so the exposure window closes on first launch after this
     * update rather than persisting for the life of the install.
     */
    fun getOmniRouteApiKey(): String {
        val encrypted = encryptedPrefs ?: return ""
        encrypted.getString(KEY_API, null)?.let { return it }

        val legacy = plainPrefs.getString(KEY_API, "") ?: ""
        if (legacy.isNotEmpty()) {
            encrypted.edit().putString(KEY_API, legacy).apply()
            plainPrefs.edit().remove(KEY_API).apply()
            Log.i(TAG, "migrated OmniRoute API key from plaintext prefs into encrypted storage")
        }
        return legacy
    }

    /** Stores (or clears, when [apiKey] is blank) the key. No-op if the keystore is unavailable. */
    fun setOmniRouteApiKey(apiKey: String) {
        val encrypted = encryptedPrefs ?: run {
            Log.w(TAG, "cannot persist API key: encrypted store unavailable")
            return
        }
        encrypted.edit().apply {
            if (apiKey.isBlank()) remove(KEY_API) else putString(KEY_API, apiKey)
        }.apply()
        // Never leave a plaintext copy behind, including one written by an older build.
        if (plainPrefs.contains(KEY_API)) plainPrefs.edit().remove(KEY_API).apply()
    }

    /**
     * Base URL for the OmniRoute gateway. Not a secret, so it stays in the plaintext file — but it
     * is exposed here so the key and its endpoint are read and written through one object.
     */
    fun getOmniRouteBaseUrl(): String = plainPrefs.getString(KEY_BASE_URL, null) ?: ""

    fun setOmniRouteBaseUrl(baseUrl: String) {
        plainPrefs.edit().putString(KEY_BASE_URL, baseUrl.trim()).apply()
    }

    private companion object {
        const val TAG = "AniobSecretStore"
        const val LEGACY_PREFS = "aniob_prefs"
        const val SECRET_PREFS = "aniob_secrets"
        const val KEY_API = "omniroute_api_key"
        const val KEY_BASE_URL = "omniroute_base_url"
    }
}
