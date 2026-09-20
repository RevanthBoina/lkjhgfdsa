package com.aniob.core.providers

import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.AniobScreenState

/**
 * The one structured-output boundary for every provider (finding #3).
 *
 * Cloud, local SLM, mock and skill replay all return *data*; parsing lives here, and only here,
 * so three ad-hoc parsers can no longer drift. Flow:
 *
 * ```
 * raw --parse--> action?  --miss--> one repair retry with the schema error fed back
 *                          --miss--> deterministic fallback (never a guessed tap)
 * ```
 *
 * The fallback for executor-role output is a bounded `Wait`, not a fabricated tap: a model that
 * cannot emit the vocabulary must not move the UI. Callers can distinguish a repaired parse from
 * a fallback via [StructuredParse.usedFallback].
 */
object AniobStructuredOutput {

    /** Outcome of the parse-or-repair boundary. */
    data class StructuredParse(
        val action: AniobAction,
        val repaired: Boolean,
        val usedFallback: Boolean,
        val reason: String
    )

    /**
     * @param screenState live screen used to reject hallucinated SoM indices.
     * @param decode per-role generation params; the fallback and prompt budget derive from it.
     * @param repairCall given a repair prompt (schema + parse error + original text), returns a
     *   fresh completion or null when the provider declines / is unavailable.
     */
    fun parseOrRepair(
        raw: String,
        screenState: AniobScreenState?,
        decode: AniobDecodeConfig = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.EXECUTOR),
        repairCall: (String) -> String? = { null }
    ): StructuredParse {
        firstAttempt(raw, screenState)?.let { return it }

        val error = describeError(raw)
        val repairedRaw = runCatching { repairCall(repairPrompt(raw, error)) }.getOrNull()
        if (repairedRaw != null) {
            firstAttempt(repairedRaw, screenState)?.let {
                return it.copy(repaired = true, reason = "repaired: ${it.reason}")
            }
        }

        return StructuredParse(
            action = fallbackFor(decode.role),
            repaired = true,
            usedFallback = true,
            reason = "unparseable after repair ($error) - using deterministic fallback"
        )
    }

    /** Returns a parse result, or null when [raw] is not a valid action (repair not yet tried). */
    private fun firstAttempt(raw: String, screenState: AniobScreenState?): StructuredParse? {
        val parsed = AniobToolCallParser.parseToAction(raw) ?: return null
        // A model-emitted, schema-valid FAIL is legitimate output, not a parse failure.
        if (parsed is AniobAction.Fail && parsed.reason.isBlank()) return null
        val validated = AniobToolCallParser.toAction(raw, screenState)
        return StructuredParse(validated, repaired = false, usedFallback = false, reason = "parsed")
    }

    /** Builds the repair prompt so suspend-aware callers can fetch the retry themselves. */
    fun repairPrompt(raw: String): String = repairPrompt(raw, describeError(raw))

    private fun describeError(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "empty response"
        val coordField = COORDINATE_HINTS.firstOrNull { Regex("\"$it\"\\s*:").containsMatchIn(trimmed) }
        if (coordField != null) return "coordinate field '$coordField' is not part of the vocabulary"
        return "no valid tool object found"
    }

    private fun repairPrompt(raw: String, error: String): String = buildString {
        appendLine("Your previous output could not be parsed into an action.")
        appendLine("ERROR: $error")
        appendLine("SCHEMA:")
        appendLine(AniobActionSchema.ACTION_JSON_SCHEMA)
        appendLine("PREVIOUS OUTPUT:")
        appendLine(raw.take(600))
        append("Return ONE JSON object matching the schema. Do not include prose or coordinates.")
    }

    /**
     * Deterministic fallback per role. Executor waits (never taps blind); a planner keeps its
     * prior summary and a reflector retries an alternative, both represented as a bounded Wait.
     */
    private fun fallbackFor(role: AniobDecodeConfig.Role): AniobAction = when (role) {
        AniobDecodeConfig.Role.EXECUTOR,
        AniobDecodeConfig.Role.PLANNER,
        AniobDecodeConfig.Role.REFLECTOR,
        AniobDecodeConfig.Role.GRILL,
        AniobDecodeConfig.Role.EXPLORER -> AniobAction.Wait(durationMs = 1000)
    }

    private val COORDINATE_HINTS = listOf("x", "y", "startx", "endx", "bounds", "target_node_id")
}