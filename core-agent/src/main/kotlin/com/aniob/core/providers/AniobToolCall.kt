package com.aniob.core.providers

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SwipeDirection

/**
 * Parsed shape of the JSON tool call an on-device SLM is expected to emit.
 */
data class AniobToolCall(
    val thought: String,
    val tool: String,
    val targetNodeId: Int? = null,
    val text: String? = null,
    val packageName: String? = null,
    val direction: SwipeDirection? = null,
    val key: KeyType? = null,
    val summary: String? = null,
    val reason: String? = null
)

/**
 * Outcome of a local generation attempt.
 *
 * The critical contract: only [Ready] carries model output. Every other variant means
 * "no model produced this" so callers must surface an explicit, user-actionable failure
 * instead of executing a fabricated action against a node id that may not exist.
 */
sealed class AniobGenerationResult {
    data class Ready(val fullText: String) : AniobGenerationResult()

    /** Neither the native library nor the model file is usable on this device. */
    data object ModelUnavailable : AniobGenerationResult()

    /** Native library/model present but generation failed or timed out (>15s). */
    data class GenerationFailed(val reason: String) : AniobGenerationResult()

    /**
     * Model ran, but the emitted text is not a parseable action tool call. Raw text is
     * retained for the caller's diagnostics but must not be executed as an action.
     */
    data class Unparseable(val rawText: String) : AniobGenerationResult()
}

/**
 * Parses the strict single-step JSON schema emitted by local SLM providers:
 * `{"thought": "...", "tool": "tap", "target_node_id": 3}`.
 */
object AniobToolCallParser {

    private val TOOL_OBJECT = Regex("\\{[^{}]*\"tool\"\\s*:\\s*\"[^\"]+\"[^{}]*}", RegexOption.DOT_MATCHES_ALL)
    private val STRING_FIELD: (String) -> Regex = { field ->
        Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
    private val INT_FIELD: (String) -> Regex = { field ->
        Regex("\"$field\"\\s*:\\s*(-?\\d+)")
    }

    fun parse(rawText: String): AniobToolCall? {
        val candidate = TOOL_OBJECT.find(rawText)?.value ?: rawText.trim()
        if (!candidate.contains("\"tool\"")) return null
        val tool = str(candidate, "tool") ?: str(candidate, "action") ?: return null

        val targetNodeId = int(candidate, "target_node_id")
            ?: int(candidate, "targetNodeId")
            ?: int(candidate, "node_id")

        return AniobToolCall(
            thought = str(candidate, "thought") ?: "Local model step",
            tool = tool.trim().lowercase(),
            targetNodeId = targetNodeId,
            text = str(candidate, "text"),
            packageName = str(candidate, "package_name") ?: str(candidate, "packageName"),
            direction = str(candidate, "direction")?.let(::swipeDirection),
            key = str(candidate, "key")?.let(::keyType),
            summary = str(candidate, "summary"),
            reason = str(candidate, "reason")
        )
    }

    /**
     * Maps a parsed tool call onto the canonical 15-tool action model.
     * [validNodeIds] guards taps against hallucinated node ids.
     */
    fun toAction(call: AniobToolCall, validNodeIds: Set<Int> = emptySet()): AniobAction {
        fun nodeOrNull(): Int? = call.targetNodeId?.takeIf { validNodeIds.isEmpty() || it in validNodeIds }
        return when (call.tool) {
            "tap", "click" -> nodeOrNull()?.let { AniobAction.Click(targetNodeId = it, thought = call.thought) }
                ?: AniobAction.Fail(reason = "Local model proposed ${call.tool} on missing node ${call.targetNodeId}", thought = call.thought)
            "long_press" -> nodeOrNull()?.let { AniobAction.LongPress(targetNodeId = it, thought = call.thought) }
                ?: AniobAction.Fail(reason = "Local model proposed long_press on missing node ${call.targetNodeId}", thought = call.thought)
            "input_text", "type" -> nodeOrNull()?.let {
                AniobAction.InputText(targetNodeId = it, text = call.text.orEmpty(), thought = call.thought)
            } ?: AniobAction.Fail(reason = "Local model proposed input_text on missing node ${call.targetNodeId}", thought = call.thought)
            "swipe", "scroll" -> AniobAction.Swipe(direction = call.direction ?: SwipeDirection.DOWN, thought = call.thought)
            "open_app" -> AniobAction.OpenApp(packageName = call.packageName.orEmpty(), thought = call.thought)
            "system_key", "press_key" -> AniobAction.PressKey(key = call.key ?: KeyType.BACK, thought = call.thought)
            "finish" -> AniobAction.Finish(summary = call.summary ?: "Task completed", thought = call.thought)
            "fail" -> AniobAction.Fail(reason = call.reason ?: "Local model reported failure", thought = call.thought)
            else -> AniobAction.Fail(reason = "Unsupported local tool '${call.tool}'", thought = call.thought)
        }
    }

    private fun str(json: String, field: String): String? {
        val match = STRING_FIELD(field).find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }

    private fun int(json: String, field: String): Int? = INT_FIELD(field).find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun swipeDirection(raw: String): SwipeDirection? =
        SwipeDirection.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    private fun keyType(raw: String): KeyType? =
        KeyType.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
}