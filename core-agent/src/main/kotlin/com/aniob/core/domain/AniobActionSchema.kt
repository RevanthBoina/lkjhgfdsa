package com.aniob.core.domain

/**
 * The ONE parser for LLM-emitted action JSON.
 *
 * Every provider (local SLM, cloud, mock, skill replay) must route raw model text through
 * [parseActionJson]. No other file may parse action JSON — three ad-hoc parsers previously
 * drifted apart and let two of them emit unschema'd garbage.
 *
 * Accepted wire shape (closed vocabulary):
 * ```
 * {
 *   "thought": "...",
 *   "tool": "TAP" | "LONG_PRESS" | "INPUT_TEXT" | "SWIPE" | "OPEN_APP" | "SYSTEM_KEY"
 *          | "WAIT" | "FINISH" | "FAIL",
 *   "target": { "kind": "som_index"|"resource_id"|"text"|"content_desc", "value": "..." },
 *   "text": "...", "distance_px": 600, "direction": "DOWN",
 *   "package_name": "...", "key": "BACK", "duration_ms": 1000,
 *   "summary": "...", "reason": "..."
 * }
 * ```
 * `target` is required for TAP/LONG_PRESS/INPUT_TEXT. Any coordinate field (`x`, `y`, `bounds`,
 * `startX`, ...) is a hard error — coordinates are not representable in this vocabulary.
 */
object AniobActionSchema {

    /** Human-readable schema for prompts and repair feedback. */
    const val ACTION_JSON_SCHEMA: String = """
{
  "thought": "why this step",
  "tool": "TAP|LONG_PRESS|INPUT_TEXT|SWIPE|OPEN_APP|SYSTEM_KEY|WAIT|FINISH|FAIL",
  "target": {"kind": "som_index|resource_id|text|content_desc", "value": "..."},
  "text": "for INPUT_TEXT",
  "direction": "UP|DOWN|LEFT|RIGHT for SWIPE",
  "distance_px": 600,
  "package_name": "for OPEN_APP",
  "key": "BACK|HOME|ENTER|RECENTS for SYSTEM_KEY",
  "duration_ms": 1000,
  "summary": "for FINISH",
  "reason": "for FAIL"
}
Coordinates are NOT valid. target is required for TAP, LONG_PRESS and INPUT_TEXT.
"""

    /** Fields that would smuggle pixel coordinates into the action vocabulary. */
    private val COORDINATE_FIELDS = listOf("x", "y", "startx", "starty", "endx", "endy", "bounds", "target_node_id", "targetnodeid", "node_id")

