package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogOscillationTest {

    @Test
    fun testOscillatingABSequenceDetected() {
        val watchdog = AniobWatchdog(loopThreshold = 3)
        val tapA = AniobAction.Tap(SemanticTarget.Text("Option A"))
        val tapB = AniobAction.Tap(SemanticTarget.Text("Option B"))

        watchdog.record("hash1", tapA)
        assertFalse(watchdog.isLoopDetected())
        watchdog.record("hash2", tapB)
        assertFalse(watchdog.isLoopDetected())
        watchdog.record("hash1", tapA)
        assertFalse(watchdog.isLoopDetected())
        watchdog.record("hash2", tapB)
        // Now we have A, B, A, B -> oscillation detected!
        assertTrue(watchdog.isLoopDetected())
        assertTrue(watchdog.isOscillating())
    }

    @Test
    fun testNonOscillatingSequenceNotDetected() {
        val watchdog = AniobWatchdog(loopThreshold = 3)
        val tapA = AniobAction.Tap(SemanticTarget.Text("Option A"))
        val tapB = AniobAction.Tap(SemanticTarget.Text("Option B"))
        val tapC = AniobAction.Tap(SemanticTarget.Text("Option C"))

        watchdog.record("hash1", tapA)
        watchdog.record("hash2", tapB)
        watchdog.record("hash3", tapC)
        assertFalse(watchdog.isLoopDetected())
    }

    @Test
    fun testRepetitionThresholdStillWorks() {
        val watchdog = AniobWatchdog(loopThreshold = 3)
        val tap = AniobAction.Tap(SemanticTarget.Text("Submit"))

        watchdog.record("hash1", tap)
        watchdog.record("hash1", tap)
        assertFalse(watchdog.isLoopDetected())
        watchdog.record("hash1", tap)
        assertTrue(watchdog.isLoopDetected())
    }
}
