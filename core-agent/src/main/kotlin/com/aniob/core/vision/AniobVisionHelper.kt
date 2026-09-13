package com.aniob.core.vision

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect

/**
 * Helper for Set-of-Mark (SoM) bounding box indexing and vision prompt formatting.
 * Pure JVM design.
 */
object AniobVisionHelper {

    data class MarkedElement(
        val markIndex: Int,
        val label: String,
        val bounds: AniobRect,
        val isClickable: Boolean
    )

    fun generateSoMMarks(nodes: List<AniobNode>, maxElements: Int = 60): List<MarkedElement> {
        val actionableNodes = nodes.filter { it.isClickable || it.isEditable || it.text.isNotBlank() }
            .take(maxElements)

        return actionableNodes.mapIndexed { index, node ->
            val markId = index + 1
            val label = when {
                node.text.isNotBlank() -> node.text
                node.contentDescription.isNotBlank() -> node.contentDescription
                else -> node.className.substringAfterLast(".")
            }
            MarkedElement(
                markIndex = markId,
                label = label,
                bounds = node.bounds,
                isClickable = node.isClickable
            )
        }
    }

    fun formatVisionPrompt(taskGoal: String, marks: List<MarkedElement>): String {
        return buildString {
            appendLine("Goal: $taskGoal")
            appendLine("Screen elements with Set-of-Mark (SoM) tags:")
            marks.forEach { m ->
                appendLine("[${m.markIndex}] \"${m.label}\" at bounds (${m.bounds.left},${m.bounds.top},${m.bounds.right},${m.bounds.bottom}) clickable=${m.isClickable}")
            }
            appendLine("Reply with exactly one JSON action object indicating tool name and target mark ID.")
        }
    }
}
