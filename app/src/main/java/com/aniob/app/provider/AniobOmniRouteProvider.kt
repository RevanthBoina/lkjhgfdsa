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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OmniRoute cloud provider client.
 *
 * Talks to a self-hosted OmniRoute gateway's `/chat/completions` endpoint. The gateway address is
 * [baseUrl] and is entered by the user in Settings (see [AniobProviderConfig.validateBaseUrl]);
 * [AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL] is only a local/emulator fallback because
 * `localhost` on a phone is the phone itself.
 *
 * The model is [AniobProviderConfig.OMNIROUTE_MODEL] (`auto`) by default so the gateway can route
 * across the providers the user connected; OmniRoute also accepts `auto/coding`, `auto/fast`,
 * `auto/cheap` and `provider/model` ids, which Settings allows.
 *
 * When [jsonMode] is enabled, all generation requests enforce `response_format:
 * json_object` with temperature 0.1 so the ladder can trust structured output.
 */
class AniobOmniRouteProvider(
    private val apiKey: String,
    private val baseUrl: String = AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL,
    private val model: String = "auto",
    private val jsonMode: Boolean = false,
    /**
     * Invoked once per outbound generation request, immediately before the socket is opened.
     * `AniobViewModel` wires this to `ResearchTelemetryManager.recordLlmCall()`; the default no-op
     * keeps tests and one-off callers unchanged. The Settings connection probe does not use it.
     */
    private val onLlmRequest: () -> Unit = {}
) : AniobCloudLlmProvider {
    override val name: String = "OMNIROUTE_CLOUD"
    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val client: OkHttpClient =
        AniobHttpClientSingleton.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    override suspend fun generate(prompt: String, systemPrompt: String): String = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
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
                .url(AniobProviderConfig.resolveEndpoint(baseUrl))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            onLlmRequest()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext "Error from Omniroute Cloud (${response.code}): $body"
                }
                val rootJson = JSONObject(body)
                val fullContent = rootJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                AniobEgressLedger.record(name, model, prompt.length + systemPrompt.length, fullContent.length, System.currentTimeMillis() - startTime)
                fullContent
            }
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
        jsonMode = true,
        onLlmRequest = onLlmRequest
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
        val startTime = System.currentTimeMillis()
        var call: okhttp3.Call? = null
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
                .url(AniobProviderConfig.resolveEndpoint(baseUrl))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            onLlmRequest()
            call = client.newCall(request)
            call.execute().use { response: Response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = "Error from Omniroute Cloud (${response.code}): $body"
                    onDelta(msg)
                    return@withContext msg
                }
                val rootJson = JSONObject(body)
                val full = rootJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                AniobEgressLedger.record(name, model, prompt.length + systemPrompt.length, full.length, System.currentTimeMillis() - startTime)
                // Immediate emission: zero simulated streaming delay
                onDelta(full)
                full
            }
        } catch (e: Exception) {
            call?.cancel()
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
                .url(AniobProviderConfig.resolveEndpoint(baseUrl))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            onLlmRequest()
            val call = client.newCall(request)
            suspendCancellableCoroutine<Result<String>> { continuation ->
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resume(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val body = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                if (continuation.isActive) {
                                    continuation.resume(Result.failure(Exception("Omniroute Cloud API error (${response.code}): $body")))
                                }
                                return
                            }
                            val rawContent = JSONObject(body).getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                            AniobEgressLedger.record(name, model, userPrompt.length + systemPrompt.length, rawContent.length, 0L)
                            if (continuation.isActive) {
                                continuation.resume(Result.success(rawContent))
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(Result.failure(e))
                        } finally {
                            response.close()
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 60L
    }
}

enum class OmniRouteConnectionStatus {
    SUCCESS,
    BAD_KEY,
    UNREACHABLE
}

/**
 * One-shot connectivity probe used by the "Test" button in Settings.
 *
 * Sends a one-token `auto` completion to the user's gateway. A two-second queue on a busy provider
 * is normal, so the read timeout is 20 s rather than the eight seconds the old probe used (which
 * reported a healthy gateway as unreachable whenever a model took a moment to answer).
 */
suspend fun testOmniRouteConnection(apiKey: String, baseUrl: String): OmniRouteConnectionStatus = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext OmniRouteConnectionStatus.BAD_KEY
    if (AniobProviderConfig.validateBaseUrl(baseUrl) != null) return@withContext OmniRouteConnectionStatus.UNREACHABLE
    val client = AniobHttpClientSingleton.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    val payload = JSONObject().apply {
        put("model", AniobProviderConfig.OMNIROUTE_MODEL)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "ping")
            })
        })
        put("max_tokens", 1)
    }

    val request = Request.Builder()
        .url(AniobProviderConfig.resolveEndpoint(baseUrl))
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()

    try {
        client.newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> OmniRouteConnectionStatus.SUCCESS
                401, 403 -> OmniRouteConnectionStatus.BAD_KEY
                else -> OmniRouteConnectionStatus.UNREACHABLE
            }
        }
    } catch (_: java.net.UnknownHostException) {
        OmniRouteConnectionStatus.UNREACHABLE
    } catch (_: java.io.IOException) {
        OmniRouteConnectionStatus.UNREACHABLE
    } catch (_: Exception) {
        OmniRouteConnectionStatus.UNREACHABLE
    }
}
