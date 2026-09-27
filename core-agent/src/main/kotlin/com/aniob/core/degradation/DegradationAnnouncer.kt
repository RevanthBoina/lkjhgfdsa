package com.aniob.core.degradation

/**
 * Plan-7.9 — ANNOUNCE DEGRADATION.
 *
 * When a primary path fails and you fall back, SAY SO — in the timeline, in the status surface,
 * and in the final summary. Three places, every time.
 *
 * Silently catching `UnsatisfiedLinkError` is how the on-device model path became fiction nobody
 * noticed: both native engines exist, neither has a call site, `System.loadLibrary("llama")`
 * always throws, and the app kept reporting that everything was fine. A fallback the user cannot
 * see is indistinguishable from a bug.
 */
class DegradationAnnouncer {

    enum class Subsystem {
        INFERENCE_RUNTIME, MODEL_TIER, PERCEPTION_TIER, GROUNDING_SOURCE,
        NETWORK_PROVIDER, ACCESSIBILITY, SCREENSHOT, VERIFICATION, SUMMARIZER
    }

    /**
     * @param wanted what we tried to use
     * @param used   what we actually used
     * @param userImpact one plain sentence about what is different FOR THEM — not what broke
     */
    data class Degradation(
        val subsystem: Subsystem,
        val wanted: String,
        val used: String,
        val userImpact: String,
        val atStep: Int? = null,
        val at: Long = System.currentTimeMillis()
    )

    /** The same fact, shaped for the three surfaces it must appear on. */
    data class Announcement(
        val timelineLine: String,
        val statusLine: String,
        val finalSummaryLine: String
    )

    private val recorded = mutableListOf<Degradation>()

    @Synchronized
    fun record(d: Degradation): Announcement {
        // De-duplicate per subsystem+pair so a per-step fallback does not spam the timeline,
        // while still guaranteeing it is announced at least once.
        val isNew = recorded.none { it.subsystem == d.subsystem && it.wanted == d.wanted && it.used == d.used }
        if (isNew) recorded += d
        return announce(d)
    }

    fun announce(d: Degradation): Announcement = Announcement(
        timelineLine = "Using ${d.used} instead of ${d.wanted}. ${d.userImpact}",
        statusLine = "Running on ${d.used}",
        finalSummaryLine = "${label(d.subsystem)}: I used ${d.used} rather than ${d.wanted}. ${d.userImpact}"
    )

    @Synchronized
    fun all(): List<Degradation> = recorded.toList()

    @Synchronized
    fun isDegraded(): Boolean = recorded.isNotEmpty()

    /**
     * The block appended to the final summary. Empty when nothing degraded, so a clean run stays
     * clean, and impossible to forget when something did.
     */
    @Synchronized
    fun finalSummaryBlock(): String =
        if (recorded.isEmpty()) ""
        else buildString {
            appendLine("Things I had to work around:")
            recorded.forEach { appendLine("- " + announce(it).finalSummaryLine) }
        }.trimEnd()

    @Synchronized
    fun clear() = recorded.clear()

    private fun label(s: Subsystem): String = when (s) {
        Subsystem.INFERENCE_RUNTIME -> "On-device model"
        Subsystem.MODEL_TIER -> "Model size"
        Subsystem.PERCEPTION_TIER -> "Screen reading"
        Subsystem.GROUNDING_SOURCE -> "Finding elements"
        Subsystem.NETWORK_PROVIDER -> "Network provider"
        Subsystem.ACCESSIBILITY -> "Accessibility service"
        Subsystem.SCREENSHOT -> "Screenshots"
        Subsystem.VERIFICATION -> "Verification"
        Subsystem.SUMMARIZER -> "Step summaries"
    }

    companion object {
        /**
         * The specific case Plan-7.9 names. Call this from the catch block instead of swallowing.
         */
        fun nativeRuntimeMissing(wanted: String): Degradation = Degradation(
            subsystem = Subsystem.INFERENCE_RUNTIME,
            wanted = wanted,
            used = "the cloud model",
            userImpact = "This needs a connection, and what is on your screen is sent to the provider."
        )
    }
}
