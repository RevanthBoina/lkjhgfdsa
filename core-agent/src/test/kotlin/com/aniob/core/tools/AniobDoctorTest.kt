package com.aniob.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AniobDoctorTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "aniob_doctor_test_${System.currentTimeMillis()}").apply { mkdirs() }

    @Test
    fun `offline shortcuts must not require network or model downloads`() {
        val doctorResult = AniobDoctor.preflightCheck(
            taskPrompt = "turn on flashlight",
            targetPackage = null,
            isAccessibilityConnected = true,
            modelsDir = tempDir,
            installedModelId = null,
            isNetworkAvailable = false, // Offline!
            autoRouterMode = "auto",
            totalRamGb = 4,
            isAppInstalled = { true },
            isOfflineShortcut = true // Offline shortcut exemption!
        )

        assertTrue("Offline shortcut must pass preflight even without network or local models", doctorResult.allPassed)
        assertEquals("Readiness tier must be READY for offline shortcut", ReadinessTier.READY, doctorResult.readinessTier)
        val networkCheck = doctorResult.checks.find { it.name == "network_available" }
        assertTrue("Network check must pass when exempt as offline shortcut", networkCheck?.passed == true)
        val modelCheck = doctorResult.checks.find { it.name == "model_file_exists" }
        assertTrue("Model check must pass when exempt as offline shortcut", modelCheck?.passed == true)
    }

    @Test
    fun `non-shortcut task fails preflight when network is unavailable and no local model exists`() {
        val doctorResult = AniobDoctor.preflightCheck(
            taskPrompt = "normal task",
            targetPackage = null,
            isAccessibilityConnected = true,
            modelsDir = tempDir,
            installedModelId = null,
            isNetworkAvailable = false,
            autoRouterMode = "auto",
            totalRamGb = 4,
            isAppInstalled = { true },
            isOfflineShortcut = false
        )

        assertFalse("Must fail preflight when offline and no local model is installed", doctorResult.allPassed)
        assertEquals(ReadinessTier.NEEDS_SETUP, doctorResult.readinessTier)
    }

    @Test
    fun `accessibility disconnected fails critical check`() {
        val doctorResult = AniobDoctor.preflightCheck(
            taskPrompt = "normal task",
            targetPackage = null,
            isAccessibilityConnected = false,
            modelsDir = tempDir,
            installedModelId = null,
            isNetworkAvailable = true,
            autoRouterMode = "auto",
            totalRamGb = 4,
            isAppInstalled = { true },
            isOfflineShortcut = false
        )

        assertFalse("Missing accessibility must fail critical check", doctorResult.allPassed)
        val a11yCheck = doctorResult.checks.find { it.name == "accessibility_connected" }
        assertFalse(a11yCheck?.passed == true)
    }
}
