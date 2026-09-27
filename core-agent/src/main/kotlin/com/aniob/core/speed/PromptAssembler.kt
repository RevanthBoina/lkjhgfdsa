package com.aniob.core.speed

import com.aniob.core.memory.history.NeutralWordingContract

/**
 * Plan-5.1 / 5.2 / 5.3 — the prompt is built so provider-side caching can actually hit.
 *
 * ```
 * [ invariant system prefix ][ committed turns, appended in order ][ fresh tail ]
 * ```
 *
 * Never reorder, never edit an earlier turn. This is free latency, and latency is accuracy:
 * the longer inference takes, the further the UI has moved by the time the tap lands.
 *
 * The assembler is also the enforcement point for two rules that are otherwise unenforceable:
 *
 *  - **5.2 don't narrate success.** A trailing execution-result message is attached only to a
 *    turn whose action did NOT produce the expected change. Successful dispatch is implied by the
 *    next observation; the chatter costs tokens on every single step and buys nothing.
 *  - **5.3 ephemeral messages are deleted, not frozen.** Nudges, reminders, warnings and guidance
 *    wrappers are attached to the tail only. They are never committed, so they can never reach
 *    frozen history or a compressed chunk.
 */
class PromptAssembler(
    private val systemPrefix: String,
    private val estimateTokens: (String) -> Int = { it.length / 4 }
) {

    /** A committed turn. Once committed the bytes are immutable. */
    data class CommittedTurn(
        val stepNumber: Int,
        val body: String,
        /** 5.2: present only when the action did not produce the expected change. */
        val executionResult: String? = null
    ) {
        fun render(): String = buildString {
            appendLine("--- step $stepNumber ---")
            appendLine(body.trimEnd())
            executionResult?.let { appendLine("execution result: ${it.trimEnd()}") }
        }
    }

    /** The volatile part of the prompt. Rebuilt every turn, committed never. */
    data class Tail(
        val observation: String,
        val elementList: String? = null,
        val planRecitation: String? = null,
        /** 5.3: deleted at the edge; they exist only for this one call. */
        val ephemeral: List<String> = emptyList(),
        /** Plan-3: an open incident renders at the TOP of the observation. */
        val incidentPrefix: String? = null
    ) {
        fun render(): String = buildString {
            incidentPrefix?.let { appendLine(it.trimEnd()); appendLine() }
            ephemeral.forEach { appendLine(it.trimEnd()) }
            planRecitation?.let { appendLine(it.trimEnd()) }
            appendLine(observation.trimEnd())
            elementList?.let { appendLine(it.trimEnd()) }
        }
    }

    data class Assembled(
        val text: String,
        /** Bytes that are byte-identical to the previous assembly, from the start. */
        val stablePrefixBytes: Int,
        val totalBytes: Int,
        val prefixTokens: Int,
        val committedTokens: Int,
        val tailTokens: Int,
        /** Plan-8.6 "cache hit ratio" — the share of the prompt a provider cache can reuse. */
        val cacheableRatio: Double,
        /** Non-null when an already-committed turn changed, which silently destroys caching. */
        val stabilityViolation: String?
    )

    private val committed = mutableListOf<CommittedTurn>()
    private val committedBytes = mutableListOf<String>()
    private var lastAssembly: String? = null
    private var lastCommittedEnd: Int = 0

    val committedCount: Int get() = committed.size

    /**
     * Appends a turn. Rejects any attempt to rewrite history, because an in-place edit is exactly
     * the thing that invalidates the provider cache for every byte that follows it.
     */
    @Synchronized
    fun commit(turn: CommittedTurn) {
        require(committed.isEmpty() || turn.stepNumber > committed.last().stepNumber) {
            "history is append-only: cannot commit step ${turn.stepNumber} after ${committed.last().stepNumber}"
        }
        committed += turn
        committedBytes += turn.render()
    }

    /**
     * 5.2 helper — build the committed turn for a step, attaching a trailing execution-result
     * message ONLY on the failure path.
     */
    fun committedTurnFor(stepNumber: Int, body: String, verified: Boolean, failureReason: String?): CommittedTurn =
        CommittedTurn(
            stepNumber = stepNumber,
            body = body,
            executionResult = if (verified) null
            else NeutralWordingContract.neutralize(failureReason ?: "the expected change was not observed")
        )

    @Synchronized
    fun assemble(tail: Tail): Assembled {
        val prefix = systemPrefix.trimEnd() + "\n"
        val history = committedBytes.joinToString("")
        val tailText = tail.render()
        val text = prefix + history + tailText

        val previous = lastAssembly
        val stable = if (previous == null) 0 else commonPrefixLength(previous, text)

        // A stability violation means a region that was ALREADY committed last time changed.
        // The tail always differs, and the region newly committed since the last assembly is
        // expected to differ too — it replaced the previous tail. Only divergence inside the
        // region both assemblies had committed is a real cache break.
        val committedEnd = prefix.length + history.length
        val sharedCommitted = minOf(lastCommittedEnd, committedEnd)
        val violation = if (previous != null && stable < sharedCommitted) {
            "prompt diverged at byte $stable, inside the region committed by both assemblies " +
                "(shared committed bytes: $sharedCommitted); everything after that point is a cache miss"
        } else null

        lastAssembly = text
        lastCommittedEnd = committedEnd

        val prefixTokens = estimateTokens(prefix)
        val committedTokens = estimateTokens(history)
        val tailTokens = estimateTokens(tailText)
        return Assembled(
            text = text,
            stablePrefixBytes = stable,
            totalBytes = text.length,
            prefixTokens = prefixTokens,
            committedTokens = committedTokens,
            tailTokens = tailTokens,
            cacheableRatio = if (text.isEmpty()) 0.0 else committedEnd.toDouble() / text.length.toDouble(),
            stabilityViolation = violation
        )
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    @Synchronized
    fun reset() {
        committed.clear()
        committedBytes.clear()
        lastAssembly = null
        lastCommittedEnd = 0
    }
}
