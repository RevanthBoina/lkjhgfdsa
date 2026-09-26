package com.aniob.app.provider

import com.aniob.core.external.AniobCloudLlmProvider

object AniobProviderConfig {
    const val OMNIROUTE_ONLINE = "https://omniroute.online/v1/chat/completions"
    const val LOCAL_GATEWAY = "http://localhost:20128/v1/chat/completions"
}

object AniobProviderFactory {
    fun createOmniRoute(
        apiKey: String,
        baseUrl: String = AniobProviderConfig.OMNIROUTE_ONLINE,
        model: String = "auto",
        jsonMode: Boolean = false
    ): AniobCloudLlmProvider {
        return AniobOmniRouteProvider(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            jsonMode = jsonMode
        )
    }
}
