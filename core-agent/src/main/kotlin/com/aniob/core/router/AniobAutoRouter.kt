package com.aniob.core.router

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.tools.AniobBatteryHealth
import com.aniob.core.tools.DevicePowerState
import java.io.File

enum class RouteTarget {
    FASTPATH,
    INTENT,
    SKILL,
    LOCAL_SLM,
    OMNIROUTE_CLOUD,
    EXTERNAL_AI_QUERY
}

data class RouteDecision(
    val target: RouteTarget,
    val reason: String,
    val requiresVision: Boolean = false,
    val modelId: String? = null
)

/**
 * World-Class AutoRouter:
 * - Local-First default for on-device SLMs (e.g. Phi-4 Mini 3.8B, Llama 3.2 3B).
 * - Cloud escalation only when truly necessary (low battery <15%, repeated local failures, or explicit vision needs).
 * - Validates local model file existence.
 */
object AniobAutoRouter {

    const val PROVIDER_OMNIROUTE = "OMNIROUTE_CLOUD"
    const val PROVIDER_LOCAL = "LOCAL_SLM"
    const val PROVIDER_FASTPATH = "FASTPATH"
    const val PROVIDER_SKILL = "SKILL"
    const val PROVIDER_INTENT = "INTENT"
    const val PROVIDER_EXTERNAL_AI = "EXTERNAL_AI_QUERY"

    fun decideRoute(
        taskPrompt: String,
        screenState: AniobScreenState,
        powerState: DevicePowerState,
        hasFastPathHit: Boolean = false,
        isIntentShortcut: Boolean = false,
        installedModelId: String? = null,
        lastLocalFailCount: Int = 0,
        modelsDir: File? = null,
        isModelFileMissing: Boolean = false
    ): RouteDecision {
        // 1. Intent Shortcut (0ms)
        if (isIntentShortcut) {
            return RouteDecision(
                target = RouteTarget.INTENT,
                reason = "Intent Shortcut matched: Zero-latency system dispatch",
                requiresVision = false
            )
        }

        // 2. FastPath Cache Hit (0 tokens)
        if (hasFastPathHit) {
            return RouteDecision(
                target = RouteTarget.FASTPATH,
                reason = "FastPath trajectory hit in AppKnowledgeBase: 0 tokens",
                requiresVision = false
            )
        }

        // 3. Offline constraint -> Must use local SLM
        if (!powerState.isNetworkAvailable) {
            return RouteDecision(
                target = RouteTarget.LOCAL_SLM,
                reason = "Offline -> local ${installedModelId ?: "SLM"}",
                requiresVision = false,
                modelId = installedModelId
            )
        }

        // 4. Battery / Thermal Check (only offload if battery < 15% AND not charging, or thermal throttled)
        if (powerState.batteryPercent < 15 && !powerState.isCharging) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Battery ${powerState.batteryPercent}% low not charging -> cloud",
                requiresVision = false
            )
        }
        if (powerState.isThermalThrottled) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Device thermal throttled -> offload to cloud",
                requiresVision = false
            )
        }

        // 5. Check installed model actually exists
        val modelMissing = isModelFileMissing || (modelsDir != null && installedModelId != null && !File(modelsDir, "$installedModelId.gguf").exists())
        if (modelMissing) {
            return if (powerState.isNetworkAvailable) {
                RouteDecision(
                    target = RouteTarget.OMNIROUTE_CLOUD,
                    reason = "Local model file missing or not installed, using Omniroute",
                    requiresVision = false
                )
            } else {
                RouteDecision(
                    target = RouteTarget.LOCAL_SLM,
                    reason = "Offline no model, try local mock",
                    requiresVision = false
                )
            }
        }

        // 6. Vision: less aggressive - need (unlabelled >= 2 && taskNeedsVision) OR (unlabelled >= 5 && taskNeedsVision) OR (hasCanvas && taskNeedsVision)
        val unlabelled = screenState.nodes.count { it.isClickable && it.text.isBlank() && it.contentDescription.isBlank() }
        val hasCanvas = screenState.nodes.any { it.className.contains("Canvas") || it.className.contains("SurfaceView") }
        val taskNeedsVision = taskPrompt.contains("icon", ignoreCase = true) ||
                taskPrompt.contains("image", ignoreCase = true) ||
                taskPrompt.contains("canvas", ignoreCase = true)
        val needsVision = (unlabelled >= 2 && taskNeedsVision) || (unlabelled >= 5 && taskNeedsVision) || (hasCanvas && taskNeedsVision)
        if (needsVision) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Vision needed unlabelled $unlabelled canvas $hasCanvas -> cloud vision",
                requiresVision = true
            )
        }

        // 7. Simple text nav: relax to total <=40 and ratio >=0.6
        val textRatio = if (screenState.nodes.isNotEmpty()) {
            screenState.nodes.count { it.text.isNotBlank() }.toFloat() / screenState.nodes.size
        } else 0f
        val isSimple = screenState.nodes.isNotEmpty() && screenState.nodes.size <= 40 && textRatio >= 0.6f
        if (isSimple) {
            return RouteDecision(
                target = RouteTarget.LOCAL_SLM,
                reason = "Simple text nav ${screenState.nodes.size} nodes ratio $textRatio -> local ${installedModelId ?: "SLM"}",
                requiresVision = false,
                modelId = installedModelId
            )
        }

        // 8. Learning: if local failed last 2 times, escalate to cloud
        if (lastLocalFailCount >= 2) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Local failed $lastLocalFailCount times -> escalate to cloud",
                requiresVision = false
            )
        }

        // 9. Default: LOCAL-FIRST for 8GB RAM phones, not cloud-first
        return RouteDecision(
            target = RouteTarget.LOCAL_SLM,
            reason = "Default local-first for 8GB RAM phones, model ${installedModelId ?: "SLM"}, complex task but try local first",
            requiresVision = false,
            modelId = installedModelId
        )
    }
}
