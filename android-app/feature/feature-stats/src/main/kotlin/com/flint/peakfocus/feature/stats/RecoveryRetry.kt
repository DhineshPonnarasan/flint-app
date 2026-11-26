package com.flint.peakfocus.feature.stats

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Bounded retry for the durable protection bind.
 *
 * A bind can fail while enforcement is still starting (or a write races another process). The
 * bind itself verifies enforcement and is idempotent for the same challenge, so retrying is
 * safe. Cancellation (a new challenge day, preview mode, leaving the screen) stops the loop
 * before it can bind a stale challenge, and the budget bounds battery use.
 */
internal object RecoveryBindRetry {
    const val MAX_ATTEMPTS = 3

    suspend fun <T : Any> run(
        attempts: Int = MAX_ATTEMPTS,
        delayFor: (Int) -> Long = { attempt -> 1_000L * attempt },
        bind: suspend () -> T?,
    ): T? {
        var attempt = 0
        while (attempt < attempts) {
            currentCoroutineContext().ensureActive()
            val bound = bind()
            // A cancelled effect must never publish the profile of a stale challenge.
            currentCoroutineContext().ensureActive()
            if (bound != null) return bound
            attempt++
            if (attempt >= attempts) return null
            delay(delayFor(attempt))
        }
        return null
    }
}

/** One commit outcome, decoupled from the store's result type so the retry logic is testable. */
internal enum class RecoveryCommitResult { SUCCESS, REJECTED, PERSIST_FAILED }

/**
 * Runs one recovery commit and retries only transient persistence failures, with bounded
 * backoff. A policy rejection is final for this attempt; every failure is surfaced to the user,
 * and the caller blocks further attempts until a genuinely new challenge/attempt context begins.
 */
internal object RecoveryCommitAttempt {
    const val MAX_TRANSIENT_RETRIES = 2
    const val RETRY_DELAY_MS = 5_000L

    suspend fun run(
        commit: suspend () -> RecoveryCommitResult,
        onFailure: (String) -> Unit,
        onSuccess: () -> Unit,
    ): Boolean {
        var retries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            when (commit()) {
                RecoveryCommitResult.SUCCESS -> {
                    onSuccess()
                    return true
                }
                RecoveryCommitResult.REJECTED -> {
                    onFailure(StreakRecoveryCopy.CONFIRM_FAILED)
                    return false
                }
                RecoveryCommitResult.PERSIST_FAILED -> {
                    onFailure(StreakRecoveryCopy.PERSIST_FAILED)
                    if (retries >= MAX_TRANSIENT_RETRIES) return false
                    retries++
                    delay(RETRY_DELAY_MS * retries)
                }
            }
        }
    }
}
