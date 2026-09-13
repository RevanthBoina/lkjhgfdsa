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
 * Omniroute Cloud Provider client.
 * Connects to https://api.omniroute.ai/v1/chat/completions with gpt-4o vision.
 */
class AniobOmniRouteProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.omniroute.ai/v1/chat/completions",
    private val model: String = "gpt-4o"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getNextAction(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String? = null
    ): Result<AniobAction> = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()

            // System message
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            // User message with optional image URL
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

            val rootJson = JSONObject(body)
            val rawContent = rootJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val action = parseActionJson(rawContent)
            Result.success(action)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseActionJson(jsonString: String): AniobAction {
        val json = JSONObject(jsonString)
        val thought = json.optString("thought", "Action planned by Omniroute Cloud")
        val actionType = json.optString("action", "WAIT").uppercase()
        val targetNodeId = json.optInt("targetNodeId", 1)

        return when (actionType) {
            "CLICK" -> AniobAction.Click(targetNodeId = targetNodeId, thought = thought)
            "INPUT_TEXT" -> AniobAction.InputText(
                targetNodeId = targetNodeId,
                text = json.optString("text", ""),
                thought = thought
            )
            "SWIPE" -> {
                val dirStr = json.optString("swipeDirection", "UP").uppercase()
                val dir = try { SwipeDirection.valueOf(dirStr) } catch (e: Exception) { SwipeDirection.UP }
                AniobAction.Swipe(direction = dir, thought = thought)
            }
            "PRESS_BACK" -> AniobAction.PressKey(KeyType.BACK, thought = thought)
            "PRESS_HOME" -> AniobAction.PressKey(KeyType.HOME, thought = thought)
            "FINISH" -> AniobAction.Finish(summary = json.optString("reason", "Task finished"), thought = thought)
            "FAIL" -> AniobAction.Fail(reason = json.optString("reason", "Task failed"), thought = thought)
            else -> AniobAction.Wait(durationMs = 1000L, thought = thought)
        }
    }
}
