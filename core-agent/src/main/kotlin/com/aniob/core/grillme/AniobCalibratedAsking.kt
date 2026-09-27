package com.aniob.core.grillme

import com.aniob.core.safety.AniobVaultLedger
import com.aniob.core.safety.VaultEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plan-7.7 — CALIBRATED ASKING.
 *
 * This replaces the hardcoded three-domain keyword ladder in [AniobGrillMeEngine] (cab / message /
 * alarm), which asks four questions about a taxi and zero about anything else in the world.
 *
 * ONE RULE: **ask only if the answer changes what Aniob will do AND Aniob cannot get it another
 * way.** In order — extract from the prompt; look up the vault; read from the device; if a
 * sensible default exists ACT on it and offer a one-tap correction afterwards; only what survives
 * gets asked.
 *
 * A corollary that removes most questions on its own: *any question that already carries a
 * default is, by this rule, not a question.* The old engine asks "Which ride tier?" while holding
 * `defaultOption = "Cheapest"` — it already knew, and it interrupted anyway.
 *
 * ## Why this is the thing worth being best at
 * On the MobileWorld agent-user interaction split the best frontier model scores 0.40 and several
 * score 0.00. It is the one axis where the field is empty, the mechanism already exists in this
 * codebase, and being best is simultaneously a research result and the thing that makes the
 * product feel like a competent assistant instead of a gambler.
 */
