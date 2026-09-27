package com.aniob.core.termination

/**
 * Plan-7.1 — ONE TERMINAL PATH.
 *
 * `isRunning = false` is assigned in 17 places in AniobViewModel. Seventeen exits means nothing
 * can guarantee what state the phone is left in: whichever branch happens to win the race decides
 * whether a half-typed message is still sitting in someone's compose box.
 *
 * This is the ONLY code allowed to end a run. Everything else REQUESTS termination.
 */
class TerminationStage {

    enum class ExitRoute {
        GOAL_MET,
        USER_STOPPED,
        BUDGET_EXHAUSTED,
        BLOCKED_BY_SAFETY,
        DEVICE_DEFECT,
        UNRECOVERABLE_ERROR,
        QUESTION_UNANSWERED
    }

    /** Anyone may request; nobody else may act on it. */
    data class Request(
        val route: ExitRoute,
        val requestedBy: String,
        val reason: String,
        val atStep: Int
    )

    /**
     * What the agent left behind on the device. The safe-stop contract is defined over this.
     */
    data class DeviceResidue(
        /** Text the agent typed but never submitted, and the field it sits in. */
        val partiallyTypedField: String? = null,
        val partiallyTypedText: String? = null,
        /** Dialogs/sheets the agent itself opened and did not close. */
        val agentOpenedDialogs: List<String> = emptyList(),
        /** The app the user was in before the agent navigated away. */
        val navigatedAwayFrom: String? = null
    ) {
        val isClean: Boolean
            get() = partiallyTypedField == null && agentOpenedDialogs.isEmpty() && navigatedAwayFrom == null
    }

    sealed interface Cleanup {
        data class ClearField(val field: String) : Cleanup
        data class DiscloseField(val field: String, val text: String) : Cleanup
        data class DismissDialog(val name: String) : Cleanup
        data class OfferReturn(val toPackage: String) : Cleanup
    }

    data class RunOutcome(
        val route: ExitRoute,
        /** Machine-readable; what dashboards and tests read. */
        val machineOutcome: String,
        /** Exactly one sentence saying what was and was not done. */
        val userFacingSummary: String,
        val cleanup: List<Cleanup>,
        val didDo: List<String>,
        val didNotDo: List<String>
    )

    private val requests = mutableListOf<Request>()
    private var terminated = false

    val isTerminated: Boolean get() = terminated
    val pending: List<Request> get() = requests.toList()

    /** The only thing every other stage is allowed to call. */
    @Synchronized
    fun requestTermination(request: Request) {
        if (terminated) return
        requests += request
    }

    /**
     * Ends the run. Idempotent — a second call returns the same outcome rather than running the
     * safe-stop contract twice, because dismissing a dialog the user has since reopened is worse
     * than doing nothing.
     */
    @Synchronized
    fun terminate(
        goal: String,
        residue: DeviceResidue,
        didDo: List<String>,
        didNotDo: List<String>,
        /** Set when the user pressed stop, which outranks every other request. */
        userStopped: Boolean = false
    ): RunOutcome {
        check(!terminated) { "terminate() called twice; the run already ended" }
        terminated = true

        val route = when {
            userStopped -> ExitRoute.USER_STOPPED
            requests.isEmpty() -> ExitRoute.GOAL_MET
            else -> requests.minByOrNull { PRIORITY.indexOf(it.route) }!!.route
        }

        val cleanup = buildCleanup(residue, route)

        return RunOutcome(
            route = route,
            machineOutcome = route.name,
            userFacingSummary = summarize(goal, route, didDo, didNotDo, residue),
            cleanup = cleanup,
            didDo = didDo,
            didNotDo = didNotDo
        )
    }

    /**
     * The safe-stop contract: clear or disclose a partially-typed field, dismiss agent-opened
     * dialogs, offer to return if the agent navigated away.
     *
     * Clearing vs disclosing is a real decision, not a preference. On a user stop we CLEAR, because
     * the user asked us to stop and leaving half a message in their compose box is the single most
     * alarming thing an automation can do. When the goal was met we DISCLOSE instead, because the
     * text may be the deliverable and deleting it would destroy work.
     */
    private fun buildCleanup(residue: DeviceResidue, route: ExitRoute): List<Cleanup> = buildList {
        residue.partiallyTypedField?.let { field ->
            if (route == ExitRoute.GOAL_MET) {
                add(Cleanup.DiscloseField(field, residue.partiallyTypedText.orEmpty()))
            } else {
                add(Cleanup.ClearField(field))
            }
        }
        residue.agentOpenedDialogs.forEach { add(Cleanup.DismissDialog(it)) }
        residue.navigatedAwayFrom?.let { add(Cleanup.OfferReturn(it)) }
    }

    private fun summarize(
        goal: String,
        route: ExitRoute,
        didDo: List<String>,
        didNotDo: List<String>,
        residue: DeviceResidue
    ): String {
        val head = when (route) {
            ExitRoute.GOAL_MET -> "I finished: $goal."
            ExitRoute.USER_STOPPED -> "I stopped when you asked."
            ExitRoute.BUDGET_EXHAUSTED -> "I ran out of steps before finishing."
            ExitRoute.BLOCKED_BY_SAFETY -> "I stopped because the next step needed your go-ahead."
            ExitRoute.DEVICE_DEFECT -> "I stopped: the app did not do what it should have."
            ExitRoute.UNRECOVERABLE_ERROR -> "I stopped: I could not recover from an error."
            ExitRoute.QUESTION_UNANSWERED -> "I stopped: I needed an answer and did not get one."
        }
        val done = if (didDo.isEmpty()) "nothing was changed" else didDo.joinToString(", ")
        val notDone = if (didNotDo.isEmpty()) "" else "; not done: " + didNotDo.joinToString(", ")
        val left = when {
            residue.partiallyTypedField != null && route != ExitRoute.GOAL_MET ->
                " I cleared the text I had typed into ${residue.partiallyTypedField}."
            residue.partiallyTypedField != null ->
                " I left text in ${residue.partiallyTypedField} for you to review."
            else -> ""
        }
        return "$head Done: $done$notDone.$left"
    }

    private companion object {
        /** Earlier wins. A safety block outranks a budget exhaustion outranks a goal claim. */
        val PRIORITY = listOf(
            ExitRoute.USER_STOPPED,
            ExitRoute.BLOCKED_BY_SAFETY,
            ExitRoute.UNRECOVERABLE_ERROR,
            ExitRoute.DEVICE_DEFECT,
            ExitRoute.QUESTION_UNANSWERED,
            ExitRoute.BUDGET_EXHAUSTED,
            ExitRoute.GOAL_MET
        )
    }
}
