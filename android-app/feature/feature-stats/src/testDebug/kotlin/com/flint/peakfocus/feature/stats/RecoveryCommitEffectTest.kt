package com.flint.peakfocus.feature.stats

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M3 regression: a transient commit rejection must become retryable when the recovery offer
 * identity changes, without ever running two commits concurrently.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryCommitEffectTest {
    @get:Rule val compose = createComposeRule()

    private fun attempt(
        satisfied: Boolean,
        progressMinutes: Int = 5,
        challengeId: String = "challenge-1",
    ) = RecoveryAttempt(
        recoveryDay = LocalDate.of(2026, 11, 26),
        requiredFocusedMs = 300_000L,
        challengeId = challengeId,
        enforcementIntact = true,
        progressMinutes = progressMinutes,
        satisfied = satisfied,
    )

    @Test
    fun raceRejectionRetriesAfterAMeaningfulOfferTransition() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    val call = calls.incrementAndGet()
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                    try {
                        if (call == 1) {
                            started.complete(Unit)
                            withContext(NonCancellable) { gate.await() }
                            RecoveryCommitOutcome.Rejected(retryable = true)
                        } else {
                            RecoveryCommitOutcome.Success
                        }
                    } finally {
                        active.decrementAndGet()
                    }
                },
            )
        }

        compose.waitUntil(5_000) { started.isCompleted }
        gate.complete(Unit)
        compose.waitUntil(5_000) { state.failure != null }
        assertEquals(StreakRecoveryCopy.CONFIRM_FAILED, state.failure?.message)
        assertTrue("a race rejection must be marked retryable", state.failure?.retryable == true)
        assertEquals(1, calls.get())

        compose.runOnIdle {
            // A real offer-state transition: unsatisfied, then satisfied with new progress.
            attemptState.value = attempt(satisfied = false, progressMinutes = 5)
            attemptState.value = attempt(satisfied = true, progressMinutes = 6)
        }
        compose.waitForIdle()
        compose.waitUntil(2_000) { state.failure == null }
        compose.waitUntil(5_000) { calls.get() == 2 }
        compose.waitUntil(5_000) { state.celebration }

        assertNull(state.failure)
        assertEquals(2, calls.get())
        assertEquals("commits must never overlap", 1, maxActive.get())
    }

    @Test
    fun attemptChangeDuringCommitPublishesTheDurableSuccess() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    gate.await()
                    RecoveryCommitOutcome.Success
                },
            )
        }

        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle {
            attemptState.value = attempt(satisfied = true, progressMinutes = 6)
        }
        compose.waitForIdle()

        // The attempt changed while the durable write was in flight. If cancellation wins, the
        // retry sees already_recovered and the success is lost; the committed outcome must win.
        gate.complete(Unit)
        compose.waitUntil(5_000) { state.celebrationKey?.challengeId == "challenge-1" }

        assertTrue(state.celebration)
        assertEquals("a committed write must not be retried", 1, calls.get())
    }

    @Test
    fun transitionDuringAnInFlightCommitQueuesOneRetryWithoutOverlap() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val lastCommitted = AtomicReference<RecoveryAttempt?>(null)

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    lastCommitted.set(attemptState.value)
                    val call = calls.incrementAndGet()
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                    try {
                        if (call == 1) {
                            started.complete(Unit)
                            withContext(NonCancellable) { gate.await() }
                            RecoveryCommitOutcome.Rejected(retryable = true)
                        } else {
                            RecoveryCommitOutcome.Success
                        }
                    } finally {
                        active.decrementAndGet()
                    }
                },
            )
        }

        compose.waitUntil(5_000) { started.isCompleted }
        // Several sequential identity changes while the first commit is in flight. Only the
        // latest one may retry, and none may start a second concurrent commit.
        for (progress in listOf(6, 7)) {
            compose.runOnIdle {
                attemptState.value = attempt(satisfied = true, progressMinutes = progress)
            }
            compose.waitForIdle()
            assertEquals("a new attempt must not start while one is in flight", 1, calls.get())
        }
        compose.runOnIdle {
            attemptState.value = attempt(satisfied = false, progressMinutes = 7)
        }
        compose.waitForIdle()
        assertEquals("an unsatisfied transition must not commit", 1, calls.get())
        compose.runOnIdle {
            attemptState.value = attempt(satisfied = true, progressMinutes = 8)
        }
        compose.waitForIdle()
        assertEquals("only the latest transition may retry", 1, calls.get())
        assertEquals(1, active.get())

        gate.complete(Unit)
        compose.waitUntil(5_000) { calls.get() == 2 }
        compose.waitUntil(5_000) { state.celebration }

        assertNull(state.failure)
        assertEquals(2, calls.get())
        assertEquals("queued retry must not overlap the first commit", 1, maxActive.get())
        assertEquals("the queued retry must use the latest attempt", 8, lastCommitted.get()?.progressMinutes)
        assertTrue(lastCommitted.get()?.satisfied == true)
    }

    @Test
    fun permanentRejectionDuringAnInFlightTransitionIsNotRetried() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    val call = calls.incrementAndGet()
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                    try {
                        if (call == 1) {
                            started.complete(Unit)
                            withContext(NonCancellable) { gate.await() }
                            RecoveryCommitOutcome.Rejected(retryable = false)
                        } else {
                            RecoveryCommitOutcome.Success
                        }
                    } finally {
                        active.decrementAndGet()
                    }
                },
            )
        }

        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle {
            attemptState.value = attempt(satisfied = true, progressMinutes = 6)
        }
        compose.waitForIdle()
        assertEquals(1, calls.get())

        gate.complete(Unit)
        compose.waitUntil(5_000) { state.failure != null }
        compose.waitForIdle()

        assertEquals("a permanent rejection must not be retried", 1, calls.get())
        assertEquals(1, maxActive.get())
        assertFalse(state.celebration)
        assertTrue(state.failure?.retryable == false)
    }

    @Test
    fun permanentRejectionIsNotRetriedOnOfferProgress() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    calls.incrementAndGet()
                    RecoveryCommitOutcome.Rejected(retryable = false)
                },
            )
        }

        compose.waitUntil(5_000) { state.failure != null }
        assertEquals(1, calls.get())
        assertTrue(state.failure?.retryable == false)

        compose.runOnIdle {
            for (progress in listOf(6, 7, 8)) {
                attemptState.value = attempt(satisfied = true, progressMinutes = progress)
            }
        }
        compose.waitForIdle()

        assertEquals("a permanent rejection must not retry on offer progress", 1, calls.get())
        assertFalse(state.celebration)
        assertTrue(state.failure != null)
    }

    @Test
    fun celebrationIsScopedToTheChallenge() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    calls.incrementAndGet()
                    RecoveryCommitOutcome.Success
                },
            )
        }

        compose.waitUntil(5_000) {
            state.celebration && state.celebrationKey?.challengeId == "challenge-1"
        }
        assertEquals(1, calls.get())

        compose.runOnIdle {
            attemptState.value = attempt(satisfied = true, challengeId = "challenge-2")
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { calls.get() == 2 }
        compose.waitUntil(5_000) { state.celebrationKey?.challengeId == "challenge-2" }

        assertTrue("the second challenge must be allowed to commit", state.celebration)
        assertEquals(2, calls.get())
    }

    @Test
    fun unrelatedRecompositionDoesNotRetry() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val tick = mutableStateOf(0)
        val calls = AtomicInteger()

        compose.setContent {
            Text(tick.value.toString())
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    calls.incrementAndGet()
                    RecoveryCommitOutcome.Rejected(retryable = true)
                },
            )
        }

        compose.waitUntil(5_000) { state.failure != null }
        assertEquals(1, calls.get())

        compose.runOnIdle { tick.value++ }
        compose.waitForIdle()

        assertEquals("a clock-like recomposition must not retry", 1, calls.get())
    }

    @Test
    fun pendingRetryIsDroppedWhenOfferBecomesUnsatisfied() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    val call = calls.incrementAndGet()
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                    try {
                        if (call == 1) {
                            started.complete(Unit)
                            withContext(NonCancellable) { gate.await() }
                            RecoveryCommitOutcome.Rejected(retryable = true)
                        } else {
                            RecoveryCommitOutcome.Success
                        }
                    } finally {
                        active.decrementAndGet()
                    }
                },
            )
        }

        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle {
            attemptState.value = attempt(satisfied = false, progressMinutes = 5)
        }
        compose.waitForIdle()
        assertEquals(1, calls.get())

        gate.complete(Unit)
        compose.waitUntil(2_000) { state.failure == null }
        compose.waitForIdle()
        assertEquals("an unsatisfied offer must not commit", 1, calls.get())

        compose.runOnIdle {
            attemptState.value = attempt(satisfied = true, progressMinutes = 6)
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { calls.get() == 2 }
        compose.waitUntil(5_000) { state.celebration }

        assertEquals(2, calls.get())
        assertEquals(1, maxActive.get())
    }

    @Test
    fun successAfterChallengeChangeDoesNotCelebrateTheWrongChallenge() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    val call = calls.incrementAndGet()
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                    try {
                        if (call == 1) {
                            started.complete(Unit)
                            withContext(NonCancellable) { gate.await() }
                        }
                        RecoveryCommitOutcome.Success
                    } finally {
                        active.decrementAndGet()
                    }
                },
            )
        }

        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle {
            attemptState.value = attempt(satisfied = true, challengeId = "challenge-2")
        }
        compose.waitForIdle()
        assertEquals(1, calls.get())

        gate.complete(Unit)
        compose.waitUntil(5_000) { calls.get() == 2 }
        compose.waitUntil(5_000) { state.celebrationKey?.challengeId == "challenge-2" }

        assertTrue(state.celebration)
        assertEquals("the superseded success must not suppress challenge B", 2, calls.get())
        assertEquals(1, maxActive.get())
    }

    @Test
    fun failureAfterChallengeChangeDoesNotBlockTheNewChallenge() {
        val state = RecoveryCommitState()
        val attemptState = mutableStateOf(attempt(satisfied = true))
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        compose.setContent {
            RecoveryCommitEffect(
                attempt = attemptState.value,
                state = state,
                commit = {
                    val call = calls.incrementAndGet()
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                    try {
                        if (call == 1) {
                            started.complete(Unit)
                            withContext(NonCancellable) { gate.await() }
                            RecoveryCommitOutcome.Rejected(retryable = false)
                        } else {
                            RecoveryCommitOutcome.Success
                        }
                    } finally {
                        active.decrementAndGet()
                    }
                },
            )
        }

        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle {
            attemptState.value = attempt(satisfied = true, challengeId = "challenge-2")
        }
        compose.waitForIdle()
        assertEquals(1, calls.get())

        gate.complete(Unit)
        compose.waitUntil(5_000) { calls.get() == 2 }
        compose.waitUntil(5_000) { state.celebrationKey?.challengeId == "challenge-2" }

        assertTrue(state.celebration)
        assertEquals(2, calls.get())
        assertEquals(1, maxActive.get())
    }
}
