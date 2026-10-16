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

    private fun escapeField(value: String): String =
        value.replace("\\", "\\\\")
            .replace("|", "\\p")
            .replace("\r", "\\r")
            .replace("\n", "\\n")

    private fun unescapeField(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> sb.append('\\')
                    'p' -> sb.append('|')
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    else -> {
                        sb.append(ch)
                        sb.append(next)
                    }
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    fun encode(trajectories: List<AniobReplayEngine.ReplayTrajectory>): String = buildString {
        trajectories.forEach { t ->
            appendLine(
                listOf(
                    HEADER,
                    escapeField(t.taskSignature),
                    escapeField(t.packageName),
                    t.steps.size.toString()
                ).joinToString("|")
            )
            t.steps.forEach { s ->
                appendLine(
                    listOf(
                        STEP,
                        escapeField(s.expectedFingerprint),
                        AniobActionSchema.toActionJson(s.action)
                    ).joinToString("|")
                )
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
                            taskSignature = unescapeField(parts[1]),
                            packageName = unescapeField(parts[2]),
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
                                    expectedFingerprint = unescapeField(parts[1]),
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