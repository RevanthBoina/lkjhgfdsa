package com.aniob.app.ui.model

/**
 * Frozen UI contracts (UX-0 §3).
 *
 * UX-1…UX-7 consume these shapes; they must not change once shipped. The human layer reads
 * [StepEvent.narration] / [ConfirmRequest.what] / [EvidenceItem.label]; the expandable
 * "Details" affordance reads [StepEvent.technical]. Both audiences are served by one model.
 */
data class StepEvent(
    val stepIndex: Int,
    val narration: String,
    val status: Status,
    val provider: String,
    val latencyMs: Long,
    val failureReason: String? = null,
    val technical: String? = null
) {
    enum class Status { RUNNING, OK, FAILED, SKIPPED }
}

data class ConfirmRequest(
    val id: String,
    val title: String,
    val what: String,
    val why: String,
    val risk: Risk,
    val details: String,
    val timeoutSec: Int = 60,
    val taskId: String = "",
    val payload: String = "",
    val target: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    enum class Risk { LOW, MEDIUM, HIGH }
}

data class EvidenceItem(
    val label: String,
    val met: Boolean,
    val detail: String = "",
    val state: com.aniob.core.domain.CriterionState = if (met) com.aniob.core.domain.CriterionState.PASSED else com.aniob.core.domain.CriterionState.FAILED
)

enum class Outcome { SUCCESS, FAILED, STOPPED, INTERRUPTED, UNVERIFIED }

enum class NextAction { RUN_AGAIN, VIEW_STEPS, RETRY, EXPLORE_APP, TEACH_ME, COPY_PROMPT, MAKE_SKILL }

data class TaskSummary(
    val prompt: String,
    val outcome: Outcome,
    val steps: Int,
    val durationMs: Long,
    val provider: String,
    val evidence: List<EvidenceItem> = emptyList(),
    val nextActions: List<NextAction> = emptyList(),
    val reason: String? = null
)

/** A confirmation decision the user actually made (UX-4). */
enum class ConfirmDecision { APPROVE_ONCE, APPROVE_FOR_TASK, DENY, TIMEOUT_DENY }

/** How aggressively Aniob asks before acting (UX-4 §2). Safety is never silently off. */
enum class SafetyLevel {
    STANDARD,
    STRICT,
    OFF;

    val displayName: String
        get() = when (this) {
            STANDARD -> "Standard"
            STRICT -> "Strict"
            OFF -> "Off"
        }
}

/** A "Remembered Moment" or skill-replay chip rendered in chat (UX-5 §2). */
data class MomentChip(
    val id: String,
    val label: String,
    val kind: Kind
) {
    enum class Kind { VAULT_HIT, SKILL_REPLAY }
}

/** One recorded approval decision (UX-4 Trust Center). */
data class ConfirmationRecord(
    val what: String,
    val risk: ConfirmRequest.Risk,
    val decision: ConfirmDecision,
    val at: Long
)

/** One hard-blocked action fingerprint (UX-4 Trust Center). */
data class BlockedRecord(
    val fingerprint: String,
    val rule: String,
    val at: Long
)
