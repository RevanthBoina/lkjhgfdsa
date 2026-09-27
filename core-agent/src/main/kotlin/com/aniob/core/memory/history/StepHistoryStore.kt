package com.aniob.core.memory.history

import com.aniob.core.execution.StepPipeline

/**
 * Plan-4: the piece that was missing — the thing that actually OWNS history and runs the rest.
 *
 * Before this existed, `AsyncStepSummaryService`, `TwoEdgeContextScrubber`, `ThreeBandChunk` and
 * `SearchableHistoryIndex` had zero production call sites: five classes and a green test, and a
 * running agent that behaved exactly as it did before they were written. This store is the single
 * owner that ties them together and the single seam the loop talks to.
 *
 * Responsibilities, in order, once per step:
 *   1. append the turn (append-only; earlier turns are never reordered or edited)
 *   2. dispatch its summary asynchronously — never waiting for it
 *   3. index it for recall
 *   4. apply both scrub edges destructively, driven by measured occupancy
 *   5. freeze the summary slot of any turn that just passed both edges
 *   6. fold the oldest span into a three-band chunk when pressure demands it
 */
class StepHistoryStore(
    private val budget: HistoryBudget = HistoryBudget.DEFAULT,
    private val summaries: AsyncStepSummaryService = AsyncStepSummaryService(),
    private val index: SearchableHistoryIndex = SearchableHistoryIndex(),
    private val scrubber: TwoEdgeContextScrubber = TwoEdgeContextScrubber(budget),
    /** Cheap token estimate. Replace with the provider's tokenizer when one is available. */
    private val estimateTokens: (String) -> Int = { it.length / 4 },
    /** Fixed cost of the parts of the prompt that are not turns. */
    private val systemPrefixTokens: () -> Int = { 0 },
    private val toolSchemaTokens: () -> Int = { 0 },
    private val noteTokens: () -> Int = { 0 }
) {
    private val turns = mutableListOf<TwoEdgeContextScrubber.HistoryTurn>()
    private val chunks = mutableListOf<ThreeBandChunk>()
    private val ledger = mutableListOf<ThreeBandChunk.LedgerEntry>()

    val activeTurnCount: Int get() = turns.size
    val chunkCount: Int get() = chunks.size

    fun stats(): AsyncStepSummaryService.Stats = summaries.stats()

    /**
     * Append one step. Returns the outcome of this step's scrub pass so callers can log it.
     *
     * @param detail  the full rendering used when the summary is not ready yet
     */
    @Synchronized
    fun append(
        stepNumber: Int,
        userPrompt: String,
        detail: String,
        uiElementList: List<String>? = null,
        planRecitation: String? = null,
        ephemeralBlocks: List<String>? = null,
        screenshotRef: String? = null,
        executionResult: String? = null,
        actionType: String = "",
        targetDescription: String = "",
        recordedResult: String = ""
    ): TwoEdgeContextScrubber.ScrubOutcome {
        val stepId = "step-$stepNumber"
        val turn = TwoEdgeContextScrubber.HistoryTurn(
            stepNumber = stepNumber,
            stepId = stepId,
            userPrompt = userPrompt,
            uiElementList = uiElementList,
            planRecitation = planRecitation,
            ephemeralBlocks = ephemeralBlocks,
            screenshotRef = screenshotRef,
            visualSummary = null,
            executionResult = executionResult,
            tokenEstimate = estimateTokens(detail)
        )
        turns += turn

        // 2. never blocks
        summaries.dispatchSummary(stepId, detail)

        // 3. recall stays possible after compression
        index.indexStep(stepNumber, detail, screenshotRef, estimateTokens(detail))

        // band 3 is assembled in CODE, as the step happens
        if (actionType.isNotBlank()) {
            ledger += ThreeBandChunk.LedgerEntry(stepNumber, actionType, targetDescription, recordedResult)
        }

        // 4 + 5
        val outcome = scrubber.scrub(turns, occupancy())
        outcome.newlyFrozenSteps.forEach { summaries.freezeStep("step-$it") }

        // 6
        outcome.plan.chunkRange?.let { fold(it) }

        return outcome
    }

    /** Adapter for [StepPipeline.recordStep], so wiring this in is one constructor argument. */
    fun asPipelineRecorder(
        userPrompt: () -> String = { "" },
        renderDetail: (StepPipeline.StepRecord) -> String = { defaultDetail(it) }
    ): (StepPipeline.StepRecord) -> Unit = { record ->
        append(
            stepNumber = record.stepIndex,
            userPrompt = userPrompt(),
            detail = renderDetail(record),
            // Plan-5.2: only a FAILED turn carries a trailing execution-result message.
            executionResult = if (record.verified) null else record.failureReason?.let {
                NeutralWordingContract.neutralize(it)
            },
            actionType = record.action.toolName,
            targetDescription = record.resolvedNode?.let { n ->
                n.text.ifBlank { n.contentDescription }.ifBlank { n.viewId }
            } ?: record.action.describeAction(),
            recordedResult = if (record.verified) "the expected change was observed"
            else NeutralWordingContract.neutralize(record.failureReason ?: "no change was observed")
        )
    }

    fun occupancy(): HistoryBudget.Occupancy = HistoryBudget.Occupancy(
        systemPrefixTokens = systemPrefixTokens(),
        chunkTokens = chunks.sumOf { estimateTokens(it.renderIntervals()) },
        noteTokens = noteTokens(),
        toolSchemaTokens = toolSchemaTokens(),
        activeTurnTokens = turns.sumOf { it.tokenEstimate }
    )

    /**
     * Renders history for the prompt: chunks oldest-first, then the active turns.
     * Append-only and byte-stable — a frozen turn renders identically on every later call, which
     * is the precondition for provider-side prompt caching (Plan-5.1).
     */
    @Synchronized
    fun render(): String = buildString {
        chunks.forEach { c ->
            appendLine("[compressed steps ${c.startStep}-${c.endStep}]")
            appendLine("doing: ${c.synopsis.doing}")
            appendLine("did: ${c.synopsis.did}")
            appendLine("effect: ${c.synopsis.effect}")
            appendLine(c.renderIntervals())
            appendLine()
        }
        turns.forEach { t ->
            appendLine("--- step ${t.stepNumber} ---")
            if (t.uiElementList != null) {
                t.uiElementList!!.forEach { appendLine(it) }
            } else if (t.textScrubbed) {
                appendLine(TwoEdgeContextScrubber.ELEMENTS_WITHHELD_MARKER)
            }
            t.planRecitation?.let { appendLine(it) }
            val body = summaries.getSummaryOrFallback(t.stepId, t.visualSummary ?: "").render()
            if (body.isNotBlank()) appendLine(body)
            t.executionResult?.let { appendLine("execution result: $it") }
        }
    }

    fun recall(keyword: String? = null, range: IntRange? = null) = index.recall(keyword, range)

    fun replay(steps: List<Int>) = index.replaySteps(steps)

    /** Bounded barrier, e.g. before producing the final user-facing summary. */
    fun awaitSummaries(timeoutSeconds: Long = 30): Boolean = summaries.awaitQuiescence(timeoutSeconds)

    fun shutdown() = summaries.shutdown()

    /**
     * Folds [range] into a three-band chunk and drops those turns from the active window.
     * Band 3 comes from the code-assembled ledger; only bands 1 and 2 would ever be model-written.
     */
    @Synchronized
    private fun fold(range: IntRange) {
        if (chunks.size >= budget.maxChunks) return
        val folded = turns.filter { it.stepNumber in range }
        if (folded.size < budget.chunkMinSteps) return

        val entries = ledger.filter { it.stepNumber in range }.sortedBy { it.stepNumber }
        val intervals = mergeRuns(entries)

        val chunk = ThreeBandChunk(
            chunkIndex = chunks.size,
            startStep = range.first,
            endStep = range.last,
            synopsis = ThreeBandChunk.SegmentSynopsis(
                doing = "I was working through steps ${range.first} to ${range.last}.",
                did = entries.map { it.actionType }.distinct().joinToString(", ")
                    .ifBlank { "I recorded no actions in this span" }
                    .let { "I used: $it." },
                effect = "The recorded end state follows the step ${range.last} observation.",
                entryState = "step ${range.first}",
                exitState = "step ${range.last}"
            ),
            intervals = intervals,
            actionLedger = ThreeBandChunk.assembleLedgerInCode(entries)
        )

        // A chunk that fails its own machine check is not shipped.
        if (ThreeBandChunk.validateChunk(chunk, budget).isNotEmpty()) return

        chunks += chunk
        turns.removeAll { it.stepNumber in range }
        folded.forEach { summaries.freezeStep(it.stepId) }
    }

    /** "merge homogeneous runs into one interval", with exact coverage guaranteed by construction. */
    private fun mergeRuns(entries: List<ThreeBandChunk.LedgerEntry>): List<ThreeBandChunk.Interval> {
        if (entries.isEmpty()) return emptyList()
        val out = mutableListOf<ThreeBandChunk.Interval>()
        var runStart = entries.first().stepNumber
        var prev = entries.first()
        for (e in entries.drop(1)) {
            val contiguous = e.stepNumber == prev.stepNumber + 1
            if (!contiguous || e.actionType != prev.actionType) {
                out += ThreeBandChunk.Interval(runStart, prev.stepNumber, narrate(prev.actionType, runStart, prev.stepNumber))
                runStart = e.stepNumber
            }
            prev = e
        }
        out += ThreeBandChunk.Interval(runStart, prev.stepNumber, narrate(prev.actionType, runStart, prev.stepNumber))
        return out
    }

    private fun narrate(actionType: String, from: Int, to: Int): String =
        if (from == to) "I performed one $actionType."
        else "I performed ${to - from + 1} consecutive $actionType actions."

    private companion object {
        fun defaultDetail(r: StepPipeline.StepRecord): String = buildString {
            append("I ran ${r.action.toolName}")
            r.resolvedNode?.let { n ->
                val label = n.text.ifBlank { n.contentDescription }.ifBlank { n.viewId }
                if (label.isNotBlank()) append(" on \"$label\"")
            }
            append(". ")
            append(
                if (r.verified) "The expected change was observed."
                else NeutralWordingContract.neutralize(r.failureReason ?: "The expected change was not observed.")
            )
            r.textEvidence?.takeIf { it.isNotBlank() }?.let { append(" On-screen text: \"$it\".") }
        }
    }
}
