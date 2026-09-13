package com.aniob.core.input

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.TargetSpec

/**
 * Pure JVM Input planner and gesture coordinate resolver.
 */
object AniobInputController {

    data class GestureCommand(
        val type: String, // TAP, LONG_PRESS, SWIPE
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long
    )

    fun resolveGesture(action: AniobAction, screenWidth: Int = 1080, screenHeight: Int = 2400): GestureCommand {
        return when (action) {
            is AniobAction.Tap -> GestureCommand(
                type = "TAP",
                startX = action.x.coerceIn(0, screenWidth),
                startY = action.y.coerceIn(0, screenHeight),
                endX = action.x.coerceIn(0, screenWidth),
                endY = action.y.coerceIn(0, screenHeight),
                durationMs = 50L
            )
            is AniobAction.LongPress -> GestureCommand(
                type = "LONG_PRESS",
                startX = action.x.coerceIn(0, screenWidth),
                startY = action.y.coerceIn(0, screenHeight),
                endX = action.x.coerceIn(0, screenWidth),
                endY = action.y.coerceIn(0, screenHeight),
                durationMs = action.durationMs
            )
            is AniobAction.Swipe -> {
                val midX = screenWidth / 2
                val midY = screenHeight / 2
                val dist = action.distancePx
                val (endX, endY) = when (action.direction) {
                    com.aniob.core.domain.SwipeDirection.UP -> midX to (midY - dist).coerceAtLeast(100)
                    com.aniob.core.domain.SwipeDirection.DOWN -> midX to (midY + dist).coerceAtMost(screenHeight - 100)
                    com.aniob.core.domain.SwipeDirection.LEFT -> (midX - dist).coerceAtLeast(100) to midY
                    com.aniob.core.domain.SwipeDirection.RIGHT -> (midX + dist).coerceAtMost(screenWidth - 100) to midY
                }
                GestureCommand(
                    type = "SWIPE",
                    startX = midX,
                    startY = midY,
                    endX = endX,
                    endY = endY,
                    durationMs = 300L
                )
            }
            else -> GestureCommand("UNKNOWN", 0, 0, 0, 0, 0L)
        }
    }
}
