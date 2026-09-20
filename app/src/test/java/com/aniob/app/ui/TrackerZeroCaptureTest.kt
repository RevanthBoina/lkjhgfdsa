package com.aniob.app.ui

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Addition C: opening the Tracker screen must stay a pure view over already-recorded Room /
 * EventLogger data. No screen capture and no model call may happen as a side effect of opening it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class TrackerZeroCaptureTest {

    @Test
    fun openingTrackerPerformsZeroScreenReads() {
        val vm = AniobViewModel(ApplicationProvider.getApplicationContext())

        val before = vm.screenCaptureCount
        vm.setShowTrackerSheet(true)

        assertEquals("Tracker must not capture the screen on open", before, vm.screenCaptureCount)
        assertEquals(1, vm.trackerOpenCount())
    }

    @Test
    fun closingTrackerPerformsZeroScreenReads() {
        val vm = AniobViewModel(ApplicationProvider.getApplicationContext())

        vm.setShowTrackerSheet(true)
        val before = vm.screenCaptureCount
        vm.setShowTrackerSheet(false)

        assertEquals(before, vm.screenCaptureCount)
        assertEquals(1, vm.trackerOpenCount())
    }
}