package com.aniob.app.learning

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.app.db.AppKnowledgeBaseEntity
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.tools.AniobReplayEngine
import com.aniob.core.tools.AniobTrajectoryCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class AniobFineTuningDatasetBuilderTest {

    @Test
    fun piiAndSecretsAreScrubbed() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val builder = AniobFineTuningDatasetBuilder(
            database = app.database,
            outputDir = File(app.cacheDir, "dataset_test")
        )

        val textWithPii = "Send email to john.doe@example.com, call +1-555-123-4567, otp: 849201, key=sk-proj-9876543210abcdef"
        val redacted = builder.redactText(textWithPii)

        assertFalse("Raw email must be scrubbed", redacted.contains("john.doe@example.com"))
        assertTrue("Email replacement tag must be present", redacted.contains("[EMAIL_REDACTED]"))

        assertFalse("Raw phone must be scrubbed", redacted.contains("555-123-4567"))
        assertTrue("Phone replacement tag must be present", redacted.contains("[PHONE_REDACTED]"))

        assertFalse("Raw OTP must be scrubbed", redacted.contains("849201"))
        assertTrue("OTP replacement tag must be present", redacted.contains("[OTP_REDACTED]"))

        assertFalse("Raw API key must be scrubbed", redacted.contains("9876543210abcdef"))
        assertTrue("Key replacement tag must be present", redacted.contains("[KEY_REDACTED]"))
    }

    @Test
    fun builderGeneratesValidDatasetAndExcludesBenchmarkTasks() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val db = app.database
        val outDir = File(app.cacheDir, "dataset_build_output")

        // Populate a verified non-benchmark macro
        val verifiedTrajectory = AniobReplayEngine.ReplayTrajectory(
            taskSignature = "toggle dark mode in display",
            packageName = "com.android.settings",
            steps = listOf(
                AniobReplayEngine.ReplayStep(
                    expectedFingerprint = "fp_settings_main",
                    action = AniobAction.Tap(SemanticTarget.Text("Display"))
                ),
                AniobReplayEngine.ReplayStep(
                    expectedFingerprint = "fp_settings_display",
                    action = AniobAction.Tap(SemanticTarget.Text("Dark theme"))
                )
            )
        )
        db.appKnowledgeBaseDao().saveMacro(
            AppKnowledgeBaseEntity(
                taskSignature = "toggle dark mode in display",
                packageName = "com.android.settings",
                macroStepsJson = AniobTrajectoryCodec.encode(verifiedTrajectory),
                successCount = 5
            )
        )

        // Populate a benchmark task (must be excluded from training dataset)
        val benchmarkTrajectory = AniobReplayEngine.ReplayTrajectory(
            taskSignature = "open settings",
            packageName = "com.android.settings",
            steps = listOf(
                AniobReplayEngine.ReplayStep(
                    expectedFingerprint = "fp_home",
                    action = AniobAction.OpenApp("com.android.settings")
                )
            )
        )
        db.appKnowledgeBaseDao().saveMacro(
            AppKnowledgeBaseEntity(
                taskSignature = "open settings",
                packageName = "com.android.settings",
                macroStepsJson = AniobTrajectoryCodec.encode(benchmarkTrajectory),
                successCount = 10
            )
        )

        val builder = AniobFineTuningDatasetBuilder(db, outDir)
        val report = builder.buildDataset()

        assertTrue("Benchmark traces must be excluded", report.benchmarkTracesExcluded >= 1)
        assertTrue("Verified traces must be accepted", report.verifiedTracesAccepted > 0)
        assertTrue("Train file must exist", report.trainFile.exists())
        assertTrue("Val file must exist", report.valFile.exists())
        assertTrue("Test file must exist", report.testFile.exists())

        // Verify JSONL content formatting
        val trainLines = report.trainFile.readLines()
        assertTrue("Train JSONL must have entries", trainLines.isNotEmpty())
        assertTrue("Line must be valid JSON with instruction", trainLines.first().contains("\"instruction\""))
        assertTrue("Line must not contain benchmark task 'open settings'", trainLines.none { it.contains("Task: open settings") })
    }
}
