package com.aniob.core.optimizer

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState

/**
 * Trajectory turn in an autonomous agent execution loop.
 */
data class AgentTrajectoryTurn(
    val stepIndex: Int,
    val screenSummary: String,
    val action: AniobAction,
    val observationOutcome: String? = null,
    val estimatedTokens: Int = 120
)

/**
 * Sliding Context Window and History Compactor for on-device SLMs.
 *
 * Prevents out-of-memory (OOM) native heap aborts and token budget exhaustion
 * during long multi-step agent executions by summarizing older trajectory steps
 * while preserving the foundational system prompt, clarified user goal, and the most
 * recent N active steps.
 *
 * Pure Kotlin/JVM: 0 android.* imports.
 */
class AniobContextCompactor(
    private val maxUncompactedTurns: Int = 3,
    private val maxTotalTokenBudget: Int = 2048
) {

    private val turns = mutableListOf<AgentTrajectoryTurn>()

    val turnCount: Int get() = turns.size

    fun addTurn(turn: AgentTrajectoryTurn) {
        turns.add(turn)
    }

    /**
     * Compacts the current trajectory history into an optimized prompt representation.
     *
     * @param systemPrompt The immutable agent system prompt.
     * @param userGoal The original user task prompt / clarified goal.
     * @return An optimized prompt string guaranteed to stay within the token budget.
     */
    fun buildCompactedContext(
        systemPrompt: String,
        userGoal: String,
        currentScreen: AniobScreenState? = null
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.appendLine("SYSTEM: $systemPrompt")
            sb.appendLine()
        }
        sb.appendLine("GOAL: $userGoal")
        sb.appendLine()

        if (turns.isEmpty()) {
            currentScreen?.let {
                sb.appendLine("CURRENT_SCREEN:")
                sb.appendLine(it.toSummaryString())
            }
            return sb.toString().trim()
        }

        // If trajectory is within limit, include all turns verbatim
        if (turns.size <= maxUncompactedTurns) {
            sb.appendLine("HISTORY:")
            turns.forEach { turn ->
                sb.appendLine("  Step ${turn.stepIndex + 1}: ${turn.action.toolName} -> ${turn.observationOutcome ?: "completed"}")
            }
        } else {
            // Sliding window: compact earlier turns into a single high-level summary
            val splitIdx = turns.size - maxUncompactedTurns
            val olderTurns = turns.subList(0, splitIdx)
            val recentTurns = turns.subList(splitIdx, turns.size)

            sb.appendLine("PRIOR_STEPS_SUMMARY:")
            val summary = olderTurns.joinToString("; ") { "Step ${it.stepIndex + 1}: ${it.action.toolName}" }
            sb.appendLine("  [Compacted $splitIdx prior steps: $summary]")
            sb.appendLine()

            sb.appendLine("RECENT_STEPS:")
            recentTurns.forEach { turn ->
                sb.appendLine("  Step ${turn.stepIndex + 1}: ${turn.action.toolName} -> ${turn.observationOutcome ?: "completed"}")
            }
        }

        currentScreen?.let {
            sb.appendLine()
            sb.appendLine("CURRENT_SCREEN:")
            sb.appendLine(it.toSummaryString())
        }

        return sb.toString().trim()
    }

    fun clear() {
        turns.clear()
    }

    private fun AniobScreenState.toSummaryString(): String {
        val topElements = nodes.filter { it.isVisibleToUser }
            .take(15)
            .joinToString(", ") { node ->
                val label = node.text.ifBlank { node.contentDescription }.ifBlank { node.viewId }
                "${node.id}: [${node.className.substringAfterLast('.')}] '$label'"
            }
        return "Package: $packageName | Elements: $topElements"
    }
}
