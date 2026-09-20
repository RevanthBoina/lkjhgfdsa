package com.aniob.core.tools

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SwipeDirection

data class ScrollResult(val found: Boolean, val node: AniobNode?, val swipes: Int)

object AniobScrollHelper {
    const val MAX_SWIPES = 4
    
    fun findScrollableContainers(screen: AniobScreenState): List<AniobNode> {
        return screen.nodes.filter { it.isScrollable && it.bounds.area > 1000 }
            .sortedByDescending { it.bounds.area }
    }
    
    fun getSemanticDirection(taskPrompt: String): SwipeDirection {
        val lower = taskPrompt.lowercase()
        return when {
            lower.contains("up") || lower.contains("top") || lower.contains("previous") -> SwipeDirection.UP
            else -> SwipeDirection.DOWN // Default down to reveal content below
        }
    }
    
    fun scrollUntilFound(
        screenState: AniobScreenState,
        targetPredicate: (AniobNode) -> Boolean,
        taskPrompt: String,
        capture: () -> AniobScreenState,
        executeSwipe: (AniobAction.Swipe) -> Boolean
    ): ScrollResult {
        // First try without scroll
        screenState.nodes.firstOrNull(targetPredicate)?.let { return ScrollResult(true, it, 0) }
        
        val containers = findScrollableContainers(screenState)
        if (containers.isEmpty()) return ScrollResult(false, null, 0)
        
        val direction = getSemanticDirection(taskPrompt)
        var currentScreen = screenState
        var swipes = 0
        
        while (swipes < MAX_SWIPES) {
            val container = containers.firstOrNull() ?: break
            val swipeAction = AniobAction.Swipe(
                direction = direction,
                distancePx = 500,
                containerId = container.id
            )
            val success = executeSwipe(swipeAction)
            if (!success) break
            
            try {
                Thread.sleep(500) // Wait for scroll animation
            } catch (_: InterruptedException) {}
            currentScreen = capture()
            currentScreen.nodes.firstOrNull(targetPredicate)?.let { 
                return ScrollResult(true, it, swipes + 1) 
            }
            swipes++
        }
        return ScrollResult(false, null, swipes)
    }
}
