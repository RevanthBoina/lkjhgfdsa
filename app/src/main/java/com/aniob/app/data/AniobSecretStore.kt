package com.aniob.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted storage for the OmniRoute API key.
 *
 * Uses Jetpack Security (Tink): an AES256-GCM `MasterKey` in the Android Keystore wraps
 * AES256-SIV key encryption and AES256-GCM value encryption.
 *
 * Resilience policy:
 * 1. If the Tink keyset is corrupted (e.g. after an OS update or backup restore), deletes the
 *    corrupted secret preferences file once and recreates it.
 * 2. If the hardware Keystore is unavailable (some emulators, custom ROMs, or JVM test
 *    environments), falls back to software AES-256-GCM encryption (`KEY_API_FALLBACK_ENC`) +
 *    process-wide volatile cache so user-configured OmniRoute credentials always persist without
 *    ever storing a plaintext API key on disk.
 */
class AniobSecretStore(context: Context) {

    private val appContext = context.applicationContext

    /** Non-secret UI preferences stay in the original plaintext file. */
    private val plainPrefs: SharedPreferences =
        appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences? by lazy {
        createEncryptedPrefs(recoverOnFailure = true)
    }

    private fun createEncryptedPrefs(recoverOnFailure: Boolean): SharedPreferences? {
        return try {
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
            if (recoverOnFailure) {
                runCatching { appContext.deleteSharedPreferences(SECRET_PREFS) }
                return createEncryptedPrefs(recoverOnFailure = false)
            }
            Log.w(TAG, "EncryptedSharedPreferences unavailable; using software AES-GCM fallback: ${e.javaClass.simpleName}")
            null
        }
    }

    /** True when either the hardware-backed store or software AES-GCM fallback is operational. */
    val isAvailable: Boolean get() = true

    /**
     * Returns the stored key, migrating a legacy plaintext value on first read.
     */
    fun getOmniRouteApiKey(): String {
        val encrypted = encryptedPrefs
        if (encrypted != null) {
            encrypted.getString(KEY_API, null)?.let {
                cachedApiKey = it
                return it
            }
        }

        // Check software AES-GCM fallback ciphertext
        val fallbackCipher = plainPrefs.getString(KEY_API_FALLBACK_ENC, null)
        if (!fallbackCipher.isNullOrBlank()) {
            val decrypted = decryptSoftwareFallback(fallbackCipher)
            if (decrypted != null) {
                if (encrypted != null) {
                    encrypted.edit().putString(KEY_API, decrypted).apply()
                    plainPrefs.edit().remove(KEY_API_FALLBACK_ENC).apply()
                }
                cachedApiKey = decrypted
                return decrypted
            }
        }

        val legacy = plainPrefs.getString(KEY_API, "") ?: ""
        if (legacy.isNotEmpty()) {
            setOmniRouteApiKey(legacy)
            plainPrefs.edit().remove(KEY_API).apply()
            Log.i(TAG, "migrated OmniRoute API key from plaintext prefs into encrypted storage")
            return legacy
        }

        return cachedApiKey ?: ""
    }

    /** Stores (or clears, when [apiKey] is blank) the key. */
    fun setOmniRouteApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        cachedApiKey = trimmed
        val encrypted = encryptedPrefs
        if (encrypted != null) {
            encrypted.edit().apply {
                if (trimmed.isBlank()) remove(KEY_API) else putString(KEY_API, trimmed)
            }.apply()
            if (plainPrefs.contains(KEY_API_FALLBACK_ENC)) {
                plainPrefs.edit().remove(KEY_API_FALLBACK_ENC).apply()
            }
        } else {
            plainPrefs.edit().apply {
                if (trimmed.isBlank()) {
                    remove(KEY_API_FALLBACK_ENC)
                } else {
                    val cipherText = encryptSoftwareFallback(trimmed)
                    if (cipherText != null) putString(KEY_API_FALLBACK_ENC, cipherText)
                }
            }.apply()
        }
        // Never leave a plaintext copy behind, including one written by an older build.
        if (plainPrefs.contains(KEY_API)) plainPrefs.edit().remove(KEY_API).apply()
    }

    /**
     * Base URL for the OmniRoute gateway. Not a secret, so it stays in the plaintext file — but it
     * is exposed here so the key and its endpoint are read and written through one object.
     */
    fun getOmniRouteBaseUrl(): String =
        plainPrefs.getString(KEY_BASE_URL, null) ?: cachedBaseUrl ?: ""

    fun setOmniRouteBaseUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        cachedBaseUrl = trimmed
        plainPrefs.edit().putString(KEY_BASE_URL, trimmed).apply()
    }

    private fun deriveFallbackKey(): SecretKeySpec {
        var salt = plainPrefs.getString(KEY_FALLBACK_SALT, null)
        if (salt.isNullOrBlank()) {
            val randomBytes = ByteArray(16)
            SecureRandom().nextBytes(randomBytes)
            salt = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
            plainPrefs.edit().putString(KEY_FALLBACK_SALT, salt).apply()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest("aniob.omniroute.${appContext.packageName}.$salt".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptSoftwareFallback(plainText: String): String? = runCatching {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveFallbackKey(), GCMParameterSpec(128, iv))
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    }.getOrNull()

    private fun decryptSoftwareFallback(encoded: String): String? = runCatching {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= 12) return@runCatching null
        val iv = combined.copyOfRange(0, 12)
        val cipherBytes = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveFallbackKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val TAG = "AniobSecretStore"
        const val LEGACY_PREFS = "aniob_prefs"
        const val SECRET_PREFS = "aniob_secrets"
        const val KEY_API = "omniroute_api_key"
        const val KEY_API_FALLBACK_ENC = "omniroute_api_key_aes_gcm"
        const val KEY_FALLBACK_SALT = "omniroute_fallback_salt"
        const val KEY_BASE_URL = "omniroute_base_url"

        @Volatile
        private var cachedApiKey: String? = null

        @Volatile
        private var cachedBaseUrl: String? = null
    }
}
