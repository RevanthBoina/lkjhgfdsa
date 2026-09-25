package com.aniob.app.ui

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class AniobViewModelExecutionWiringTest {

    @Test
    fun setFastPathEnabledSynchronizesRouterAndUiState() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.setFastPathEnabled(false)
        assertFalse("uiState.fastPathEnabled must be false", vm.uiState.value.fastPathEnabled)
        assertFalse("executionRouter.fastPathEnabled must be synced to false", vm.executionRouter.fastPathEnabled)

        vm.setFastPathEnabled(true)
        assertTrue("uiState.fastPathEnabled must be true", vm.uiState.value.fastPathEnabled)
        assertTrue("executionRouter.fastPathEnabled must be synced to true", vm.executionRouter.fastPathEnabled)
    }

    @Test
    fun queueFollowUpStoresSingleQueuedTaskAndCancelsGracefully() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.submitTask("First in-flight task")
        assertTrue("Task should be marked running", vm.uiState.value.isRunning)

        vm.queueFollowUp("Follow-up second task")
        assertEquals("Follow-up second task", vm.uiState.value.queuedPrompt)

        // Queueing another replaces or keeps single-owner queue
        vm.queueFollowUp("Follow-up third task")
        assertEquals("Follow-up third task", vm.uiState.value.queuedPrompt)

        vm.cancelQueuedFollowUp()
        assertNull("Queued prompt should be cleared after cancellation", vm.uiState.value.queuedPrompt)

        vm.stopCurrentTask()
    }

    @Test
    fun preflightOfflineShortcutExemption() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        // "turn on flashlight" or "open website https://example.com" should resolve as offline/intent shortcuts
        val doctorResult = vm.checkDoctorPreflight("open website https://example.com")
        assertTrue("Offline/intent shortcut must pass doctor preflight even without model downloads", doctorResult.allPassed)
    }
}
