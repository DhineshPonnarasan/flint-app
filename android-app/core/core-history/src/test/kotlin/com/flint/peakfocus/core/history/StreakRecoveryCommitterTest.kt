package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakRecoveryCommitterTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val packages = listOf("com.instagram.android")
    private val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)

    private fun at(day: LocalDate, hour: Int = 10): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun protection(day: LocalDate = today) = StreakRecoveryProtection.bind(
        packages, false, day, day.minusDays(1), at(day),
    )!!

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun openStore(
        clock: MutableClock,
        directory: File = createTempDirectory("flint-recovery").toFile(),
        authenticator: CheckpointAuthenticator? = null,
    ): FocusHistoryStore = if (authenticator == null) {
        FocusHistoryStore(directory = directory, clock = clock, zone = { zone })
    } else {
        FocusHistoryStore(
            directory = directory, clock = clock, authenticator = authenticator, zone = { zone },
        )
    }

    private suspend fun seedCompleted(
        store: FocusHistoryStore,
        id: String,
        day: LocalDate,
        minutes: Int,
        grant: String? = null,
    ) {
        val start = at(day, 9)
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

    @Test fun durableSuccessConsumesTokenOnce() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        seedCompleted(store, "b", today, 40, grant = fingerprint)

        val first = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "rec-1", protection = protection(),
        )
        assertTrue("expected success, got $first", first is StreakRecoveryCommitter.Result.Success)
        assertTrue(store.snapshots.value.events.any { it.type == "streak_recovered" })

        val second = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "rec-2", protection = protection(),
        )
        assertTrue(second is StreakRecoveryCommitter.Result.Rejected)
        assertEquals(
            1,
            store.snapshots.value.events.count { it.type == "streak_recovered" },
        )
    }

    @Test fun insufficientDurationDoesNotConsume() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        seedCompleted(store, "b", today, 20, grant = fingerprint)

        val result = StreakRecoveryCommitter.tryCommit(store, zone = zone, protection = protection())
        assertTrue(result is StreakRecoveryCommitter.Result.Rejected)
        assertFalse(store.snapshots.value.events.any { it.type == "streak_recovered" })
        assertTrue(StreakRecoveryPolicy.tokenAvailable(store.snapshots.value.events, store.millis()))
    }

    @Test fun substitutedAppsDoNotQualify() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        val calendarFp = StreakRecoveryProtection.fingerprint(listOf("com.google.android.calendar"), false)
        seedCompleted(store, "b", today, 40, grant = calendarFp)

        val result = StreakRecoveryCommitter.tryCommit(store, zone = zone, protection = protection())
        assertTrue(result is StreakRecoveryCommitter.Result.Rejected)
        assertEquals(
            "insufficient_duration",
            (result as StreakRecoveryCommitter.Result.Rejected).reason,
        )
        assertFalse(store.snapshots.value.events.any { it.type == "streak_recovered" })
    }

    @Test fun missingProtectionRejected() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        seedCompleted(store, "b", today, 40, grant = fingerprint)
        val result = StreakRecoveryCommitter.tryCommit(store, zone = zone, protection = null)
        assertTrue(result is StreakRecoveryCommitter.Result.Rejected)
        assertEquals("protection_required", (result as StreakRecoveryCommitter.Result.Rejected).reason)
    }

    @Test fun inactiveProtectionRejectedEvenWhenDurationMet() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        seedCompleted(store, "b", today, 40, grant = fingerprint)
        val result = StreakRecoveryCommitter.tryCommit(
            store,
            zone = zone,
            protection = protection(),
            protectionActive = false,
            protectionMatchesSelection = true,
        )
        assertTrue(result is StreakRecoveryCommitter.Result.Rejected)
        assertEquals("protection_inactive", (result as StreakRecoveryCommitter.Result.Rejected).reason)
        assertFalse(store.snapshots.value.events.any { it.type == "streak_recovered" })
        assertTrue(StreakRecoveryPolicy.tokenAvailable(store.snapshots.value.events, store.millis()))
    }

    @Test fun changedSelectionRejectedAsPermanentWithoutConsuming() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        seedCompleted(store, "b", today, 40, grant = fingerprint)
        val result = StreakRecoveryCommitter.tryCommit(
            store,
            zone = zone,
            protection = protection(),
            protectionActive = true,
            protectionMatchesSelection = false,
        )
        assertTrue(result is StreakRecoveryCommitter.Result.Rejected)
        assertEquals("protection_changed", (result as StreakRecoveryCommitter.Result.Rejected).reason)
        assertFalse(store.snapshots.value.events.any { it.type == "streak_recovered" })
        assertTrue(StreakRecoveryPolicy.tokenAvailable(store.snapshots.value.events, store.millis()))
    }

    @Test fun forgedRecoveryWithoutPolicyMetadataRejected() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        val missed = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val ok = store.append(
            HistoryEvent(
                id = "forged",
                type = "streak_recovered",
                session = "forged",
                at = now,
                tz = zone.id,
                breakEnd = missed,
            ),
        )
        assertFalse(ok)
        assertFalse(store.snapshots.value.events.any { it.type == "streak_recovered" })
    }

    @Test fun ignoresCallerSessionsNotInStore() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        val result = StreakRecoveryCommitter.tryCommit(store, zone = zone, protection = protection())
        assertTrue(result is StreakRecoveryCommitter.Result.Rejected)
        assertFalse(store.snapshots.value.events.any { it.type == "streak_recovered" })
    }

    @Test fun replayingTheSameRecoveryIdDoesNotAppendTwice() = runBlocking {
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        seedCompleted(store, "b", today, 40, grant = fingerprint)
        val first = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "replay-1", protection = protection(),
        )
        assertTrue(first is StreakRecoveryCommitter.Result.Success)
        val replay = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "replay-1", protection = protection(),
        )
        assertTrue(replay is StreakRecoveryCommitter.Result.Rejected)
        assertEquals(1, store.snapshots.value.events.count { it.type == "streak_recovered" })
    }

    @Test fun concurrentCommitsConsumeAtMostOneToken() = runBlocking {        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(clock)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        seedCompleted(store, "b", today, 40, grant = fingerprint)
        val results = coroutineScope {
            (0 until 4).map { index ->
                async {
                    StreakRecoveryCommitter.tryCommit(
                        store, zone = zone, recoveryId = "race-$index", protection = protection(),
                    )
                }
            }.awaitAll()
        }
        assertEquals(1, results.count { it is StreakRecoveryCommitter.Result.Success })
        assertEquals(1, store.snapshots.value.events.count { it.type == "streak_recovered" })
    }

    /**
     * Blocks the first *ledger* signature (the durable allowance mirror written inside a
     * recovery commit). The commit holds the store lock and the authorization window at that
     * point, which lets a test place a forged append exactly inside the window without sleeps.
     */
    private class GatedAuthenticator(
        private val delegate: CheckpointAuthenticator,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : CheckpointAuthenticator {
        private val armed = AtomicBoolean(true)

        override fun sign(payload: String): String? {
            if (payload.startsWith("recovery-ledger|") && armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "the commit window was never released" }
            }
            return delegate.sign(payload)
        }

        override fun verify(payload: String, mac: String): Boolean = delegate.verify(payload, mac)
    }

    /** A well-formed bridge row for the eligible day that nobody authorized. */
    private fun forgedRecovery(now: Long, id: String = "forged-race"): HistoryEvent {
        val missed = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return HistoryEvent(
            id = id, type = "streak_recovered", session = id, at = now, tz = zone.id,
            plannedEnd = 40 * 60_000L, breakEnd = missed, recoveryEnd = 30 * 60_000L,
            sessions = listOf("b"), source = fingerprint, grant = id,
            reason = StreakRecoveryPolicy.REASON, originRoute = StreakRecoveryPolicy.ORIGIN,
        )
    }

    /** Prior-day 30m completion plus the recovery-day 40m grant-bound completion. */
    private suspend fun eligibleStore(
        clock: MutableClock,
        now: Long,
        directory: File,
        authenticator: CheckpointAuthenticator? = null,
    ): FocusHistoryStore {
        val store = openStore(clock, directory, authenticator)
        store.load()
        clock.nowMs = now
        seedCompleted(store, "a", today.minusDays(2), 30)
        seedCompleted(store, "b", today, 40, grant = fingerprint)
        return store
    }

    private fun FocusHistoryStore.recoveredIds() =
        snapshots.value.events.filter { it.type == "streak_recovered" }.map { it.id }

    /**
     * Ordering B — the forged append runs first. This is the ordering that used to fail: the
     * refusal degraded the store, and the legitimate commit was then rejected as unreadable.
     */
    @Test fun rejectedForgedAppendBeforeTheCommitDoesNotDegradeOrBlockIt() = runBlocking {
        val now = at(today, 18)
        val dir = createTempDirectory("flint-race-forged-first").toFile()
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = eligibleStore(clock, now, dir)
        val ledger = File(dir, "recovery-ledger.v1.json")
        assertFalse("no allowance has been consumed yet", ledger.exists())

        assertFalse("an unauthorized bridge row must be refused", store.append(forgedRecovery(now)))
        assertFalse("a refused write is not store corruption", store.snapshots.value.degraded)
        assertEquals(emptyList<String>(), store.recoveredIds())
        assertFalse("a refused write must not touch the allowance mirror", ledger.exists())

        val result = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "legit-race", protection = protection(),
        )
        assertTrue("a refused forged append cannot block the real commit, got $result",
            result is StreakRecoveryCommitter.Result.Success)
        assertFalse(store.snapshots.value.degraded)
        assertEquals(listOf("legit-race"), store.recoveredIds())
        assertEquals(now, store.snapshots.value.recoveryFloorAt)
    }

    /** Ordering A — the legitimate commit runs first; the forged append must change nothing. */
    @Test fun rejectedForgedAppendAfterTheCommitDoesNotDegradeOrRefund() = runBlocking {
        val now = at(today, 18)
        val dir = createTempDirectory("flint-race-commit-first").toFile()
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = eligibleStore(clock, now, dir)
        val committed = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "legit-race", protection = protection(),
        )
        assertTrue(committed is StreakRecoveryCommitter.Result.Success)

        val ledger = File(dir, "recovery-ledger.v1.json")
        val sealed = ledger.readBytes()
        assertFalse("the forged row must be refused", store.append(forgedRecovery(now, "forged-after")))
        assertFalse("a refused write is not store corruption", store.snapshots.value.degraded)
        assertTrue("a refused write must not rewrite the allowance", sealed.contentEquals(ledger.readBytes()))
        assertEquals(listOf("legit-race"), store.recoveredIds())

        val snapshot = store.snapshots.value
        val dayMs = 24L * 60 * 60 * 1000
        assertFalse("the allowance must stay consumed",
            StreakRecoveryPolicy.tokenAvailable(snapshot.events, now + dayMs, snapshot.recoveryFloorAt))
        assertEquals(now + StreakRecoveryPolicy.COOLDOWN_MS,
            StreakRecoveryPolicy.nextTokenAvailableAt(snapshot.events, snapshot.recoveryFloorAt))
    }

    /**
     * The genuine overlap: the forged append is issued while the commit holds the lock and its
     * authorization window (the gate blocks the ledger write inside the commit). Whichever side
     * is scheduled, exactly the authorized row may exist and the store must stay healthy.
     */
    @Test fun forgedAppendInsideTheCommitAuthorizationWindowIsRejected() = runBlocking {
        val now = at(today, 18)
        val dir = createTempDirectory("flint-race-window").toFile()
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = eligibleStore(
            clock, now, dir,
            GatedAuthenticator(HmacCheckpointAuthenticator(ByteArray(32) { 7 }), entered, release),
        )
        val commit = async(Dispatchers.IO) {
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "legit-race", protection = protection(),
            )
        }
        assertTrue("the commit must reach its durable write", entered.await(10, TimeUnit.SECONDS))
        val forged = async(Dispatchers.IO) { store.append(forgedRecovery(now)) }
        assertFalse("the append cannot have completed inside the window", forged.isCompleted)
        release.countDown()

        val result = commit.await()
        assertTrue("the authorized commit must win its own window, got $result",
            result is StreakRecoveryCommitter.Result.Success)
        assertFalse("the forged append must be refused", forged.await())
        assertFalse(store.snapshots.value.degraded)
        assertEquals(listOf("legit-race"), store.recoveredIds())
    }

    /** Two authorized commits: the window and the allowance may be consumed exactly once. */
    @Test fun twoConcurrentCommitsConsumeTheAuthorizationOnce() = runBlocking {
        val now = at(today, 18)
        val dir = createTempDirectory("flint-race-double").toFile()
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = eligibleStore(clock, now, dir)

        val results = coroutineScope {
            listOf("legit-one", "legit-two").map { id ->
                async(Dispatchers.IO) {
                    StreakRecoveryCommitter.tryCommit(
                        store, zone = zone, recoveryId = id, protection = protection(),
                    )
                }
            }.awaitAll()
        }
        assertEquals(1, results.count { it is StreakRecoveryCommitter.Result.Success })
        assertEquals(1, results.count { it is StreakRecoveryCommitter.Result.Rejected })
        assertEquals(0, results.count { it is StreakRecoveryCommitter.Result.PersistFailed })
        assertEquals(1, store.recoveredIds().size)
        assertFalse("a rejected commit is not store corruption", store.snapshots.value.degraded)
        assertEquals(now, store.snapshots.value.recoveryFloorAt)
    }

    /** A refused authorization must not become valid credit after a process restart. */
    @Test fun restartAfterARejectedForgedAppendDoesNotRestoreCredit() = runBlocking {
        val now = at(today, 18)
        val dir = createTempDirectory("flint-race-restart").toFile()
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = eligibleStore(clock, now, dir)
        assertFalse(store.append(forgedRecovery(now)))

        val reopened = openStore(clock, dir)
        reopened.load()
        assertFalse("a refused write must not persist a degraded flag", reopened.snapshots.value.degraded)
        assertEquals(emptyList<String>(), reopened.recoveredIds())
        assertFalse("authorization never survives a restart",
            reopened.append(forgedRecovery(now, "forged-restart")))
        assertFalse(reopened.snapshots.value.degraded)

        val result = StreakRecoveryCommitter.tryCommit(
            reopened, zone = zone, recoveryId = "legit-restart", protection = protection(),
        )
        assertTrue("the allowance is still available, got $result",
            result is StreakRecoveryCommitter.Result.Success)
        assertEquals(listOf("legit-restart"), reopened.recoveredIds())
        assertEquals(now, reopened.snapshots.value.recoveryFloorAt)
    }
}
