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
 * OmniRoute cloud provider client (https://github.com/diegosouzapw/OmniRoute).
 *
 * Talks to an OmniRoute gateway's `/v1/chat/completions` and `/v1/models` endpoints.
 * Supports:
 * - Local Termux / desktop gateway (`http://localhost:20128/v1`, `http://10.0.2.2:20128/v1`, or custom LAN/VPS URL)
 * - Hosted OmniRoute Cloud (`https://omniroute.online/v1`)
 * - Zero-Config Auto-Combo channels (`auto`, `auto/fast`, `auto/smart`, `auto/vision`, `auto/coding`, `auto/cheap`)
 *   and explicit `<provider>/<model>` or custom combo targets
 * - `x-omniroute-no-memory` and `X-OmniRoute-Session-Id` headers for deterministic UI action steps
 *   and LKGP / cache-affinity session stickiness.
 */
class AniobOmniRouteProvider(
    private val apiKey: String,
    private val baseUrl: String = AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL,
    private val model: String = "auto",
    private val jsonMode: Boolean = false,
    private val sessionId: String? = null
) : AniobCloudLlmProvider {
    override val name: String = "OMNIROUTE_CLOUD"
    override val isConfigured: Boolean get() = apiKey.isNotBlank() || isLocalGateway(baseUrl)

    private val client: OkHttpClient =
        AniobHttpClientSingleton.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    override suspend fun generate(prompt: String, systemPrompt: String): String = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        if (apiKey.isBlank() && !isLocalGateway(baseUrl)) {
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
            val effectiveModel = AniobProviderConfig.coerceModel(model)
            val payload = buildChatPayload(effectiveModel, messages, maxTokens = 1024, enforceJsonObject = jsonMode)
            val endpoints = AniobProviderConfig.candidateEndpoints(baseUrl)

            var lastError = "Unreachable"
            for (endpoint in endpoints) {
                try {
                    val responseResult = executeWithJsonFallback(endpoint, payload, enforceJson = jsonMode)
                    val code = responseResult.code
                    val body = responseResult.body
                    if (code in 200..299) {
                        val fullContent = extractAssistantContent(body)
                        val routedModel = responseResult.routedModel ?: effectiveModel
                        AniobEgressLedger.record(
                            name,
                            routedModel,
                            prompt.length + systemPrompt.length,
                            fullContent.length,
                            System.currentTimeMillis() - startTime
                        )
                        return@withContext fullContent
                    } else {
                        lastError = "Error from Omniroute Cloud ($code): $body"
                        if (code == 401 || code == 403) return@withContext lastError
                    }
                } catch (ioe: IOException) {
                    lastError = "Request failed: ${ioe.message}"
                }
            }
            lastError
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
        sessionId = sessionId
    )

    private suspend fun streamingResult(
        prompt: String,
        systemPrompt: String,
        screenshotBase64: String?,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && !isLocalGateway(baseUrl)) {
            val msg = "Omniroute API key is not configured. Please add it in Settings."
            onDelta(msg)
            return@withContext msg
        }
        val startTime = System.currentTimeMillis()
        try {
            val messages = buildMessages(systemPrompt, prompt, screenshotBase64)
            val effectiveModel = resolveModelForVision(AniobProviderConfig.coerceModel(model), screenshotBase64)
            val payload = buildChatPayload(effectiveModel, messages, maxTokens = 1024, enforceJsonObject = jsonMode)
            val endpoints = AniobProviderConfig.candidateEndpoints(baseUrl)

            var lastError = "Request failed: gateway unreachable"
            for (endpoint in endpoints) {
                try {
                    val responseResult = executeWithJsonFallback(endpoint, payload, enforceJson = jsonMode)
                    val code = responseResult.code
                    val body = responseResult.body
                    if (code in 200..299) {
                        val full = extractAssistantContent(body)
                        val routedModel = responseResult.routedModel ?: effectiveModel
                        AniobEgressLedger.record(
                            name,
                            routedModel,
                            prompt.length + systemPrompt.length,
                            full.length,
                            System.currentTimeMillis() - startTime
                        )
                        onDelta(full)
                        return@withContext full
                    } else {
                        lastError = "Error from Omniroute Cloud ($code): $body"
                        if (code == 401 || code == 403) {
                            onDelta(lastError)
                            return@withContext lastError
                        }
                    }
                } catch (ioe: IOException) {
                    lastError = "Request failed: ${ioe.message}"
                }
            }
            onDelta(lastError)
            lastError
        } catch (e: Exception) {
            val msg = "Request failed: ${e.message}"
            onDelta(msg)
            msg
        }
    }

    private fun buildMessages(systemPrompt: String, userPrompt: String, screenshotBase64: String?): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        if (!screenshotBase64.isNullOrBlank()) {
            val userContent = JSONArray()
            userContent.put(JSONObject().apply { put("type", "text"); put("text", userPrompt) })
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$screenshotBase64") })
            })
            messages.put(JSONObject().apply { put("role", "user"); put("content", userContent) })
        } else {
            messages.put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
        }
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
        val startTime = System.currentTimeMillis()
        try {
            val messages = buildMessages(systemPrompt, userPrompt, screenshotBase64)
            val effectiveModel = resolveModelForVision(AniobProviderConfig.coerceModel(model), screenshotBase64)
            val payload = buildChatPayload(
                targetModel = effectiveModel,
                messages = messages,
                maxTokens = 512,
                enforceJsonObject = true
            )
            val endpoints = AniobProviderConfig.candidateEndpoints(baseUrl)
            var lastFailure: Exception = IOException("OmniRoute gateway unreachable")

            for (endpoint in endpoints) {
                val attempt = executeAsyncWithJsonFallback(endpoint, payload)
                if (attempt.isSuccess) {
                    val responseResult = attempt.getOrThrow()
                    val rawContent = extractAssistantContent(responseResult.body)
                    val routedModel = responseResult.routedModel ?: effectiveModel
                    AniobEgressLedger.record(
                        name,
                        routedModel,
                        userPrompt.length + systemPrompt.length,
                        rawContent.length,
                        System.currentTimeMillis() - startTime
                    )
                    return@withContext Result.success(rawContent)
                } else {
                    val ex = attempt.exceptionOrNull() as? Exception ?: Exception("OmniRoute request failed")
                    lastFailure = ex
                    // Do not fail over to other hosts if key was explicitly rejected by the gateway
                    if (ex.message?.contains("(401)") == true || ex.message?.contains("(403)") == true) {
                        return@withContext Result.failure(ex)
                    }
                }
            }
            Result.failure(lastFailure)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class HttpOutcome(
        val code: Int,
        val body: String,
        val routedModel: String?,
        val routedProvider: String?,
        val decisionHeader: String?
    )

    private fun buildChatPayload(
        targetModel: String,
        messages: JSONArray,
        maxTokens: Int,
        enforceJsonObject: Boolean
    ): JSONObject = JSONObject().apply {
        put("model", targetModel)
        put("messages", messages)
        put("max_tokens", maxTokens)
        if (enforceJsonObject) {
            put("temperature", 0.1)
            put("response_format", JSONObject().apply { put("type", "json_object") })
        }
    }

    private fun buildRequest(endpoint: String, payload: JSONObject, noMemory: Boolean): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
        }
        if (noMemory) {
            builder.addHeader("x-omniroute-no-memory", "true")
        }
        if (!sessionId.isNullOrBlank()) {
            builder.addHeader("X-OmniRoute-Session-Id", sessionId)
        }
        return builder
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun executeWithJsonFallback(
        endpoint: String,
        payload: JSONObject,
        enforceJson: Boolean
    ): HttpOutcome {
        val primaryReq = buildRequest(endpoint, payload, noMemory = enforceJson)
        client.newCall(primaryReq).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 400 && enforceJson && payload.has("response_format")) {
                // Retry once without response_format for upstream providers in OmniRoute that reject json_object
                val fallbackPayload = JSONObject(payload.toString()).apply { remove("response_format") }
                val fallbackReq = buildRequest(endpoint, fallbackPayload, noMemory = true)
                client.newCall(fallbackReq).execute().use { retryResp ->
                    return HttpOutcome(
                        code = retryResp.code,
                        body = retryResp.body?.string().orEmpty(),
                        routedModel = retryResp.header("X-OmniRoute-Model"),
                        routedProvider = retryResp.header("X-OmniRoute-Provider"),
                        decisionHeader = retryResp.header("X-OmniRoute-Decision")
                    )
                }
            }
            return HttpOutcome(
                code = response.code,
                body = body,
                routedModel = response.header("X-OmniRoute-Model"),
                routedProvider = response.header("X-OmniRoute-Provider"),
                decisionHeader = response.header("X-OmniRoute-Decision")
            )
        }
    }

    private suspend fun executeAsyncWithJsonFallback(
        endpoint: String,
        payload: JSONObject
    ): Result<HttpOutcome> {
        val first = enqueueCall(buildRequest(endpoint, payload, noMemory = true))
        if (first.isSuccess) return first
        val errMsg = first.exceptionOrNull()?.message.orEmpty()
        if (errMsg.contains("(400)") && payload.has("response_format")) {
            val fallbackPayload = JSONObject(payload.toString()).apply { remove("response_format") }
            return enqueueCall(buildRequest(endpoint, fallbackPayload, noMemory = true))
        }
        return first
    }

    private suspend fun enqueueCall(request: Request): Result<HttpOutcome> {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    Result.failure(Exception("Omniroute Cloud API error (${response.code}): $body"))
                                )
                            }
                            return
                        }
                        val outcome = HttpOutcome(
                            code = response.code,
                            body = body,
                            routedModel = response.header("X-OmniRoute-Model"),
                            routedProvider = response.header("X-OmniRoute-Provider"),
                            decisionHeader = response.header("X-OmniRoute-Decision")
                        )
                        if (continuation.isActive) {
                            continuation.resume(Result.success(outcome))
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(Result.failure(e))
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 60L

        internal fun isLocalGateway(url: String): Boolean {
            val lower = url.trim().lowercase()
            return lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("10.0.2.2")
        }

        internal fun resolveModelForVision(configuredModel: String, screenshotBase64: String?): String {
            if (!screenshotBase64.isNullOrBlank() && configuredModel == "auto") {
                return "auto"
            }
            return configuredModel
        }

        /**
         * Extracts the assistant text content from an OpenAI / OmniRoute chat completion response.
         * Supports string content, multimodal array content blocks, and reasoning_content fallback.
         */
        internal fun extractAssistantContent(body: String): String {
            val rootJson = JSONObject(body)
            val choices = rootJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice0 = choices.getJSONObject(0)
                val message = choice0.optJSONObject("message") ?: choice0.optJSONObject("delta")
                if (message != null) {
                    val rawContent = message.opt("content")
                    when (rawContent) {
                        is String -> if (rawContent.isNotBlank()) return rawContent
                        is JSONArray -> {
                            val sb = StringBuilder()
                            for (i in 0 until rawContent.length()) {
                                val part = rawContent.opt(i)
                                when (part) {
                                    is JSONObject -> {
                                        val text = part.optString("text", "")
                                        if (text.isNotEmpty()) sb.append(text)
                                    }
                                    is String -> sb.append(part)
                                }
                            }
                            if (sb.isNotEmpty()) return sb.toString()
                        }
                    }
                    val reasoning = message.optString("reasoning_content", "").ifBlank {
                        message.optString("reasoning", "")
                    }
                    if (reasoning.isNotBlank()) return reasoning
                }
            }
            val directOutput = rootJson.optString("output_text", "")
            if (directOutput.isNotBlank()) return directOutput
            throw org.json.JSONException("No assistant message content in OmniRoute response")
        }
    }
}

