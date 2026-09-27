package com.aniob.core.recovery

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.gates.GateBFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentContextManagerTest {

    @Test
    fun testIncidentLifecycleAndEscalation() {
        val manager = IncidentContextManager()
        assertNull(manager.renderObservationPrefix())

        // 1. Record safety net incident
        val incident = manager.recordFailure(
            kind = IncidentKind.SAFETY_NET,
            category = GateBFailureCategory.TARGET_OCCUPIED,
            reason = "Dialog obscured target",
            sanitizedAction = "tap(100, 200)",
            actionDescription = "submit button",
            stepNumber = 4
        )

        assertEquals(1, incident.consecutiveFailures)
        assertTrue(incident.userFacingExplanation().contains("I didn't do it"))
        assertNotNull(manager.renderObservationPrefix())
        assertTrue(manager.renderObservationPrefix()!!.contains("OPEN INCIDENT - ESCALATION LEVEL 1"))
        assertTrue(manager.renderObservationPrefix()!!.contains("OPERATIONAL RULES"))

        // 2. Second failure escalates
        val escalated = manager.recordFailure(
            kind = IncidentKind.EXEC_ERROR,
            category = GateBFailureCategory.GENERAL,
            reason = "App crashed",
            sanitizedAction = "tap(100, 200)",
            actionDescription = "submit button",
            stepNumber = 5
        )
        assertEquals(2, escalated.consecutiveFailures)
        assertTrue(escalated.userFacingExplanation().contains("I tried and it broke"))
        assertTrue(manager.renderObservationPrefix()!!.contains("OPEN INCIDENT - ESCALATION LEVEL 2"))

        // 3. Successful action closes incident
        val notice = manager.onActionDispatchedSuccessfully(AniobAction.Tap(SemanticTarget.SomIndex(1)))
        assertNotNull(notice)
        assertTrue(notice!!.contains("CLOSED INCIDENT NOTICE"))
        assertNull(manager.currentIncident)

        // Notice emitted for one turn
        val prefix = manager.renderObservationPrefix()
        assertNotNull(prefix)
        assertTrue(prefix!!.contains("CLOSED INCIDENT NOTICE"))

        // Next turn has no prefix
        assertNull(manager.renderObservationPrefix())
    }
}
