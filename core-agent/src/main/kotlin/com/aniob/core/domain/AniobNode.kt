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
    val isVisibleToUser: Boolean = true,
    val isPassword: Boolean = false
) {
    val centerX: Int get() = (bounds.left + bounds.right) / 2
    val centerY: Int get() = (bounds.top + bounds.bottom) / 2
    val width: Int get() = bounds.right - bounds.left
    val height: Int get() = bounds.bottom - bounds.top

    /**
     * Compact single-line Set-of-Mark representation formatted for LLM token efficiency
     * (Skyvern fovea/SoM pattern). id is a clean 1-based sequential index post-optimize.
     * Example: [1] android.widget.Button text='Search' contentDesc='' bounds=[100,200,300,250] clickable=true
     */
    fun toOptimizedTokenString(): String {
        val viewIdPart = if (viewId.isNotBlank()) " viewId='$viewId'" else ""
        val enabledPart = if (!isEnabled) " enabled=false" else ""
        return "[$id] $className$viewIdPart text='$text' contentDesc='$contentDescription' bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] clickable=$isClickable$enabledPart"
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
