package com.aniob.core.domain

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

    /** A verdict is deterministic only when every criterion present is machine-checkable. */
    val isFullyCheckable: Boolean get() = hasObservableCheck

    /**
     * Checks [screen] (the final observed state) against the criteria.
     * Returns the list of unmet criteria; empty means the criteria are satisfied.
     */
    fun unmet(screen: AniobScreenState, steps: Int): List<String> {
        val failures = mutableListOf<String>()

        mustContainText.forEach { needle ->
            val found = screen.nodes.any {
                it.text.contains(needle, ignoreCase = true) ||
                    it.contentDescription.contains(needle, ignoreCase = true)
            }
            if (!found) failures += "expected text '$needle' not present"
        }

        mustShowPackage.forEach { pkg ->
            if (!screen.packageName.equals(pkg, ignoreCase = true) &&
                !screen.packageName.contains(pkg, ignoreCase = true)
            ) {
                failures += "expected package '$pkg' not foreground (was '${screen.packageName}')"
            }
        }

        mustShowActivity.forEach { activity ->
            val short = activity.substringAfterLast('.')
            if (!screen.activityName.contains(short, ignoreCase = true)) {
                failures += "expected activity '$activity' not shown (was '${screen.activityName}')"
            }
        }

        if (steps < minSteps) failures += "expected at least $minSteps steps, took $steps"

        return failures
    }
}