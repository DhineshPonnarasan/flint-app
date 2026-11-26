package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 30-day allowance must survive every path that deletes session rows: a normal Clear History,
 * an interrupted clear at any phase, an over-budget reset, and a restart. The durable ledger is
 * the only carrier; recovery rows are no longer retained for the cooldown.
 */
class RecoveryLedgerTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val packages = listOf("com.instagram.android")
    private val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)
    private val dayMs = 24L * 60 * 60 * 1000

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun at(day: LocalDate, hour: Int = 9): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun openStore(
        dir: File,
        clock: Clock,
        clearPhase: (HistoryClearPhase) -> Unit = {},
    ) = FocusHistoryStore(
        directory = dir,
        clock = clock,
        clearPhase = clearPhase,
        zone = { zone },
    )

    private fun protection(day: LocalDate = today) = StreakRecoveryProtection.bind(
        packages, false, day, day.minusDays(1), at(day, 18),
    )!!

    private suspend fun seedEligible(store: FocusHistoryStore) {
        val priorStart = at(today.minusDays(2))
        val priorEnd = priorStart + 30 * 60_000L
        assertTrue(store.append(HistoryEvent(
            id = "start-a", type = "started", session = "a", at = priorStart, tz = zone.id,
            plannedEnd = priorEnd, tier = "easy", source = "manual", name = "Focus",
            grant = fingerprint,
        )))
        assertTrue(store.append(HistoryEvent(
            id = "end-a", type = "completed", session = "a", at = priorEnd, tz = zone.id,
        )))
        val todayStart = at(today)
        val todayEnd = todayStart + 40 * 60_000L
        assertTrue(store.append(HistoryEvent(
            id = "start-b", type = "started", session = "b", at = todayStart, tz = zone.id,
            plannedEnd = todayEnd, tier = "easy", source = "manual", name = "Focus",
            grant = fingerprint,
        )))
        assertTrue(store.append(HistoryEvent(
            id = "end-b", type = "completed", session = "b", at = todayEnd, tz = zone.id,
        )))
    }

    /** Seed + durable recovery at [now]; returns the store and clock. */
    private suspend fun recoveredStore(
        dir: File,
        clearPhase: (HistoryClearPhase) -> Unit = {},
        now: Long = at(today, 18),
    ): Pair<FocusHistoryStore, MutableClock> {
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = openStore(dir, clock, clearPhase)
        store.load()
        clock.nowMs = now
        seedEligible(store)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        return store to clock
    }

    private fun assertAllowanceHeld(store: FocusHistoryStore, now: Long) {
        val snapshot = store.snapshots.value
        val floor = snapshot.recoveryFloorAt
        assertNotNull("the ledger must carry the recovery", floor)
        assertEquals(now, floor)
        assertFalse(
            "the allowance must not reopen",
            StreakRecoveryPolicy.tokenAvailable(snapshot.events, now + dayMs, floor),
        )
        assertEquals(
            now + StreakRecoveryPolicy.COOLDOWN_MS,
            StreakRecoveryPolicy.nextTokenAvailableAt(snapshot.events, floor),
        )
        assertEquals(
            "no session row is retained for the cooldown",
            snapshot.events.count { StreakRecoveryPolicy.isWellFormedRecovery(it) },
            0,
        )
    }

    private suspend fun rejectedImmediately(store: FocusHistoryStore) {
        val result = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "too-soon", protection = protection(),
        )
        assertTrue("recovery must be rejected, got $result", result is StreakRecoveryCommitter.Result.Rejected)
        assertEquals(0, store.snapshots.value.events.count { StreakRecoveryPolicy.isWellFormedRecovery(it) })
    }

    @Test fun normalClearPreservesTheAllowanceThroughTheLedger() = runBlocking {
        val dir = createTempDirectory("flint-ledger-clear").toFile()
        val now = at(today, 18)
        val (store, _) = recoveredStore(dir, now = now)
        assertTrue(store.clear(emptySet()))
        store.load()

        assertAllowanceHeld(store, now)
        rejectedImmediately(store)
    }

    @Test fun interruptedBeforeLogReplacementPreservesTheAllowance() = runBlocking {
        val dir = createTempDirectory("flint-ledger-before").toFile()
        val now = at(today, 18)
        val (store, clock) = recoveredStore(dir, clearPhase = { phase ->
            if (phase == HistoryClearPhase.INTENT_COMMITTED) throw IllegalStateException("crash")
        }, now = now)
        assertFalse(store.clear(emptySet()))

        // Restart during the pending clear: the next read completes it.
        val reopened = openStore(dir, clock)
        reopened.load()
        assertAllowanceHeld(reopened, now)
        rejectedImmediately(reopened)
    }

    @Test fun interruptedAfterLogReplacementPreservesTheAllowance() = runBlocking {
        val dir = createTempDirectory("flint-ledger-after").toFile()
        val now = at(today, 18)
        val (store, clock) = recoveredStore(dir, clearPhase = { phase ->
            if (phase == HistoryClearPhase.LOG_REPLACED) throw IllegalStateException("crash")
        }, now = now)
        assertFalse(store.clear(emptySet()))

        val reopened = openStore(dir, clock)
        reopened.load()
        assertAllowanceHeld(reopened, now)
        rejectedImmediately(reopened)
    }

    @Test fun interruptedBeforeMetadataCompletionPreservesTheAllowance() = runBlocking {
        val dir = createTempDirectory("flint-ledger-meta").toFile()
        val now = at(today, 18)
        val (store, clock) = recoveredStore(dir, clearPhase = { phase ->
            if (phase == HistoryClearPhase.METADATA_COMMITTED) throw IllegalStateException("crash")
        }, now = now)
        // The final metadata write already happened; the failure only surfaces as `false`.
        assertFalse(store.clear(emptySet()))

        val reopened = openStore(dir, clock)
        reopened.load()
        assertAllowanceHeld(reopened, now)
        rejectedImmediately(reopened)
    }

    @Test fun overBudgetResetFollowedByClearKeepsTheAllowance() = runBlocking {
        val dir = createTempDirectory("flint-ledger-over").toFile()
        val now = at(today, 18)
        val (store, _) = recoveredStore(dir, now = now)

        // Push the log over the record budget; the safety reset empties it.
        val junk = (0..20_001).joinToString("") { "{\"v\":1}\n" }
        File(dir, "events.v1.jsonl").appendText(junk)
        store.load()
        assertTrue("the over-budget reset must degrade the store", store.snapshots.value.degraded)
        assertEquals(0, store.snapshots.value.events.size)

        assertTrue(store.clear(emptySet()))
        store.load()
        assertAllowanceHeld(store, now)
        rejectedImmediately(store)
    }

    @Test fun failedLedgerWriteIsDegradedAndClearStartsACooldown() = runBlocking {
        val dir = createTempDirectory("flint-ledger-writefail").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = openStore(dir, clock)
        store.load()
        clock.nowMs = now
        seedEligible(store)

        // A directory at the ledger's staging path makes the mirror write fail after the event
        // is durable (the atomic replace never starts).
        val block = File(dir, "recovery-ledger.v1.json.tmp")
        assertTrue(block.mkdirs())
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertTrue("a failed allowance mirror must fail closed", store.load().degraded)

        // Simulate the log being lost by the over-budget safety reset: the events are gone, so
        // only the durable degraded flag can preserve the allowance.
        val junk = (0..20_001).joinToString("") { "{\"v\":1}\n" }
        File(dir, "events.v1.jsonl").appendText(junk)
        store.load()
        assertEquals(0, store.snapshots.value.events.size)

        assertTrue(store.clear(emptySet()))
        store.load()
        val floor = store.snapshots.value.recoveryFloorAt
        assertNotNull("a degraded store must not clear into a refund", floor)
        assertEquals(clock.millis(), floor)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(store.snapshots.value.events, now + dayMs, floor))
    }

    @Test fun missingLedgerIsSeededFromRecoveryRows() = runBlocking {
        val dir = createTempDirectory("flint-ledger-seed").toFile()
        val now = at(today, 18)
        val (store, _) = recoveredStore(dir, now = now)
        File(dir, "recovery-ledger.v1.json").delete()

        store.load()
        assertEquals("load repairs the ledger from durable rows", now, store.snapshots.value.recoveryFloorAt)
    }

    @Test fun corruptLedgerFailsClosedAndClearStartsACooldown() = runBlocking {
        val dir = createTempDirectory("flint-ledger-corrupt").toFile()
        val now = at(today, 18)
        val (store, clock) = recoveredStore(dir, now = now)
        File(dir, "recovery-ledger.v1.json").writeText("{not json")

        store.load()
        assertTrue("an unreadable ledger must fail closed", store.snapshots.value.degraded)
        // The event still proves the allowance; the point is that it is not reopened.
        assertEquals(now, store.snapshots.value.recoveryFloorAt)

        assertTrue(store.clear(emptySet()))
        store.load()
        val floor = store.snapshots.value.recoveryFloorAt
        assertNotNull("a clear repairs the corrupt ledger with a fresh cooldown", floor)
        assertEquals(clock.millis(), floor)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(store.snapshots.value.events, now + dayMs, floor))
    }
}
