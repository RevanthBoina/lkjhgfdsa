package com.aniob.core.recovery

import com.aniob.core.domain.AniobAction

/**
 * Plan-3.2: Manages incident lifecycle across conversation turns.
 * The incident stays open in context until a turn-ending action dispatches without error.
 */
class IncidentContextManager {

    private var activeIncident: ExecutionIncident? = null
    private var justClosedIncidentNotice: String? = null

    val currentIncident: ExecutionIncident?
        get() = activeIncident

    /**
     * Records an execution failure or safety refusal, escalating consecutive failure count.
     */
    fun recordFailure(
        kind: IncidentKind,
        category: com.aniob.core.gates.GateBFailureCategory,
        reason: String,
        sanitizedAction: String,
        actionDescription: String,
        stepNumber: Int,
        evidence: Map<String, String> = emptyMap()
    ): ExecutionIncident {
        justClosedIncidentNotice = null
        val previous = activeIncident
        val consecutive = if (previous != null) previous.consecutiveFailures + 1 else 1
        val burst = if (previous != null) previous.burstSize + 1 else 1

        val incident = ExecutionIncident(
            kind = kind,
            category = category,
            reason = reason,
            sanitizedAction = sanitizedAction,
            actionDescription = actionDescription,
            stepNumber = stepNumber,
            consecutiveFailures = consecutive,
            burstSize = burst,
            evidence = evidence
        )
        activeIncident = incident
        return incident
    }

    /**
     * Closes the open incident when an action successfully dispatches without error.
     */
    fun onActionDispatchedSuccessfully(action: AniobAction): String? {
        val hadIncident = activeIncident
        activeIncident = null
        if (hadIncident != null) {
            justClosedIncidentNotice = """
[CLOSED INCIDENT NOTICE]
The execution channel is active again after resolving incident at step ${hadIncident.stepNumber}.
Target intent was: '${hadIncident.actionDescription}'.
Note: Closing this incident means execution succeeded, NOT that the previous intent was achieved. Settle that intent against your plan before moving forward.
""".trimIndent()
            return justClosedIncidentNotice
        }
        justClosedIncidentNotice = null
        return null
    }

    /**
     * Renders context block to inject at the TOP of the next observation.
     */
    fun renderObservationPrefix(): String? {
        val incident = activeIncident
        if (incident != null) {
            return """
[OPEN INCIDENT - ESCALATION LEVEL ${incident.consecutiveFailures}]
${incident.renderForObservation()}

OPERATIONAL RULES:
- Read this incident first, then re-observe the live screen.
- If the screen has moved past the blocked action (e.g. ad finished, dialog self-closed), just continue with the task.
- A verbatim retry of the blocked action is NOT a diagnosis.
- Exception: If the target is visibly still present on screen, the safety gate may have misfired; one vetted retry is permitted.
""".trimIndent()
        }

        val notice = justClosedIncidentNotice
        if (notice != null) {
            justClosedIncidentNotice = null // one-turn emission
            return notice
        }

        return null
    }

    fun clear() {
        activeIncident = null
        justClosedIncidentNotice = null
    }
}