class AniobCalibratedAsking(
    private val vault: AniobVaultLedger,
    private val device: DeviceFactReader = DeviceFactReader.None,
    private val metrics: Metrics = Metrics()
) {

    /** Facts Aniob can read instead of asking. Implementations live in `:app`. */
    enum class DeviceFact { CONTACTS, CALENDAR, RECENT_APPS, HOME_ADDRESS, DEFAULT_MESSAGING_APP, LOCALE }

    fun interface DeviceFactReader {
        fun read(fact: DeviceFact, hint: String?): String?

        companion object {
            val None = DeviceFactReader { _, _ -> null }
        }
    }

    /**
     * @param changesBehaviour false when the answer would not alter a single action Aniob takes.
     *                         Such a slot is never asked, no matter how unknown it is.
     * @param default          a sensible default. Its presence makes this not a question.
     * @param extractor        pulls the value straight out of the user's own words.
     */
    data class Slot(
        val id: String,
        val questionAsAsked: String,
        val options: List<String> = emptyList(),
        val changesBehaviour: Boolean = true,
        val default: String? = null,
        val deviceFact: DeviceFact? = null,
        val deviceHint: String? = null,
        val extractor: ((String) -> String?)? = null,
        /** True when getting this wrong is expensive/irreversible — then a default is NOT enough. */
        val consequential: Boolean = false
    )

    sealed interface Resolution {
        val slotId: String

        data class NotNeeded(override val slotId: String, val why: String) : Resolution
        data class FromPrompt(override val slotId: String, val value: String) : Resolution
        data class FromVault(override val slotId: String, val value: String) : Resolution
        data class FromDevice(override val slotId: String, val value: String, val fact: DeviceFact) : Resolution
        /** Acted on a default; the user gets a one-tap correction afterwards. */
        data class ActedOnDefault(
            override val slotId: String,
            val value: String,
            val correctionOffer: String
        ) : Resolution
        data class MustAsk(override val slotId: String, val slot: Slot) : Resolution
    }

    data class AskingPlan(
        /** At most ONE question before starting. */
        val upfront: Slot?,
        /** Everything else is asked at the moment it is needed, in context. */
        val justInTime: List<Slot>,
        val resolved: Map<String, String>,
        val corrections: List<String>,
        val trace: List<Resolution>
    ) {
        val questionsBeforeStarting: Int get() = if (upfront == null) 0 else 1
    }

    /** Plan-7.7: over-asking and under-asking are BOTH failures, so both are counted. */
    class Metrics {
        var asked: Int = 0; private set
        var answeredWithDefault: Int = 0; private set
        var answeredFromVault: Int = 0; private set
        var answeredFromDevice: Int = 0; private set
        var answeredFromPrompt: Int = 0; private set
        var skippedNoBehaviourChange: Int = 0; private set
        var tasksFailedForLackOfQuestion: Int = 0; private set

        fun record(r: Resolution) = when (r) {
            is Resolution.MustAsk -> asked++
            is Resolution.ActedOnDefault -> answeredWithDefault++
            is Resolution.FromVault -> answeredFromVault++
            is Resolution.FromDevice -> answeredFromDevice++
            is Resolution.FromPrompt -> answeredFromPrompt++
            is Resolution.NotNeeded -> skippedNoBehaviourChange++
        }

        /** Called when a task failed because Aniob guessed instead of asking. */
        fun recordUnderAsk() { tasksFailedForLackOfQuestion++ }

        fun snapshot(): Map<String, Int> = mapOf(
            "asked" to asked,
            "answered_with_default" to answeredWithDefault,
            "answered_from_vault" to answeredFromVault,
            "answered_from_device" to answeredFromDevice,
            "answered_from_prompt" to answeredFromPrompt,
            "skipped_no_behaviour_change" to skippedNoBehaviourChange,
            "failed_for_lack_of_question" to tasksFailedForLackOfQuestion
        )
    }

    val metricsSnapshot: Map<String, Int> get() = metrics.snapshot()

    /** The ladder, in order. The first rung that answers wins. */
    fun resolve(slot: Slot, prompt: String): Resolution {
        // 0. Would the answer change anything? If not, it is not a question.
        if (!slot.changesBehaviour) {
            return Resolution.NotNeeded(slot.id, "the answer would not change any action")
        }

        // 1. The user may already have said it.
        slot.extractor?.invoke(prompt)?.takeIf { it.isNotBlank() }?.let {
            return Resolution.FromPrompt(slot.id, it.trim())
        }

        // 2. They may have told us before.
        vault.entries().firstOrNull { it.key == slot.id }?.let {
            return Resolution.FromVault(slot.id, it.answer)
        }

        // 3. The phone may already know.
        slot.deviceFact?.let { fact ->
            device.read(fact, slot.deviceHint)?.takeIf { it.isNotBlank() }?.let {
                return Resolution.FromDevice(slot.id, it.trim(), fact)
            }
        }

        // 4. A default means act now and offer a correction — UNLESS getting it wrong is
        //    expensive. Defaulting a ride tier is fine; defaulting a recipient is not.
        slot.default?.takeIf { !slot.consequential }?.let { d ->
            return Resolution.ActedOnDefault(
                slot.id, d,
                "I went with $d for ${slot.questionAsAsked.trimEnd('?').lowercase()}. Change it?"
            )
        }

        // 5. Only what survives gets asked.
        return Resolution.MustAsk(slot.id, slot)
    }

    /**
     * Builds the plan for a whole task.
     *
     * At most ONE question before starting; anything else is deferred to the moment it is needed,
     * in context. A five-question interrogation before the first tap is how an assistant announces
     * that it does not understand the request.
     */
    fun plan(slots: List<Slot>, prompt: String): AskingPlan {
        val trace = slots.map { resolve(it, prompt) }
        trace.forEach { metrics.record(it) }

        val resolved = mutableMapOf<String, String>()
        val corrections = mutableListOf<String>()
        val mustAsk = mutableListOf<Slot>()

        trace.forEach { r ->
            when (r) {
                is Resolution.FromPrompt -> resolved[r.slotId] = r.value
                is Resolution.FromVault -> resolved[r.slotId] = r.value
                is Resolution.FromDevice -> resolved[r.slotId] = r.value
                is Resolution.ActedOnDefault -> {
                    resolved[r.slotId] = r.value
                    corrections += r.correctionOffer
                }
                is Resolution.MustAsk -> mustAsk += r.slot
                is Resolution.NotNeeded -> Unit
            }
        }

        // The one question worth interrupting for is the most consequential one.
        val upfront = mustAsk.firstOrNull { it.consequential } ?: mustAsk.firstOrNull()
        return AskingPlan(
            upfront = upfront,
            justInTime = mustAsk.filter { it !== upfront },
            resolved = resolved,
            corrections = corrections,
            trace = trace
        )
    }

    /** Remembers an answer with the question in the words it was asked (Plan-7.6). */
    fun remember(slot: Slot, answer: String, source: VaultEntry.Source = VaultEntry.Source.ASKED) {
        vault.remember(
            VaultEntry(
                key = slot.id,
                questionAsAsked = slot.questionAsAsked,
                answer = answer,
                recordedAt = System.currentTimeMillis(),
                source = source
            )
        )
    }

    /**
     * Suspend/resume bridge. Parks the loop until the UI answers, the task is cancelled, or a
     * timeout elapses — the three outcomes, all of them explicit. A question with no timeout is a
     * hang, and a question that silently proceeds on timeout is a guess.
     */
    class ClarificationBridge {
        sealed interface Answer {
            data class Given(val value: String) : Answer
            object Cancelled : Answer
            object TimedOut : Answer
        }

        private var pending: CompletableDeferred<String>? = null
        private var pendingSlot: Slot? = null

        val awaitingSlot: Slot? get() = pendingSlot

        /** Called by the loop. Suspends. */
        suspend fun ask(slot: Slot, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Answer {
            val deferred = CompletableDeferred<String>()
            pending = deferred
            pendingSlot = slot
            return try {
                val value = withTimeoutOrNull(timeoutMs) { deferred.await() }
                when {
                    value == null -> Answer.TimedOut
                    value == CANCEL_SENTINEL -> Answer.Cancelled
                    else -> Answer.Given(value)
                }
            } finally {
                pending = null
                pendingSlot = null
            }
        }

        /** Called by the UI. */
        fun answer(value: String) {
            pending?.complete(value)
        }

        fun cancel() {
            pending?.complete(CANCEL_SENTINEL)
        }

        private companion object {
            const val CANCEL_SENTINEL = "\u0000ANIOB_CANCELLED"
            const val DEFAULT_TIMEOUT_MS = 120_000L
        }
    }
}
