package com.aniob.app.research

import java.util.Locale

/**
 * One completed task, exactly as written to `research_runs.csv`.
 *
 * Pure data with pure CSV encoding so it can be unit-tested without an Android runtime. The file is
 * append-only: a manual pass/fail override rewrites the last row instead of appending a second one,
 * so every row is one task and the summary never double-counts.
 *
 * Goals and failure reasons are collapsed to a single line ([CsvCodec.singleLine]) because the file
 * is appended and read line-by-line; a raw newline inside a quoted field would split one task across
 * two physical rows. Commas and quotes are still quoted and survive the round trip.
 */
data class ResearchRun(
    val timestampMs: Long,
    val mode: AblationMode,
    val goal: String,
    val latencySeconds: Double,
    val llmCalls: Int,
    val localInferences: Int,
    val steps: Int,
    val success: Boolean,
    val failureReason: String,
    val intentShortcutUsed: Boolean,
    val rememberCacheHit: Boolean,
    /** True once the researcher replaced the automatic verdict with a manual one. */
    val manuallyOverridden: Boolean = false,
    val screenshots: Int = 0,
    val promptTokens: Int = 0,
    val studyPass: StudyPass = StudyPass.MEASURED
) {

    fun toCsvRow(): String = listOf(
        timestampMs.toString(),
        mode.id,
        CsvCodec.escape(CsvCodec.singleLine(goal)),
        formatLatency(latencySeconds),
        llmCalls.toString(),
        localInferences.toString(),
        steps.toString(),
        success.toString(),
        CsvCodec.escape(CsvCodec.singleLine(failureReason)),
        intentShortcutUsed.toString(),
        rememberCacheHit.toString(),
        manuallyOverridden.toString(),
        screenshots.toString(),
        promptTokens.toString(),
        studyPass.id
    ).joinToString(CsvCodec.DELIMITER.toString())

    companion object {
        const val HEADER =
            "timestamp_ms,mode,goal,latency_s,llm_calls,local_inferences,steps,success," +
                "failure_reason,intent_shortcut_used,remember_cache_hit,manually_overridden," +
                "screenshots,prompt_tokens,study_pass"

        const val COLUMN_COUNT = 15
        const val LEGACY_COLUMN_COUNT = 12

        /** Milliseconds precision in the raw file; the summary table and HUD round to 2 decimals. */
        fun formatLatency(seconds: Double): String =
            String.format(Locale.US, "%.3f", if (seconds.isFinite()) seconds else 0.0)

        /** Parses one CSV row; returns `null` for the header, blank lines or malformed rows. */
        fun fromCsvRow(row: String): ResearchRun? {
            val cells = CsvCodec.split(row)
            if (cells.size != COLUMN_COUNT && cells.size != LEGACY_COLUMN_COUNT) return null
            val timestamp = cells[0].toLongOrNull() ?: return null
            val latency = cells[3].toDoubleOrNull() ?: return null
            val llmCalls = cells[4].toIntOrNull() ?: return null
            val localInferences = cells[5].toIntOrNull() ?: return null
            val steps = cells[6].toIntOrNull() ?: return null
            val success = cells[7].toBooleanStrictOrNull() ?: return null
            val intentShortcutUsed = cells[9].toBooleanStrictOrNull() ?: return null
            val rememberCacheHit = cells[10].toBooleanStrictOrNull() ?: return null
            val manuallyOverridden = cells[11].toBooleanStrictOrNull() ?: false

            val screenshots = if (cells.size >= 13) cells[12].toIntOrNull() ?: return null else 0
            val promptTokens = if (cells.size >= 14) cells[13].toIntOrNull() ?: return null else 0
            val studyPass = if (cells.size >= 15) StudyPass.fromId(cells[14]) else StudyPass.MEASURED

            return ResearchRun(
                timestampMs = timestamp,
                mode = AblationMode.fromId(cells[1]),
                goal = cells[2],
                latencySeconds = latency,
                llmCalls = llmCalls,
                localInferences = localInferences,
                steps = steps,
                success = success,
                failureReason = cells[8],
                intentShortcutUsed = intentShortcutUsed,
                rememberCacheHit = rememberCacheHit,
                manuallyOverridden = manuallyOverridden,
                screenshots = screenshots,
                promptTokens = promptTokens,
                studyPass = studyPass
            )
        }
    }
}

/**
 * Minimal RFC 4180 quoting. Goals and failure reasons are free text, so a comma in
 * "message Ana, about dinner" must not shift every later column.
 */
object CsvCodec {
    const val DELIMITER = ','

    /** Collapses line breaks and tabs so one task is always exactly one physical CSV row. */
    fun singleLine(value: String): String = value
        .replace("\r\n", " ")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')

    fun escape(value: String): String {
        val needsQuoting = value.any { it == DELIMITER || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }

    fun split(row: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < row.length) {
            val ch = row[index]
            when {
                inQuotes && ch == '"' && index + 1 < row.length && row[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == DELIMITER && !inQuotes -> {
                    cells.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            index++
        }
        cells.add(current.toString())
        return cells
    }
}
