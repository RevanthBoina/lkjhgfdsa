package com.aniob.core.domain

/**
 * Deterministically checkable success conditions for a task.
 *
 * Extracted from the raw prompt (and grill-me answers) before step 0 so that a `Finish`
 * proposal can be *disproved* without an LLM. Any criterion left empty is simply not checked;
 * [isFullyCheckable] reports whether a deterministic verdict is possible at all.
 */
enum class CriterionState {
    PASSED,
    FAILED,
    INCONCLUSIVE,
    UNCHECKED
}

data class CriterionEvaluation(
    val description: String,
    val state: CriterionState,
    val detail: String = ""
)

/**
 * Deterministically checkable success conditions for a task.
 *
 * Extracted from the raw prompt (and grill-me answers) before step 0 so that a `Finish`
 * proposal can be *disproved* without an LLM. Any criterion left empty is simply not checked;
 * [isFullyCheckable] reports whether a deterministic verdict is possible at all.
 */
data class SuccessCriteria(
    val mustContainText: List<String> = emptyList(),
    val mustShowPackage: List<String> = emptyList(),
    val mustShowActivity: List<String> = emptyList(),
    val minSteps: Int = 0
) {
    val isEmpty: Boolean
        get() = mustContainText.isEmpty() && mustShowPackage.isEmpty() &&
            mustShowActivity.isEmpty() && minSteps == 0

    val hasObservableCheck: Boolean
        get() = mustContainText.isNotEmpty() || mustShowPackage.isNotEmpty() || mustShowActivity.isNotEmpty()

    /** A verdict is deterministic only when an observable check is present; minSteps alone is never sufficient evidence. */
    val isFullyCheckable: Boolean get() = hasObservableCheck

    /**
     * Evaluates all individual criteria against the observed screen and step count.
     */
    fun evaluateCriteria(screen: AniobScreenState, steps: Int): List<CriterionEvaluation> {
        val evaluations = mutableListOf<CriterionEvaluation>()

        mustContainText.forEach { needle ->
            val found = screen.nodes.any {
                it.text.contains(needle, ignoreCase = true) ||
                    it.contentDescription.contains(needle, ignoreCase = true)
            }
            if (found) {
                evaluations += CriterionEvaluation("Text '$needle'", CriterionState.PASSED, "Found on screen")
            } else {
                evaluations += CriterionEvaluation("Text '$needle'", CriterionState.FAILED, "Expected text '$needle' not present")
            }
        }

        mustShowPackage.forEach { pkg ->
            if (screen.packageName.equals(pkg, ignoreCase = true)) {
                evaluations += CriterionEvaluation("Package '$pkg'", CriterionState.PASSED, "Foreground package matches")
            } else {
                evaluations += CriterionEvaluation("Package '$pkg'", CriterionState.FAILED, "Expected package '$pkg' not foreground (was '${screen.packageName}')")
            }
        }

        mustShowActivity.forEach { activity ->
            if (screen.activityName.isBlank()) {
                evaluations += CriterionEvaluation("Activity '$activity'", CriterionState.INCONCLUSIVE, "Activity identity unknown")
            } else {
                val short = activity.substringAfterLast('.')
                if (screen.activityName.contains(short, ignoreCase = true)) {
                    evaluations += CriterionEvaluation("Activity '$activity'", CriterionState.PASSED, "Activity matches")
                } else {
                    evaluations += CriterionEvaluation("Activity '$activity'", CriterionState.FAILED, "Expected activity '$activity' not shown (was '${screen.activityName}')")
                }
            }
        }

        if (minSteps > 0) {
            if (steps >= minSteps) {
                evaluations += CriterionEvaluation("Min steps ($minSteps)", CriterionState.PASSED, "Took $steps steps")
            } else {
                evaluations += CriterionEvaluation("Min steps ($minSteps)", CriterionState.FAILED, "Expected at least $minSteps steps, took $steps")
            }
        }

        return evaluations
    }

    /**
     * Checks [screen] (the final observed state) against the criteria.
     * Returns the list of unmet criteria; empty means the criteria are satisfied.
     */
    fun unmet(screen: AniobScreenState, steps: Int): List<String> {
        val evaluations = evaluateCriteria(screen, steps)
        return evaluations
            .filter { it.state == CriterionState.FAILED || it.state == CriterionState.INCONCLUSIVE }
            .map { it.detail.ifBlank { it.description } }
    }

    fun describeList(): List<String> {
        val list = mutableListOf<String>()
        mustContainText.forEach { list.add("Must contain text: $it") }
        mustShowPackage.forEach { list.add("Must show package: $it") }
        mustShowActivity.forEach { list.add("Must show activity: $it") }
        if (minSteps > 0) list.add("At least $minSteps steps")
        return list
    }
}