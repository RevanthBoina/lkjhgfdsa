package com.aniob.core

import com.aniob.core.domain.AniobAction
import com.aniob.core.verifier.AniobWatchdog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobWatchdogTest {

    @Test
    fun testLoopDetectedAfterThreeRepeats() {
        val watchdog = AniobWatchdog(loopThreshold = 3)
        val clickAction = AniobAction.Click(targetNodeId = 5)

        watchdog.record("hashA", clickAction)
        assertFalse(watchdog.isLoopDetected())

        watchdog.record("hashA", clickAction)
        assertFalse(watchdog.isLoopDetected())

        watchdog.record("hashA", clickAction)
        assertTrue("Loop should be detected on 3rd identical action on identical screen", watchdog.isLoopDetected())
    }

    @Test
    fun testDifferentActionsDoNotTriggerLoop() {
        val watchdog = AniobWatchdog(loopThreshold = 3)

        watchdog.record("hashA", AniobAction.Click(targetNodeId = 5))
        watchdog.record("hashA", AniobAction.Click(targetNodeId = 6))
        watchdog.record("hashA", AniobAction.Click(targetNodeId = 5))

        assertFalse(watchdog.isLoopDetected())
    }
}
