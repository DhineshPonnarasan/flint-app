package com.flint.peakfocus.core.history

import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recovery challenge's enforcement latch is scoped to its challenge id, not to the device date:
 * re-enabling accessibility, restarting, or moving the clock cannot make the *same* challenge
 * healthy again. Only a new challenge does, and it needs fresh verification.
 */
class RecoveryChallengeLatchTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val packages = listOf("com.instagram.android")

    private fun guard(dir: File, now: () -> Long = { 0L }) = RecoveryChallengeGuardStore(dir, now)

    private fun bind(store: RecoveryChallengeGuardStore, day: LocalDate = today, healthy: Boolean = true) =
        store.ensureFrozen(packages, false, day, day.minusDays(1), 0L, healthyNow = healthy)

    @Test fun newChallengeRequiresFreshIndependentVerification() {
        val dir = createTempDirectory("flint-latch-health").toFile()
        val store = guard(dir)
        assertNull("no challenge while enforcement is not proven", bind(store, healthy = false))
        assertNull(store.current())

        assertNotNull(bind(store, healthy = true))
        assertTrue(store.isEnforcementIntact())
    }

    @Test fun sameDayRebindReturnsTheSameChallengeAndCannotClearTheLatch() {
        val dir = createTempDirectory("flint-latch-sameday").toFile()
        val store = guard(dir)
        val first = bind(store)!!
        store.markEnforcementBroken()
        assertFalse(store.isEnforcementIntact())

        // A same-day rebind must not create a new challenge, even with health restored.
        val again = bind(store, healthy = true)!!
        assertEquals(first.challengeId, again.challengeId)
        assertFalse("re-enabling does not clear a broken challenge", store.isEnforcementIntact())
    }

    @Test fun aNewDayNeedsHealthAndKeepsTheOldLatchRecord() {
        val dir = createTempDirectory("flint-latch-newday").toFile()
        val store = guard(dir)
        val first = bind(store)!!
        store.markEnforcementBroken()

        assertNull("a new challenge still needs live enforcement", bind(store, today.plusDays(2), healthy = false))
        val second = bind(store, today.plusDays(2), healthy = true)!!
        assertNotEquals(first.challengeId, second.challengeId)
        assertTrue(store.isEnforcementIntact())
        assertEquals(
            "the broken challenge stays on record even after a new one binds",
            first.challengeId,
            store.latchedChallenge(),
        )
    }

    @Test fun reEnablingHealthDoesNotClearTheLatch() {
        val dir = createTempDirectory("flint-latch-reenable").toFile()
        val store = guard(dir)
        bind(store)
        store.markEnforcementBroken()

        // A fresh process that sees perfectly healthy enforcement still fails closed.
        val restarted = guard(dir)
        restarted.noteWatchStarted()
        assertFalse(restarted.isEnforcementIntact())
        assertEquals(store.current()!!.challengeId, restarted.latchedChallenge())
    }

    @Test fun watchLatchIsScopedToTheActiveChallenge() = runBlocking {
        val dir = createTempDirectory("flint-latch-watch").toFile()
        val store = guard(dir)
        val first = bind(store)!!
        val samples = listOf(true, false).iterator()
        RecoveryEnforcementWatch.watch(
            guard = store,
            freezeActive = { true },
            pathAHealthy = { samples.next() },
            intervalMs = 0,
            iterations = 2,
        )
        assertFalse(store.isEnforcementIntact())

        // A later day is a different challenge and may proceed once enforcement is proven.
        val next = bind(store, today.plusDays(1), healthy = true)!!
        assertNotEquals(first.challengeId, next.challengeId)
        assertTrue(store.isEnforcementIntact())
        assertEquals(first.challengeId, store.latchedChallenge())
    }

    @Test fun processDeathKeepsTheChallengeBroken() {
        val dir = createTempDirectory("flint-latch-death").toFile()
        val first = guard(dir)
        bind(first)
        first.markEnforcementBroken()

        // Same challenge read by a new process generation.
        val reopened = guard(dir)
        assertFalse(reopened.isEnforcementIntact())
        assertFalse(reopened.noteWatchStarted().let { reopened.isEnforcementIntact() })
        assertEquals(first.current()!!.challengeId, reopened.latchedChallenge())
    }

    @Test fun dateChangeDoesNotReopenTheLatchedChallenge() {
        val dir = createTempDirectory("flint-latch-date").toFile()
        val store = guard(dir)
        val first = bind(store)!!
        store.markEnforcementBroken()

        // Moving the device date forward produces a *new* challenge; the broken one is never
        // rehabilitated and remains on record. The new challenge is independently verified.
        val shifted = bind(store, today.plusDays(1), healthy = true)!!
        assertNotEquals(first.challengeId, shifted.challengeId)
        assertTrue("a new day may proceed once enforcement is proven", store.isEnforcementIntact())
        assertEquals(first.challengeId, store.latchedChallenge())
    }

    @Test fun aBrokenDayCannotBeReboundEvenAfterTheProfileIsCleared() {
        val dir = createTempDirectory("flint-latch-rebind").toFile()
        val store = guard(dir)
        bind(store)
        store.markEnforcementBroken()

        // A stale-day cleanup (or a clock round-trip) drops the profile but must not resurrect
        // the same day's challenge.
        store.clear()
        assertNull("the same broken day cannot be bound again", bind(store, healthy = true))
        assertNull(store.current())

        // A later day is still allowed.
        assertNotNull(bind(store, today.plusDays(1), healthy = true))
    }

    @Test fun legacyFreezeWithoutAChallengeIdFailsClosed() {
        val dir = createTempDirectory("flint-latch-legacy").toFile()
        val store = guard(dir)
        bind(store)
        // Rewrite the sidecar as an older build would have: no challengeId, boolean latch only.
        val file = File(dir, "recovery-challenge.v1.json")
        val legacy = file.readText()
            .replace(Regex("\"challengeId\":\"[^\"]*\"[,]?"), "")
            .replace(Regex("\"latchChallengeId\":[^,]*[,]?"), "")
        file.writeText(legacy)

        val reopened = guard(dir)
        assertTrue("a legacy profile has no challenge identity", reopened.current()!!.challengeId.isEmpty())
        assertFalse("continuity cannot be scoped to a legacy freeze", reopened.isEnforcementIntact())
    }
}
