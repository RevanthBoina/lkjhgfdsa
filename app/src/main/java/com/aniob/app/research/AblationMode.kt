package com.aniob.app.research

/**
 * One experimental condition of the ablation study.
 *
 * Each mode states which of the agent's speed mechanisms may run for a task. The flags are applied
 * once per task by `AniobViewModel.applyAblationMode`, which flips the real switches (the router's
 * intent-shortcut lookup, FastPath replay, the observe-burst policy and the event-driven capture
 * skip) — nothing here is a placebo label.
 *
 * | mode | intent shortcut | motif bursts | fast delta | remember replay |
 * |---|---|---|---|---|
 * | [BASELINE] | off | off | off | off |
 * | [INTENT_SHORTCUT_ONLY] | on | off | off | off |
 * | [MOTIF_AND_FAST_A11Y] | on | on | on | off |
 * | [FULL_HUMAN_SPEED] | on | on | on | on |
 *
 * Node pruning (`AniobTokenOptimizer`) is NOT an ablation flag: it is the hard 60-node cap every
 * request depends on, so it stays on in all four modes. That is the only mechanism that does not
 * vary across this table.
 *
 * [FULL_HUMAN_SPEED] is the default because it preserves the behaviour the app had before the
 * study existed; the other modes are opt-in degradations for measurement.
 *
 * The compact observe pipeline ([fastObservation]) is part of the motif conditions: it prunes and
 * merges the live tree, gives every surviving element a sequential id and skips the screenshot
 * when the tree is dense enough to plan from.
 */
enum class AblationMode(
    /** Stable identifier used in `research_runs.csv`; never rename a shipped value. */
    val id: String,
    /** Short UI label. */
    val label: String,
    /** One-line explanation shown under the radio button in Settings. */
    val description: String,
    val intentShortcuts: Boolean,
    val motifBursts: Boolean,
    val fastDeltaVerify: Boolean,
    val rememberReplay: Boolean
) {
    BASELINE(
        id = "BASELINE",
        label = "Baseline",
        description = "Full observation every step, one action per model call. No shortcuts, no replay.",
        intentShortcuts = false,
        motifBursts = false,
        fastDeltaVerify = false,
        rememberReplay = false
    ),
    INTENT_SHORTCUT_ONLY(
        id = "INTENT_SHORTCUT_ONLY",
        label = "Shortcuts only",
        description = "Direct intent shortcuts are on; every remaining step uses the standard loop.",
        intentShortcuts = true,
        motifBursts = false,
        fastDeltaVerify = false,
        rememberReplay = false
    ),
    MOTIF_AND_FAST_A11Y(
        id = "MOTIF_AND_FAST_A11Y",
        label = "Motif + fast capture",
        description = "Shortcuts, multi-action bursts and the event-driven capture skip.",
        intentShortcuts = true,
        motifBursts = true,
        fastDeltaVerify = true,
        rememberReplay = false
    ),
    FULL_HUMAN_SPEED(
        id = "FULL_HUMAN_SPEED",
        label = "Full human speed",
        description = "Every mechanism, including verified replay from remembered tasks.",
        intentShortcuts = true,
        motifBursts = true,
        fastDeltaVerify = true,
        rememberReplay = true
    );

    /**
     * True when the Observe phase runs the compact pipeline: prune invisible/decorative nodes,
     * fold child labels into their interactive parent, assign sequential ids, hash the structure
     * and gate the bitmap capture. Both motif conditions enable it; the other two stay raw.
     */
    val fastObservation: Boolean get() = motifBursts

    companion object {
        val DEFAULT: AblationMode = FULL_HUMAN_SPEED

        /** Accepts the enum name or the persisted id, case-insensitively; unknown → [DEFAULT]. */
        fun fromId(id: String?): AblationMode {
            if (id.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(id.trim(), ignoreCase = true) }
                ?: DEFAULT
        }
    }
}
