package com.aniob.core.skills

/**
 * Tier 1.5 Matcher between Intent and Agent Loop:
 * Matches input prompt against loaded YAML skills in <1ms without LLM invocations.
 */
class AniobSkillMatcher(
    private val skillEngine: AniobSkillEngine = AniobSkillEngine()
) {

    data class MatchResult(
        val matched: Boolean,
        val skill: AniobSkill? = null,
        val confidence: Float = 0.0f,
        val extractedSlots: Map<String, String> = emptyMap(),
        val reason: String = ""
    )

    fun match(prompt: String, disabledSkills: Set<String> = emptySet()): MatchResult {
        val cleanPrompt = prompt.trim().lowercase()
        if (cleanPrompt.isBlank()) {
            return MatchResult(matched = false, reason = "Empty prompt")
        }

        // Only active catalog skills participate in matching - draft/disabled skills are strictly skipped
        val candidates = skillEngine.getActiveCatalog().filter {
            !disabledSkills.contains(it.name) && it.status == SkillStatus.ACTIVE && !it.isDraft
        }

        for (skill in candidates) {
            // Check direct trigger keywords
            for (kw in skill.triggerKeywords) {
                if (cleanPrompt == kw || cleanPrompt.startsWith(kw) || cleanPrompt.contains(kw)) {
                    val slots = extractSlots(cleanPrompt, skill)
                    return MatchResult(
                        matched = true,
                        skill = skill,
                        confidence = 0.95f,
                        extractedSlots = slots,
                        reason = "Tier 1.5 YAML skill matched: '${skill.name}' via trigger '$kw' (0 LLM calls)"
                    )
                }
            }

            // Check regex pattern if defined
            if (skill.intentPattern.isNotBlank()) {
                try {
                    val regex = Regex(skill.intentPattern, RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(cleanPrompt)) {
                        return MatchResult(
                            matched = true,
                            skill = skill,
                            confidence = 0.90f,
                            reason = "Tier 1.5 YAML skill matched: '${skill.name}' via pattern (0 LLM calls)"
                        )
                    }
                } catch (e: Exception) {
                    // Malformed regex in skill skipped gracefully
                }
            }
        }

        return MatchResult(matched = false, reason = "No YAML skill match found")
    }

    private fun extractSlots(prompt: String, skill: AniobSkill): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((slotName, _) in skill.slots) {
            // simple extraction heuristic: text following slot keyword
            val regex = Regex("$slotName\\s+(?:is|to|as)?\\s*([a-zA-Z0-9_ -]+)", RegexOption.IGNORE_CASE)
            regex.find(prompt)?.let { match ->
                val value = match.groupValues.getOrNull(1)?.trim()
                if (!value.isNullOrBlank()) {
                    result[slotName] = value
                }
            }
        }
        return result
    }
}
