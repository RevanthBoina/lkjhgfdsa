package com.aniob.core

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.verifier.AniobWatchdog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobWatchdogTest {

    @Test
    fun testLoopDetectedAfterThreeRepeats() {
        val watchdog = AniobWatchdog(loopThreshold = 3)
        val clickAction = AniobAction.Tap(SemanticTarget.SomIndex(5))

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

        watchdog.record("hashA", AniobAction.Tap(SemanticTarget.SomIndex(5)))
        watchdog.record("hashA", AniobAction.Tap(SemanticTarget.SomIndex(6)))
        watchdog.record("hashA", AniobAction.Tap(SemanticTarget.SomIndex(5)))

        assertFalse(watchdog.isLoopDetected())
    }

    @Test
    fun testSameActionAcrossChangingScreensTriggersLoop() {
        // Pin: repeated same action 3x is a loop even if the screen hash re-renders.
        val watchdog = AniobWatchdog(loopThreshold = 3)
        val tap = AniobAction.Tap(SemanticTarget.Text("Submit"))

        watchdog.record("hashA", tap)
        watchdog.record("hashB", tap)
        watchdog.record("hashC", tap)

        assertTrue("Same action N=3 on changing screens should trigger loop", watchdog.isLoopDetected())
    }

    @Test
    fun testSameScreenWithDifferentActionsDoesNotTriggerLoop() {
        // Same empty screen, but distinct actions -> user is retrying, not stuck.
        val watchdog = AniobWatchdog(loopThreshold = 3)

        watchdog.record("hashZ", AniobAction.Tap(SemanticTarget.Text("Retry")))
        watchdog.record("hashZ", AniobAction.Swipe(direction = com.aniob.core.domain.SwipeDirection.UP, distancePx = 300))
        watchdog.record("hashZ", AniobAction.PressKey(key = com.aniob.core.domain.KeyType.BACK))

        assertFalse("Same screen with distinct actions should not trigger loop", watchdog.isLoopDetected())
    }

    @Test
    fun testResetClearsHistory() {
        val watchdog = AniobWatchdog(loopThreshold = 3)
        watchdog.record("hashA", AniobAction.Tap(SemanticTarget.SomIndex(1)))
        watchdog.reset()
        watchdog.record("hashB", AniobAction.Tap(SemanticTarget.SomIndex(2)))
        watchdog.record("hashC", AniobAction.Tap(SemanticTarget.SomIndex(3)))
        watchdog.record("hashD", AniobAction.Tap(SemanticTarget.SomIndex(4)))
        assertFalse(watchdog.isLoopDetected())
    }
}
