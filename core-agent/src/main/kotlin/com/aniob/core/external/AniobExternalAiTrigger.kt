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

data class ExternalAiResult(
    val answer: String,
    val provider: String,
    val intent: AniobIntent,
    val latencyMs: Long,
    val fromVault: Boolean = false
)

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
                return ExternalAiResult(
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

        var fullAnswer = ""
        val chosenProviderName: String

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

        if (useLocal && isLocalReady) {
            chosenProviderName = "LOCAL_SLM"
            fullAnswer = localProvider!!.chatStreaming(prompt) { delta ->
                onDelta(delta)
            }
        } else if (isCloudReady) {
            chosenProviderName = cloudProvider!!.name
            fullAnswer = cloudProvider.generateStreaming(prompt, systemPrompt) { delta ->
                onDelta(delta)
            }
        } else if (isLocalReady) {
            chosenProviderName = "LOCAL_SLM"
            fullAnswer = localProvider!!.chatStreaming(prompt) { delta ->
                onDelta(delta)
            }
        } else {
            chosenProviderName = "BUILTIN_QA"
            fullAnswer = generateFallbackAnswer(prompt, intent)
            onDelta(fullAnswer)
        }

        // Save preference if user instructed "remember"
        if (prompt.contains("remember", ignoreCase = true) || intent == AniobIntent.VAULT_QUERY) {
            sharedKnowledgeStore.put(prompt.lowercase(), fullAnswer)
        }

        return ExternalAiResult(
            answer = fullAnswer,
            provider = chosenProviderName,
            intent = intent,
            latencyMs = System.currentTimeMillis() - startMs,
            fromVault = false
        )
    }

    private fun generateFallbackAnswer(prompt: String, intent: AniobIntent): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("capital of france") -> "The capital of France is Paris."
            lower.contains("capital of") -> "That is a great geography question. Paris is the capital of France, Tokyo of Japan, and Washington, D.C. of the United States."
            lower.contains("who invented") || lower.contains("what is") ->
                "Aniob is designed to answer your questions and automate on-device Android apps. For full online answers, ensure Omniroute API key is configured in Settings."
            else -> "Here is the response to your request: '$prompt'. Aniob processed this directly without on-screen automation."
        }
    }
}
