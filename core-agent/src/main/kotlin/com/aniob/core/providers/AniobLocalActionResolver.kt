package com.aniob.core.providers

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
        unavailableReason: String = DEFAULT_UNAVAILABLE_REASON
    ): AniobAction = when (generation) {
        AniobGenerationResult.ModelUnavailable -> AniobAction.Fail(reason = unavailableReason)
        is AniobGenerationResult.GenerationFailed ->
            AniobAction.Fail(reason = "On-device model failed: ${generation.reason}")
        is AniobGenerationResult.Unparseable ->
            AniobAction.Fail(reason = "On-device model returned no actionable tool call")
        is AniobGenerationResult.Ready -> {
            val call = AniobToolCallParser.parse(generation.fullText)
            if (call == null) {
                AniobAction.Fail(reason = "On-device model returned unparseable step")
            } else {
                val validIds = screenState?.nodes?.map { it.id }?.toSet().orEmpty()
                AniobToolCallParser.toAction(call, validIds)
            }
        }
    }

    const val DEFAULT_UNAVAILABLE_REASON =
        "No on-device model engine available \u2014 install a model in Models screen or switch to Auto mode"
}