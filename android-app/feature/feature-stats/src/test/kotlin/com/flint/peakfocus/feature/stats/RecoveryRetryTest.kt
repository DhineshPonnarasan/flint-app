package com.flint.peakfocus.feature.stats

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
                if (calls == 1) RecoveryCommitResult.PERSIST_FAILED else RecoveryCommitResult.SUCCESS
            },
            onFailure = { failure = it },
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
            commit = { calls++; RecoveryCommitResult.REJECTED },
            onFailure = { failure = it },
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
            commit = { calls++; RecoveryCommitResult.PERSIST_FAILED },
            onFailure = { failure = it },
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
                commit = { calls++; RecoveryCommitResult.PERSIST_FAILED },
                onFailure = { },
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
}
