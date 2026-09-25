package com.aniob.core.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowReducerTest {

    private val identity = OperationIdentity(
        taskId = "task_1",
        actionId = "action_1",
        generation = 1L,
        destinationKey = "https://example.com"
    )

    private val destination = Destination(
        handler = "example.com",
        url = "https://example.com",
        category = DestinationCategory.WEBSITE
    )

    private val action = WorkflowAction.OpenWebsite(
        actionId = "action_1",
        destination = destination
    )

    @Test
    fun testValidTransitionCycle() {
        val initial = WorkflowState.Draft("Open example")

        // 1. Ready
        val ready = WorkflowReducer.reduce(initial, WorkflowReducer.TransitionEvent.MarkReady(identity, action))
        assertTrue(ready is WorkflowState.Ready)

        // 2. Dispatching
        val dispatching = WorkflowReducer.reduce(ready, WorkflowReducer.TransitionEvent.StartDispatch(identity, action, null))
        assertTrue(dispatching is WorkflowState.Dispatching)

        // 3. User Handoff
        val waiting = WorkflowReducer.reduce(dispatching, WorkflowReducer.TransitionEvent.RequireUserHandoff(identity, destination, null))
        assertTrue(waiting is WorkflowState.WaitingForUser)

        // 4. Completed
        val result = WorkflowResult.UserReportedCompletion("Done in browser")
        val completed = WorkflowReducer.reduce(waiting, WorkflowReducer.TransitionEvent.Complete(identity, result))
        assertTrue(completed is WorkflowState.Completed)
        assertEquals("Completed", completed.stateName)
    }

    @Test
    fun testRejectsMismatchedIdentity() {
        val initial = WorkflowState.Dispatching(identity, action, null)
        val staleIdentity = identity.copy(generation = 2L)

        // Attempting to advance dispatch with stale generation must be rejected
        val unchanged = WorkflowReducer.reduce(
            initial,
            WorkflowReducer.TransitionEvent.RequireUserHandoff(staleIdentity, destination, null)
        )
        assertTrue("Mismatched generation must not advance state", unchanged is WorkflowState.Dispatching)
    }

    @Test
    fun testEffectUnknownOnInterruptedDispatch() {
        val dispatching = WorkflowState.Dispatching(identity, action, null)
        val unknown = WorkflowReducer.reduce(
            dispatching,
            WorkflowReducer.TransitionEvent.MarkEffectUnknown(identity, "Process terminated")
        )
        assertTrue(unknown is WorkflowState.EffectUnknown)
        assertEquals("EffectUnknown", unknown.stateName)
    }
}
