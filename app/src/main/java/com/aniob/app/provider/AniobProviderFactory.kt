package com.aniob.app.provider

import com.aniob.core.external.AniobCloudLlmProvider

/**
 * Single source of truth for OmniRoute endpoints.
 *
 * These two constants used to disagree with everything else in the repo: `providers.yaml` and the
 * `AniobOmniRouteProvider` KDoc both claimed `https://api.omniroute.ai/v1/chat/completions` with
 * `model: "gpt-4o"`, while the code pointed at `omniroute.online`. Nothing loaded `providers.yaml`
 * (no consumer exists anywhere in the source tree), so the yaml was decoration that actively
 * contradicted the shipping behaviour.
 *
 * A base URL is stored and the chat-completions path is appended here, matching the proven reference
 * implementation. [resolveEndpoint] still accepts a legacy full endpoint so older persisted values
 * and existing call sites keep working.
 */
object AniobProviderConfig {
    /** OmniRoute gateway running alongside the app (the default). */
    const val OMNIROUTE_DEFAULT_BASE_URL = "http://localhost:20128/v1"

    /** Direct hosted endpoint, for users without a local gateway. */
    const val OMNIROUTE_CLOUD_BASE_URL = "https://omniroute.online/v1"

    /** Android emulator host loopback for desktop OmniRoute instances on :20128. */
    const val OMNIROUTE_EMULATOR_BASE_URL = "http://10.0.2.2:20128/v1"

    const val CHAT_COMPLETIONS_PATH = "/chat/completions"
    const val MODELS_PATH = "/models"

    /** Default OmniRoute Auto-Combo model target. */
    const val OMNIROUTE_MODEL = "auto"

    /** Built-in OmniRoute Auto-Combo routing channels (diegosouzapw/OmniRoute). */
    val OMNIROUTE_AUTO_COMBOS: List<Pair<String, String>> = listOf(
        "auto" to "Auto (16-Factor)",
        "auto/fast" to "Fast",
        "auto/smart" to "Smart",
        "auto/vision" to "Vision",
        "auto/coding" to "Coding",
        "auto/cheap" to "Free / Cheap"
    )

    private val LEGACY_BARE_MODELS = setOf("gpt-4o", "gpt-4o-mini")

    /**
     * Normalises a stored or user-supplied gateway URL into a `/v1` base URL.
     */
    fun resolveBaseUrl(baseUrl: String): String {
        val raw = baseUrl.trim().ifBlank { OMNIROUTE_DEFAULT_BASE_URL }.trimEnd('/')
        val withoutSubpath = when {
            raw.endsWith(CHAT_COMPLETIONS_PATH) -> raw.removeSuffix(CHAT_COMPLETIONS_PATH).trimEnd('/')
            raw.endsWith(MODELS_PATH) -> raw.removeSuffix(MODELS_PATH).trimEnd('/')
            else -> raw
        }
        return if (withoutSubpath.endsWith("/v1")) withoutSubpath else "$withoutSubpath/v1"
    }

    /**
     * Normalises a stored or user-supplied base URL into a full chat-completions endpoint.
     * Blank falls back to [OMNIROUTE_DEFAULT_BASE_URL]; a value that already ends in the path is
     * returned unchanged (after trailing-slash trimming) so it is idempotent.
     */
    fun resolveEndpoint(baseUrl: String): String {
        val raw = baseUrl.trim().ifBlank { OMNIROUTE_DEFAULT_BASE_URL }.trimEnd('/')
        if (raw.endsWith(CHAT_COMPLETIONS_PATH)) return raw
        return resolveBaseUrl(raw) + CHAT_COMPLETIONS_PATH
    }

    /**
     * Normalises a base URL into the `/v1/models` catalog endpoint.
     */
    fun resolveModelsEndpoint(baseUrl: String): String =
        resolveBaseUrl(baseUrl) + MODELS_PATH

    /**
     * Ordered candidate `/v1/chat/completions` endpoints.
     * When the user leaves [baseUrl] blank or at `localhost:20128`, automatically fails over to
     * `10.0.2.2:20128` (emulator host) and `https://omniroute.online/v1` (hosted cloud) if no local
     * daemon is listening on `localhost:20128`.
     */
    fun candidateEndpoints(baseUrl: String): List<String> {
        val primary = resolveEndpoint(baseUrl)
        val trimmed = baseUrl.trim()
        return if (trimmed.isBlank() || trimmed.contains("localhost:20128") || trimmed.contains("127.0.0.1:20128")) {
            listOf(
                primary,
                resolveEndpoint(OMNIROUTE_EMULATOR_BASE_URL),
                resolveEndpoint(OMNIROUTE_CLOUD_BASE_URL)
            ).distinct()
        } else {
            listOf(primary)
        }
    }

    /**
     * Ordered candidate `/v1/models` endpoints matching [candidateEndpoints].
     */
    fun candidateModelsEndpoints(baseUrl: String): List<String> {
        val primary = resolveModelsEndpoint(baseUrl)
        val trimmed = baseUrl.trim()
        return if (trimmed.isBlank() || trimmed.contains("localhost:20128") || trimmed.contains("127.0.0.1:20128")) {
            listOf(
                primary,
                resolveModelsEndpoint(OMNIROUTE_EMULATOR_BASE_URL),
                resolveModelsEndpoint(OMNIROUTE_CLOUD_BASE_URL)
            ).distinct()
        } else {
            listOf(primary)
        }
    }

    /**
     * Normalises an OmniRoute model or combo identifier.
     *
     * Coerces blank strings and legacy unprefixed `gpt-4o` / `gpt-4o-mini` prefs back to `auto`,
     * while preserving valid OmniRoute targets (`auto`, `auto/*`, `<provider>/<model>`, and custom
     * combo names).
     */
    fun coerceModel(model: String): String {
        val trimmed = model.trim()
        if (trimmed.isBlank() || trimmed.lowercase() in LEGACY_BARE_MODELS) {
            return OMNIROUTE_MODEL
        }
        return trimmed
    }
}

object AniobProviderFactory {
    fun createOmniRoute(
        apiKey: String,
        baseUrl: String = AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL,
        model: String = AniobProviderConfig.OMNIROUTE_MODEL,
        jsonMode: Boolean = false
    ): AniobCloudLlmProvider {
        return AniobOmniRouteProvider(
            apiKey = apiKey,
            baseUrl = AniobProviderConfig.resolveEndpoint(baseUrl),
            model = model,
            jsonMode = jsonMode
        )
    }
}
