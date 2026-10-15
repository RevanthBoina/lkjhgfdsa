package com.aniob.core.perception

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.GroundingSource
import kotlin.math.max

/**
 * Unified element index fusing Accessibility tree nodes and OCR elements into one searchable collection.
 * Supports exact match, fuzzy text search, and coordinate hit testing.
 */
class AniobFusedIndex(
    val elements: List<AniobNode> = emptyList()
) {
    fun findById(id: Int): AniobNode? = elements.find { it.id == id }

    fun exactMatch(query: String, ignoreCase: Boolean = true): List<AniobNode> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return elements.filter {
            it.text.equals(q, ignoreCase = ignoreCase) ||
            it.contentDescription.equals(q, ignoreCase = ignoreCase) ||
            it.viewId.equals(q, ignoreCase = ignoreCase) ||
            it.tooltip.equals(q, ignoreCase = ignoreCase)
        }
    }

    fun fuzzyTextSearch(query: String, minSimilarity: Double = 0.65): List<Pair<AniobNode, Double>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        return elements.mapNotNull { node ->
            val targets = listOf(node.text, node.contentDescription, node.tooltip)
                .filter { it.isNotBlank() }
                .map { it.lowercase() }

            val bestScore = targets.maxOfOrNull { textSimilarity(q, it) } ?: 0.0
            if (bestScore >= minSimilarity) node to bestScore else null
        }.sortedByDescending { it.second }
    }

    fun hitTest(x: Int, y: Int): AniobNode? {
        val candidates = elements.filter { it.bounds.contains(x, y) && it.isVisibleToUser }
        // Prefer smallest area (most specific leaf element) and clickable items
        return candidates.minWithOrNull(
            compareBy<AniobNode> { !it.isClickable }
                .thenBy { it.bounds.area }
        )
    }

    val sourceDistribution: Map<GroundingSource, Int>
        get() = elements.groupingBy { it.source }.eachCount()

    companion object {
        fun textSimilarity(s1: String, s2: String): Double {
            if (s1 == s2) return 1.0
            if (s1.contains(s2) || s2.contains(s1)) {
                val minLen = minOf(s1.length, s2.length).toDouble()
                val maxLen = maxOf(s1.length, s2.length).toDouble()
                return max(0.75, minLen / maxLen)
            }
            val distance = levenshteinDistance(s1, s2)
            val maxLen = maxOf(s1.length, s2.length)
            return if (maxLen == 0) 1.0 else (1.0 - (distance.toDouble() / maxLen))
        }

        private fun levenshteinDistance(s1: String, s2: String): Int {
            if (s1 == s2) return 0
            if (s1.isEmpty()) return s2.length
            if (s2.isEmpty()) return s1.length

            val (shorter, longer) = if (s1.length <= s2.length) s1 to s2 else s2 to s1
            val cols = shorter.length
            var prev = IntArray(cols + 1) { it }
            var curr = IntArray(cols + 1)

            for (i in 1..longer.length) {
                curr[0] = i
                val cLong = longer[i - 1]
                for (j in 1..cols) {
                    val cost = if (cLong == shorter[j - 1]) 0 else 1
                    curr[j] = minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + cost
                    )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[cols]
        }
    }
}
