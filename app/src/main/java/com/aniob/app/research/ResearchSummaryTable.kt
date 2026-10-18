package com.aniob.app.research

import java.util.Locale

/** Running averages for one [AblationMode] — one row of `research_summary_table.csv`. */
data class ResearchSummaryRow(
    val mode: AblationMode,
    val runs: Int,
    val successes: Int,
    val meanLatencySeconds: Double,
    val meanLlmCallsPerTask: Double,
    val successRatePercent: Double
) {
    /**
     * The line shown in the end-of-task toast and in `Log.i("RESEARCH_METRICS", ...)`:
     * `[BASELINE] Latency: 12.34s | LLM Calls: 3 | Success Rate: 66.7%`
     *
     * The success rate is the running rate for the mode, so it moves with every finished task
     * rather than reporting only the latest one.
     */
    fun hudLine(): String = String.format(
        Locale.US,
        "[%s] Latency: %.2fs | LLM Calls: %d | Success Rate: %.1f%%",
        mode.id,
        meanLatencySeconds,
        meanLlmCallsPerTask.toInt(),
        successRatePercent
    )

    fun toCsvRow(): String = listOf(
        mode.id,
        runs.toString(),
        successes.toString(),
        String.format(Locale.US, "%.2f", meanLatencySeconds),
        String.format(Locale.US, "%.2f", meanLlmCallsPerTask),
        String.format(Locale.US, "%.1f", successRatePercent)
    ).joinToString(CsvCodec.DELIMITER.toString())
}

/**
 * Aggregation for the paper's three metrics, grouped by ablation mode.
 *
 * Deliberately pure: no files, no Android, no clock. `ResearchTelemetryManager` owns the IO and
 * feeds the parsed runs here, which is what makes the numbers unit-testable.
 */
object ResearchSummaryTable {

    const val HEADER = "mode,runs,successes,mean_latency_s,mean_llm_calls_per_task,success_rate_percent"

    /**
     * One row per mode that has at least one run, in enum declaration order so the CSV is stable
     * between rewrites. Modes with no data are omitted instead of emitting NaN means.
     */
    fun build(runs: List<ResearchRun>): List<ResearchSummaryRow> =
        AblationMode.entries.mapNotNull { mode ->
            val modeRuns = runs.filter { it.mode == mode }
            if (modeRuns.isEmpty()) return@mapNotNull null
            val successes = modeRuns.count { it.success }
            ResearchSummaryRow(
                mode = mode,
                runs = modeRuns.size,
                successes = successes,
                meanLatencySeconds = modeRuns.sumOf { it.latencySeconds } / modeRuns.size,
                meanLlmCallsPerTask = modeRuns.sumOf { it.llmCalls.toDouble() } / modeRuns.size,
                successRatePercent = successes.toDouble() / modeRuns.size * 100.0
            )
        }

    /** Full file contents, header included, newline-terminated. */
    fun toCsv(rows: List<ResearchSummaryRow>): String = buildString {
        append(HEADER).append('\n')
        rows.forEach { append(it.toCsvRow()).append('\n') }
    }

    /** Convenience for the HUD: the running row for one mode, or `null` when it has no runs. */
    fun rowFor(runs: List<ResearchRun>, mode: AblationMode): ResearchSummaryRow? =
        build(runs).firstOrNull { it.mode == mode }
}
