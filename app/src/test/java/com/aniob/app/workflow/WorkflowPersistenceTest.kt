package com.aniob.app.workflow

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.app.db.WorkflowEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class WorkflowPersistenceTest {

    private lateinit var app: AniobApplication
    private lateinit var repository: WorkflowRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repository = app.workflowRepository
    }

    @Test
    fun testPersistAndRetrieveWaitingWorkflow() = runTest {
        repository.persistDraft("task_persist_1", "act_1", 1L, "chatgpt.com", "https://chatgpt.com/")
        val entity = repository.getWorkflow("task_persist_1")
        assertNotNull(entity)
        assertEquals("Draft", entity?.state)
    }

    @Test
    fun testCrashDuringDispatchBecomesEffectUnknownOnStartup() = runTest {
        // Simulate a process crash mid-dispatch: entity left in 'Dispatching'
        val crashedEntity = WorkflowEntity(
            taskId = "task_crash_1",
            actionId = "act_crash",
            generation = 5L,
            state = "Dispatching",
            destinationKey = "https://example.com",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        app.database.workflowDao().insertOrUpdate(crashedEntity)

        val recovered = repository.checkAndRecoverOnStartup()
        assertNotNull(recovered)
        assertEquals("EffectUnknown", recovered?.state)
        assertEquals("EffectUnknown", recovered?.resultKind)
    }
}