enum class OmniRouteConnectionStatus {
    SUCCESS,
    BAD_KEY,
    UNREACHABLE
}

data class OmniRouteDiagnostics(
    val status: OmniRouteConnectionStatus,
    val resolvedEndpoint: String = "",
    val latencyMs: Long = 0L,
    val routedModel: String? = null,
    val routedProvider: String? = null,
    val omniRouteVersion: String? = null,
    val availableModels: List<String> = emptyList(),
    val message: String = ""
)

/**
 * Fetches available models and combos from an OmniRoute gateway's `GET /v1/models` endpoint.
 */
suspend fun fetchOmniRouteModels(apiKey: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
    val client = AniobHttpClientSingleton.client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    for (modelsUrl in AniobProviderConfig.candidateModelsEndpoints(baseUrl)) {
        try {
            val reqBuilder = Request.Builder()
                .url(modelsUrl)
                .get()
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val data = root.optJSONArray("data") ?: return@use
                    val models = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id", "").trim()
                        if (id.isNotEmpty()) models.add(id)
                    }
                    if (models.isNotEmpty()) return@withContext models.distinct()
                }
            }
        } catch (_: Exception) {
            // Try next candidate
        }
    }
    emptyList()
}

/**
 * Performs an end-to-end diagnostic check against the OmniRoute gateway (`/v1/models` + `/v1/chat/completions`).
 */
