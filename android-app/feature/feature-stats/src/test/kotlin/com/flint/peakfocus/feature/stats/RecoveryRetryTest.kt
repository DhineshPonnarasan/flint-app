package com.flint.peakfocus.feature.stats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3/M4: bounded retries must recover from transient failures without loops or stale work. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryRetryTest {
    @Test fun bindRetriesUntilItSucceeds() = runTest {
        var calls = 0
        val result = RecoveryBindRetry.run<String> {
            calls++
            if (calls < 3) null else "bound"
        }
        assertEquals(3, calls)
        assertEquals("bound", result)
        assertEquals(3_000L, testScheduler.currentTime) // 1 s + 2 s backoff
    }

    @Test fun bindGivesUpAfterItsBudget() = runTest {
        var calls = 0
        val result = RecoveryBindRetry.run<String> {
            calls++
            null
        }
        assertNull(result)
        assertEquals(RecoveryBindRetry.MAX_ATTEMPTS, calls)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun bindStopsWhenTheChallengeChanges() = runTest {
        var calls = 0
        val job = launch { RecoveryBindRetry.run<String> { calls++; null } }
        runCurrent() // first attempt fails and schedules the backoff
        assertEquals(1, calls)
        job.cancel() // a challenge change cancels the effect
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(job.isCancelled)
    }

    @Test fun commitRetriesTransientPersistenceFailuresThenSucceeds() = runTest {
        var calls = 0
        var failure: String? = null
        var successes = 0
        val ok = RecoveryCommitAttempt.run(
            commit = {
                calls++
                if (calls == 1) RecoveryCommitOutcome.PersistFailed else RecoveryCommitOutcome.Success
            },
            onFailure = { message, _ -> failure = message },
            onSuccess = { successes++ },
        )
        assertTrue(ok)
        assertEquals(2, calls)
        assertEquals(1, successes)
        assertEquals(StreakRecoveryCopy.PERSIST_FAILED, failure)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test fun commitDoesNotRetryAPolicyRejection() = runTest {
        var calls = 0
        var failure: String? = null
        val ok = RecoveryCommitAttempt.run(
            commit = { calls++; RecoveryCommitOutcome.Rejected(retryable = false) },
            onFailure = { message, _ -> failure = message },
            onSuccess = { },
        )
        assertFalse(ok)
        assertEquals(1, calls)
        assertEquals(StreakRecoveryCopy.CONFIRM_FAILED, failure)
    }

    @Test fun commitStopsAfterItsRetryBudget() = runTest {
        var calls = 0
        var failure: String? = null
        val ok = RecoveryCommitAttempt.run(
            commit = { calls++; RecoveryCommitOutcome.PersistFailed },
            onFailure = { message, _ -> failure = message },
            onSuccess = { },
        )
        assertFalse(ok)
        assertEquals(3, calls) // initial attempt + two retries
        assertEquals(StreakRecoveryCopy.PERSIST_FAILED, failure)
        assertEquals(15_000L, testScheduler.currentTime) // 5 s + 10 s
    }

    @Test fun commitStopsOnCancellation() = runTest {
        var calls = 0
        val job = launch {
            RecoveryCommitAttempt.run(
                commit = { calls++; RecoveryCommitOutcome.PersistFailed },
                onFailure = { _, _ -> },
                onSuccess = { },
            )
        }
        runCurrent() // first failure and backoff
        assertEquals(1, calls)
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(job.isCancelled)
    }

    @Test fun commitPublishesSuccessWhenTheCallerIsCancelledMidWrite() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var successes = 0
        val job = launch {
            RecoveryCommitAttempt.run(
                commit = {
                    started.complete(Unit)
                    gate.await()
                    RecoveryCommitOutcome.Success
                },
                onFailure = { _, _ -> },
                onSuccess = { successes++ },
            )
        }
        runCurrent()
        assertTrue(started.isCompleted)
        job.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("a durable outcome must survive effect cancellation", 1, successes)
    }

    @Test fun `rejection retryability is explicit`() {
        assertTrue(recoveryRejectionRetryable("race_rejected"))
        assertTrue(recoveryRejectionRetryable("protection_inactive"))
        assertTrue(recoveryRejectionRetryable("insufficient_duration"))
        assertFalse(recoveryRejectionRetryable("protection_changed"))
        assertFalse(recoveryRejectionRetryable("protection_mismatch"))
        assertFalse(recoveryRejectionRetryable("duplicate_id"))
        assertFalse(recoveryRejectionRetryable("already_recovered"))
    }
}
