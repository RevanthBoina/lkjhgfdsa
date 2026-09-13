package com.aniob.core.logging

import java.util.concurrent.atomic.AtomicInteger

/**
 * Privacy-hardened audit logger.
 * STRICT PRIVACY MANDATE: Never logs user utterance text or raw prompts.
 * Uses closed-vocabulary categorical tokens and event counters.
 */
object AniobXLog {

    enum class SecurityEvent {
        INJECTION_ATTEMPT_PROMPT,
        INJECTION_ATTEMPT_CANARY,
        SENSITIVE_KEYWORD_BLOCKED,
        OTP_BLOCKED,
        PAYMENT_BLOCKED,
        CONFIRMATION_GATE_TRIGGERED,
        NEVER_RETRY_ENFORCED
    }

    data class AuditEntry(
        val timestamp: Long,
        val eventType: SecurityEvent,
        val targetPackage: String,
        val stepIndex: Int
    )

    private val auditLog = mutableListOf<AuditEntry>()
    private val eventCounters = mutableMapOf<SecurityEvent, AtomicInteger>()

    init {
        SecurityEvent.values().forEach {
            eventCounters[it] = AtomicInteger(0)
        }
    }

    @Synchronized
    fun recordEvent(event: SecurityEvent, targetPackage: String = "unknown", stepIndex: Int = 0) {
        eventCounters[event]?.incrementAndGet()
        auditLog.add(
            AuditEntry(
                timestamp = System.currentTimeMillis(),
                eventType = event,
                targetPackage = targetPackage,
                stepIndex = stepIndex
            )
        )
    }

    fun getCounter(event: SecurityEvent): Int {
        return eventCounters[event]?.get() ?: 0
    }

    @Synchronized
    fun getRecentAuditEntries(limit: Int = 50): List<AuditEntry> {
        return auditLog.takeLast(limit)
    }

    @Synchronized
    fun clear() {
        auditLog.clear()
        eventCounters.values.forEach { it.set(0) }
    }
}
