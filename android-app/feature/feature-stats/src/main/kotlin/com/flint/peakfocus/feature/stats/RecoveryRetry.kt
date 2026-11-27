package com.flint.peakfocus.feature.stats

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
internal sealed interface RecoveryCommitOutcome {
    data object Success : RecoveryCommitOutcome
    data object PersistFailed : RecoveryCommitOutcome

    /**
     * A policy rejection. [retryable] is true only when a genuinely new attempt context (new
     * evidence, recovered enforcement, or a new offer state) can legitimately change the answer.
     * Permanent refusals stay latched for the current challenge.
     */
    data class Rejected(val retryable: Boolean) : RecoveryCommitOutcome
}

/**
 * Rejections that may be retried after a meaningful attempt-state change. A race can disappear
 * once the competing write settles; an inactive probe can recover; the store can disagree with
 * the UI until the next durable event lands. Permanent policy answers are not retried: in
 * particular `protection_changed` (the frozen selection was edited) cannot become valid from more
 * progress, and `protection_mismatch`/`duplicate_id`/`already_recovered` stay latched.
 */
internal fun recoveryRejectionRetryable(reason: String): Boolean = when (reason) {
    "race_rejected", "protection_inactive", "insufficient_duration" -> true
    else -> false
}

/**
 * Runs one recovery commit and retries only transient persistence failures, with bounded
 * backoff. A policy rejection is final for this attempt; every failure is surfaced with its
 * retryability so the caller can wait for a new attempt identity instead of re-entering the
 * store lock on unrelated recompositions.
 *
 * The durable write itself is shielded from cancellation: an effect whose attempt identity
 * changes can cancel while the store append is already committed, and losing that outcome would
 * turn a durable success into a retry that the store correctly rejects (`already_recovered`).
 * Cancellation is still honored between attempts and during persistence backoff.
 */
internal object RecoveryCommitAttempt {
    const val MAX_TRANSIENT_RETRIES = 2
    const val RETRY_DELAY_MS = 5_000L

    suspend fun run(
        commit: suspend () -> RecoveryCommitOutcome,
        onFailure: (message: String, retryable: Boolean) -> Unit,
        onSuccess: () -> Unit,
    ): Boolean {
        var retries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val outcome = withContext(NonCancellable) { commit() }
            when (outcome) {
                RecoveryCommitOutcome.Success -> {
                    onSuccess()
                    return true
                }
                is RecoveryCommitOutcome.Rejected -> {
                    onFailure(StreakRecoveryCopy.CONFIRM_FAILED, outcome.retryable)
                    return false
                }
                RecoveryCommitOutcome.PersistFailed -> {
                    onFailure(StreakRecoveryCopy.PERSIST_FAILED, true)
                    if (retries >= MAX_TRANSIENT_RETRIES) return false
                    retries++
                    delay(RETRY_DELAY_MS * retries)
                }
            }
        }
    }
}
