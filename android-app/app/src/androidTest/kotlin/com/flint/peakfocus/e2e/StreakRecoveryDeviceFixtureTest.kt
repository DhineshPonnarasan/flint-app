package com.flint.peakfocus.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flint.peakfocus.core.history.FocusHistoryAggregator
import com.flint.peakfocus.core.history.FocusHistoryStore
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.RecoveryChallengeGuardStore
import com.flint.peakfocus.core.history.RecoveryEnforcementWatch
import com.flint.peakfocus.core.history.StreakRecoveryCommitter
import com.flint.peakfocus.core.history.StreakRecoveryPolicy
import com.flint.peakfocus.core.history.StreakRecoveryProtection
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated on-device recovery fixtures.
 *
 * This is a self-instrumenting test APK (`app.flint.focus.test`), so `targetContext` is the test
 * package: every store points at its own cache directory and the owner's
 * `app.flint.focus/files/focus-history` log is never read, written or cleared by the test.
 *
 * IMPORTANT — the Gradle wrapper still uninstalls the *tested* app when the run ends, which
 * deletes that app's local data. Always run this with
 * `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (the project's
 * `scripts/android-e2e.sh` already does) or the owner's history is destroyed on teardown.
 */
@RunWith(AndroidJUnit4::class)
class StreakRecoveryDeviceFixtureTest {
    /** Half-hour offset zone: day math must not assume whole-hour shifts. */
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 28)
    private val packages = listOf("com.instagram.android")
    private val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)
    private lateinit var root: File

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "recovery-fixture-${System.nanoTime()}").apply { mkdirs() }
        // Hard guard: a fixture must never point at the owner's real history directory.
        assertFalse(root.absolutePath.contains("files/focus-history"))
        assertFalse(root.absolutePath.contains("app.flint.focus/files"))
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun at(day: LocalDate, hour: Int = 9, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun store(clock: Clock): FocusHistoryStore =
        FocusHistoryStore(directory = root, clock = clock) { zone }

    private fun protection(day: LocalDate = today) = StreakRecoveryProtection.bind(
        packages, false, day, day.minusDays(1), at(day, 18),
    )!!

    private suspend fun seedCompleted(
        store: FocusHistoryStore,
        id: String,
        day: LocalDate,
        minutes: Int,
        grant: String? = null,
        recordedZone: ZoneId = zone,
    ) {
        val start = at(day)
        val end = start + minutes * 60_000L
        assertTrue(
            store.append(
                HistoryEvent(
                    id = "start-$id", type = "started", session = id, at = start, tz = recordedZone.id,
                    plannedEnd = end, tier = "easy", source = "manual", name = "Focus", grant = grant,
                ),
            ),
        )
        assertTrue(
            store.append(
                HistoryEvent(
                    id = "end-$id", type = "completed", session = id, at = end, tz = recordedZone.id,
                ),
            ),
        )
    }

    @Test fun firstCompletionIsDayOneAndSameDayCompletionsCountOnce() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val history = store(clock)
        history.load()
        clock.nowMs = now
        seedCompleted(history, "one", today, 20)
        seedCompleted(history, "two", today, 20)
        history.load()

        val summary = FocusHistoryAggregator.summarize(history.snapshots.value, now, zone, emptySet())
        assertEquals(1, summary.streak)
        assertEquals(1, summary.days.count { it.completed > 0 })
    }

    @Test fun recoveryBridgesOneMissedDayAndSurvivesReopen() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val history = store(clock)
        history.load()
        clock.nowMs = now
        seedCompleted(history, "prior", today.minusDays(2), 30)
        seedCompleted(history, "resume", today, 40, grant = fingerprint)

        val offer = StreakRecoveryPolicy.evaluate(
            FocusHistoryAggregator.sessions(history.snapshots.value.events, now, emptySet()),
            history.snapshots.value.events, now, zone, today.minusDays(40), readable = true,
        )
        assertTrue("a valid missed-day fixture must produce an offer", offer is StreakRecoveryPolicy.Eligibility.Offer)

        val result = StreakRecoveryCommitter.tryCommit(
            history, zone = zone, recoveryId = "device-1", protection = protection(),
        )
        assertTrue("expected success, got $result", result is StreakRecoveryCommitter.Result.Success)

        // Reopen the same durable directory: the bridge must be read back, not re-derived.
        val reopened = store(clock)
        reopened.load()
        assertEquals(1, reopened.snapshots.value.events.count { it.type == "streak_recovered" })
        val summary = FocusHistoryAggregator.summarize(reopened.snapshots.value, now, zone, emptySet())
        assertEquals(3, summary.streak)
        val missedDay = summary.days.first { it.date == today.minusDays(1) }
        assertEquals(0, missedDay.completed)
        assertTrue("the bridged day must be marked recovered, not completed", missedDay.recovered)
    }

    @Test fun replayingTheSameRecoveryIdIsRejected() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val history = store(clock)
        history.load()
        clock.nowMs = now
        seedCompleted(history, "prior", today.minusDays(2), 30)
        seedCompleted(history, "resume", today, 40, grant = fingerprint)

        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                history, zone = zone, recoveryId = "device-replay", protection = protection(),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                history, zone = zone, recoveryId = "device-replay", protection = protection(),
            ) is StreakRecoveryCommitter.Result.Rejected,
        )
        assertEquals(1, history.snapshots.value.events.count { it.type == "streak_recovered" })
    }

    @Test fun recordedZoneKeepsTheDayWhenTheDeviceZoneIsAhead() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val history = store(clock)
        history.load()
        clock.nowMs = now
        // 02:00 IST tomorrow == 13:30 today in Los Angeles. The completion belongs to today.
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val start = at(today.plusDays(1), 2)
        val end = start + 30 * 60_000L
        clock.nowMs = start
        assertTrue(
            history.append(
                HistoryEvent(
                    id = "start-la", type = "started", session = "la", at = start, tz = losAngeles.id,
                    plannedEnd = end, tier = "easy", source = "manual", name = "Focus", grant = fingerprint,
                ),
            ),
        )
        // A terminal cannot be recorded in the future; time passes before it arrives.
        clock.nowMs = end
        assertTrue(
            history.append(
                HistoryEvent(
                    id = "end-la", type = "completed", session = "la", at = end, tz = losAngeles.id,
                ),
            ),
        )
        history.load()

        val sessions = FocusHistoryAggregator.sessions(history.snapshots.value.events, end, emptySet())
        val credit = StreakRecoveryProtection.focusedMsOnDay(
            sessions, today, zone, history.snapshots.value.events, fingerprint,
        )
        assertEquals("30 minutes recorded in Los Angeles belongs to the LA day", 30 * 60_000L, credit)
        val tomorrowCredit = StreakRecoveryProtection.focusedMsOnDay(
            sessions, today.plusDays(1), zone, history.snapshots.value.events, fingerprint,
        )
        assertEquals(0L, tomorrowCredit)
    }

    @Test fun forgedRecoveryAppendThroughTheStoreIsRefused() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val history = store(clock)
        history.load()
        clock.nowMs = now
        seedCompleted(history, "prior", today.minusDays(2), 30)

        // Android has no file inbox; every publisher goes through the store's guarded append.
        // A recovery row must not be appendable without the committer's authorization.
        val forged = HistoryEvent(
            id = "forged", type = "streak_recovered", session = "forged", at = now, tz = zone.id,
            breakEnd = at(today.minusDays(1), 0), plannedEnd = 2_400_000L, recoveryEnd = 1_800_000L,
            sessions = listOf("prior"), source = fingerprint, grant = "forged",
            reason = "policy:v1", originRoute = "streak_recovery",
        )
        assertFalse("a forged bridge must never be appended", history.append(forged))
        assertEquals(0, history.snapshots.value.events.count { it.type == "streak_recovered" })
    }

    @Test fun degradedHistoryCannotCommitRecovery() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val history = store(clock)
        history.load()
        clock.nowMs = now
        seedCompleted(history, "prior", today.minusDays(2), 30)
        seedCompleted(history, "resume", today, 40, grant = fingerprint)
        File(root, "events.v1.jsonl").appendText("{\"id\":\"truncated\",\"type\":\"streak_rec\n")
        history.load()
        assertTrue(history.snapshots.value.degraded)

        val result = StreakRecoveryCommitter.tryCommit(
            history, zone = zone, recoveryId = "degraded", protection = protection(),
        )
        assertEquals(StreakRecoveryCommitter.Result.Rejected("history_unreadable"), result)
        assertEquals(0, history.snapshots.value.events.count { it.type == "streak_recovered" })
    }

    @Test fun secondMissedDayInsideCooldownIsRejectedAndNewSessionStartsAtOne() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val history = store(clock)
        history.load()
        clock.nowMs = now
        seedCompleted(history, "prior", today.minusDays(2), 30)
        seedCompleted(history, "resume", today, 40, grant = fingerprint)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                history, zone = zone, recoveryId = "cd-1", protection = protection(),
            ) is StreakRecoveryCommitter.Result.Success,
        )

        // Two days later: another day is missed, but the free allowance is still cooling down.
        clock.nowMs = at(today.plusDays(2), 18)
        seedCompleted(history, "next", today.plusDays(2), 40, grant = fingerprint)
        history.load()
        val eligibility = StreakRecoveryPolicy.evaluate(
            FocusHistoryAggregator.sessions(history.snapshots.value.events, clock.nowMs, emptySet()),
            history.snapshots.value.events, clock.nowMs, zone, today.minusDays(40), readable = true,
        )
        assertEquals(StreakRecoveryPolicy.Eligibility.Unavailable("token_cooldown"), eligibility)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                history, zone = zone, recoveryId = "cd-2",
                protection = protection(day = today.plusDays(2)),
            ) is StreakRecoveryCommitter.Result.Rejected,
        )

        // With the gap unrecovered, the new completion is a fresh one-day streak and best keeps
        // the historical three-day run.
        val summary = FocusHistoryAggregator.summarize(
            history.snapshots.value, clock.nowMs, zone, emptySet(),
        )
        assertEquals(1, summary.streak)
        assertEquals(3, summary.bestRecordedStreak)
    }

    @Test fun enforcementWatchLatchesOnADropAndNeverClears() = runBlocking {
        val dir = File(root, "watch").apply { mkdirs() }
        val guard = RecoveryChallengeGuardStore(dir)
        assertNotNull(guard.ensureFrozen(packages, false, today, today.minusDays(1), 0L))
        assertTrue(guard.isEnforcementIntact())

        val samples = listOf(true, false, true).iterator()
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { samples.next() },
            intervalMs = 0,
            iterations = 3,
        )

        assertFalse("a healthy to down edge must void the challenge", guard.isEnforcementIntact())
        // Re-enabling health must not clear it, and the latch is durable across a reopen.
        val reopened = RecoveryChallengeGuardStore(dir)
        assertFalse("the latch must survive a reopened store", reopened.isEnforcementIntact())
    }

    @Test fun sixtyConsecutiveDaysExtendToSixtyOne() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 80L * 24 * 60 * 60 * 1000, zone)
        val history = store(clock)
        history.load()
        clock.nowMs = now
        for (offset in 0..60) {
            seedCompleted(history, "day-$offset", today.minusDays((60 - offset).toLong()), 40)
        }
        history.load()
        val summary = FocusHistoryAggregator.summarize(history.snapshots.value, now, zone, emptySet())
        assertEquals(61, summary.streak)
        assertEquals(61, summary.bestRecordedStreak)
    }

    // MARK: - M1 watcher bind seeding (Phase 20)
    //
    // The bind is injected at the sample boundary — exactly where the card's bind can land
    // between two samples — so the production loop observes it deterministically. The policy
    // state is never seeded by hand: only `binding = { guard.activeBinding() }` feeds it.

    private fun latchFile(dir: File) = File(dir, "recovery-enforcement-latch.v1.json")

    private fun latchedDurably(dir: File): Boolean {
        val file = latchFile(dir)
        return file.isFile && file.readText().contains("challengeId")
    }

    private fun latchedDay(dir: File): String? {
        val file = latchFile(dir)
        if (!file.isFile) return null
        return Regex("\"recoveryDay\":\"([^\"]+)\"").find(file.readText())?.groupValues?.get(1)
    }

    /**
     * Public equivalent of the module-internal `activeBinding()` used by the production wiring:
     * the challenge identity plus its verified continuity, read from the same guard store. The
     * watcher's `binding` callback is the code under test here; the atomic helper itself stays
     * internal and is covered by the core-history JVM suite.
     */
    private fun bindingOf(guard: RecoveryChallengeGuardStore) =
        guard.current()?.let {
            RecoveryChallengeGuardStore.ActiveBinding(it.challengeId, guard.isEnforcementIntact())
        }

    @Test fun watcherSeedsFromTheBindAndLatchesItsFirstDrop() = runBlocking {
        val dir = File(root, "watch-bind").apply { mkdirs() }
        val fixedMs = at(today, 18)
        val guard = RecoveryChallengeGuardStore(dir, clock = { fixedMs })
        assertNull("the watcher starts before any challenge", guard.current())

        var healthy = false // the interruption lands with the bind
        var started = false
        var sawNoBinding = false
        var bound = false

        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = {
                if (!started) {
                    started = true
                    sawNoBinding = guard.current() == null
                    false
                } else if (!bound) {
                    // A challenge is bound between two samples while enforcement is healthy.
                    bound = true
                    assertNotNull(
                        guard.ensureFrozen(
                            packages, false, today, today.minusDays(1), fixedMs, healthyNow = true,
                        ),
                    )
                    true
                } else {
                    guard.current() != null
                }
            },
            pathAHealthy = { healthy },
            intervalMs = 0,
            iterations = 2,
            binding = { bindingOf(guard) },
        )

        assertTrue("the first sample must see no active challenge", sawNoBinding)
        assertTrue(bound)
        assertFalse("the first drop after a verified bind must latch", guard.isEnforcementIntact())
        assertTrue("the latch must be durable", latchedDurably(dir))

        // Restoring enforcement must never clear the latch.
        healthy = true
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { guard.current() != null },
            pathAHealthy = { healthy },
            intervalMs = 0,
            iterations = 2,
            binding = { bindingOf(guard) },
        )
        assertFalse("re-enabling enforcement must not clear the latch", guard.isEnforcementIntact())
        assertTrue(latchedDurably(dir))
    }

    @Test fun aNewChallengeSeedsItsOwnStateAfterABreak() = runBlocking {
        val dir = File(root, "watch-reseed").apply { mkdirs() }
        val fixedMs = at(today, 18)
        val guard = RecoveryChallengeGuardStore(dir, clock = { fixedMs })
        assertNotNull(
            guard.ensureFrozen(packages, false, today, today.minusDays(1), fixedMs, healthyNow = true),
        )

        RecoveryEnforcementWatch.watch(
            guard = guard, freezeActive = { guard.current() != null },
            pathAHealthy = { false }, intervalMs = 0, iterations = 1,
            binding = { bindingOf(guard) },
        )
        assertFalse(guard.isEnforcementIntact())
        assertEquals(today.toString(), latchedDay(dir))

        // A new day's challenge is seeded from its own bind, not from the previous latch.
        val next = today.plusDays(1)
        assertNotNull(guard.ensureFrozen(packages, false, next, today, fixedMs, healthyNow = true))
        assertTrue("a new day must not inherit the previous latch", guard.isEnforcementIntact())

        RecoveryEnforcementWatch.watch(
            guard = guard, freezeActive = { guard.current() != null },
            pathAHealthy = { false }, intervalMs = 0, iterations = 1,
            binding = { bindingOf(guard) },
        )
        assertFalse("the new challenge must latch its own first drop", guard.isEnforcementIntact())
        assertEquals(next.toString(), latchedDay(dir))
    }

    @Test fun aStaleGenerationCannotOverwriteTheActiveChallengeState() = runBlocking {
        val dir = File(root, "watch-stale").apply { mkdirs() }
        val fixedMs = at(today, 18)
        val active = RecoveryChallengeGuardStore(dir, clock = { fixedMs })
        assertNotNull(
            active.ensureFrozen(packages, false, today, today.minusDays(1), fixedMs, healthyNow = true),
        )
        assertTrue(active.markEnforcementBroken())
        assertFalse(active.isEnforcementIntact())

        // The next process reads the same freeze and must remain fail-closed.
        val stale = RecoveryChallengeGuardStore(dir, clock = { fixedMs })
        stale.noteWatchStarted()
        assertFalse("a freeze from another process cannot prove continuity", stale.isEnforcementIntact())

        RecoveryEnforcementWatch.watch(
            guard = stale, freezeActive = { stale.current() != null },
            pathAHealthy = { true }, intervalMs = 0, iterations = 2,
            binding = { bindingOf(stale) },
        )
        assertFalse("healthy samples must not clear the stale latch", stale.isEnforcementIntact())

        // Re-binding the same day returns the stored freeze; it cannot be replaced as healthy.
        assertNotNull(
            stale.ensureFrozen(packages, false, today, today.minusDays(1), fixedMs, healthyNow = true),
        )
        assertFalse("re-binding must not rehabilitate the stale freeze", stale.isEnforcementIntact())
        assertTrue(latchedDurably(dir))
    }
}