suspend fun verifyOmniRouteGateway(
    apiKey: String,
    baseUrl: String,
    model: String = "auto"
): OmniRouteDiagnostics = withContext(Dispatchers.IO) {
    val trimmedKey = apiKey.trim()
    if (trimmedKey.isBlank() && !AniobOmniRouteProvider.isLocalGateway(baseUrl)) {
        return@withContext OmniRouteDiagnostics(
            status = OmniRouteConnectionStatus.BAD_KEY,
            message = "Enter your Cloud Access Key first."
        )
    }

    val client = AniobHttpClientSingleton.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    val discoveredModels = runCatching { fetchOmniRouteModels(trimmedKey, baseUrl) }.getOrDefault(emptyList())
    val targetModel = AniobProviderConfig.coerceModel(model)
    val payload = JSONObject().apply {
        put("model", targetModel)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "ping")
            })
        })
        put("max_tokens", 1)
    }

    for (endpoint in AniobProviderConfig.candidateEndpoints(baseUrl)) {
        val startMs = System.currentTimeMillis()
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-omniroute-no-memory", "true")
        if (trimmedKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $trimmedKey")
        }
        val request = reqBuilder
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val routedModel = response.header("X-OmniRoute-Model") ?: targetModel
                val routedProvider = response.header("X-OmniRoute-Provider")
                val version = response.header("X-OmniRoute-Version")
                when (response.code) {
                    in 200..299 -> {
                        val providerSuffix = if (!routedProvider.isNullOrBlank()) " via $routedProvider" else ""
                        val modelCountSuffix = if (discoveredModels.isNotEmpty()) " · ${discoveredModels.size} models available" else ""
                        return@withContext OmniRouteDiagnostics(
                            status = OmniRouteConnectionStatus.SUCCESS,
                            resolvedEndpoint = endpoint.removeSuffix(AniobProviderConfig.CHAT_COMPLETIONS_PATH),
                            latencyMs = latency,
                            routedModel = routedModel,
                            routedProvider = routedProvider,
                            omniRouteVersion = version,
                            availableModels = discoveredModels,
                            message = "Connected ✓ (${latency}ms · $routedModel$providerSuffix$modelCountSuffix)"
                        )
                    }
                    401, 403 -> {
                        return@withContext OmniRouteDiagnostics(
                            status = OmniRouteConnectionStatus.BAD_KEY,
                            resolvedEndpoint = endpoint.removeSuffix(AniobProviderConfig.CHAT_COMPLETIONS_PATH),
                            latencyMs = latency,
                            availableModels = discoveredModels,
                            message = "Authentication failed (${response.code}): check your Cloud Access Key."
                        )
                    }
                    else -> {
                        // If /v1/models succeeded on this gateway (e.g. fresh OmniRoute install before connecting an upstream provider),
                        // report reachable gateway with helpful guidance.
                        if (discoveredModels.isNotEmpty()) {
                            return@withContext OmniRouteDiagnostics(
                                status = OmniRouteConnectionStatus.SUCCESS,
                                resolvedEndpoint = endpoint.removeSuffix(AniobProviderConfig.CHAT_COMPLETIONS_PATH),
                                latencyMs = latency,
                                omniRouteVersion = version,
                                availableModels = discoveredModels,
                                message = "Gateway reachable ✓ (${discoveredModels.size} models cataloged; connect an upstream provider for '$targetModel')"
                            )
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Try next candidate endpoint
        } catch (_: Exception) {
            // Try next candidate endpoint
        }
    }

    if (discoveredModels.isNotEmpty()) {
        return@withContext OmniRouteDiagnostics(
            status = OmniRouteConnectionStatus.SUCCESS,
            resolvedEndpoint = AniobProviderConfig.resolveBaseUrl(baseUrl),
            availableModels = discoveredModels,
            message = "Gateway reachable ✓ (${discoveredModels.size} models discovered)"
        )
    }

    OmniRouteDiagnostics(
        status = OmniRouteConnectionStatus.UNREACHABLE,
        resolvedEndpoint = AniobProviderConfig.resolveBaseUrl(baseUrl),
        message = "Gateway unreachable at ${AniobProviderConfig.resolveBaseUrl(baseUrl)} — verify server address or network."
    )
}

suspend fun testOmniRouteConnection(apiKey: String, baseUrl: String): OmniRouteConnectionStatus =
    verifyOmniRouteGateway(apiKey = apiKey, baseUrl = baseUrl).status