    private val TOOL_OBJECT = Regex("\\{[^{}]*\"tool\"\\s*:\\s*\"[^\"]+\"[^{}]*}", RegexOption.DOT_MATCHES_ALL)
    private val STRING_FIELD: (String) -> Regex = { field ->
        Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
    private val INT_FIELD: (String) -> Regex = { field ->
        Regex("\"$field\"\\s*:\\s*(-?\\d+)")
    }

    class ActionSchemaException(message: String) : IllegalArgumentException(message)

    /**
     * Parses [json] into an [AniobAction].
     *
     * @throws ActionSchemaException with a field-level message when the payload is not a
     * valid action — including any attempt to pass coordinates.
     */
    fun parseActionJson(json: String): AniobAction {
        val candidate = TOOL_OBJECT.find(json)?.value ?: json.trim()
        if (!candidate.contains("\"tool\"")) {
            throw ActionSchemaException("missing required field 'tool'")
        }
        rejectCoordinateFields(candidate)

        val toolRaw = str(candidate, "tool")
            ?: throw ActionSchemaException("missing required field 'tool'")
        val tool = toolRaw.trim().uppercase().replace('-', '_')
        val thought = str(candidate, "thought") ?: "Local model step"

        return when (tool) {
            "TAP", "CLICK" -> AniobAction.Tap(requireTarget(candidate, tool), thought)
            "LONG_PRESS" -> AniobAction.LongPress(
                target = requireTarget(candidate, tool),
                durationMs = int(candidate, "duration_ms")?.toLong() ?: 1000L,
                thought = thought
            )
            "INPUT_TEXT", "TYPE" -> AniobAction.InputText(
                target = requireTarget(candidate, tool),
                text = str(candidate, "text") ?: throw ActionSchemaException("INPUT_TEXT requires 'text'"),
                clearFirst = bool(candidate, "clear_first") ?: false,
                thought = thought
            )
            "SWIPE", "SCROLL" -> AniobAction.Swipe(
                direction = str(candidate, "direction")?.let(::swipeDirection)
                    ?: throw ActionSchemaException("SWIPE requires 'direction' in UP|DOWN|LEFT|RIGHT"),
                distancePx = int(candidate, "distance_px") ?: 600,
                thought = thought
            )
            "OPEN_APP" -> AniobAction.OpenApp(
                packageName = str(candidate, "package_name")
                    ?: str(candidate, "package")
                    ?: throw ActionSchemaException("OPEN_APP requires 'package_name'"),
                thought = thought
            )
            "SYSTEM_KEY", "PRESS_KEY", "PRESS_BACK", "PRESS_HOME" -> AniobAction.SystemKey(
                key = str(candidate, "key")?.let(::keyType) ?: keyFromTool(tool),
                thought = thought
            )
            "WAIT" -> AniobAction.Wait(
                durationMs = int(candidate, "duration_ms")?.toLong() ?: AniobAction.Wait.DEFAULT_WAIT_MS,
                thought = thought
            ).clamped()
            "FINISH" -> AniobAction.Finish(
                summary = str(candidate, "summary") ?: str(candidate, "reason") ?: "Task completed",
                thought = thought
            )
            "FAIL" -> AniobAction.Fail(
                reason = str(candidate, "reason") ?: "Model reported failure",
                thought = thought
            )
            else -> throw ActionSchemaException("unknown tool '$toolRaw'")
        }
    }

    /** Lenient parse: null instead of throwing. Used where a caller prefers a fallback path. */
    fun parseActionJsonOrNull(json: String): AniobAction? =
        try {
            parseActionJson(json)
        } catch (_: ActionSchemaException) {
            null
        }

    /** Serializes back to the wire shape (used by tests and repair prompts). */
    fun toActionJson(action: AniobAction): String = when (action) {
        is AniobAction.Tap -> """{"thought":"${esc(action.thought)}","tool":"TAP","target":${targetJson(action.target)}}"""
        is AniobAction.LongPress -> """{"thought":"${esc(action.thought)}","tool":"LONG_PRESS","target":${targetJson(action.target)},"duration_ms":${action.durationMs}}"""
        is AniobAction.InputText -> """{"thought":"${esc(action.thought)}","tool":"INPUT_TEXT","target":${targetJson(action.target)},"text":"${esc(action.text)}"}"""
        is AniobAction.Swipe -> """{"thought":"${esc(action.thought)}","tool":"SWIPE","direction":"${action.direction}","distance_px":${action.distancePx}}"""
        is AniobAction.OpenApp -> """{"thought":"${esc(action.thought)}","tool":"OPEN_APP","package_name":"${esc(action.packageName)}"}"""
        is AniobAction.SystemKey -> """{"thought":"${esc(action.thought)}","tool":"SYSTEM_KEY","key":"${action.key}"}"""
        is AniobAction.PressKey -> """{"thought":"${esc(action.thought)}","tool":"SYSTEM_KEY","key":"${action.key}"}"""
        is AniobAction.Wait -> """{"thought":"${esc(action.thought)}","tool":"WAIT","duration_ms":${action.durationMs}}"""
        is AniobAction.Finish -> """{"thought":"${esc(action.thought)}","tool":"FINISH","summary":"${esc(action.summary)}"}"""
        is AniobAction.Fail -> """{"thought":"${esc(action.thought)}","tool":"FAIL","reason":"${esc(action.reason)}"}"""
        else -> throw ActionSchemaException("tool '${action.toolName}' is not part of the closed action vocabulary")
    }

    private fun targetJson(target: SemanticTarget): String = when (target) {
        is SemanticTarget.SomIndex -> """{"kind":"som_index","value":$target.index}"""
        is SemanticTarget.ResourceId -> """{"kind":"resource_id","value":"${esc(target.id)}"}"""
        is SemanticTarget.Text -> """{"kind":"text","value":"${esc(target.text)}","exact":${target.exact}}"""
        is SemanticTarget.ContentDesc -> """{"kind":"content_desc","value":"${esc(target.desc)}","exact":${target.exact}}"""
    }

    /** `target` may be a nested object or a bare string/int scalar. */
    private fun requireTarget(json: String, tool: String): SemanticTarget =
        parseTarget(json) ?: throw ActionSchemaException("$tool requires a 'target' with kind and value")

    private fun parseTarget(json: String): SemanticTarget? {
        val block = Regex("\"target\"\\s*:\\s*\\{([^{}]*)}").find(json)?.groupValues?.get(1)
        if (block != null) {
            val kind = str(block, "kind")?.trim()?.lowercase()
            val value = str(block, "value") ?: int(block, "value")?.toString()
            val exact = bool(block, "exact") ?: false
            if (kind == null || value == null) return null
            return when (kind) {
                "som_index", "som", "index", "node" -> value.toIntOrNull()?.let { SemanticTarget.SomIndex(it) }
                "resource_id", "id", "view_id" -> SemanticTarget.ResourceId(value)
                "text" -> SemanticTarget.Text(value, exact)
                "content_desc", "content_description", "desc" -> SemanticTarget.ContentDesc(value, exact)
                else -> null
            }
        }
        // Scalar shorthand: "target": 3  |  "target": "Wi-Fi"
        int(json, "target")?.let { return SemanticTarget.SomIndex(it) }
        str(json, "target")?.let { return SemanticTarget.Text(it) }
        return null
    }

    private fun rejectCoordinateFields(json: String) {
        val lower = json.lowercase()
        // Match the field *name* (quoted and followed by a colon) so a thought value of "x"
        // or "y" is not mistaken for a coordinate field.
        val offending = COORDINATE_FIELDS.filter { Regex("\"$it\"\\s*:").containsMatchIn(lower) }
        if (offending.isNotEmpty()) {
            throw ActionSchemaException(
                "coordinate field(s) ${offending.joinToString(", ")} are not allowed; " +
                    "use target:{kind,value} — pixels are resolved from the live tree"
            )
        }
    }

    private fun keyFromTool(tool: String): KeyType = when (tool) {
        "PRESS_BACK" -> KeyType.BACK
        "PRESS_HOME" -> KeyType.HOME
        else -> KeyType.BACK
    }

    private fun str(json: String, field: String): String? {
        val match = STRING_FIELD(field).find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }

    private fun int(json: String, field: String): Int? =
        INT_FIELD(field).find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun bool(json: String, field: String): Boolean? =
        Regex("\"$field\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun swipeDirection(raw: String): SwipeDirection? =
        SwipeDirection.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    private fun keyType(raw: String): KeyType? =
        KeyType.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}