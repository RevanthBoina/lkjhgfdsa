package com.aniob.core.embedding

import kotlin.math.sqrt

data class EmbeddingRecord(
    val id: String,
    val text: String,
    val vector: FloatArray,
    val metadata: Map<String, String> = emptyMap()
)

data class SimilarityMatch(
    val id: String,
    val text: String,
    val score: Float,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Pure JVM in-memory Embedding Store for Semantic Skill Matching & Memory RAG (Phase 2).
 * Fast vector similarity search with cosine similarity.
 */
class AniobEmbeddingStore {

    private val records = mutableMapOf<String, EmbeddingRecord>()

    @Synchronized
    fun put(id: String, text: String, vector: FloatArray, metadata: Map<String, String> = emptyMap()) {
        records[id] = EmbeddingRecord(id, text, vector, metadata)
    }

    @Synchronized
    fun get(id: String): EmbeddingRecord? = records[id]

    @Synchronized
    fun search(queryVector: FloatArray, topK: Int = 3, threshold: Float = 0.65f): List<SimilarityMatch> {
        return records.values
            .map { record ->
                val sim = cosineSimilarity(queryVector, record.vector)
                SimilarityMatch(record.id, record.text, sim, record.metadata)
            }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(topK)
    }

    @Synchronized
    fun clear() {
        records.clear()
    }

    companion object {
        fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
            if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
            var dot = 0f
            var norm1 = 0f
            var norm2 = 0f
            for (i in v1.indices) {
                dot += v1[i] * v2[i]
                norm1 += v1[i] * v1[i]
                norm2 += v2[i] * v2[i]
            }
            val denom = (sqrt(norm1.toDouble()) * sqrt(norm2.toDouble())).toFloat()
            return if (denom > 0f) dot / denom else 0f
        }

        /**
         * Fast TF-IDF / Bag-of-Characters hashing pseudo-embedding for zero-weight fallback.
         * Produces a 64-dimensional normalized vector from text tokens.
         */
        fun generatePseudoEmbedding(text: String, dim: Int = 64): FloatArray {
            val vec = FloatArray(dim)
            val tokens = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return vec

            for (token in tokens) {
                val idx = (token.hashCode() and 0x7FFFFFFF) % dim
                vec[idx] += 1f
            }
            // Normalize
            var norm = 0f
            for (v in vec) norm += v * v
            val denom = sqrt(norm.toDouble()).toFloat()
            if (denom > 0f) {
                for (i in vec.indices) vec[i] /= denom
            }
            return vec
        }
    }
}

interface AniobEmbeddingProvider {
    fun isAvailable(): Boolean
    fun embed(text: String): FloatArray // Real nomic-embed-text 0.3GB via llama.cpp
    fun embedBatch(texts: List<String>): List<FloatArray>
}

class AniobPseudoEmbeddingProvider : AniobEmbeddingProvider {
    override fun isAvailable() = true
    override fun embed(text: String) = AniobEmbeddingStore.generatePseudoEmbedding(text)
    override fun embedBatch(texts: List<String>) = texts.map { embed(it) }
}

