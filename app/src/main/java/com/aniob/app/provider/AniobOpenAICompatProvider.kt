package com.aniob.app.provider

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SwipeDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Universal OpenAI-Compatible Provider Adapter (reference DroidRun provider family).
 * Supports any OpenAI-compatible endpoint (Ollama, vLLM, DeepSeek, Groq, LMStudio).
 */
open class AniobOpenAICompatProvider(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val modelName: String = "qwen2.5:1.5b"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun complete(prompt: String, systemPrompt: String = ""): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            if (systemPrompt.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })

            val payload = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", 512)
            }

            val requestBuilder = Request.Builder()
                .url(baseUrl)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Provider returned ${response.code}: $body"))
            }

            val root = JSONObject(body)
            val text = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * On-Device SLM client targeting local Ollama or llama.cpp endpoint.
 */
class AniobOnDeviceProvider(
    baseUrl: String = "http://127.0.0.1:11434/v1/chat/completions",
    modelName: String = "qwen2.5:1.5b"
) : AniobOpenAICompatProvider(baseUrl = baseUrl, apiKey = "", modelName = modelName)
