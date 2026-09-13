package com.aniob.core

import com.aniob.core.grillme.AniobGrillMeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobGrillMeEngineTest {

    private val engine = AniobGrillMeEngine()

    @Test
    fun testSelfContainedTaskNeedsNoClarification() = runBlocking {
        val result = engine.evaluateTask("Open Settings")
        assertFalse(result.needsClarification)
        assertTrue(result.questions.isEmpty())
    }

    @Test
    fun testCabBookingTriggersClarification() = runBlocking {
        val result = engine.evaluateTask("Book a cab")
        assertTrue(result.needsClarification)
        assertTrue("Expected at least 2 questions", result.questions.size >= 2)
        val firstQuestion = result.questions.first()
        assertTrue("Expected destination question", firstQuestion.question.contains("destination", ignoreCase = true))
        assertTrue("Expected options", firstQuestion.options.isNotEmpty())
        assertTrue("Expected free text option", firstQuestion.allowFreeText)
    }
}
