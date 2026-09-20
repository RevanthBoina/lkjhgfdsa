package com.aniob.core.providers

import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState

/**
 * Single entry point for turning a local SLM completion into an executable [AniobAction].
 *
 * Guarantees the two invariants that were previously violated:
 * 1. A missing native engine/model yields an explicit [AniobAction.Fail] carrying a
 *    user-actionable reason — never a fabricated tap.
 * 2. A model-proposed node id that does not exist on the captured screen is rejected before
 *    it can be dispatched.
 */
object AniobLocalActionResolver {

    fun resolve(
        generation: AniobGenerationResult,
        screenState: AniobScreenState?,
        unavailableReason: String = DEFAULT_UNAVAILABLE_REASON,
        repairCall: (String) -> String? = { null }
    ): AniobAction = when (generation) {
        AniobGenerationResult.ModelUnavailable -> AniobAction.Fail(reason = unavailableReason)
        is AniobGenerationResult.GenerationFailed ->
            AniobAction.Fail(reason = "On-device model failed: ${generation.reason}")
        is AniobGenerationResult.Unparseable ->
            AniobAction.Fail(reason = "On-device model returned no actionable tool call")
        is AniobGenerationResult.Ready ->
            // Everything the model emits goes through the single parse-or-repair boundary, so
            // the local path cannot drift from the cloud path's action vocabulary.
            AniobStructuredOutput.parseOrRepair(
                raw = generation.fullText,
                screenState = screenState,
                decode = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.EXECUTOR),
                repairCall = repairCall
            ).action
    }

    /**
     * Structured variant used where the caller needs to know whether a fallback was used, so
     * provider metrics can name the real producer instead of claiming LOCAL_SLM (finding #6).
     */
    fun resolveStructured(
        generation: AniobGenerationResult,
        screenState: AniobScreenState?,
        unavailableReason: String = DEFAULT_UNAVAILABLE_REASON,
        repairCall: (String) -> String? = { null }
    ): AniobStructuredOutput.StructuredParse = when (generation) {
        AniobGenerationResult.ModelUnavailable -> AniobStructuredOutput.StructuredParse(
            AniobAction.Fail(reason = unavailableReason), repaired = false, usedFallback = true,
            reason = "engine unavailable"
        )
        is AniobGenerationResult.GenerationFailed -> AniobStructuredOutput.StructuredParse(
            AniobAction.Fail(reason = "On-device model failed: ${generation.reason}"),
            repaired = false, usedFallback = true, reason = generation.reason
        )
        is AniobGenerationResult.Unparseable -> AniobStructuredOutput.StructuredParse(
            AniobAction.Fail(reason = "On-device model returned no actionable tool call"),
            repaired = false, usedFallback = true, reason = "unparseable"
        )
        is AniobGenerationResult.Ready -> AniobStructuredOutput.parseOrRepair(
            raw = generation.fullText,
            screenState = screenState,
            decode = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.EXECUTOR),
            repairCall = repairCall
        )
    }

    const val DEFAULT_UNAVAILABLE_REASON =
        "No on-device model engine available \u2014 install a model in Models screen or switch to Auto mode"
}