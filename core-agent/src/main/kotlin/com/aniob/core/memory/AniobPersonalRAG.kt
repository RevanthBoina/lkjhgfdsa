package com.aniob.core.memory

import com.aniob.core.embedding.AniobEmbeddingStore
import com.aniob.core.embedding.SimilarityMatch

data class RetrievedMemoryItem(
    val title: String,
    val content: String,
    val score: Float,
    val source: String
)

/**
 * Personal RAG (Phase 2.3).
 * Vector-assisted retrieval of app tips, past trajectories, and user preferences.
 */
class AniobPersonalRAG(
    private val sharedKnowledgeStore: AniobSharedKnowledgeStore,
    private val embeddingStore: AniobEmbeddingStore = AniobEmbeddingStore()
) {

    @Synchronized
    fun indexKnowledge(id: String, content: String, source: String = "VAULT") {
        val vector = AniobEmbeddingStore.generatePseudoEmbedding(content)
        embeddingStore.put(id, content, vector, mapOf("source" to source))
    }

    fun retrieveRelevantContext(prompt: String, topK: Int = 3): List<RetrievedMemoryItem> {
        val queryVector = AniobEmbeddingStore.generatePseudoEmbedding(prompt)
        val matches = embeddingStore.search(queryVector, topK = topK, threshold = 0.55f)

        if (matches.isNotEmpty()) {
            return matches.map { match ->
                RetrievedMemoryItem(
                    title = match.id,
                    content = match.text,
                    score = match.score,
                    source = match.metadata["source"] ?: "VAULT"
                )
            }
        }

        // Fallback: Check shared knowledge store keys and values using pre-compiled token splitting & O(1) set lookup
        val allKnowledge = sharedKnowledgeStore.getAll()
        if (allKnowledge.isEmpty()) return emptyList()
        val queryTokenSet = prompt.lowercase().split(TOKEN_REGEX).filterTo(HashSet()) { it.length > 2 }
        if (queryTokenSet.isEmpty()) return emptyList()

        val fallbackList = mutableListOf<RetrievedMemoryItem>()
        for ((k, v) in allKnowledge) {
            val keyTokens = k.lowercase().split(TOKEN_REGEX).filter { it.isNotBlank() }
            val valTokens = v.lowercase().split(TOKEN_REGEX).filter { it.isNotBlank() }
            val overlap = keyTokens.count { it in queryTokenSet } * 2 + valTokens.count { it in queryTokenSet }
            if (overlap > 0) {
                val normScore = (0.5f + 0.1f * overlap).coerceAtMost(0.95f)
                fallbackList.add(
                    RetrievedMemoryItem(
                        title = k,
                        content = v,
                        score = normScore,
                        source = "SHARED_STORE"
                    )
                )
            }
        }

        return fallbackList.sortedByDescending { it.score }.take(topK)
    }

    private companion object {
        val TOKEN_REGEX = Regex("[^a-zA-Z0-9]+")
    }
}
