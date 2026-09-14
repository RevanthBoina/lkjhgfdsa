package com.aniob.core.skills

import com.aniob.core.embedding.AniobEmbeddingStore

/**
 * Semantic Skill Matcher (Phase 2.2).
 * Integrates vector embeddings with keyword fallback to match user intent to Playbooks & Skills,
 * even when the user phrasing diverges (e.g. "Book cab to airport" vs "book_taxi").
 */
class AniobSemanticSkillMatcher(
    private val skillEngine: AniobSkillEngine = AniobSkillEngine(),
    private val embeddingStore: AniobEmbeddingStore = AniobEmbeddingStore()
) {

    private val keywordMatcher = AniobSkillMatcher(skillEngine)
    private var isStoreIndexed = false

    init {
        indexSkills()
    }

    @Synchronized
    fun indexSkills() {
        val skills = skillEngine.getLoadedSkills()
        for (skill in skills) {
            val textToEmbed = "${skill.name} ${skill.description} ${skill.triggerKeywords.joinToString(" ")}"
            val vector = AniobEmbeddingStore.generatePseudoEmbedding(textToEmbed)
            embeddingStore.put(
                id = skill.name,
                text = textToEmbed,
                vector = vector,
                metadata = mapOf("skillName" to skill.name)
            )
        }
        isStoreIndexed = true
    }

    fun findBestSkill(prompt: String): AniobSkillMatcher.MatchResult {
        // 1. Fast keyword check first
        val keywordResult = keywordMatcher.match(prompt)
        if (keywordResult.matched) {
            return keywordResult
        }

        // 2. Semantic Embedding search
        val promptVector = AniobEmbeddingStore.generatePseudoEmbedding(prompt)
        val matches = embeddingStore.search(promptVector, topK = 1, threshold = 0.60f)

        if (matches.isNotEmpty()) {
            val bestMatch = matches.first()
            val matchedSkill = skillEngine.getLoadedSkills().firstOrNull { it.name == bestMatch.id }
            if (matchedSkill != null) {
                return AniobSkillMatcher.MatchResult(
                    matched = true,
                    skill = matchedSkill,
                    confidence = bestMatch.score,
                    reason = "Semantic Vector Match: '${matchedSkill.name}' (score: ${"%.2f".format(bestMatch.score)})"
                )
            }
        }

        return AniobSkillMatcher.MatchResult(matched = false, reason = "No semantic or keyword skill match")
    }
}
