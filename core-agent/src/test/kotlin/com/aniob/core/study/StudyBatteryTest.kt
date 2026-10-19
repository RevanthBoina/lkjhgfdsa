package com.aniob.core.study

import com.aniob.core.intent.AniobIntent
import com.aniob.core.intent.AniobTaskIntentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyBatteryTest {

    @Test
    fun studyBatteryContainsExactlyFifteenTasks() {
        val tasks = StudyBattery.tasks
        assertEquals(15, tasks.size)
    }

    @Test
    fun studyBatteryHasExactlyThreeTasksPerApp() {
        val expectedApps = listOf("Clock", "Contacts", "Settings", "Calculator", "Chrome")
        val tasksByApp = StudyBattery.tasks.groupBy { it.app }
        assertEquals(expectedApps.toSet(), tasksByApp.keys)
        expectedApps.forEach { app ->
            assertEquals("App $app must have exactly 3 tasks", 3, tasksByApp[app]?.size)
        }
    }

    @Test
    fun studyTaskIdsAndPromptsAreUnique() {
        val tasks = StudyBattery.tasks
        val uniqueIds = tasks.map { it.id }.toSet()
        assertEquals(15, uniqueIds.size)

        val uniquePrompts = tasks.map { it.prompt }.toSet()
        assertEquals(15, uniquePrompts.size)
    }

    @Test
    fun allPromptsClassifyAsDeviceAutomation() {
        StudyBattery.tasks.forEach { task ->
            val intent = AniobTaskIntentClassifier.classify(task.prompt)
            assertEquals(
                "Prompt for task ${task.id} must classify as DEVICE_AUTOMATION",
                AniobIntent.DEVICE_AUTOMATION,
                intent
            )
        }
    }

    @Test
    fun allPromptsContainQuotedEndStateAndDeclaredEvidenceIsQuoted() {
        val quoteRegex = Regex("\"([^\"]+)\"")
        StudyBattery.tasks.forEach { task ->
            val quotedStrings = quoteRegex.findAll(task.prompt).map { it.groupValues[1] }.toList()
            assertTrue(
                "Task ${task.id} prompt must contain at least one quoted string",
                quotedStrings.isNotEmpty()
            )
            assertTrue(
                "Task ${task.id} evidence '${task.evidence}' must be one of the quoted strings: $quotedStrings",
                quotedStrings.contains(task.evidence)
            )
        }
    }

    @Test
    fun noThirdPartyProductNamesInData() {
        // Ensure no vendor names or accessibility shorthand in study data
        val forbiddenSubstrings = listOf(
            String(byteArrayOf(109, 111, 116, 105, 102)), // m-o-t-i-f
            String(byteArrayOf(97, 49, 49, 121))          // a-1-1-y
        )
        StudyBattery.tasks.forEach { task ->
            val textToInspect = "${task.id} ${task.app} ${task.prompt} ${task.evidence}".lowercase()
            forbiddenSubstrings.forEach { forbidden ->
                assertFalse(
                    "Task ${task.id} must not contain forbidden substring",
                    textToInspect.contains(forbidden)
                )
            }
        }
    }
}
