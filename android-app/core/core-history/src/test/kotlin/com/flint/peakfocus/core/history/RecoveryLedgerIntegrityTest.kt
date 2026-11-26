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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery ledger is authenticated: an unverifiable, replayed, future or legacy-unsigned
 * value can never refund the 30-day allowance. Corruption is repaired fail-closed from the
 * durable event floor, or from "now" when no floor can be established.
 */
class RecoveryLedgerIntegrityTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val packages = listOf("com.instagram.android")
    private val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)
    private val key = ByteArray(32) { 11 }
    private val dayMs = 24L * 60 * 60 * 1000
    private val ledgerName = "recovery-ledger.v1.json"

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    private object AlwaysFailing : CheckpointAuthenticator {
        override fun sign(payload: String): String? = null
        override fun verify(payload: String, mac: String): Boolean = false
    }

    private fun at(day: LocalDate, hour: Int = 9): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun openStore(
        dir: File,
        clock: Clock,
        authenticator: CheckpointAuthenticator = HmacCheckpointAuthenticator(key),
    ) = FocusHistoryStore(
        directory = dir,
        clock = clock,
        authenticator = authenticator,
        zone = { zone },
    )

    private fun protection(day: LocalDate) = StreakRecoveryProtection.bind(
        packages, false, day, day.minusDays(1), at(day, 18),
    )!!

    private suspend fun seedCompleted(
        store: FocusHistoryStore,
        id: String,
        day: LocalDate,
        minutes: Long = 40,
    ) {
        val start = at(day)
        val end = start + minutes * 60_000L
        assertTrue(store.append(HistoryEvent(
            id = "start-$id", type = "started", session = id, at = start, tz = zone.id,
            plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
            grant = fingerprint,
        )))
        assertTrue(store.append(HistoryEvent(
            id = "end-$id", type = "completed", session = id, at = end, tz = zone.id,
        )))
    }

    /** Prior-day completion plus the recovery-day completion. */
    private suspend fun seedEligible(store: FocusHistoryStore, day: LocalDate) {
        // A 30-minute prior day keeps the required recovery focus (prior + 10 min) at 40 minutes.
        seedCompleted(store, "prior-$day", day.minusDays(2), minutes = 30)
        seedCompleted(store, "today-$day", day)
    }

    private suspend fun recoveredStore(
        dir: File,
        clock: MutableClock,
        day: LocalDate,
        now: Long,
    ): FocusHistoryStore {
        // Stamp historyStart before the recovery-day events exist, then move to the test time.
        clock.nowMs = now - 40 * dayMs
        val store = openStore(dir, clock)
        store.load()
        clock.nowMs = now
        seedEligible(store, day)
        return store
    }

    private fun writeLedger(dir: File, at: Long, mac: String?) {
        dir.mkdirs()
        val macField = if (mac == null) "null" else "\"$mac\""
        File(dir, ledgerName).writeText("{\"v\":1,\"lastRecoveryAt\":$at,\"mac\":$macField}")
    }

    /** Payload documented in docs/architecture/streak-best-retention.md. */
    private fun ledgerPayload(at: Long) = "recovery-ledger|v1|$at"

    private fun signedLedger(dir: File, at: Long) {
        val mac = HmacCheckpointAuthenticator(key).sign(ledgerPayload(at))!!
        writeLedger(dir, at, mac)
    }

    private fun readLedger(dir: File): String = File(dir, ledgerName).readText()

    @Test fun validSignedLedgerSurvivesReopenAndPreservesTheCooldown() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-ok").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )

        val reopened = openStore(dir, clock)
        reopened.load()
        assertEquals(now, reopened.snapshots.value.recoveryFloorAt)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, now))
        assertEquals(now + StreakRecoveryPolicy.COOLDOWN_MS,
            StreakRecoveryPolicy.nextTokenAvailableAt(reopened.snapshots.value.events, now))
    }

    @Test fun changedTimestampWithTheOldMacIsRejected() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-edit").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        val original = readLedger(dir)
        val mac = Regex("\"mac\":\"([0-9a-f]+)\"").find(original)!!.groupValues[1]
        writeLedger(dir, now + 3_600_000L, mac) // one hour later, same MAC

        val reopened = openStore(dir, clock)
        reopened.load()
        assertEquals("the untrusted value is repaired from the durable event floor", now,
            reopened.snapshots.value.recoveryFloorAt)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, now))
        assertEquals(now + StreakRecoveryPolicy.COOLDOWN_MS,
            StreakRecoveryPolicy.nextTokenAvailableAt(reopened.snapshots.value.events, now))
    }

    @Test fun missingMacFailsClosed() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-nomac").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        writeLedger(dir, now, null)

        val reopened = openStore(dir, clock)
        reopened.load()
        assertEquals(now, reopened.snapshots.value.recoveryFloorAt)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, now))
    }

    @Test fun malformedMacFailsClosed() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-badmac").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        writeLedger(dir, now, "not-a-hex-mac")

        val reopened = openStore(dir, clock)
        reopened.load()
        assertEquals(now, reopened.snapshots.value.recoveryFloorAt)
        assertTrue("a detected corruption must be visible", reopened.snapshots.value.degraded)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, now))
    }

    @Test fun failedRepairPersistenceBlocksRecoveryUntilItCanBeWritten() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-unwritable").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        File(dir, ledgerName).writeText("{corrupt")
        // A directory at the staging path makes the repair write fail on its first attempt.
        assertTrue(File(dir, "$ledgerName.tmp").mkdirs())

        val failing = openStore(dir, clock)
        failing.load()
        assertTrue("a detected corruption must be visible", failing.snapshots.value.degraded)
        val rejected = StreakRecoveryCommitter.tryCommit(
            failing, zone = zone, recoveryId = "again", protection = protection(today),
        )
        assertTrue("recovery must fail closed, got $rejected", rejected is StreakRecoveryCommitter.Result.Rejected)
        assertFalse(
            "the corrupt value must never read as an unused allowance",
            StreakRecoveryPolicy.tokenAvailable(
                failing.snapshots.value.events, now + dayMs, failing.snapshots.value.recoveryFloorAt,
            ),
        )
    }

    @Test fun malformedLedgerFailsClosedAndPreservesEvidence() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-malformed").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        File(dir, ledgerName).writeText("{not json")

        val reopened = openStore(dir, clock)
        reopened.load()
        assertEquals(now, reopened.snapshots.value.recoveryFloorAt)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, now))
        assertTrue("corrupt evidence must be retained", File(dir, "quarantine").listFiles()?.isNotEmpty() == true)
    }

    @Test fun unsupportedSchemaVersionFailsClosed() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-version").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        val mac = HmacCheckpointAuthenticator(key).sign(ledgerPayload(now))!!
        File(dir, ledgerName).writeText("{\"v\":2,\"lastRecoveryAt\":$now,\"mac\":\"$mac\"}")

        val reopened = openStore(dir, clock)
        reopened.load()
        assertEquals(now, reopened.snapshots.value.recoveryFloorAt)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, now))
    }

    @Test fun timestampMoreThanADayInTheFutureFailsClosed() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-future").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        // A *validly signed* value beyond the skew bound: the bound, not the MAC, rejects it.
        signedLedger(dir, now + dayMs + 3_600_000L)

        val reopened = openStore(dir, clock)
        reopened.load()
        assertEquals(now, reopened.snapshots.value.recoveryFloorAt)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, now))
    }

    @Test fun corruptionCannotMakeRecoveryAvailableImmediately() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-refund").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertTrue(store.clear(emptySet()))
        File(dir, ledgerName).writeText("{corrupt")

        val reopened = openStore(dir, clock)
        reopened.load()
        val floor = reopened.snapshots.value.recoveryFloorAt
        assertNotNull(floor)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, floor))
        val rejected = StreakRecoveryCommitter.tryCommit(
            reopened, zone = zone, recoveryId = "too-soon", protection = protection(today),
        )
        assertTrue("recovery must be rejected, got $rejected", rejected is StreakRecoveryCommitter.Result.Rejected)
    }

    @Test fun failedRepairSigningKeepsRecoveryUnavailable() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-nosign").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertTrue(store.clear(emptySet()))
        File(dir, ledgerName).writeText("{corrupt")

        val failing = openStore(dir, clock, AlwaysFailing)
        failing.load()
        assertTrue("an unrepairable ledger must degrade the store", failing.snapshots.value.degraded)
        val rejected = StreakRecoveryCommitter.tryCommit(
            failing, zone = zone, recoveryId = "again", protection = protection(today),
        )
        assertTrue("recovery must fail closed, got $rejected", rejected is StreakRecoveryCommitter.Result.Rejected)
        assertFalse("a clear must not bypass the fail-closed state", failing.clear(emptySet()))
    }

    @Test fun clearCannotRefundAnUnverifiableLedger() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-clear").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertTrue(store.clear(emptySet()))
        File(dir, ledgerName).writeText("{corrupt")

        val failing = openStore(dir, clock, AlwaysFailing)
        failing.load()
        assertFalse(failing.clear(emptySet()))

        // Once signing works again the repair starts a fresh cooldown from detection.
        val repaired = openStore(dir, clock)
        repaired.load()
        assertEquals(now, repaired.snapshots.value.recoveryFloorAt)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(repaired.snapshots.value.events, now + dayMs, now))
    }

    @Test fun overBudgetResetCannotRefundACorruptLedger() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-over").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        File(dir, ledgerName).writeText("{corrupt")
        File(dir, "events.v1.jsonl").appendText((0..20_001).joinToString("") { "{\"v\":1}\n" })

        val reopened = openStore(dir, clock)
        reopened.load()
        assertTrue(reopened.snapshots.value.degraded)
        val floor = reopened.snapshots.value.recoveryFloorAt
        assertNotNull(floor)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, floor))
    }

    @Test fun processRestartCannotTurnCorruptionIntoAnAllowance() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-restart").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertTrue(store.clear(emptySet()))
        File(dir, ledgerName).writeText("{corrupt")

        repeat(3) {
            val reopened = openStore(dir, clock)
            reopened.load()
            val floor = reopened.snapshots.value.recoveryFloorAt
            assertNotNull(floor)
            assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, now + dayMs, floor))
        }
    }

    @Test fun exactCooldownBoundaryAfterAuthenticatedPersistence() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-boundary").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, now)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        val reopened = openStore(dir, clock)
        reopened.load()
        val floor = reopened.snapshots.value.recoveryFloorAt!!
        val cooldown = StreakRecoveryPolicy.COOLDOWN_MS
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, floor + cooldown - 1, floor))
        assertTrue(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, floor + cooldown, floor))
        assertTrue(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, floor + cooldown + 1, floor))
    }

    @Test fun legacyUnsignedLedgerCannotRefundTheAllowance() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-legacy").toFile()
        val lastRecovery = at(today.minusDays(10), 18)
        val clock = MutableClock(lastRecovery - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today.minusDays(10), lastRecovery)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection(today.minusDays(10)),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        // A pre-integrity ledger: correct value, no MAC.
        writeLedger(dir, lastRecovery, null)

        val clockAtNow = MutableClock(lastRecovery + dayMs, zone)
        val reopened = openStore(dir, clockAtNow)
        reopened.load()
        assertEquals(
            "the durable event floor preserves the real cooldown instead of restarting it",
            lastRecovery, reopened.snapshots.value.recoveryFloorAt,
        )
        assertFalse(StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, lastRecovery + dayMs, lastRecovery))
        assertEquals(lastRecovery + StreakRecoveryPolicy.COOLDOWN_MS,
            StreakRecoveryPolicy.nextTokenAvailableAt(reopened.snapshots.value.events, lastRecovery))
    }

    /**
     * Documents a limitation, not a guarantee: a previously valid signed ledger can be replayed.
     * The Keystore key authenticates the value but provides no anti-rollback state.
     */
    @Test fun replayingAnOlderSignedLedgerIsAcceptedDocumentedLimitation() = runBlocking {
        val dir = createTempDirectory("flint-ledger-auth-replay").toFile()
        val first = at(today, 18)
        val clock = MutableClock(first - 40 * dayMs, zone)
        val store = recoveredStore(dir, clock, today, first)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "first", protection = protection(today),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertTrue(store.clear(emptySet()))
        val olderLedger = File(dir, ledgerName).readBytes()

        val secondDay = today.plusDays(31)
        val second = at(secondDay, 18)
        clock.nowMs = second
        seedEligible(store, secondDay)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "second", protection = protection(secondDay),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertTrue(store.clear(emptySet()))

        // Restore the older, still validly signed ledger.
        File(dir, ledgerName).writeBytes(olderLedger)
        val reopened = openStore(dir, clock)
        reopened.load()
        val floor = reopened.snapshots.value.recoveryFloorAt
        assertEquals("the replayed MAC is accepted", first, floor)
        assertTrue(
            "documented limitation: replay re-opens the allowance early",
            StreakRecoveryPolicy.tokenAvailable(reopened.snapshots.value.events, second + dayMs, floor),
        )
    }
}
