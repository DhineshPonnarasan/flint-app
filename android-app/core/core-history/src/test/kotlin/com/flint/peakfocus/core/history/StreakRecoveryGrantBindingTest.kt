package com.flint.peakfocus.core.history

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production-path binding for recovery credit.
 *
 * [HistoryReconciler] is the only production writer of `started` rows, and recovery credit
 * accepts nothing but a matching `started.grant`. These tests therefore drive the reconciler
 * itself — no hand-built grant — so a regression in that wiring fails here instead of silently
 * making every challenge unsatisfiable.
 */
class StreakRecoveryGrantBindingTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val packages = listOf("com.instagram.android")
    private val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun at(day: LocalDate, hour: Int = 9, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun openStore(clock: MutableClock): FocusHistoryStore =
        FocusHistoryStore(createTempDirectory("flint-grant").toFile(), clock) { zone }

    private fun protection(day: LocalDate = today) = StreakRecoveryProtection.bind(
        packages, false, day, day.minusDays(1), at(day, 18),
    )!!

    private suspend fun seedCompleted(
        store: FocusHistoryStore,
        id: String,
        day: LocalDate,
        minutes: Int,
        grant: String? = null,
    ) {
        val start = at(day)
        val end = start + minutes * 60_000L
        assertTrue(
            store.append(
                HistoryEvent(
                    id = "start-$id", type = "started", session = id, at = start, tz = zone.id,
                    plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
                    grant = grant,
                ),
            ),
        )
        assertTrue(
            store.append(
                HistoryEvent(
                    id = "end-$id", type = "completed", session = id, at = end, tz = zone.id,
                ),
            ),
        )
    }

    @Test fun reconcilerStampsStartedWithTheActiveFreezeFingerprint() = runBlocking {
        val now = at(today)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        val reconciler = HistoryReconciler(store, clock, zone = { zone }, recoveryGrant = { fingerprint })
        reconciler.observe(listOf(HistoryRule("session-granted", "Focus timer", "easy", now + 40 * 60_000L)))

        val started = store.snapshots.value.events.single { it.knownType == HistoryEventType.STARTED }
        assertEquals(fingerprint, started.grant)
        assertEquals(
            setOf("session-granted"),
            StreakRecoveryProtection.qualifyingSessionIds(store.snapshots.value.events, fingerprint),
        )
    }

    @Test fun reconcilerWithoutAnActiveFreezeWritesNoGrantAndCreditsNothing() = runBlocking {
        val now = at(today)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        val reconciler = HistoryReconciler(store, clock, zone = { zone }, recoveryGrant = { null })
        reconciler.observe(listOf(HistoryRule("session-plain", "Focus timer", "easy", now + 40 * 60_000L)))

        val started = store.snapshots.value.events.single { it.knownType == HistoryEventType.STARTED }
        assertNull(started.grant)
        assertTrue(
            StreakRecoveryProtection.qualifyingSessionIds(store.snapshots.value.events, fingerprint)
                .isEmpty(),
        )
    }

    /**
     * End to end: a prior-day completion plus a recovery-day session whose `started` row came
     * from the reconciler must satisfy the challenge and commit. Before the grant was stamped
     * here, this exact flow returned `insufficient_duration` forever.
     */
    @Test fun reconcilerGrantedSessionSatisfiesTheChallengeAndCommits() = runBlocking {
        val now = at(today)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "prior", today.minusDays(2), 30)

        val start = at(today)
        val end = start + 40 * 60_000L
        val reconciler = HistoryReconciler(store, clock, zone = { zone }, recoveryGrant = { fingerprint })
        reconciler.observe(listOf(HistoryRule("session-today", "Focus timer", "easy", end)))
        // The session runs to its planned end, then the terminal arrives from the normal path.
        clock.nowMs = end
        assertTrue(
            store.append(
                HistoryEvent(
                    id = "end-session-today", type = "completed", session = "session-today",
                    at = end, tz = zone.id,
                ),
            ),
        )
        store.load()

        val sessions = FocusHistoryAggregator.sessions(store.snapshots.value.events, end, emptySet())
        val challenge = (StreakRecoveryPolicy.evaluate(
            sessions, store.snapshots.value.events, end, zone, today.minusDays(40), store.snapshots.value.readable,
        ) as StreakRecoveryPolicy.Eligibility.Offer).challenge
        assertTrue(
            "reconciler-stamped session must satisfy the challenge",
            StreakRecoveryPolicy.isSatisfied(
                sessions, challenge, zone, store.snapshots.value.events, fingerprint,
            ),
        )

        val result = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "reconciler-e2e", protection = protection(),
        )
        assertTrue("expected success, got $result", result is StreakRecoveryCommitter.Result.Success)
        assertEquals(1, store.snapshots.value.events.count { it.type == "streak_recovered" })
    }

    @Test fun sessionStartedUnderAnotherFreezeDoesNotQualify() = runBlocking {
        val now = at(today)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        val other = StreakRecoveryProtection.fingerprint(listOf("com.example.other"), false)
        val reconciler = HistoryReconciler(store, clock, zone = { zone }, recoveryGrant = { other })
        reconciler.observe(listOf(HistoryRule("session-other", "Focus timer", "easy", now + 40 * 60_000L)))

        val started = store.snapshots.value.events.single { it.knownType == HistoryEventType.STARTED }
        assertEquals(other, started.grant)
        assertTrue(
            StreakRecoveryProtection.qualifyingSessionIds(store.snapshots.value.events, fingerprint)
                .isEmpty(),
        )
    }
}
