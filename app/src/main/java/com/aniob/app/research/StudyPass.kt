package com.aniob.app.research

/**
 * Distinguishes warm-up runs from measured runs in the ablation study.
 *
 * `FULL_HUMAN_SPEED` must be run twice: once in [WARMUP] to populate the verified trajectory
 * replay cache, and once in [MEASURED] to measure execution latency with the warmed cache.
 *
 * Both passes are appended to `research_runs.csv` for auditability, but only [MEASURED] runs
 * are aggregated into `research_summary_table.csv`.
 */
enum class StudyPass(
    val id: String,
    val label: String
) {
    WARMUP("WARMUP", "Warm-up"),
    MEASURED("MEASURED", "Measured");

    companion object {
        val DEFAULT: StudyPass = MEASURED

        fun fromId(id: String?): StudyPass {
            if (id.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(id.trim(), ignoreCase = true) }
                ?: DEFAULT
        }
    }
}
