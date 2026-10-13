package com.aniob.app.safety

import com.aniob.app.db.AuditLogDao
import com.aniob.app.db.AuditLogEntity
import com.aniob.core.safety.AniobDlpSanitizer
import java.security.MessageDigest

/**
 * Local-First Tamper-Evident Audit Trail Logger.
 *
 * Each action is sanitized with [AniobDlpSanitizer] and chained to the previous entry
 * using cryptographic SHA-256 hashes, ensuring an immutable record of all on-device
 * agent executions.
 */
class AniobAuditLogger(private val auditLogDao: AuditLogDao) {

    companion object {
        const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    /**
     * Appends an action execution record to the tamper-evident audit log.
     */
    suspend fun recordAction(
        taskId: String,
        stepIndex: Int,
        actionType: String,
        targetPackage: String,
        rawPayload: String,
        verificationStatus: String
    ): AuditLogEntity {
        val lastEntry = auditLogDao.getLastEntry()
        val previousHash = lastEntry?.entryHash ?: GENESIS_HASH
        val timestamp = System.currentTimeMillis()
        val sanitizedPayload = AniobDlpSanitizer.sanitize(rawPayload)

        val entryHash = computeHash(
            previousHash = previousHash,
            timestamp = timestamp,
            taskId = taskId,
            stepIndex = stepIndex,
            actionType = actionType,
            targetPackage = targetPackage,
            sanitizedPayload = sanitizedPayload,
            verificationStatus = verificationStatus
        )

        val entity = AuditLogEntity(
            timestamp = timestamp,
            taskId = taskId,
            stepIndex = stepIndex,
            actionType = actionType,
            targetPackage = targetPackage,
            sanitizedPayload = sanitizedPayload,
            verificationStatus = verificationStatus,
            previousHash = previousHash,
            entryHash = entryHash
        )

        auditLogDao.insert(entity)
        return entity
    }

    /**
     * Verifies the cryptographic integrity of the entire append-only audit trail.
     * Returns true if all hashes are valid and unbroken, false if any row was tampered with.
     */
    suspend fun verifyIntegrity(): Boolean {
        val entries = auditLogDao.getAll()
        if (entries.isEmpty()) return true

        var expectedPrevHash = GENESIS_HASH
        for (entry in entries) {
            if (entry.previousHash != expectedPrevHash) {
                return false
            }
            val expectedHash = computeHash(
                previousHash = entry.previousHash,
                timestamp = entry.timestamp,
                taskId = entry.taskId,
                stepIndex = entry.stepIndex,
                actionType = entry.actionType,
                targetPackage = entry.targetPackage,
                sanitizedPayload = entry.sanitizedPayload,
                verificationStatus = entry.verificationStatus
            )
            if (entry.entryHash != expectedHash) {
                return false
            }
            expectedPrevHash = entry.entryHash
        }
        return true
    }

    /**
     * Exports all audit log entries as a JSON bundle for post-mortem analysis.
     */
    suspend fun exportAuditBundle(): String {
        val entries = auditLogDao.getAll()
        val jsonArray = entries.joinToString(",") { e ->
            """{"id":${e.id},"timestamp":${e.timestamp},"taskId":"${e.taskId}","stepIndex":${e.stepIndex},"action":"${e.actionType}","package":"${e.targetPackage}","payload":"${e.sanitizedPayload.replace("\"", "\\\"")}","status":"${e.verificationStatus}","prevHash":"${e.previousHash}","hash":"${e.entryHash}"}"""
        }
        return "[$jsonArray]"
    }

    private fun computeHash(
        previousHash: String,
        timestamp: Long,
        taskId: String,
        stepIndex: Int,
        actionType: String,
        targetPackage: String,
        sanitizedPayload: String,
        verificationStatus: String
    ): String {
        val data = "$previousHash|$timestamp|$taskId|$stepIndex|$actionType|$targetPackage|$sanitizedPayload|$verificationStatus"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
