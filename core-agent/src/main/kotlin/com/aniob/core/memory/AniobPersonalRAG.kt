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

        // Fallback: Check shared knowledge store keys and values
        val allKnowledge = sharedKnowledgeStore.getAll()
        val queryTokens = prompt.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val fallbackList = mutableListOf<RetrievedMemoryItem>()

        for ((k, v) in allKnowledge) {
            val keyTokens = k.lowercase().split(Regex("\\W+"))
            val overlap = queryTokens.count { it in keyTokens }
            if (overlap > 0) {
                fallbackList.add(
                    RetrievedMemoryItem(
                        title = k,
                        content = v,
                        score = 0.7f,
                        source = "SHARED_STORE"
                    )
                )
            }
        }

        return fallbackList.take(topK)
    }
}
