package com.aniob.core.execution

import com.aniob.core.domain.*
import org.junit.Assert.*
import org.junit.Test

class AniobActionTransactionTest {

    @Test
    fun testInputTextReversibility() {
        val stack = AniobActionTransactionStack()
        val node = AniobNode(
            id = 1,
            className = "android.widget.EditText",
            text = "Old Title",
            viewId = "com.test:id/title"
        )
        val screen = AniobScreenState(packageName = "com.test", nodes = listOf(node))

        val action = AniobAction.InputText(
            target = SemanticTarget.ResourceId("com.test:id/title"),
            text = "New Title",
            clearFirst = true
        )

        val txn = stack.record(action, screen)
        assertTrue(txn.isReversible)
        assertNotNull(txn.compensatingAction)

        val compensation = txn.compensatingAction as AniobAction.InputText
        assertEquals("Old Title", compensation.text)
        assertTrue(compensation.clearFirst)

        val popped = stack.popCompensation() as AniobAction.InputText
        assertEquals("Old Title", popped.text)
        assertTrue(stack.isEmpty)
    }

    @Test
    fun testSwipeReversibility() {
        val stack = AniobActionTransactionStack()
        val action = AniobAction.Swipe(direction = SwipeDirection.UP, distancePx = 500)
        val txn = stack.record(action, null)

        assertTrue(txn.isReversible)
        val compensation = txn.compensatingAction as AniobAction.Swipe
        assertEquals(SwipeDirection.DOWN, compensation.direction)
        assertEquals(500, compensation.distancePx)
    }

    @Test
    fun testToggleReversibility() {
        val stack = AniobActionTransactionStack()
        val switchNode = AniobNode(
            id = 2,
            className = "android.widget.Switch",
            text = "Wi-Fi",
            viewId = "com.android.settings:id/switch_widget"
        )
        val screen = AniobScreenState(packageName = "com.android.settings", nodes = listOf(switchNode))

        val action = AniobAction.Tap(target = SemanticTarget.ResourceId("com.android.settings:id/switch_widget"))
        val txn = stack.record(action, screen)

        assertTrue(txn.isReversible)
        val compensation = txn.compensatingAction as AniobAction.Tap
        assertEquals(action.target, compensation.target)
    }

    @Test
    fun testMultipleActionRollbackPlan() {
        val stack = AniobActionTransactionStack()

        val textAction = AniobAction.InputText(
            target = SemanticTarget.SomIndex(1),
            text = "Hello"
        )
        val swipeAction = AniobAction.Swipe(direction = SwipeDirection.LEFT)

        stack.record(textAction, null)
        stack.record(swipeAction, null)

        val rollbacks = stack.popAllCompensations()
        assertEquals(2, rollbacks.size)
        // LIFO order: Swipe compensation first, then InputText compensation
        assertTrue(rollbacks[0] is AniobAction.Swipe)
        assertEquals(SwipeDirection.RIGHT, (rollbacks[0] as AniobAction.Swipe).direction)
        assertTrue(rollbacks[1] is AniobAction.InputText)
        assertTrue(stack.isEmpty)
    }
}
