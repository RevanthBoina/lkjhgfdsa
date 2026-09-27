package com.aniob.core.speed

/**
 * Plan-5.8 — TOOL BUDGET PER TURN.
 *
 * Caps total tool calls per turn, warns the agent at the last one, and requires that a
 * turn-ending action (or at least a memory write) still fits before the budget runs out.
 * Unbounded tool loops are one of the five documented production failure modes; an agent that
 * spends its whole turn reading and then gets cut off makes no progress and leaves no trace.
 *
 * It also enforces the call-list rule, which is a correctness property rather than a budget one:
 * a pre-decision tool and a turn-ending action must never be submitted together. If they are, the
 * action was decided BEFORE its own evidence arrived, and the evidence is decoration.
 */
class TurnToolBudget(
    private val maxCallsPerTurn: Int = 8,
    /** Calls reserved so a turn-ending action or a memory write always fits. */
    private val reserve: Int = 1
) {
    enum class Kind {
        /** Gathers evidence used to decide. Must be submitted alone. */
        PRE_DECISION,

        /** Writes a note/memory. Always permitted inside the reserve. */
        MEMORY_WRITE,

        /** Ends the turn: an action dispatched to the device. */
        TURN_ENDING
    }

    enum class Decision { ALLOW, ALLOW_LAST_CALL, REJECT }

    data class Verdict(
        val decision: Decision,
        val used: Int,
        val remaining: Int,
        val message: String?
    ) {
        val allowed: Boolean get() = decision != Decision.REJECT
    }

    private var used = 0
    private var turnEnded = false

    val callsUsed: Int get() = used

    fun startTurn() {
        used = 0
        turnEnded = false
    }

    fun onCall(kind: Kind): Verdict {
        if (turnEnded) {
            return Verdict(Decision.REJECT, used, 0, "the turn already ended; start a new turn")
        }
        val softLimit = maxCallsPerTurn - reserve
        val isReserved = kind == Kind.TURN_ENDING || kind == Kind.MEMORY_WRITE

        if (used >= maxCallsPerTurn) {
            return Verdict(Decision.REJECT, used, 0, "tool budget exhausted ($maxCallsPerTurn calls)")
        }
        if (!isReserved && used >= softLimit) {
            return Verdict(
                Decision.REJECT, used, maxCallsPerTurn - used,
                "only $reserve call(s) left, reserved for a turn-ending action or a memory write. " +
                    "Act now or record what you learned."
            )
        }

        used++
        if (kind == Kind.TURN_ENDING) turnEnded = true

        val remaining = maxCallsPerTurn - used
        return when {
            remaining == 0 -> Verdict(Decision.ALLOW_LAST_CALL, used, 0, "that was your last call this turn")
            !isReserved && used == softLimit -> Verdict(
                Decision.ALLOW_LAST_CALL, used, remaining,
                "this is your last evidence-gathering call this turn; the next one must act or record"
            )
            else -> Verdict(Decision.ALLOW, used, remaining, null)
        }
    }

    companion object {
        /**
         * Pre-decision tools may NEVER share a call list with a turn-ending action.
         * Returns null when the list is legal, otherwise the rejection message.
         */
        fun validateCallList(kinds: List<Kind>): String? {
            val hasPreDecision = kinds.any { it == Kind.PRE_DECISION }
            val hasTurnEnding = kinds.any { it == Kind.TURN_ENDING }
            if (hasPreDecision && hasTurnEnding) {
                return "A pre-decision tool was submitted in the same call list as a turn-ending " +
                    "action. The action was therefore chosen before its own evidence existed. " +
                    "Submit the evidence call alone, read the result, then act."
            }
            if (kinds.count { it == Kind.TURN_ENDING } > 1) {
                return "More than one turn-ending action in a single call list. Use an action " +
                    "burst if these targets are transient, otherwise submit them one per turn."
            }
            return null
        }
    }
}
