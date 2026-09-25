package com.aniob.app.workflow

import com.aniob.app.db.AniobDatabase
import com.aniob.app.db.WorkflowDao
import com.aniob.app.db.WorkflowEntity
import com.aniob.core.workflow.Destination
import com.aniob.core.workflow.OperationIdentity
import com.aniob.core.workflow.WorkflowResult
import kotlinx.coroutines.flow.Flow

/**
 * WorkflowRepository: durable persistence for workflow operations, waiting records,
 * and external receipts.
 *
 * Invariant: Never auto-executes on restore. Dispatches interrupted by process death
 * are restored as EffectUnknown rather than silently replayed.
 */
class WorkflowRepository(
    private val database: AniobDatabase
) {
    private val dao: WorkflowDao get() = database.workflowDao()

    suspend fun getWorkflow(taskId: String): WorkflowEntity? {
        return dao.getWorkflow(taskId)
    }

    suspend fun getLatestWorkflow(): WorkflowEntity? {
        return dao.getLatestWorkflow()
    }

    fun getWaitingWorkflows(): Flow<List<WorkflowEntity>> {
        return dao.getWaitingWorkflows()
    }

    suspend fun getLatestWaitingWorkflow(): WorkflowEntity? {
        return dao.getLatestWaitingWorkflow()
    }

    suspend fun persistDraft(
        taskId: String,
        actionId: String,
        generation: Long,
        destinationKey: String,
        destinationUrl: String?
    ) {
        val entity = WorkflowEntity(
            taskId = taskId,
            actionId = actionId,
            generation = generation,
            state = "Draft",
            destinationKey = destinationKey,
            destinationUrl = destinationUrl,
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdate(entity)
    }

    suspend fun persistDispatching(
        identity: OperationIdentity,
        payloadHash: String?
    ) {
        val existing = dao.getWorkflow(identity.taskId)
        val entity = WorkflowEntity(
            taskId = identity.taskId,
            actionId = identity.actionId,
            generation = identity.generation,
            state = "Dispatching",
            destinationKey = identity.destinationKey,
            destinationUrl = existing?.destinationUrl,
            payloadHash = payloadHash ?: identity.approvedPayloadHash,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdate(entity)
    }

    suspend fun persistWaitingForUser(
        identity: OperationIdentity,
        destination: Destination,
        brief: String? = null
    ) {
        val existing = dao.getWorkflow(identity.taskId)
        val entity = WorkflowEntity(
            taskId = identity.taskId,
            actionId = identity.actionId,
            generation = identity.generation,
            state = "WaitingForUser",
            destinationKey = destination.destinationKey,
            destinationUrl = destination.url,
            payloadHash = identity.approvedPayloadHash,
            evidence = brief,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdate(entity)
    }

    suspend fun persistResult(
        identity: OperationIdentity,
        result: WorkflowResult
    ) {
        val existing = dao.getWorkflow(identity.taskId)
        val (state, resultKind, evidence) = when (result) {
            is WorkflowResult.LaunchAccepted -> Triple("ResultReady", "LaunchAccepted", result.receiptId)
            is WorkflowResult.WaitingForUser -> Triple("WaitingForUser", "WaitingForUser", result.reason)
            is WorkflowResult.CompletedWithEvidence -> Triple("Completed", "CompletedWithEvidence", "${result.evidenceKind}: ${result.detail}")
            is WorkflowResult.UserReportedCompletion -> Triple("Completed", "UserReportedCompletion", result.userNotes)
            is WorkflowResult.Failed -> Triple("Failed", "Failed", result.errorReason)
            is WorkflowResult.Cancelled -> Triple("Cancelled", "Cancelled", result.reason)
            is WorkflowResult.EffectUnknown -> Triple("EffectUnknown", "EffectUnknown", result.details)
        }

        val entity = WorkflowEntity(
            taskId = identity.taskId,
            actionId = identity.actionId,
            generation = identity.generation,
            state = state,
            destinationKey = identity.destinationKey,
            destinationUrl = existing?.destinationUrl,
            payloadHash = identity.approvedPayloadHash,
            resultKind = resultKind,
            evidence = evidence,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdate(entity)
    }

    suspend fun markEffectUnknown(
        identity: OperationIdentity,
        reason: String
    ) {
        persistResult(identity, WorkflowResult.EffectUnknown(reason))
    }

    /**
     * Cold-start inspection:
     * If a workflow was in `Dispatching` when process died, marks it EffectUnknown.
     * If a workflow was in `WaitingForUser`, restores it as reviewable waiting state.
     */
    suspend fun checkAndRecoverOnStartup(): WorkflowEntity? {
        val latest = dao.getLatestWorkflow() ?: return null
        if (latest.state == "Dispatching") {
            val recovered = latest.copy(
                state = "EffectUnknown",
                resultKind = "EffectUnknown",
                evidence = "Process terminated during dispatch. External effect cannot be verified.",
                updatedAt = System.currentTimeMillis()
            )
            dao.insertOrUpdate(recovered)
            return recovered
        }
        return latest
    }

    suspend fun deleteWorkflow(taskId: String) {
        dao.deleteWorkflow(taskId)
    }
}
