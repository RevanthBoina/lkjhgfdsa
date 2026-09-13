package com.aniob.core.domain

/**
 * Pure JVM representation of an interactive screen element.
 * Completely independent of android.view.accessibility.AccessibilityNodeInfo.
 */
data class AniobNode(
    val id: Int,
    val className: String,
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val bounds: AniobRect = AniobRect(0, 0, 0, 0),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isSelected: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true
) {
    val centerX: Int get() = (bounds.left + bounds.right) / 2
    val centerY: Int get() = (bounds.top + bounds.bottom) / 2
    val width: Int get() = bounds.right - bounds.left
    val height: Int get() = bounds.bottom - bounds.top

    /**
     * Compact single-line representation formatted for LLM token efficiency.
     * Example: [1] Button "Search" (100, 200, 300, 250) {Clickable}
     */
    fun toOptimizedTokenString(): String {
        val label = when {
            text.isNotBlank() && contentDescription.isNotBlank() -> "\"$text\" / \"$contentDescription\""
            text.isNotBlank() -> "\"$text\""
            contentDescription.isNotBlank() -> "\"$contentDescription\""
            viewId.isNotBlank() -> "[id: ${viewId.substringAfterLast('/')}]"
            else -> "<icon>"
        }
        val simpleClass = className.substringAfterLast('.')
        val traits = buildList {
            if (isClickable) add("Clickable")
            if (isEditable) add("Editable")
            if (isScrollable) add("Scrollable")
            if (isSelected) add("Selected")
        }.joinToString(",")

        return "[$id] $simpleClass $label (${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}) {$traits}"
    }
}

data class AniobRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val isEmpty: Boolean get() = left >= right || top >= bottom
    val area: Int get() = if (isEmpty) 0 else (right - left) * (bottom - top)
}
