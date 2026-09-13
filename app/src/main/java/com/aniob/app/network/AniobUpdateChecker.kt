package com.aniob.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * In-app update checker using GitHub releases API with required custom User-Agent header.
 */
object AniobUpdateChecker {

    private const val GITHUB_REPO = "revanthboina/aniob"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val USER_AGENT = "Aniob-Agent/1.0 (Android; ARM64-v8a)"

    data class UpdateInfo(
        val isUpdateAvailable: Boolean,
        val latestVersion: String,
        val downloadUrl: String? = null,
        val releaseNotes: String? = null
    )

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            AniobHttpClientSingleton.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateInfo(isUpdateAvailable = false, latestVersion = currentVersion)
                }

                val body = response.body?.string() ?: ""
                val tagMatch = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)
                val tagName = tagMatch?.groupValues?.getOrNull(1) ?: currentVersion

                val isNewer = tagName != currentVersion && !tagName.contains(currentVersion)
                UpdateInfo(
                    isUpdateAvailable = isNewer,
                    latestVersion = tagName,
                    releaseNotes = "Latest release: $tagName"
                )
            }
        } catch (_: Exception) {
            UpdateInfo(isUpdateAvailable = false, latestVersion = currentVersion)
        }
    }
}
