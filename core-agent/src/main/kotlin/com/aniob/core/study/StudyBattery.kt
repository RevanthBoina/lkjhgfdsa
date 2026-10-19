package com.aniob.core.study

/**
 * Represents an individual evaluation task in the empirical study battery.
 *
 * @property id Unique identifier for the study task.
 * @property app The target Android application under test.
 * @property prompt Natural language instruction given to the agent.
 * @property evidence Expected textual evidence on screen confirming deterministic success.
 */
data class StudyTask(
    val id: String,
    val app: String,
    val prompt: String,
    val evidence: String
)

/**
 * 15-Task Evaluation Battery for Empirical Research Study.
 *
 * Covers 5 standard stock applications with 3 tasks each:
 * - Clock (alarms, timers)
 * - Contacts (create, search, edit)
 * - Settings (Wi-Fi, Battery, Bluetooth)
 * - Calculator (multiplication, addition)
 * - Chrome (tabs, downloads, history)
 *
 * All prompts start with an imperative verb ("Open") to deterministically qualify
 * as [com.aniob.core.intent.AniobIntent.DEVICE_AUTOMATION] and quote end-state evidence.
 */
object StudyBattery {

    val tasks: List<StudyTask> = listOf(
        // Clock
        StudyTask(
            id = "study_01_clock_alarm_730am",
            app = "Clock",
            prompt = """Open Clock and set an alarm for 7:30 AM, leaving the alarm list showing "7:30 AM".""",
            evidence = "7:30 AM"
        ),
        StudyTask(
            id = "study_02_clock_alarm_615pm",
            app = "Clock",
            prompt = """Open Clock and set an alarm for 6:15 PM, leaving the alarm list showing "6:15 PM".""",
            evidence = "6:15 PM"
        ),
        StudyTask(
            id = "study_03_clock_timer_5min",
            app = "Clock",
            prompt = """Open Clock and start a 5 minute timer, leaving the running timer showing "Pause".""",
            evidence = "Pause"
        ),

        // Contacts
        StudyTask(
            id = "study_04_contacts_create_john_study",
            app = "Contacts",
            prompt = """Open Contacts and create a new contact named "John Study" with the phone number 9876543210.""",
            evidence = "John Study"
        ),
        StudyTask(
            id = "study_05_contacts_search_john_study",
            app = "Contacts",
            prompt = """Open Contacts and search for "John Study", leaving the result on screen.""",
            evidence = "John Study"
        ),
        StudyTask(
            id = "study_06_contacts_add_email_john_study",
            app = "Contacts",
            prompt = """Open Contacts and add the email study@example.com to "John Study", leaving the contact card showing "study@example.com".""",
            evidence = "study@example.com"
        ),

        // Settings
        StudyTask(
            id = "study_07_settings_wifi",
            app = "Settings",
            prompt = """Open Settings and open Wi-Fi settings, leaving the screen showing "Wi-Fi".""",
            evidence = "Wi-Fi"
        ),
        StudyTask(
            id = "study_08_settings_battery",
            app = "Settings",
            prompt = """Open Settings and open the battery screen, leaving the screen showing "Battery".""",
            evidence = "Battery"
        ),
        StudyTask(
            id = "study_09_settings_bluetooth",
            app = "Settings",
            prompt = """Open Settings and open Bluetooth settings, leaving the screen showing "Bluetooth".""",
            evidence = "Bluetooth"
        ),

        // Calculator
        StudyTask(
            id = "study_10_calculator_multiply_12_8",
            app = "Calculator",
            prompt = """Open Calculator and compute 12 times 8, leaving the result "96" on screen.""",
            evidence = "96"
        ),
        StudyTask(
            id = "study_11_calculator_add_145_55",
            app = "Calculator",
            prompt = """Open Calculator and compute 145 plus 55, leaving the result "200" on screen.""",
            evidence = "200"
        ),
        StudyTask(
            id = "study_12_calculator_multiply_7_6",
            app = "Calculator",
            prompt = """Open Calculator and compute 7 times 6, leaving the result "42" on screen.""",
            evidence = "42"
        ),

        // Chrome
        StudyTask(
            id = "study_13_chrome_new_tab",
            app = "Chrome",
            prompt = """Open Chrome and open a new tab, leaving the page showing "New tab".""",
            evidence = "New tab"
        ),
        StudyTask(
            id = "study_14_chrome_downloads",
            app = "Chrome",
            prompt = """Open Chrome and open the Downloads page, leaving it showing "Downloads".""",
            evidence = "Downloads"
        ),
        StudyTask(
            id = "study_15_chrome_history",
            app = "Chrome",
            prompt = """Open Chrome and open the History page, leaving it showing "History".""",
            evidence = "History"
        )
    )

    fun getTasksForApp(app: String): List<StudyTask> = tasks.filter { it.app.equals(app, ignoreCase = true) }

    fun getTaskById(id: String): StudyTask? = tasks.find { it.id == id }
}
