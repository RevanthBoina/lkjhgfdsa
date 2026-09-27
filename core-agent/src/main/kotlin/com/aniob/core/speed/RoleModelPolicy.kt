package com.aniob.core.speed

import com.aniob.core.config.AniobDecodeConfig

/**
 * Plan-5.6 — RIGHT-SIZE EVERY CALL.
 *
 * "Using one model for everything is the most common and most expensive mistake."
 *
 * Two separate costs are paid for it. The obvious one is money and latency: a consistency check
 * that answers yes/no does not need the planner's model. The non-obvious one is accuracy — a
 * large reasoning model asked to do bulk extraction will reason about the extraction, and a small
 * model asked to plan will produce a plan-shaped object with no plan in it.
 *
 * Every role gets a tier, a reasoning depth and a FALLBACK. A role without a fallback is a single
 * point of failure for the whole run.
 */
object RoleModelPolicy {

    enum class Tier { STRONGEST, MID, SMALLEST }

    enum class Reasoning { HIGH, MEDIUM, LOW, NONE }

    data class Assignment(
        val role: AniobDecodeConfig.Role,
        val tier: Tier,
        val reasoning: Reasoning,
        /** Include the model's thinking in the response where the provider supports it. */
        val includeThoughts: Boolean,
        val rationale: String
    )

    /** Resolved against what is actually installed/reachable right now. */
    data class Resolution(
        val role: AniobDecodeConfig.Role,
        val modelId: String,
        val usedFallback: Boolean,
        /** Plan-7.9: when this is non-null it must be announced, not swallowed. */
        val degradationNotice: String?
    )

    val TABLE: Map<AniobDecodeConfig.Role, Assignment> = listOf(
        Assignment(
            AniobDecodeConfig.Role.PLANNER, Tier.STRONGEST, Reasoning.HIGH, includeThoughts = false,
            rationale = "milestones and recovery strategy; the only role where being wrong is expensive for the whole run"
        ),
        Assignment(
            AniobDecodeConfig.Role.EXECUTOR, Tier.MID, Reasoning.MEDIUM, includeThoughts = true,
            rationale = "one grounded action per turn; thoughts are included because Gate A checks them against the action"
        ),
        Assignment(
            AniobDecodeConfig.Role.VERIFIER, Tier.MID, Reasoning.MEDIUM, includeThoughts = true,
            rationale = "must weigh evidence and be willing to answer INCONCLUSIVE"
        ),
        Assignment(
            AniobDecodeConfig.Role.REFLECTOR, Tier.MID, Reasoning.MEDIUM, includeThoughts = true,
            rationale = "diagnoses a failure path; recovery quality is what separates >90% systems from <60% ones"
        ),
        Assignment(
            AniobDecodeConfig.Role.CONSISTENCY, Tier.SMALLEST, Reasoning.NONE, includeThoughts = false,
            rationale = "Gate A binary classifier over thought, action and action-visualization; it answers yes or no"
        ),
        Assignment(
            AniobDecodeConfig.Role.SUMMARIZER, Tier.SMALLEST, Reasoning.NONE, includeThoughts = false,
            rationale = "step summaries run off the critical path and never need to be smart"
        ),
        Assignment(
            AniobDecodeConfig.Role.EXTRACTOR, Tier.SMALLEST, Reasoning.NONE, includeThoughts = false,
            rationale = "bulk extraction, e.g. finding a package name in a list of hundreds"
        ),
        Assignment(
            AniobDecodeConfig.Role.GRILL, Tier.MID, Reasoning.MEDIUM, includeThoughts = false,
            rationale = "deciding whether a question is worth asking is a judgement call, not a lookup"
        ),
        Assignment(
            AniobDecodeConfig.Role.EXPLORER, Tier.SMALLEST, Reasoning.LOW, includeThoughts = false,
            rationale = "enumerating what is on screen; breadth matters more than depth"
        )
    ).associateBy { it.role }

    fun assignmentFor(role: AniobDecodeConfig.Role): Assignment =
        TABLE[role] ?: TABLE.getValue(AniobDecodeConfig.Role.EXECUTOR)

    /**
     * Picks a concrete model for [role] from the tier map, falling back down the tiers when the
     * preferred one is unavailable. Never returns silently degraded: [Resolution.degradationNotice]
     * is populated whenever the answer is not the first choice.
     */
    fun resolve(
        role: AniobDecodeConfig.Role,
        tierModels: Map<Tier, String>,
        available: Set<String>
    ): Resolution? {
        val assignment = assignmentFor(role)
        val order = when (assignment.tier) {
            Tier.STRONGEST -> listOf(Tier.STRONGEST, Tier.MID, Tier.SMALLEST)
            Tier.MID -> listOf(Tier.MID, Tier.STRONGEST, Tier.SMALLEST)
            Tier.SMALLEST -> listOf(Tier.SMALLEST, Tier.MID, Tier.STRONGEST)
        }
        order.forEachIndexed { i, tier ->
            val model = tierModels[tier] ?: return@forEachIndexed
            if (model in available) {
                return Resolution(
                    role = role,
                    modelId = model,
                    usedFallback = i > 0,
                    degradationNotice = if (i == 0) null else
                        "${role.name.lowercase()} is running on the ${tier.name.lowercase()} model " +
                            "because the ${assignment.tier.name.lowercase()} one is not available"
                )
            }
        }
        return null
    }
}
