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

    const val CHAT_COMPLETIONS_PATH = "/chat/completions"

    /**
     * The only model OmniRoute accepts. It routes to the best available model internally, so a
     * specific name such as `gpt-4o` is not selectable and silently misbehaves if sent.
     */
    const val OMNIROUTE_MODEL = "auto"

    /**
     * Normalises a stored or user-supplied base URL into a full chat-completions endpoint.
     * Blank falls back to [OMNIROUTE_DEFAULT_BASE_URL]; a value that already ends in the path is
     * returned unchanged (after trailing-slash trimming) so it is idempotent.
     */
    fun resolveEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().ifBlank { OMNIROUTE_DEFAULT_BASE_URL }.trimEnd('/')
        return if (base.endsWith(CHAT_COMPLETIONS_PATH)) base else base + CHAT_COMPLETIONS_PATH
    }

    /**
     * Forces any persisted OmniRoute model back to [OMNIROUTE_MODEL].
     *
     * Older builds let a user pick a concrete model name; those values are still in SharedPreferences
     * and would be sent to a gateway that only understands `auto`.
     */
    fun coerceModel(model: String): String = OMNIROUTE_MODEL
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
