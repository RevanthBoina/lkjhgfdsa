package com.aniob.core.input

import com.aniob.core.execution.DispatchCommand

/**
 * Pure JVM gesture planner.
 *
 * Coordinates arrive pre-resolved inside a [DispatchCommand] and are clamped to the screen here.
 * This class must never read an action's fields directly — that is what made a stale or
 * hallucinated coordinate reachable before.
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

    fun resolveGesture(command: DispatchCommand, screenWidth: Int = 1080, screenHeight: Int = 2400): GestureCommand? {
        val maxX = (screenWidth - 1).coerceAtLeast(0)
        val maxY = (screenHeight - 1).coerceAtLeast(0)
        return when (command) {
            is DispatchCommand.TapAt -> GestureCommand(
                type = "TAP",
                startX = command.x.coerceIn(0, maxX),
                startY = command.y.coerceIn(0, maxY),
                endX = command.x.coerceIn(0, maxX),
                endY = command.y.coerceIn(0, maxY),
                durationMs = 50L
            )
            is DispatchCommand.LongPressAt -> GestureCommand(
                type = "LONG_PRESS",
                startX = command.x.coerceIn(0, maxX),
                startY = command.y.coerceIn(0, maxY),
                endX = command.x.coerceIn(0, maxX),
                endY = command.y.coerceIn(0, maxY),
                durationMs = command.durationMs
            )
            is DispatchCommand.SwipeGesture -> GestureCommand(
                type = "SWIPE",
                startX = command.startX.coerceIn(0, maxX),
                startY = command.startY.coerceIn(0, maxY),
                endX = command.endX.coerceIn(0, maxX),
                endY = command.endY.coerceIn(0, maxY),
                durationMs = command.durationMs
            )
            else -> null
        }
    }

    /** Builds a swipe gesture centered on the screen, used when no container was resolved. */
    fun centeredSwipe(
        direction: String,
        distancePx: Int,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): GestureCommand {
        val midX = screenWidth / 2
        val midY = screenHeight / 2
        val (endX, endY) = when (direction.uppercase()) {
            "UP" -> midX to (midY - distancePx).coerceAtLeast(100)
            "DOWN" -> midX to (midY + distancePx).coerceAtMost(screenHeight - 100)
            "LEFT" -> (midX - distancePx).coerceAtLeast(100) to midY
            else -> (midX + distancePx).coerceAtMost(screenWidth - 100) to midY
        }
        return GestureCommand("SWIPE", midX, midY, endX, endY, 300L)
    }
}