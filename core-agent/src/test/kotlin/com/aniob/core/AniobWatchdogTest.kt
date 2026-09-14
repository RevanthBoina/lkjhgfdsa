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

    @Test
    fun testSameActionAcrossChangingScreensTriggersLoop() {
        // Pin: repeated same action 3x is a loop even if the screen hash re-renders.
        val watchdog = AniobWatchdog(loopThreshold = 3)
        val tap = AniobAction.Tap(x = 100, y = 200)

        watchdog.record("hashA", tap)
        watchdog.record("hashB", tap)
        watchdog.record("hashC", tap)

        assertTrue("Same action N=3 on changing screens should trigger loop", watchdog.isLoopDetected())
    }

    @Test
    fun testSameScreenWithDifferentActionsDoesNotTriggerLoop() {
        // Same empty screen, but distinct actions -> user is retrying, not stuck.
        val watchdog = AniobWatchdog(loopThreshold = 3)

        watchdog.record("hashZ", AniobAction.Tap(x = 1, y = 2))
        watchdog.record("hashZ", AniobAction.Swipe(direction = com.aniob.core.domain.SwipeDirection.UP, distancePx = 300))
        watchdog.record("hashZ", AniobAction.PressKey(key = com.aniob.core.domain.KeyType.BACK))

        assertFalse("Same screen with distinct actions should not trigger loop", watchdog.isLoopDetected())
    }

    @Test
    fun testResetClearsHistory() {
        val watchdog = AniobWatchdog(loopThreshold = 3)
        watchdog.record("hashA", AniobAction.Click(targetNodeId = 1))
        watchdog.reset()
        watchdog.record("hashB", AniobAction.Click(targetNodeId = 2))
        watchdog.record("hashC", AniobAction.Click(targetNodeId = 3))
        watchdog.record("hashD", AniobAction.Click(targetNodeId = 4))
        assertFalse(watchdog.isLoopDetected())
    }
}
