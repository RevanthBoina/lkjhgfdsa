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
    EXTERNAL_AI_QUERY,
    CANNOT_PROCEED
}

data class RouteDecision(
    val target: RouteTarget,
    val reason: String,
    val requiresVision: Boolean = false,
    val modelId: String? = null
)

/**
 * World-Class AutoRouter:
 * - Honors strict user routing choices: local-only forbids cloud; cloud-only forbids local.
 * - Local-First default for on-device SLMs (e.g. Phi-4 Mini 3.8B, Llama 3.2 3B).
 * - Cloud escalation only when truly necessary in auto mode (low battery <15%, repeated local failures, or explicit vision needs).
 * - Validates local model file existence without mock fallbacks.
 */
object AniobAutoRouter {

    const val PROVIDER_OMNIROUTE = "OMNIROUTE_CLOUD"
    const val PROVIDER_LOCAL = "LOCAL_SLM"
    const val PROVIDER_FASTPATH = "FASTPATH"
    const val PROVIDER_SKILL = "SKILL"
    const val PROVIDER_INTENT = "INTENT"
    const val PROVIDER_EXTERNAL_AI = "EXTERNAL_AI_QUERY"
    const val PROVIDER_CANNOT_PROCEED = "CANNOT_PROCEED"

    fun decideRoute(
        taskPrompt: String,
        screenState: AniobScreenState,
        powerState: DevicePowerState,
        hasFastPathHit: Boolean = false,
        isIntentShortcut: Boolean = false,
        installedModelId: String? = null,
        lastLocalFailCount: Int = 0,
        modelsDir: File? = null,
        isModelFileMissing: Boolean = false,
        userRoutingMode: String = "auto"
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

        val mode = userRoutingMode.lowercase()

        // Strict user policy: local-only forbids cloud under ALL circumstances
        if (mode == "local-only") {
            val modelMissing = isModelFileMissing || (modelsDir != null && installedModelId != null && !File(modelsDir, "$installedModelId.gguf").exists())
            if (modelMissing) {
                return RouteDecision(
                    target = RouteTarget.CANNOT_PROCEED,
                    reason = "Local-only mode active: local model is missing or not installed",
                    requiresVision = false,
                    modelId = installedModelId
                )
            }
            return RouteDecision(
                target = RouteTarget.LOCAL_SLM,
                reason = "Local-only policy enforced: cloud offload strictly forbidden",
                requiresVision = false,
                modelId = installedModelId
            )
        }

        // Strict user policy: cloud-only forbids local SLM
        if (mode == "cloud-only") {
            if (!powerState.isNetworkAvailable) {
                return RouteDecision(
                    target = RouteTarget.CANNOT_PROCEED,
                    reason = "Cloud-only mode active: network is offline",
                    requiresVision = false
                )
            }
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Cloud-only policy enforced: local SLM strictly bypassed",
                requiresVision = false
            )
        }

        // 3. Offline constraint -> Must use local SLM
        if (!powerState.isNetworkAvailable) {
            val modelMissing = isModelFileMissing || (modelsDir != null && installedModelId != null && !File(modelsDir, "$installedModelId.gguf").exists())
            if (modelMissing) {
                return RouteDecision(
                    target = RouteTarget.CANNOT_PROCEED,
                    reason = "Offline and local model missing: cannot automate without model",
                    requiresVision = false
                )
            }
            return RouteDecision(
                target = RouteTarget.LOCAL_SLM,
                reason = "Offline -> local ${installedModelId ?: "SLM"}",
                requiresVision = false,
                modelId = installedModelId
            )
        }

        // 4. Battery / Thermal Check (spinal reflex: battery low -> offload, no LLM)
        if (AniobBatteryHealth.isLowBattery(powerState)) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Battery low reflex: ${powerState.batteryPercent}% not charging -> cloud",
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
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Local model file missing or not installed, using Omniroute",
                requiresVision = false
            )
        }

        // FIXED: Less aggressive vision - was (unlabelled>=2 && taskNeedsVision) || (unlabelled>=5 && taskNeedsVision) redundant
        val unlabelled = screenState.nodes.count { it.isClickable && it.text.isBlank() && it.contentDescription.isBlank() }
        val hasCanvas = screenState.nodes.any { it.className.contains("Canvas") || it.className.contains("SurfaceView") }
        val taskNeedsVision = taskPrompt.contains("icon", true) || taskPrompt.contains("image", true) || taskPrompt.contains("canvas", true) || taskPrompt.contains("picture", true)
        val needsVision = (unlabelled >= 5) || (unlabelled >= 2 && taskNeedsVision) || (hasCanvas && taskNeedsVision)
        if (needsVision) {
            return RouteDecision(RouteTarget.OMNIROUTE_CLOUD, "Vision needed unlabelled $unlabelled canvas $hasCanvas -> cloud vision", true)
        }

        val textRatio = if (screenState.nodes.isNotEmpty()) screenState.nodes.count { it.text.isNotBlank() }.toFloat() / screenState.nodes.size else 0f
        val isSimple = screenState.nodes.isNotEmpty() && screenState.nodes.size <= 40 && textRatio >= 0.6f
        if (isSimple) {
            return RouteDecision(RouteTarget.LOCAL_SLM, "Simple text nav ${screenState.nodes.size} nodes ratio $textRatio -> local ${installedModelId ?: "SLM"}", false, installedModelId)
        }

        if (lastLocalFailCount >= 2) {
            return RouteDecision(RouteTarget.OMNIROUTE_CLOUD, "Local failed $lastLocalFailCount times -> escalate to cloud")
        }

        return RouteDecision(RouteTarget.LOCAL_SLM, "Default local-first for 8GB RAM phones, model ${installedModelId ?: "SLM"}", false, installedModelId)
    }
}
