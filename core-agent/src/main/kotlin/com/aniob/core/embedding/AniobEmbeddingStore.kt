package com.aniob.core.embedding

import java.util.PriorityQueue
import kotlin.math.ln
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
 *
 * Upgraded with:
 * 1. Weinberger et al. (2009) Signed Feature Hashing (unbiased inner-product estimator).
 * 2. Multi-granular subword channels: sublinear Log-TF word unigrams + word bigrams + character 3-grams.
 * 3. Bounded O(N log K) min-heap top-K retrieval with IEEE-754 binary64 dot-product accumulation.
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
        if (topK <= 0 || records.isEmpty() || queryVector.isEmpty()) return emptyList()

        val minHeap = PriorityQueue<SimilarityMatch>(topK + 1, compareBy { it.score })
        for (record in records.values) {
            val sim = cosineSimilarity(queryVector, record.vector)
            if (sim >= threshold) {
                if (minHeap.size < topK) {
                    minHeap.offer(SimilarityMatch(record.id, record.text, sim, record.metadata))
                } else if (sim > minHeap.peek().score) {
                    minHeap.poll()
                    minHeap.offer(SimilarityMatch(record.id, record.text, sim, record.metadata))
                }
            }
        }

        val result = ArrayList<SimilarityMatch>(minHeap.size)
        while (minHeap.isNotEmpty()) {
            result.add(minHeap.poll())
        }
        result.reverse()
        return result
    }

    @Synchronized
    fun clear() {
        records.clear()
    }

    companion object {
        private val TOKEN_SPLIT_REGEX = Regex("\\W+")

        /**
         * Numerically stable cosine similarity using 64-bit Double accumulation and [-1, 1] clamping.
         */
        fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
            if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
            var dot = 0.0
            var norm1 = 0.0
            var norm2 = 0.0
            for (i in v1.indices) {
                val a = v1[i].toDouble()
                val b = v2[i].toDouble()
                if (!a.isFinite() || !b.isFinite()) continue
                dot += a * b
                norm1 += a * a
                norm2 += b * b
            }
            val denom = sqrt(norm1) * sqrt(norm2)
            if (denom <= 1e-12 || !denom.isFinite()) return 0f
            return (dot / denom).toFloat().coerceIn(-1.0f, 1.0f)
        }

        /**
         *Murmur3-inspired 64-bit avalanche mixer for independent bucket & sign bit extraction.
         */
        private fun mix64(hash: Int, salt: Long): Long {
            var z = (hash.toLong() and 0xFFFFFFFFL) xor salt
            z = (z xor (z ushr 33)) * -0xae502812aa7333L
            z = (z xor (z ushr 33)) * -0x3b314601e57a13adL
            return z xor (z ushr 33)
        }

        private fun accumulateSignedFeature(vec: FloatArray, dim: Int, featureHash: Int, salt: Long, weight: Float) {
            val mixed = mix64(featureHash, salt)
            val idx = ((mixed ushr 1) % dim.toLong()).toInt()
            val sign = if ((mixed and 1L) == 0L) 1f else -1f
            vec[idx] += sign * weight
        }

        /**
         * Signed Feature-Hashing Pseudo-Embedding with Sublinear Log-TF, Word Bigrams, and Character 3-Grams.
         * Produces a [dim]-dimensional L2-normalized vector from text tokens in O(L) time and 0 model weights.
         */
        fun generatePseudoEmbedding(text: String, dim: Int = 64): FloatArray {
            if (dim <= 0) return FloatArray(0)
            val vec = FloatArray(dim)
            val tokens = text.lowercase().split(TOKEN_SPLIT_REGEX).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return vec

            // 1. Word Unigrams with sublinear TF scaling: 1 + ln(tf)
            val termFreqs = HashMap<String, Int>(tokens.size)
            for (t in tokens) {
                termFreqs[t] = (termFreqs[t] ?: 0) + 1
            }
            for ((token, tf) in termFreqs) {
                val sublinearTf = (1.0 + ln(tf.toDouble())).toFloat()
                accumulateSignedFeature(vec, dim, token.hashCode(), 0x9E3779B97F4A7C15UL.toLong(), sublinearTf)

                // 2. Character 3-grams (<token>) for morphological & typo resilience (weight 0.35)
                if (token.length >= 3) {
                    val padded = "<$token>"
                    for (i in 0..padded.length - 3) {
                        val trigramHash = (padded[i].code * 31 + padded[i + 1].code) * 31 + padded[i + 2].code
                        accumulateSignedFeature(vec, dim, trigramHash, 0xBF58476D1CE4E5B9UL.toLong(), 0.35f)
                    }
                }
            }

            // 3. Word Bigrams for local phrase-order sensitivity (weight 0.60)
            for (i in 0 until tokens.size - 1) {
                val bigramHash = tokens[i].hashCode() * 31 + tokens[i + 1].hashCode()
                accumulateSignedFeature(vec, dim, bigramHash, 0x94D049BB133111EBUL.toLong(), 0.60f)
            }

            // L2 Normalization in 64-bit precision
            var normSq = 0.0
            for (v in vec) normSq += v.toDouble() * v.toDouble()
            val denom = sqrt(normSq).toFloat()
            if (denom > 1e-12f) {
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


