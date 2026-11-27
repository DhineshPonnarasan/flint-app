package com.flint.peakfocus.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * Identity of one recovery attempt. A new challenge/protection, recovered enforcement, new
 * offer progress, or a newly satisfied offer is a genuinely new attempt. A plain recomposition
 * or a 1 Hz clock tick is not.
 */
internal data class RecoveryAttempt(
    val recoveryDay: LocalDate,
    val requiredFocusedMs: Long,
    val challengeId: String,
    val enforcementIntact: Boolean,
    val progressMinutes: Int,
    val satisfied: Boolean,
) {
    /** The durable challenge identity. Permanent refusals reset only when this changes. */
    val challengeKey: RecoveryChallengeKey
        get() = RecoveryChallengeKey(recoveryDay, requiredFocusedMs, challengeId)
}

internal data class RecoveryChallengeKey(
    val recoveryDay: LocalDate,
    val requiredFocusedMs: Long,
    val challengeId: String,
)

internal data class RecoveryFailure(
    val message: String,
    val retryable: Boolean,
    val attempt: RecoveryAttempt,
)

/** UI state for the recovery commit loop; held with [remember] by the card. */
internal class RecoveryCommitState {
    var failure by mutableStateOf<RecoveryFailure?>(null)
    var committing by mutableStateOf(false)
    var celebration by mutableStateOf(false)
    var celebrationKey by mutableStateOf<RecoveryChallengeKey?>(null)
    var retryNonce by mutableStateOf(0)
    var pendingRetry by mutableStateOf<RecoveryAttempt?>(null)
}

/**
 * Serializes recovery commits for the current [attempt].
 *
 * - A retryable failure is cleared by a new attempt identity (new evidence, satisfied/progress,
 *   enforcement, or challenge/protection).
 * - A permanent rejection stays visible until the challenge identity itself changes.
 * - A transition that arrives while a commit is in flight queues one retry; the commit-in-flight
 *   guard and the single effect ensure there is never a concurrent duplicate attempt.
 * - A stale success is not published over a different challenge; a stale failure is retained
 *   only long enough for the pending transition to decide whether it may be retried.
 */
@Composable
internal fun RecoveryCommitEffect(
    attempt: RecoveryAttempt?,
    state: RecoveryCommitState,
    commit: suspend () -> RecoveryCommitOutcome,
) {
    val latestAttempt by rememberUpdatedState(attempt)

    LaunchedEffect(attempt, state.retryNonce) {
        val key = attempt
        if (key == null) {
            state.failure = null
            return@LaunchedEffect
        }
        if (state.celebration && state.celebrationKey != key.challengeKey) {
            // A celebration is about one challenge. A new challenge must not inherit it.
            state.celebration = false
            state.celebrationKey = null
        }
        if (state.committing) {
            // Let the in-flight commit finish, then start exactly one attempt for the new state.
            state.pendingRetry = key
            return@LaunchedEffect
        }
        val failure = state.failure
        if (failure != null) {
            if (failure.attempt == key) return@LaunchedEffect
            if (!failure.retryable && key.challengeKey == failure.attempt.challengeKey) {
                return@LaunchedEffect
            }
            // A new attempt identity can legitimately change a transient answer.
            state.failure = null
        }
        if (!key.satisfied || state.celebration) return@LaunchedEffect
        state.committing = true
        try {
            RecoveryCommitAttempt.run(
                commit = commit,
                onFailure = { message, retryable ->
                    // Retain the outcome even when the attempt advanced: the pending decision
                    // needs a permanent rejection to stop a concurrent transition from retrying it.
                    state.failure = RecoveryFailure(message, retryable, key)
                },
                onSuccess = {
                    // A durable success for the same challenge is still the right outcome if
                    // only progress/evidence advanced. A different challenge is left to its
                    // own attempt.
                    if (latestAttempt?.challengeKey == key.challengeKey) {
                        state.failure = null
                        state.celebration = true
                        state.celebrationKey = key.challengeKey
                    }
                },
            )
        } finally {
            state.committing = false
            val pending = state.pendingRetry
            state.pendingRetry = null
            if (pending != null && pending == latestAttempt) {
                val failure = state.failure
                val challengeChanged = failure != null &&
                    pending.challengeKey != failure.attempt.challengeKey
                val celebrationBlocks = state.celebration &&
                    state.celebrationKey == pending.challengeKey
                if (!celebrationBlocks &&
                    (failure == null || failure.retryable || challengeChanged)
                ) {
                    state.failure = null
                    state.retryNonce++
                }
            }
        }
    }
}
