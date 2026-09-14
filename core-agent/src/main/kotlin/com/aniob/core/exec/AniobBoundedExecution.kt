package com.aniob.core.exec

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

object AniobBoundedExecution {
    const val DEFAULT_TIMEOUT_MS = 15_000L
    private val mutex = Mutex() // Serializes abandoned work like reference

    suspend fun <T> runBounded(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onTimeout: (() -> T)? = null,
        block: suspend () -> T
    ): T = mutex.withLock {
        try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            if (onTimeout != null) onTimeout() else throw e
        }
    }
}
