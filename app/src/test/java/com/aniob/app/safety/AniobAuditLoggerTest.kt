package com.aniob.app.safety

import com.aniob.app.db.AuditLogDao
import com.aniob.app.db.AuditLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAuditLoggerTest {

    private class FakeAuditLogDao : AuditLogDao {
        val entries = mutableListOf<AuditLogEntity>()
        private var nextId = 1L

        override suspend fun insert(entry: AuditLogEntity): Long {
            val withId = entry.copy(id = nextId++)
            entries.add(withId)
            return withId.id
        }

        override suspend fun getLastEntry(): AuditLogEntity? = entries.lastOrNull()

        override suspend fun getAll(): List<AuditLogEntity> = entries.toList()

        override suspend fun getByTask(taskId: String): List<AuditLogEntity> =
            entries.filter { it.taskId == taskId }

        override suspend fun count(): Long = entries.size.toLong()
    }

    @Test
    fun testAppendAndIntegrityValidation() = runBlocking {
        val dao = FakeAuditLogDao()
        val logger = AniobAuditLogger(dao)

        val entry1 = logger.recordAction(
            taskId = "task-1",
            stepIndex = 1,
            actionType = "tap",
            targetPackage = "com.google.android.deskclock",
            rawPayload = "Tap Alarm at coordinates",
            verificationStatus = "SUCCESS"
        )

        assertEquals(AniobAuditLogger.GENESIS_HASH, entry1.previousHash)
        assertTrue(entry1.entryHash.isNotBlank())

        val entry2 = logger.recordAction(
            taskId = "task-1",
            stepIndex = 2,
            actionType = "input_text",
            targetPackage = "com.google.android.deskclock",
            rawPayload = "Set time to 4532 0150 0000 0007",
            verificationStatus = "SUCCESS"
        )

        assertEquals(entry1.entryHash, entry2.previousHash)
        // Verify DLP redaction in payload
        assertTrue(entry2.sanitizedPayload.contains("[CARD_REDACTED]"))

        // Integrity check should pass
        assertTrue(logger.verifyIntegrity())
    }

    @Test
    fun testTamperDetection() = runBlocking {
        val dao = FakeAuditLogDao()
        val logger = AniobAuditLogger(dao)

        logger.recordAction("task-1", 1, "tap", "com.pkg", "payload 1", "SUCCESS")
        logger.recordAction("task-1", 2, "tap", "com.pkg", "payload 2", "SUCCESS")

        assertTrue(logger.verifyIntegrity())

        // Tamper with second entry
        val tampered = dao.entries[1].copy(sanitizedPayload = "tampered payload")
        dao.entries[1] = tampered

        // Verification must detect hash mismatch
        assertFalse(logger.verifyIntegrity())
    }
}
