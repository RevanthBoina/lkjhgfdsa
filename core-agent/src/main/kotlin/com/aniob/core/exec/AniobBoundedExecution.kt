package com.aniob.core.exec

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Serialized bounded execution utility (Phase 2.1).
 * Prevents accessibility tree hangs and ANRs by bounding execution with a hard wall-clock timeout.
 */
object AniobBoundedExecution {

    const val DEFAULT_TIMEOUT_MS = 15_000L

    suspend fun <T> runBounded(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onTimeout: (() -> T)? = null,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(timeoutMs) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            if (onTimeout != null) {
                onTimeout()
            } else {
                throw e
            }
        }
    }
}
