package com.aniob.core.recovery

import com.aniob.core.gates.GateBFailureCategory

/**
 * Plan-3.1: Execution Incident as a first-class record.
 * Distinguishes physical safety net blocks from active execution failures.
 */
enum class IncidentKind {
    SAFETY_NET, // Refused to execute (e.g. target disappeared or occupied)
    EXEC_ERROR  // Dispatched to system, then failed
}

data class ExecutionIncident(
    val kind: IncidentKind,
    val category: GateBFailureCategory,
    val reason: String,
    val sanitizedAction: String,
    val actionDescription: String,
    val stepNumber: Int,
    val consecutiveFailures: Int = 1,
    val burstSize: Int = 1,
    val evidence: Map<String, String> = emptyMap(),
    val openedAt: Long = System.currentTimeMillis()
) {
    /**
     * User-facing description cleanly separating refusal from execution breakage.
     */
    fun userFacingExplanation(): String {
        return when (kind) {
            IncidentKind.SAFETY_NET -> "I didn't do it: '$actionDescription' was blocked before execution ($reason)."
            IncidentKind.EXEC_ERROR -> "I tried and it broke: failed while executing '$actionDescription' ($reason)."
        }
    }

    /**
     * Renders incident details for model observation, stripping bulky internal keys.
     */
    fun renderForObservation(): String {
        val sanitizedEvidence = evidence.filterKeys { key ->
            val lower = key.lowercase()
            !lower.contains("image") && !lower.contains("base64") && !lower.contains("raw_tree")
        }
        val evidenceStr = if (sanitizedEvidence.isNotEmpty()) {
            "\nEvidence: " + sanitizedEvidence.entries.joinToString(", ") { "${it.key}=${it.value}" }
        } else {
            ""
        }
        return """
[INCIDENT ${kind.name}]
Category: ${category.name}
Target: $actionDescription
Action: $sanitizedAction
Reason: $reason
Step: $stepNumber (Consecutive failures: $consecutiveFailures, Burst size: $burstSize)$evidenceStr
""".trimIndent()
    }
}
