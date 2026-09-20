package com.aniob.app.provider

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection
import com.aniob.app.network.AniobHttpClientSingleton
import com.aniob.core.external.AniobCloudLlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Omniroute Cloud Provider client.
 * Connects to https://api.omniroute.ai/v1/chat/completions with gpt-4o vision.
 * When [jsonMode] is enabled, all generation requests enforce `response_format:
 * json_object` with temperature 0.1 so the ladder can trust structured output.
 */
class AniobOmniRouteProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.omniroute.ai/v1/chat/completions",
    private val model: String = "gpt-4o",
    private val jsonMode: Boolean = false
) : AniobCloudLlmProvider {
    override val name: String = "OMNIROUTE_CLOUD"

    private val client: OkHttpClient =
        AniobHttpClientSingleton.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    override suspend fun generate(prompt: String, systemPrompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "Omniroute API key is not configured. Please add it in Settings."
        }
        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", 1024)
                if (jsonMode) {
                    put("temperature", 0.1)
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }
            }
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext "Error from Omniroute Cloud (${response.code}): $body"
            }
            val rootJson = JSONObject(body)
            rootJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            "Request failed: ${e.message}"
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        systemPrompt: String,
        onDelta: (String) -> Unit
    ): String = streamingResult(prompt, systemPrompt, null, onDelta)

    suspend fun generateStreaming(
        prompt: String,
        systemPrompt: String,
        screenshotBase64: String?,
        onDelta: (String) -> Unit
    ): String = streamingResult(prompt, systemPrompt, screenshotBase64, onDelta)

    /** Whether this provider instance enforces structured JSON output. */
    fun isJsonMode() = jsonMode

    /** Returns a copy with JsonMode enabled for callers that need strict JSON. */
    fun withJsonMode(): AniobOmniRouteProvider = AniobOmniRouteProvider(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
        jsonMode = true
    )

    private suspend fun streamingResult(
        prompt: String,
        systemPrompt: String,
        screenshotBase64: String?,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            val msg = "Omniroute API key is not configured. Please add it in Settings."
            onDelta(msg)
            return@withContext msg
        }
        try {
            val messages = buildMessages(systemPrompt, prompt, screenshotBase64)
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", 1024)
                if (jsonMode) {
                    put("temperature", 0.1)
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }
            }
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response: Response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = "Error from Omniroute Cloud (${response.code}): $body"
                    onDelta(msg)
                    return@withContext msg
                }
                val rootJson = JSONObject(body)
                val full = rootJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                emitThrottled(full, onDelta)
                full
            }
        } catch (e: Exception) {
            val msg = "Request failed: ${e.message}"
            onDelta(msg)
            msg
        }
    }

    private fun buildMessages(systemPrompt: String, userPrompt: String, screenshotBase64: String?): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        val userContent = JSONArray()
        userContent.put(JSONObject().apply { put("type", "text"); put("text", userPrompt) })
        if (!screenshotBase64.isNullOrBlank()) {
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$screenshotBase64") })
            })
        }
        messages.put(JSONObject().apply { put("role", "user"); put("content", userContent) })
        return messages
    }

    private suspend fun emitThrottled(text: String, onDelta: (String) -> Unit) {
        val minIntervalMs = 60L
        var lastEmitMs = 0L
        text.forEachIndexed { index, ch ->
            val now = System.currentTimeMillis()
            if (now - lastEmitMs >= minIntervalMs) {
                onDelta(ch.toString())
                lastEmitMs = now
            } else {
                delay(minIntervalMs - (now - lastEmitMs))
                onDelta(ch.toString())
                lastEmitMs = System.currentTimeMillis()
            }
            if (index % 8 == 0) {
                // Keep the stream responsive without tight-looping CPU
                delay(1)
            }
        }
    }

    suspend fun getNextAction(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String? = null
    ): Result<AniobAction> =
        // Delegates to the raw fetch and the single canonical parser. No second parser lives here.
        getNextActionRaw(systemPrompt, userPrompt, screenshotBase64).mapCatching { raw ->
            AniobActionSchema.parseActionJson(raw)
        }

    /**
     * Returns the raw model content instead of a pre-parsed action.
     *
     * Providers must return *data*, not behavior (finding #3): the caller routes this through
     * `AniobStructuredOutput.parseOrRepair` so cloud output shares the one action vocabulary and
     * benefits from the single repair retry.
     */
    suspend fun getNextActionRaw(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            val userContent = JSONArray()
            userContent.put(JSONObject().apply {
                put("type", "text")
                put("text", userPrompt)
            })
            if (!screenshotBase64.isNullOrBlank()) {
                userContent.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$screenshotBase64")
                    })
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", 512)
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Omniroute Cloud API error (${response.code}): $body"))
            }
            val rawContent = JSONObject(body).getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            Result.success(rawContent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
