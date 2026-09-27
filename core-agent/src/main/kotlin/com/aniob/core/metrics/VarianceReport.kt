package com.aniob.core.metrics

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Plan-8.4 — RUN IT TEN TIMES.
 *
 * A single run of a mobile agent is a coin flip dressed as a measurement. Animations, network
 * timing, notification interrupts and ad placement move the result. A change that "improved
 * success from 60% to 70%" on one pass of ten tasks moved one task.
 *
 * So: every task runs 10x, and the report is median plus spread, never a single number. A result
 * whose spread crosses the comparison is reported as NOT DISTINGUISHABLE, out loud, rather than
 * being rounded into a win.
 */
object VarianceReport {

    const val REQUIRED_RUNS = 10

    data class Stat(
        val median: Double,
        val min: Double,
        val max: Double,
        val p25: Double,
        val p75: Double,
        val mean: Double,
        val stdDev: Double,
        val n: Int
    ) {
        /** The interquartile band, the honest version of "about this much". */
        val spread: Double get() = p75 - p25
        fun format(unit: String = ""): String =
            "${fmt(median)}$unit (p25-p75 ${fmt(p25)}-${fmt(p75)}$unit, n=$n)"
        private fun fmt(d: Double) = if (d == d.roundToInt().toDouble()) d.roundToInt().toString()
        else String.format("%.2f", d)
    }

    data class TaskReport(
        val taskId: String,
        val runs: Int,
        val successRate: Double,
        val trustworthySuccessRate: Double,
        val falseSuccessCount: Int,
        val steps: Stat,
        val wallClockMs: Stat,
        val questionsBeforeStarting: Stat,
        val underAskFailures: Int,
        /** True when fewer than 10 runs were collected. The report still prints; it just admits it. */
        val underpowered: Boolean
    ) {
        fun summaryLine(): String = buildString {
            append("$taskId: ${pct(trustworthySuccessRate)} trustworthy")
            if (successRate != trustworthySuccessRate) append(" (${pct(successRate)} claimed)")
            append(", ${steps.format()} steps, ${wallClockMs.format(" ms")}")
            if (falseSuccessCount > 0) append("  [!] $falseSuccessCount false success")
            if (underAskFailures > 0) append("  [!] $underAskFailures guessed instead of asking")
            if (underpowered) append("  [UNDERPOWERED: $runs of $REQUIRED_RUNS runs]")
        }
    }

    /** The comparison verdict. The third value is the one that keeps everyone honest. */
    sealed interface Comparison {
        data class Better(val deltaPct: Double, val explanation: String) : Comparison
        data class Worse(val deltaPct: Double, val explanation: String) : Comparison
        data class NotDistinguishable(val explanation: String) : Comparison
    }

    fun stat(values: List<Double>): Stat {
        require(values.isNotEmpty()) { "cannot summarise zero runs" }
        val s = values.sorted()
        val mean = s.average()
        val variance = if (s.size < 2) 0.0 else s.sumOf { (it - mean) * (it - mean) } / (s.size - 1)
        return Stat(
            median = percentile(s, 0.50),
            min = s.first(), max = s.last(),
            p25 = percentile(s, 0.25), p75 = percentile(s, 0.75),
            mean = mean, stdDev = sqrt(variance), n = s.size
        )
    }

    fun report(taskId: String, runs: List<TaskMetrics>): TaskReport {
        require(runs.isNotEmpty()) { "cannot report on zero runs" }
        return TaskReport(
            taskId = taskId,
            runs = runs.size,
            successRate = runs.count { it.succeeded }.toDouble() / runs.size,
            trustworthySuccessRate = runs.count { it.trustworthySuccess }.toDouble() / runs.size,
            falseSuccessCount = runs.count { it.falseSuccess },
            steps = stat(runs.map { it.steps.toDouble() }),
            wallClockMs = stat(runs.map { it.wallClockMs.toDouble() }),
            questionsBeforeStarting = stat(runs.map { it.questionsBeforeStarting.toDouble() }),
            underAskFailures = runs.count { it.failedForLackOfQuestion },
            underpowered = runs.size < REQUIRED_RUNS
        )
    }

    /**
     * Compares two variants on success rate. Uses the normal approximation to the difference of
     * two proportions; if the 95% interval spans zero the answer is NOT DISTINGUISHABLE.
     *
     * With n=10 this will say "not distinguishable" a lot. That is the correct answer, and saying
     * it is the point: ten runs can only detect a large effect, so anything it calls a win is one.
     */
    fun compare(baseline: TaskReport, variant: TaskReport): Comparison {
        val p1 = baseline.trustworthySuccessRate
        val p2 = variant.trustworthySuccessRate
        val n1 = baseline.runs
        val n2 = variant.runs
        val delta = p2 - p1
        val se = sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2)
        val margin = 1.96 * se

        // Zero standard error with a zero delta means both were identical, not that we proved it.
        if (se == 0.0 && delta == 0.0) {
            return Comparison.NotDistinguishable(
                "Identical on all ${n1 + n2} runs. That is not the same as proven equal."
            )
        }
        val ciLow = delta - margin
        val ciHigh = delta + margin
        val band = "95% CI ${pct(ciLow)} to ${pct(ciHigh)}"

        return when {
            ciLow > 0 -> Comparison.Better(delta * 100,
                "${pct(p1)} -> ${pct(p2)}, $band. The interval clears zero, so this is real.")
            ciHigh < 0 -> Comparison.Worse(delta * 100,
                "${pct(p1)} -> ${pct(p2)}, $band. The interval clears zero, so this is a regression.")
            else -> Comparison.NotDistinguishable(
                "${pct(p1)} -> ${pct(p2)}, $band. The interval spans zero. " +
                    "With n=$n1 and n=$n2 this difference is not distinguishable from noise - " +
                    "do not report it as a win."
            )
        }
    }

    /** The whole suite, one block, safe to paste into a PR. */
    fun suiteReport(reports: List<TaskReport>): String = buildString {
        appendLine("=== ANIOB MEASUREMENT REPORT ===")
        appendLine("tasks=${reports.size}  runs_each=${reports.map { it.runs }.distinct().joinToString("/")}")
        appendLine()
        reports.forEach { appendLine(it.summaryLine()) }
        appendLine()
        val overall = reports.map { it.trustworthySuccessRate }
        if (overall.isNotEmpty()) {
            appendLine("Trustworthy success across tasks: ${stat(overall.map { it * 100 }).format("%")}")
        }
        val falseSuccesses = reports.sumOf { it.falseSuccessCount }
        appendLine(
            if (falseSuccesses == 0) "False successes: 0."
            else "False successes: $falseSuccesses. Fix these before reading anything else here."
        )
        reports.filter { it.underpowered }.takeIf { it.isNotEmpty() }?.let {
            appendLine("UNDERPOWERED: ${it.joinToString(", ") { r -> r.taskId }} " +
                "- fewer than $REQUIRED_RUNS runs, treat these numbers as anecdotes.")
        }
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo])
    }

    private fun pct(d: Double) = "${(d * 100).roundToInt()}%"
}
