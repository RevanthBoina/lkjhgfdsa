package com.aniob.core.memory

import com.aniob.core.domain.AniobAction

/**
 * Extracts and stores optimal multi-step playbooks from verified execution trajectories.
 * Enables injecting proven sequence playbooks into future similar tasks.
 */
class AniobLearnedProcedureStore {

    data class Playbook(
        val procedureId: String,
        val taskSignature: String,
        val packageName: String,
        val actions: List<AniobAction>,
        val executionCount: Int = 1,
        val avgLatencyMs: Long = 0L,
        val lastSuccessTimestamp: Long = System.currentTimeMillis()
    )

    private val playbooks = mutableMapOf<String, Playbook>()

    @Synchronized
    fun registerSuccess(
        taskSignature: String,
        packageName: String,
        actions: List<AniobAction>,
        totalLatencyMs: Long
    ) {
        val existing = playbooks[taskSignature]
        if (existing != null) {
            val updatedCount = existing.executionCount + 1
            val updatedAvg = (existing.avgLatencyMs * existing.executionCount + totalLatencyMs) / updatedCount
            playbooks[taskSignature] = existing.copy(
                executionCount = updatedCount,
                avgLatencyMs = updatedAvg,
                lastSuccessTimestamp = System.currentTimeMillis()
            )
        } else {
            playbooks[taskSignature] = Playbook(
                procedureId = "proc_${System.currentTimeMillis()}",
                taskSignature = taskSignature,
                packageName = packageName,
                actions = actions,
                executionCount = 1,
                avgLatencyMs = totalLatencyMs
            )
        }
    }

    @Synchronized
    fun findPlaybook(taskSignature: String): Playbook? {
        return playbooks[taskSignature.lowercase().trim()]
    }

    @Synchronized
    fun getAllPlaybooks(): List<Playbook> = playbooks.values.toList()

    @Synchronized
    fun clear() {
        playbooks.clear()
    }
}
