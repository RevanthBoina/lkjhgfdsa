package com.aniob.core.router

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.tools.AniobBatteryHealth
import com.aniob.core.tools.DevicePowerState

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

object AniobAutoRouter {

    const val PROVIDER_OMNIROUTE = "OMNIROUTE_CLOUD"
    const val PROVIDER_LOCAL = "LOCAL_SLM"
    const val PROVIDER_FASTPATH = "FASTPATH"
    const val PROVIDER_SKILL = "SKILL"
    const val PROVIDER_INTENT = "INTENT"
    const val PROVIDER_EXTERNAL_AI = "EXTERNAL_AI_QUERY"

    /**
     * Core routing algorithm: Evaluates task, screen state, and device telemetry.
     */
    fun decideRoute(
        taskPrompt: String,
        screenState: AniobScreenState,
        powerState: DevicePowerState,
        hasFastPathHit: Boolean = false,
        isIntentShortcut: Boolean = false,
        installedModelId: String? = null
    ): RouteDecision {
        // 1. Direct Intent Shortcut (0ms LLM)
        if (isIntentShortcut) {
            return RouteDecision(
                target = RouteTarget.INTENT,
                reason = "Intent Shortcut matched: Zero-latency system dispatch",
                requiresVision = false
            )
        }

        // 2. FastPath Cache Hit (0ms LLM)
        if (hasFastPathHit) {
            return RouteDecision(
                target = RouteTarget.FASTPATH,
                reason = "FastPath trajectory hit in AppKnowledgeBase: 0 tokens",
                requiresVision = false
            )
        }

        // 3. Offline constraint -> Must use local SLM
        if (!powerState.isNetworkAvailable) {
            val modelName = installedModelId ?: "Default Local SLM"
            return RouteDecision(
                target = RouteTarget.LOCAL_SLM,
                reason = "Device is offline: Forcing local SLM ($modelName)",
                requiresVision = false,
                modelId = installedModelId
            )
        }

        // 4. Battery / Thermal Check (battery < 20% -> offload to cloud)
        if (AniobBatteryHealth.shouldOffloadToCloud(powerState)) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Battery low (${powerState.batteryPercent}%) or thermal throttled: Offloading compute to Omniroute Cloud",
                requiresVision = false
            )
        }

        // 5. Vision Requirement Check (Custom canvas, unlabelled interactive icons)
        val needsVision = detectVisionNeed(screenState)
        if (needsVision) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Screen contains unlabelled icons / canvas views: Routing to Omniroute Cloud gpt-4o vision",
                requiresVision = true
            )
        }

        // 6. Simple structured text navigation or installed coding model -> Local SLM
        if (isSimpleTextNavigation(screenState) || (installedModelId != null && !needsVision)) {
            val modelLabel = installedModelId ?: "On-Device SLM"
            return RouteDecision(
                target = RouteTarget.LOCAL_SLM,
                reason = "Handled locally by on-device model ($modelLabel)",
                requiresVision = false,
                modelId = installedModelId
            )
        }

        // 7. Default to Omniroute Cloud for complex / unfamiliar multi-step task reasoning
        return RouteDecision(
            target = RouteTarget.OMNIROUTE_CLOUD,
            reason = "Complex multi-step task flow: Routed to Omniroute Cloud",
            requiresVision = false
        )
    }

    private fun detectVisionNeed(screen: AniobScreenState): Boolean {
        // If actionable nodes have no text or description, visual reasoning is mandatory
        val unlabelledInteractiveNodes = screen.nodes.count {
            it.isClickable && it.text.isBlank() && it.contentDescription.isBlank()
        }
        return unlabelledInteractiveNodes >= 2 || screen.nodes.any { it.className.contains("Canvas") || it.className.contains("SurfaceView") }
    }

    private fun isSimpleTextNavigation(screen: AniobScreenState): Boolean {
        if (screen.nodes.isEmpty()) return false
        val textCount = screen.nodes.count { it.text.isNotBlank() }
        val totalCount = screen.nodes.size
        return (textCount.toFloat() / totalCount) >= 0.7f && totalCount <= 20
    }
}
