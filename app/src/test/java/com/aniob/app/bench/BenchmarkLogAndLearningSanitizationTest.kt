package com.aniob.app.bench

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.app.learning.AniobLearningPipelineImpl
import com.aniob.app.metrics.EventLogger
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.domain.TaskContext
import com.aniob.core.tools.AniobReplayEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class BenchmarkLogAndLearningSanitizationTest {

    @Test
    fun eventLoggerSanitizesBearerTokensAndSecrets() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val logger = EventLogger(app.database)

        val rawWithBearer = "Connecting with Authorization: Bearer sk-ant-api03-abcdef1234567890"
        val sanitizedBearer = logger.sanitize(rawWithBearer)
        assertFalse(sanitizedBearer.contains("abcdef1234567890"))
        assertTrue(sanitizedBearer.contains("Bearer [REDACTED]"))

        val rawWithSecret = "config update: apiKey=my_super_secret_omniroute_key_9999"
        val sanitizedSecret = logger.sanitize(rawWithSecret)
        assertFalse(sanitizedSecret.contains("my_super_secret_omniroute_key_9999"))
        assertTrue(sanitizedSecret.contains("apiKey=[REDACTED]"))

        val rawWithOtp = "verification received: otp=987654"
        val sanitizedOtp = logger.sanitize(rawWithOtp)
        assertFalse(sanitizedOtp.contains("987654"))
        assertTrue(sanitizedOtp.contains("otp=[REDACTED]"))
    }

    @Test
    fun learningPipelineRejectsUnverifiedTaskWithoutObservableCriteria() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val replayEngine = AniobReplayEngine()
        val pipeline = AniobLearningPipelineImpl(
            database = app.database,
            replayEngine = replayEngine
        )

        val screen = AniobScreenState(
            packageName = "com.test.app",
            nodes = listOf(AniobNode(id = 1, className = "android.widget.TextView", text = "Home", bounds = AniobRect(0, 0, 10, 10)))
        )
        val action = AniobAction.Tap(SemanticTarget.SomIndex(1))

        // Task with NO observable criteria
        val unverifiedCtx = TaskContext(
            instruction = "Unverified task with no observable criteria",
            taskId = "unverified_task_1",
            successCriteria = SuccessCriteria() // empty
        )

        pipeline.onVerifiedStep(unverifiedCtx, action, screen)
        pipeline.onTaskSuccess(unverifiedCtx, listOf(action))

        // Must NOT be registered in replay engine
        val found = replayEngine.findTrajectory("unverified task with no observable criteria")
        assertNull("Unverified outcome must NOT enter fastpath replay cache", found)
    }

    @Test
    fun learningPipelineAcceptsVerifiedTaskWithObservableCriteria() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val replayEngine = AniobReplayEngine()
        val pipeline = AniobLearningPipelineImpl(
            database = app.database,
            replayEngine = replayEngine
        )

        val screen = AniobScreenState(
            packageName = "com.android.settings",
            nodes = listOf(AniobNode(id = 1, className = "android.widget.TextView", text = "Display", bounds = AniobRect(0, 0, 10, 10)))
        )
        val action = AniobAction.Tap(SemanticTarget.SomIndex(1))

        val verifiedCtx = TaskContext(
            instruction = "Verified task with observable criteria",
            taskId = "verified_task_1",
            successCriteria = SuccessCriteria(mustContainText = listOf("Display")),
            lastScreenState = screen
        )

        pipeline.onVerifiedStep(verifiedCtx, action, screen)
        pipeline.onTaskSuccess(verifiedCtx, listOf(action))

        val found = replayEngine.findTrajectory("verified task with observable criteria")
        assertTrue("Verified outcome with observable criteria must enter fastpath replay cache", found != null)
    }
}
