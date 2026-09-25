package com.aniob.core.external

import com.aniob.core.intent.AniobIntent
import com.aniob.core.memory.AniobSharedKnowledgeStore
import com.aniob.core.providers.AniobLocalLlmProvider
import com.aniob.core.tools.DevicePowerState

/**
 * Interface for Cloud AI Providers (Omniroute etc.) in core-agent.
 */
interface AniobCloudLlmProvider {
    val name: String
    suspend fun generate(prompt: String, systemPrompt: String): String
    suspend fun generateStreaming(prompt: String, systemPrompt: String, onDelta: (String) -> Unit): String
}

sealed interface ExternalAiResult {
    val answer: String
    val provider: String
    val intent: AniobIntent
    val latencyMs: Long

    data class Success(
        override val answer: String,
        override val provider: String,
        override val intent: AniobIntent,
        override val latencyMs: Long,
        val fromVault: Boolean = false
    ) : ExternalAiResult

    data class Unavailable(
        override val answer: String,
        override val provider: String = "NO_PROVIDER",
        override val intent: AniobIntent,
        override val latencyMs: Long,
        val reason: String
    ) : ExternalAiResult

    data class Error(
        override val answer: String,
        override val provider: String = "ERROR",
        override val intent: AniobIntent,
        override val latencyMs: Long,
        val cause: String
    ) : ExternalAiResult
}

/**
 * Trigger external AI queries for queries where Aniob should not perform UI automation.
 * Pure JVM, unit-testable.
 */
class AniobExternalAiTrigger(
    private val cloudProvider: AniobCloudLlmProvider?,
    private val localProvider: AniobLocalLlmProvider?,
    private val sharedKnowledgeStore: AniobSharedKnowledgeStore
) {

    suspend fun query(
        prompt: String,
        intent: AniobIntent,
        powerState: DevicePowerState,
        autoMode: String = "auto",
        onDelta: (String) -> Unit = {}
    ): ExternalAiResult {
        val startMs = System.currentTimeMillis()

        // VAULT_QUERY -> check SharedKnowledgeStore first
        if (intent == AniobIntent.VAULT_QUERY) {
            val lower = prompt.lowercase()
            var vaultResult = sharedKnowledgeStore.get(lower)
            if (vaultResult == null) {
                val all = sharedKnowledgeStore.getAll()
                vaultResult = all.entries.firstOrNull { (k, _) ->
                    lower.contains(k) || k.contains(lower)
                }?.value
            }
            if (vaultResult != null) {
                onDelta(vaultResult)
                return ExternalAiResult.Success(
                    answer = vaultResult,
                    provider = "VAULT",
                    intent = intent,
                    latencyMs = System.currentTimeMillis() - startMs,
                    fromVault = true
                )
            }
        }

        val systemPrompt = when (intent) {
            AniobIntent.KNOWLEDGE_QA -> """
                You are Aniob Knowledge Assistant. Provide a direct, concise, and helpful answer.
                Do NOT generate device automation actions or UI gestures.
            """.trimIndent()
            AniobIntent.WEB_RESEARCH -> """
                You are Aniob Research Assistant. Provide factual, up-to-date information concisely.
                Do NOT generate device automation actions or UI gestures.
            """.trimIndent()
            AniobIntent.VAULT_QUERY -> """
                You are Aniob Personal Memory Assistant. Answer based on known user preferences.
            """.trimIndent()
            AniobIntent.EXTERNAL_AI_QUERY -> """
                You are Aniob, a friendly and intelligent assistant. Provide a helpful response directly.
            """.trimIndent()
            else -> "You are Aniob."
        }

        val isLocalReady = localProvider != null && localProvider.isAvailable()
        val isCloudReady = powerState.isNetworkAvailable && cloudProvider != null

        val useLocal = when (autoMode.lowercase()) {
            "local-only" -> isLocalReady
            "cloud-only" -> false
            "local-first" -> isLocalReady || !isCloudReady
            "balanced", "auto" -> {
                if (!powerState.isNetworkAvailable) {
                    isLocalReady
                } else if (powerState.batteryPercent < 15 && !powerState.isCharging) {
                    false // low battery -> cloud
                } else if (intent == AniobIntent.KNOWLEDGE_QA && isLocalReady) {
                    true // For KNOWLEDGE_QA simple -> local Phi-4 Mini is smartest 3.8B MMLU 68%
                } else if (isCloudReady) {
                    false // for web research -> cloud
                } else {
                    isLocalReady
                }
            }
            else -> !isCloudReady && isLocalReady
        }

        val result: ExternalAiResult = try {
            if (useLocal && isLocalReady) {
                val fullAnswer = localProvider!!.chatStreaming(prompt) { delta ->
                    onDelta(delta)
                }
                ExternalAiResult.Success(
                    answer = fullAnswer,
                    provider = "LOCAL_SLM",
                    intent = intent,
                    latencyMs = System.currentTimeMillis() - startMs
                )
            } else if (isCloudReady) {
                val fullAnswer = cloudProvider!!.generateStreaming(prompt, systemPrompt) { delta ->
                    onDelta(delta)
                }
                ExternalAiResult.Success(
                    answer = fullAnswer,
                    provider = cloudProvider.name,
                    intent = intent,
                    latencyMs = System.currentTimeMillis() - startMs
                )
            } else if (isLocalReady) {
                val fullAnswer = localProvider!!.chatStreaming(prompt) { delta ->
                    onDelta(delta)
                }
                ExternalAiResult.Success(
                    answer = fullAnswer,
                    provider = "LOCAL_SLM",
                    intent = intent,
                    latencyMs = System.currentTimeMillis() - startMs
                )
            } else {
                val reason = "No AI provider or on-device model is configured to answer this question. Download an on-device model in Models or configure an API key in Settings."
                val answer = "⚠️ $reason"
                onDelta(answer)
                ExternalAiResult.Unavailable(
                    answer = answer,
                    provider = "NO_PROVIDER",
                    intent = intent,
                    latencyMs = System.currentTimeMillis() - startMs,
                    reason = reason
                )
            }
        } catch (e: Exception) {
            val errMsg = "Failed to query AI provider: ${e.message ?: "Unknown error"}"
            onDelta(errMsg)
            ExternalAiResult.Error(
                answer = errMsg,
                provider = "ERROR",
                intent = intent,
                latencyMs = System.currentTimeMillis() - startMs,
                cause = e.message ?: "Unknown error"
            )
        }

        // Only save preference into vault when successfully answered
        if (result is ExternalAiResult.Success) {
            if (prompt.contains("remember", ignoreCase = true) || intent == AniobIntent.VAULT_QUERY) {
                sharedKnowledgeStore.put(prompt.lowercase(), result.answer)
            }
        }

        return result
    }
}
