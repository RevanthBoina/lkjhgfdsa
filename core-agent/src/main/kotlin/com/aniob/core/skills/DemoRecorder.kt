package com.aniob.core.skills

import com.aniob.core.domain.SemanticTarget

data class RecordedDemoEvent(
    val actionType: String,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val inputText: String? = null,
    val packageName: String? = null,
    val direction: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object DemoRecorder {
    const val MAX_STEPS = 50

    private val SENSITIVE_PATTERNS = Regex("(?i)(password|passcode|pin|secret|otp|token|cvv)")

    /**
     * Converts recorded demo events into Skill Format v2.1 YAML text.
     * Semantic target resolution precedence: resource-id > text > desc. Never coordinates.
     * Demonstrated secrets (e.g. passwords/pins/tokens) are converted into {{slots}}.
     */
    fun parseRecordTxt(
        skillName: String,
        targetPackage: String,
        events: List<RecordedDemoEvent>
    ): String {
        val cappedEvents = events.take(MAX_STEPS)
        val sb = StringBuilder()
        val slots = mutableMapOf<String, String>()

        sb.appendLine("name: \"$skillName\"")
        sb.appendLine("version: \"2.1\"")
        sb.appendLine("description: \"Taught skill for $skillName\"")
        sb.appendLine("target_package: \"$targetPackage\"")
        sb.appendLine("risk_tier: \"LOW\"")
        sb.appendLine("confirmation_required: false")
        sb.appendLine("triggers:")
        sb.appendLine("  - \"${skillName.replace('_', ' ').lowercase()}\"")

        // First pass: identify slots
        var slotIndex = 1
        cappedEvents.forEach { ev ->
            if (ev.actionType.lowercase() in listOf("type_text", "input_text") && !ev.inputText.isNullOrBlank()) {
                val isSensitive = SENSITIVE_PATTERNS.containsMatchIn(ev.inputText) ||
                    (ev.resourceId?.let { SENSITIVE_PATTERNS.containsMatchIn(it) } == true) ||
                    (ev.text?.let { SENSITIVE_PATTERNS.containsMatchIn(it) } == true)
                if (isSensitive) {
                    val slotKey = "secret_input_$slotIndex"
                    slots[slotKey] = ev.inputText
                    slotIndex++
                }
            }
        }

        if (slots.isNotEmpty()) {
            sb.appendLine("slots:")
            slots.forEach { (k, v) ->
                sb.appendLine("  $k: \"$v\"")
            }
        }

        sb.appendLine("steps:")
        cappedEvents.forEachIndexed { idx, ev ->
            val stepIdx = idx + 1
            sb.appendLine("  - step: $stepIdx")
            when (ev.actionType.lowercase()) {
                "tap", "click" -> {
                    sb.appendLine("    action: tap")
                    appendSemanticTarget(sb, ev)
                    sb.appendLine("    description: \"Tap ${ev.text ?: ev.contentDescription ?: ev.resourceId ?: "element"}\"")
                }
                "type_text", "input_text" -> {
                    sb.appendLine("    action: input_text")
                    appendSemanticTarget(sb, ev)
                    val slotEntry = slots.entries.find { it.value == ev.inputText }
                    if (slotEntry != null) {
                        sb.appendLine("    text: \"{{${slotEntry.key}}}\"")
                    } else {
                        sb.appendLine("    text: \"${ev.inputText.orEmpty().replace("\"", "\\\"")}\"")
                    }
                    sb.appendLine("    description: \"Type into field\"")
                }
                "scroll" -> {
                    sb.appendLine("    action: swipe")
                    sb.appendLine("    direction: \"${ev.direction?.uppercase() ?: "DOWN"}\"")
                    sb.appendLine("    description: \"Scroll ${ev.direction ?: "down"}\"")
                }
                "swipe" -> {
                    sb.appendLine("    action: swipe")
                    sb.appendLine("    direction: \"${ev.direction?.uppercase() ?: "UP"}\"")
                    sb.appendLine("    description: \"Swipe ${ev.direction ?: "up"}\"")
                }
                "back" -> {
                    sb.appendLine("    action: system_key")
                    sb.appendLine("    key: \"BACK\"")
                    sb.appendLine("    description: \"Press back\"")
                }
                else -> {
                    sb.appendLine("    action: tap")
                    appendSemanticTarget(sb, ev)
                    sb.appendLine("    description: \"Interact with element\"")
                }
            }
        }

        return sb.toString()
    }

    private fun appendSemanticTarget(sb: StringBuilder, ev: RecordedDemoEvent) {
        sb.appendLine("    target:")
        when {
            !ev.resourceId.isNullOrBlank() -> {
                sb.appendLine("      kind: \"resource_id\"")
                sb.appendLine("      value: \"${ev.resourceId}\"")
                sb.appendLine("      exact: true")
            }
            !ev.text.isNullOrBlank() -> {
                sb.appendLine("      kind: \"text\"")
                sb.appendLine("      value: \"${ev.text}\"")
                sb.appendLine("      exact: false")
            }
            !ev.contentDescription.isNullOrBlank() -> {
                sb.appendLine("      kind: \"content_desc\"")
                sb.appendLine("      value: \"${ev.contentDescription}\"")
                sb.appendLine("      exact: false")
            }
            else -> {
                sb.appendLine("      kind: \"som_index\"")
                sb.appendLine("      value: \"1\"")
                sb.appendLine("      exact: true")
            }
        }
    }
}
