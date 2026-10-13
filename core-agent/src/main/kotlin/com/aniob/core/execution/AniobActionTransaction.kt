package com.aniob.core.execution

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SwipeDirection
import com.aniob.core.domain.matches

/**
 * Encapsulates a transactional UI state mutation with its corresponding
 * compensating (undo) action.
 *
 * Pure Kotlin/JVM: 0 android.* imports.
 */
data class ActionTransaction(
    val id: String,
    val originalAction: AniobAction,
    val compensatingAction: AniobAction?,
    val isReversible: Boolean = compensatingAction != null,
    val timestamp: Long = System.currentTimeMillis(),
    val targetDescription: String? = null
)

/**
 * Manages an append-only stack of executed UI action transactions for deterministic rollback.
 */
class AniobActionTransactionStack(private val maxDepth: Int = 20) {

    private val stack = ArrayDeque<ActionTransaction>()

    val size: Int get() = stack.size

    val isNotEmpty: Boolean get() = stack.isNotEmpty()

    val isEmpty: Boolean get() = stack.isEmpty()

    /**
     * Records an executed action with a pre-execution screen snapshot to compute compensation.
     */
    fun record(
        action: AniobAction,
        screenBefore: AniobScreenState?,
        transactionId: String = "txn_${System.currentTimeMillis()}_${stack.size + 1}"
    ): ActionTransaction {
        val compensation = computeCompensation(action, screenBefore)
        val transaction = ActionTransaction(
            id = transactionId,
            originalAction = action,
            compensatingAction = compensation,
            targetDescription = action.targetDescription()
        )
        if (stack.size >= maxDepth) {
            stack.removeFirst()
        }
        stack.addLast(transaction)
        return transaction
    }

    /**
     * Computes the compensating (inverse) action for [action] given [screenBefore].
     */
    fun computeCompensation(action: AniobAction, screenBefore: AniobScreenState?): AniobAction? = when (action) {
        is AniobAction.InputText -> {
            // Find target node in pre-execution screen to restore original text
            val targetNode = screenBefore?.let { screen ->
                screen.nodes.firstOrNull { node ->
                    action.target.matches(node)
                }
            }
            val originalText = targetNode?.text.orEmpty()
            AniobAction.InputText(
                target = action.target,
                text = originalText,
                clearFirst = true
            )
        }

        is AniobAction.Swipe -> {
            val inverseDirection = when (action.direction) {
                SwipeDirection.UP -> SwipeDirection.DOWN
                SwipeDirection.DOWN -> SwipeDirection.UP
                SwipeDirection.LEFT -> SwipeDirection.RIGHT
                SwipeDirection.RIGHT -> SwipeDirection.LEFT
            }
            AniobAction.Swipe(
                direction = inverseDirection,
                container = action.container,
                distancePx = action.distancePx
            )
        }

        is AniobAction.Tap -> {
            // If the tapped element was a toggle/checkbox or switch, tapping again restores previous state
            val targetNode = screenBefore?.let { screen ->
                screen.nodes.firstOrNull { action.target.matches(it) }
            }
            val isToggleable = targetNode?.let {
                it.className.contains("Switch", ignoreCase = true) ||
                    it.className.contains("CheckBox", ignoreCase = true) ||
                    it.className.contains("ToggleButton", ignoreCase = true)
            } ?: false

            if (isToggleable) {
                AniobAction.Tap(target = action.target)
            } else {
                // Arbitrary taps are not unconditionally reversible without domain knowledge
                null
            }
        }

        else -> null
    }

    /**
     * Pops and returns the latest reversible compensation action, or null if empty / non-reversible.
     */
    fun popCompensation(): AniobAction? {
        while (stack.isNotEmpty()) {
            val txn = stack.removeLast()
            if (txn.compensatingAction != null) {
                return txn.compensatingAction
            }
        }
        return null
    }

    /**
     * Pops all reversible transactions in reverse order (LIFO) to yield a complete rollback plan.
     */
    fun popAllCompensations(): List<AniobAction> {
        val compensations = mutableListOf<AniobAction>()
        while (stack.isNotEmpty()) {
            val txn = stack.removeLast()
            if (txn.compensatingAction != null) {
                compensations.add(txn.compensatingAction)
            }
        }
        return compensations
    }

    /**
     * Clears all transactions.
     */
    fun clear() {
        stack.clear()
    }

    private fun AniobAction.targetDescription(): String? = when (this) {
        is AniobAction.Tap -> target.describe()
        is AniobAction.LongPress -> target.describe()
        is AniobAction.InputText -> target.describe()
        is AniobAction.Swipe -> container?.describe() ?: "screen"
        is AniobAction.OpenApp -> packageName
        else -> null
    }
}
