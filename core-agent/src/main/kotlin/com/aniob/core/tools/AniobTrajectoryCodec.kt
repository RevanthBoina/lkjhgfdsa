package com.aniob.core.tools

import com.aniob.core.domain.AniobActionSchema

/**
 * Line-oriented serialization for [AniobReplayEngine.ReplayTrajectory].
 *
 * FastPath knowledge used to live in an in-memory `mutableMapOf`, so "zero-token replay" died on
 * every process restart. This codec lets the app persist trajectories in Room and rehydrate the
 * engine at cold start — the fix for the amnesia finding.
 *
 * Format (pure text, no JSON dependency):
 * ```
 * HEADER|taskSignature|packageName|stepCount
 * STEP|expectedFingerprint|actionJson
 * ...
 * ```
 * `actionJson` is a single line because [AniobActionSchema.toActionJson] escapes newlines.
 */
object AniobTrajectoryCodec {

    private const val HEADER = "HEADER"
    private const val STEP = "STEP"

    fun encode(trajectories: List<AniobReplayEngine.ReplayTrajectory>): String = buildString {
        trajectories.forEach { t ->
            appendLine(listOf(HEADER, t.taskSignature, t.packageName, t.steps.size.toString()).joinToString("|"))
            t.steps.forEach { s ->
                appendLine(listOf(STEP, s.expectedFingerprint, AniobActionSchema.toActionJson(s.action)).joinToString("|"))
            }
        }
    }

    fun encode(trajectory: AniobReplayEngine.ReplayTrajectory): String = encode(listOf(trajectory))

    /** Decodes what [encode] wrote; malformed lines are skipped rather than crashing cold start. */
    fun decode(payload: String): List<AniobReplayEngine.ReplayTrajectory> {
        val result = mutableListOf<AniobReplayEngine.ReplayTrajectory>()
        var current: AniobReplayEngine.ReplayTrajectory? = null
        payload.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            when (line.substringBefore('|')) {
                HEADER -> {
                    val parts = line.split('|')
                    if (parts.size >= 4) {
                        current = AniobReplayEngine.ReplayTrajectory(
                            taskSignature = parts[1],
                            packageName = parts[2],
                            steps = emptyList()
                        ).let { result.add(it); it }
                    }
                }
                STEP -> {
                    val parts = line.split('|', limit = 3)
                    val action = parts.getOrNull(2)?.let { raw ->
                        runCatching { AniobActionSchema.parseActionJson(raw) }.getOrNull()
                    }
                    if (parts.size == 3 && action != null) {
                        val existing = current
                        if (existing != null) {
                            val updated = existing.copy(
                                steps = existing.steps + AniobReplayEngine.ReplayStep(
                                    expectedFingerprint = parts[1],
                                    action = action
                                )
                            )
                            result[result.lastIndex] = updated
                            current = updated
                        }
                    }
                }
            }
        }
        return result
    }
}