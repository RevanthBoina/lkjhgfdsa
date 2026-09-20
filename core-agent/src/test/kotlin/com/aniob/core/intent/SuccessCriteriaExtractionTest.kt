package com.aniob.core.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Success-criteria extraction is what lets a `Finish` be disproved deterministically. */
class SuccessCriteriaExtractionTest {

    @Test
    fun `quoted text becomes a must-contain assertion`() {
        val criteria = AniobTaskIntentClassifier.extractCriteria(
            "Open Settings and turn on \"Dark theme\""
        )
        assertTrue(criteria.mustContainText.contains("Dark theme"))
    }

    @Test
    fun `open app resolves to a package assertion when the catalog knows it`() {
        val criteria = AniobTaskIntentClassifier.extractCriteria(
            prompt = "Open Settings",
            appCatalog = mapOf("settings" to "com.android.settings")
        )
        assertTrue(criteria.mustShowPackage.contains("com.android.settings"))
    }

    @Test
    fun `unknown app name yields no package assertion`() {
        val criteria = AniobTaskIntentClassifier.extractCriteria(
            prompt = "Open Frobnicator",
            appCatalog = mapOf("settings" to "com.android.settings")
        )
        assertTrue(criteria.mustShowPackage.isEmpty())
    }

    @Test
    fun `grill answers are folded into text assertions`() {
        val criteria = AniobTaskIntentClassifier.extractCriteria(
            prompt = "Open Settings",
            grillAnswers = mapOf("which panel" to "Display")
        )
        assertTrue(criteria.mustContainText.contains("Display"))
    }

    @Test
    fun `device automation carries a minimum step floor`() {
        val criteria = AniobTaskIntentClassifier.extractCriteria("Tap submit button")
        assertEquals(1, criteria.minSteps)
        assertTrue(criteria.hasObservableCheck || criteria.minSteps > 0)
    }

    @Test
    fun `knowledge question carries no device criteria`() {
        val criteria = AniobTaskIntentClassifier.extractCriteria("What is the capital of France?")
        assertFalse(criteria.isFullyCheckable)
        assertEquals(0, criteria.minSteps)
    }

    @Test
    fun `duplicate quoted strings collapse`() {
        val criteria = AniobTaskIntentClassifier.extractCriteria("\"Done\" and again \"Done\"")
        assertEquals(1, criteria.mustContainText.count { it == "Done" })
    }
}