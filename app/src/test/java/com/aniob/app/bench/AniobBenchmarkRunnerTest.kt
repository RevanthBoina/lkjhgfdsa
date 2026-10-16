package com.aniob.app.bench

import com.aniob.app.AniobApplication
import com.aniob.app.model.AniobLlamaCppEngineDriver
import com.aniob.core.bench.AniobBenchmarkManifest
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.execution.StepPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class AniobBenchmarkRunnerTest {

    private fun mockScreenWithContent(pkg: String, vararg texts: String) = AniobScreenState(
        packageName = pkg,
        activityName = "$pkg.MainActivity",
        treeHash = "hash_${pkg}_${texts.joinToString("_")}",
        nodes = texts.mapIndexed { idx, txt ->
            AniobNode(
                id = idx + 1,
                className = "android.widget.TextView",
                text = txt,
                bounds = AniobRect(10, idx * 50, 200, (idx + 1) * 50),
                isClickable = true
            )
        }
    )

    @Test
    fun benchmarkRunnerExecutesAllTasksAcrossRoundsWithAccurateMetrics() = runBlocking {
        // Driver that emits valid actions
        val driver = AniobLlamaCppEngineDriver(
            simulatedGenerator = { prompt ->
                if (prompt.contains("Battery")) {
                    "{\"action\": \"tap\", \"target\": {\"som_index\": 6}}"
                } else {
                    "{\"action\": \"finish\", \"summary\": \"done\"}"
                }
            }
        )

        val runner = AniobBenchmarkRunner(
            candidateName = "Gemma 4 E2B Q4_K_M (Simulated)",
            runtimeInfo = "llama.cpp b3950 (arm64-v8a)",
            engine = driver,
            cooldownMs = 0L // Fast execution in unit test
        )

        // Mock dispatcher that simulates screen transition to expected state
        val dispatcher: suspend (AniobBenchmarkManifest.BenchmarkTask, Int, AniobAction) -> StepPipeline.DispatchOutcome =
            { task, stepIndex, action ->
                val targetPkg = task.observableCriteria.mustShowPackage.firstOrNull() ?: task.initialScreenState.packageName
                val screenAfter = when (task.taskId) {
                    "bench_01_open_settings_shortcut" -> mockScreenWithContent("com.android.settings", "Settings", "Network")
                    "bench_02_open_clock_shortcut" -> mockScreenWithContent("com.google.android.deskclock", "Alarm", "Clock")
                    "bench_03_open_calculator_shortcut" -> mockScreenWithContent("com.google.android.calculator", "1", "2")
                    "bench_04_find_battery_setting" -> mockScreenWithContent("com.android.settings", "Battery", "Usage", "Battery Saver")
                    "bench_05_find_display_brightness" -> mockScreenWithContent("com.android.settings", "Display", "Brightness")
                    "bench_06_locate_about_phone" -> mockScreenWithContent("com.android.settings", "About phone", "Device info")
                    "bench_07_view_installed_apps" -> mockScreenWithContent("com.android.settings", "Apps", "All apps", "App info")
                    "bench_08_navigate_alarm_tab" -> mockScreenWithContent("com.google.android.deskclock", "Alarm", "Add alarm")
                    "bench_09_find_sound_vibration" -> mockScreenWithContent("com.android.settings", "Sound", "Vibration")
                    "bench_10_search_setting_navigation" -> mockScreenWithContent("com.android.settings", "Wi-Fi", "Connected")
                    else -> task.initialScreenState
                }
                StepPipeline.DispatchOutcome(dispatched = true, screenAfter = screenAfter)
            }

        val summary = runner.runBenchmark(
            tasks = AniobBenchmarkManifest.TASKS,
            modelPath = "/tmp/fake_model.gguf",
            roundsPerTask = 3,
            stepDispatcher = dispatcher
        )

        assertEquals("Should run 10 tasks * 3 rounds = 30 attempts", 30, summary.totalAttempts)
        assertEquals("Shortcut pass rate should be 100%", 1.0, summary.shortcutPassRate, 0.01)
        assertTrue("Model pass rate should be > 0.8", summary.modelGenerationPassRate >= 0.8)
        assertTrue("p95 step latency must be positive", summary.p95StepLatencyMs >= 0)
        assertEquals("No memory failures in clean run", 0, summary.memoryFailuresCount)
        assertEquals("No timeouts in clean run", 0, summary.timeoutsCount)
        assertTrue("Candidate should qualify under benchmark rules", summary.qualifyingCandidate)
    }

    @Test
    fun shortcutSuccessIsNotCountedAsModelCapability() = runBlocking {
        // An engine that FAILS on all model generation prompts
        val failingDriver = AniobLlamaCppEngineDriver(
            simulatedGenerator = { "" } // empty generation
        )

        val runner = AniobBenchmarkRunner(
            candidateName = "Failing Engine",
            runtimeInfo = "test",
            engine = failingDriver,
            cooldownMs = 0L
        )

        val dispatcher: suspend (AniobBenchmarkManifest.BenchmarkTask, Int, AniobAction) -> StepPipeline.DispatchOutcome =
            { task, _, _ ->
                val targetPkg = task.observableCriteria.mustShowPackage.firstOrNull() ?: task.initialScreenState.packageName
                StepPipeline.DispatchOutcome(
                    dispatched = true,
                    screenAfter = mockScreenWithContent(targetPkg, "Settings", "Network")
                )
            }

        val summary = runner.runBenchmark(
            tasks = AniobBenchmarkManifest.TASKS,
            modelPath = "/tmp/fake.gguf",
            roundsPerTask = 1,
            stepDispatcher = dispatcher
        )

        assertEquals("Shortcuts succeed (1.0)", 1.0, summary.shortcutPassRate, 0.01)
        assertEquals("Model capability MUST be 0.0 when engine fails, NOT inflated by shortcuts", 0.0, summary.modelGenerationPassRate, 0.01)
        assertFalse("Candidate must NOT qualify when model capability fails", summary.qualifyingCandidate)
    }
}
