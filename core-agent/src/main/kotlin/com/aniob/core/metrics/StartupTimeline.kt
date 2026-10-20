package com.aniob.core.metrics

/**
 * Monotonic marks for one task start, from "the user pressed run" to "the first action left the
 * device".
 *
 * Time-to-first-action is the number users actually feel, and it is dominated by work that has
 * nothing to do with the model: hydrating learned state, waiting for the chat window to minimise,
 * the first screen capture, the TLS handshake to the gateway. This class only records marks; the
 * log line is what makes the split visible in `adb logcat`.
 *
 * The clock is injected so the arithmetic is unit-testable. Marks are recorded once (the first
 * value wins) so a retry cannot distort the timeline.
 */
class StartupTimeline(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    private val marks = LinkedHashMap<String, Long>()

    /** Timestamp of the task-start mark, or 0 when nothing has been marked yet. */
    val startedAt: Long get() = marks[FIRST_MARK] ?: 0L

    /** Records [name] unless it was already recorded. Returns the timestamp used. */
    @Synchronized
    fun mark(name: String): Long {
        marks[name]?.let { return it }
        val now = clock()
        marks[name] = now
        return now
    }

    /** Milliseconds from the task-start mark to [name], or null when either mark is missing. */
    @Synchronized
    fun since(name: String): Long? {
        val start = marks[FIRST_MARK] ?: return null
        val at = marks[name] ?: return null
        return (at - start).coerceAtLeast(0L)
    }

    /** All marks in order, each as milliseconds since the task start. */
    @Synchronized
    fun deltas(): List<Pair<String, Long>> {
        val start = marks[FIRST_MARK] ?: return emptyList()
        return marks.map { (name, at) -> name to (at - start).coerceAtLeast(0L) }
    }

    /** True when the first action was dispatched, i.e. the number the user feels is known. */
    @Synchronized
    fun hasFirstAction(): Boolean = marks.containsKey(MARK_DISPATCHED)

    /**
     * `STARTUP_LATENCY{task_start=0,backgrounded=120,capture=190,prompt=210,ttft=640,action=690,dispatched=760,total_ms=760}`
     *
     * Every value is milliseconds from the task start, so the deltas between adjacent marks are
     * the actual cost of each phase.
     */
    @Synchronized
    fun logLine(): String = buildString {
        append("STARTUP_LATENCY{")
        append(deltas().joinToString(",") { (name, ms) -> "$name=$ms" })
        append(",total_ms=").append(since(MARK_DISPATCHED) ?: (marks.values.maxOrNull()?.minus(startedAt) ?: 0L))
        append('}')
    }

    companion object {
        /** Always the first mark; the timeline is expressed relative to it. */
        const val FIRST_MARK = "task_start"

        /** The chat window finished moving to the background. */
        const val MARK_BACKGROUNDED = "backgrounded"

        /** Learned state finished hydrating (or its budget expired). */
        const val MARK_HYDRATED = "hydrated"

        /** First screen capture of the run. */
        const val MARK_CAPTURE = "capture"

        /** The ladder returned a decision (intent, replay, launch or model dispatch). */
        const val MARK_PLANNED = "planned"

        /** The prompt was assembled and the request is about to leave. */
        const val MARK_PROMPT = "prompt"

        /** First streamed token of the first model reply. */
        const val MARK_TTFT = "ttft"

        /** A parseable action exists. */
        const val MARK_ACTION = "action"

        /** The first action was dispatched to the device. */
        const val MARK_DISPATCHED = "dispatched"
    }
}
