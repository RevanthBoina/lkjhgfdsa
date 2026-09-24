package com.aniob.app.learning

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.TaskContext
import com.aniob.core.tools.AniobReplayEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class AniobLearningPipelineTest {

    private fun sampleScreen(text: String): AniobScreenState {
        return AniobScreenState(
            packageName = "com.test.learning",
            nodes = listOf(
                AniobNode(
                    id = 1,
                    className = "android.widget.Button",
                    text = text,
                    isClickable = true,
                    isEnabled = true,
                    isVisibleToUser = true,
                    bounds = AniobRect(0, 0, 100, 50)
                )
            )
        )
    }

    @Test
    fun rehearsalKeyedByTaskIdAndPersistsOnSuccess() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val replayEngine = AniobReplayEngine()
        val pipeline = AniobLearningPipelineImpl(
            database = app.database,
            replayEngine = replayEngine
        )

        val taskId = "task_uuid_123"
        val ctx = TaskContext(
            instruction = "Open settings and tap display",
            taskId = taskId
        )
        val action1 = AniobAction.Tap(SemanticTarget.Text("Display"))
        val screen1 = sampleScreen("Display")

        pipeline.onVerifiedStep(ctx, action1, screen1)

        // Verify task success persists to replay engine and room
        pipeline.onTaskSuccess(ctx, listOf(action1))

        val found = replayEngine.findTrajectory("open settings and tap display")
        assertNotNull("Replay engine should contain persisted trajectory", found)
        assertEquals(1, found?.steps?.size)

        // Verify Room persistence
        val fromDb = app.database.appKnowledgeBaseDao().findBySignature("open settings and tap display")
        assertNotNull("Database should contain persisted macro", fromDb)
        assertTrue(fromDb?.macroStepsJson?.contains("Display") == true)
    }

    @Test
    fun onTaskEndClearsRehearsalBuffer() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val replayEngine = AniobReplayEngine()
        val pipeline = AniobLearningPipelineImpl(
            database = app.database,
            replayEngine = replayEngine
        )

        val taskId = "task_cancel_456"
        val ctx = TaskContext(
            instruction = "Some cancelled task",
            taskId = taskId
        )
        pipeline.onVerifiedStep(ctx, AniobAction.Tap(SemanticTarget.Text("Foo")), sampleScreen("Foo"))

        // Terminal end without success (e.g. cancelled/stopped)
        pipeline.onTaskEnd(taskId)

        // A subsequent onTaskSuccess call should find nothing in rehearsal
        pipeline.onTaskSuccess(ctx, emptyList())
        val found = replayEngine.findTrajectory("some cancelled task")
        assertEquals("Rehearsal must be empty after onTaskEnd", null, found)
    }

    @Test
    fun hydrationBarrierRestoresPersistedTrajectories() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val replayEngine = AniobReplayEngine()
        val pipeline = AniobLearningPipelineImpl(
            database = app.database,
            replayEngine = replayEngine
        )

        val count = pipeline.hydrate()
        val awaited = pipeline.awaitHydration()
        assertEquals(count, awaited)
    }
}
