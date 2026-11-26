package com.flint.peakfocus.core.history

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence and data-integrity guarantees for streak recovery.
 *
 * The dangerous failure is not a lost offer: it is a *corrupt* log that hides the row proving the
 * allowance was already used, or a write that fails after the UI has already celebrated. Both are
 * asserted here against the real store, not a fake.
 */
class StreakRecoveryIntegrityTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val packages = listOf("com.instagram.android")
    private val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun at(day: LocalDate, hour: Int = 9): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun openStore(dir: File, clock: Clock): FocusHistoryStore =
        FocusHistoryStore(directory = dir, clock = clock) { zone }

    private fun protection(day: LocalDate = today) = StreakRecoveryProtection.bind(
        packages, false, day, day.minusDays(1), at(day, 18),
    )!!

    private fun started(id: String, day: LocalDate, minutes: Int, grant: String? = null): String {
        val start = at(day)
        val end = start + minutes * 60_000L
        val grantField = if (grant == null) "" else ",\"grant\":\"$grant\""
        return "{\"v\":1,\"id\":\"start-$id\",\"type\":\"started\",\"session\":\"$id\",\"at\":$start," +
            "\"tz\":\"${zone.id}\",\"plannedEnd\":$end,\"tier\":\"easy\",\"source\":\"manual\"," +
            "\"name\":\"Focus\"$grantField}"
    }

    private fun completed(id: String, day: LocalDate, minutes: Int): String =
        "{\"v\":1,\"id\":\"end-$id\",\"type\":\"completed\",\"session\":\"$id\"," +
            "\"at\":${at(day) + minutes * 60_000L},\"tz\":\"${zone.id}\"}"

    private fun recoveryRow(id: String, atMs: Long, missedDay: LocalDate): String {
        val missed = missedDay.atStartOfDay(zone).toInstant().toEpochMilli()
        return "{\"v\":1,\"id\":\"$id\",\"type\":\"streak_recovered\",\"session\":\"$id\",\"at\":$atMs," +
            "\"tz\":\"${zone.id}\",\"breakEnd\":$missed,\"plannedEnd\":2400000," +
            "\"recoveryEnd\":1800000,\"sessions\":[\"a\"],\"source\":\"$fingerprint\"," +
            "\"grant\":\"$id\",\"reason\":\"policy:v1\",\"originRoute\":\"streak_recovery\"}"
    }

    private fun writeLog(dir: File, lines: List<String>) {
        dir.mkdirs()
        File(dir, "events.v1.jsonl").writeText(lines.joinToString("\n") + "\n")
        val earliest = Regex("\"at\":(\\d+)").find(lines.first())?.groupValues?.get(1)?.toLong()
        if (earliest != null) {
            File(dir, "metadata.v1.json").writeText("{\"v\":1,\"createdAt\":$earliest}")
        }
    }

    @Test fun corruptLogFailsClosedInsteadOfReopeningTheAllowance() = runBlocking {
        val dir = createTempDirectory("flint-integrity-corrupt").toFile()
        val now = at(today, 18)
        // A prior streak day, an old successful recovery, and a corrupt row. The corruption is
        // exactly the shape that could otherwise hide the old recovery and re-open the token.
        writeLog(
            dir,
            listOf(
                started("a", today.minusDays(3), 30, fingerprint),
                completed("a", today.minusDays(3), 30),
                recoveryRow("rec-old", at(today.minusDays(2), 18), today.minusDays(2)),
                started("b", today, 40, fingerprint),
                completed("b", today, 40),
                "{\"id\":\"truncated\",\"type\":\"streak_rec",
            ),
        )
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(dir, clock)
        store.load()
        clock.nowMs = now
        val snapshot = store.snapshots.value
        assertTrue("partial corruption must be flagged", snapshot.degraded)
        // The store quarantines the whole damaged log (a support copy is kept) rather than
        // silently trusting a prefix, so no corrupt-derived state can authorize credit.
        val quarantine = File(dir, "quarantine")
        assertTrue(
            "the damaged log must be preserved for recovery",
            quarantine.isDirectory && quarantine.listFiles()?.isNotEmpty() == true,
        )

        val result = StreakRecoveryCommitter.tryCommit(
            store, zone = zone, recoveryId = "never", protection = protection(),
        )
        assertEquals(StreakRecoveryCommitter.Result.Rejected("history_unreadable"), result)
        assertFalse(snapshot.events.any { it.type == "streak_recovered" && it.id == "never" })
    }

    @Test fun malformedRecoveryRecordNeitherCreditsNorCharges() {
        val malformed = HistoryEvent(
            id = "bad", type = StreakRecoveryPolicy.WIRE_TYPE, session = "bad",
            at = at(today, 18), tz = zone.id,
            breakEnd = at(today.minusDays(1)), plannedEnd = 2_400_000L, recoveryEnd = 1_800_000L,
            // No sessions, no source: the row cannot prove anything.
            grant = "bad", reason = StreakRecoveryPolicy.REASON,
            originRoute = StreakRecoveryPolicy.ORIGIN,
        )
        assertFalse(StreakRecoveryPolicy.isWellFormedRecovery(malformed))
        assertNull(StreakRecoveryPolicy.lastSuccessfulRecoveryAt(listOf(malformed)))
        assertTrue(StreakRecoveryPolicy.tokenAvailable(listOf(malformed), at(today, 18)))
        assertTrue(
            StreakRecoveryPolicy.recoveredDays(listOf(malformed), zone, today.minusDays(40)).isEmpty(),
        )
    }

    @Test fun writeFailureIsPersistFailedAndNeverSuccess() = runBlocking {
        val dir = createTempDirectory("flint-integrity-write").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(dir, clock)
        store.load()
        clock.nowMs = now
        seedEligible(store)

        // The single-record fast path appends straight to the log, so make the log unwritable
        // rather than intercepting the atomic path.
        val log = File(dir, "events.v1.jsonl")
        assertTrue("fixture must be able to make the log read-only", log.setWritable(false))
        try {
            val result = StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "disk-full", protection = protection(),
            )
            assertEquals(StreakRecoveryCommitter.Result.PersistFailed, result)
            assertFalse(
                "a failed write must not publish a recovery row",
                store.snapshots.value.events.any { it.id == "disk-full" },
            )
            assertTrue(
                "a failed write must not consume the allowance",
                StreakRecoveryPolicy.tokenAvailable(store.snapshots.value.events, now),
            )
        } finally {
            log.setWritable(true)
        }
    }

    @Test fun durableRecoverySurvivesReopenAndIsNotRepublished() = runBlocking {
        val dir = createTempDirectory("flint-integrity-reopen").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(dir, clock)
        store.load()
        clock.nowMs = now
        seedEligible(store)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "durable-1", protection = protection(),
            ) is StreakRecoveryCommitter.Result.Success,
        )

        val reopened = openStore(dir, clock)
        reopened.load()
        assertEquals(1, reopened.snapshots.value.events.count { it.type == "streak_recovered" })
        // Re-committing the same day on the reopened store is rejected, not duplicated.
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                reopened, zone = zone, recoveryId = "durable-2", protection = protection(),
            ) is StreakRecoveryCommitter.Result.Rejected,
        )
        assertEquals(1, reopened.snapshots.value.events.count { it.type == "streak_recovered" })
    }

    @Test fun clearingHistoryDoesNotRefundTheFreeRecovery() = runBlocking {
        val dir = createTempDirectory("flint-integrity-clear").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(dir, clock)
        store.load()
        clock.nowMs = now
        seedEligible(store)
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "clear-1", protection = protection(),
            ) is StreakRecoveryCommitter.Result.Success,
        )
        assertFalse(StreakRecoveryPolicy.tokenAvailable(store.snapshots.value.events, now))

        assertTrue(store.clear(emptySet()))
        store.load()
        val events = store.snapshots.value.events
        // The ledger, not retained session rows, carries the allowance.
        assertFalse(
            "no recovery row is retained solely for the cooldown",
            events.any { StreakRecoveryPolicy.isWellFormedRecovery(it) },
        )
        val floor = store.snapshots.value.recoveryFloorAt
        assertEquals("the durable ledger mirrors the recovery", now, floor)
        assertFalse(
            "a clear must not refund the allowance",
            StreakRecoveryPolicy.tokenAvailable(events, now + 24L * 60 * 60 * 1000, floor),
        )
        assertEquals(
            now + StreakRecoveryPolicy.COOLDOWN_MS,
            StreakRecoveryPolicy.nextTokenAvailableAt(events, floor),
        )
        val summary = FocusHistoryAggregator.summarize(store.snapshots.value, now, zone, emptySet())
        assertEquals(0, summary.streak)
    }

    @Test fun malformedRecoveryRowOnDiskIsDroppedAndDoesNotConsumeTheAllowance() = runBlocking {
        val dir = createTempDirectory("flint-integrity-malformed").toFile()
        val now = at(today, 18)
        // A bridge row missing `sessions`/`source` cannot prove anything.
        val malformed =
            "{\"v\":1,\"id\":\"bad\",\"type\":\"streak_recovered\",\"session\":\"bad\"," +
                "\"at\":${at(today.minusDays(1), 18)},\"tz\":\"${zone.id}\"," +
                "\"breakEnd\":${at(today.minusDays(2))},\"plannedEnd\":2400000,\"recoveryEnd\":1800000," +
                "\"grant\":\"bad\",\"reason\":\"policy:v1\",\"originRoute\":\"streak_recovery\"}"
        writeLog(
            dir,
            listOf(
                started("a", today.minusDays(2), 30, fingerprint),
                completed("a", today.minusDays(2), 30),
                started("b", today, 40, fingerprint),
                completed("b", today, 40),
                malformed,
            ),
        )
        val clock = MutableClock(now - 40L * 24 * 60 * 60 * 1000, zone)
        val store = openStore(dir, clock)
        store.load()
        clock.nowMs = now
        val snapshot = store.snapshots.value
        assertFalse("the malformed bridge must be dropped", snapshot.events.any { it.id == "bad" })
        assertTrue(snapshot.degraded)
        // Allowance state: nothing was consumed, and recovery still fails closed while degraded.
        assertTrue(StreakRecoveryPolicy.tokenAvailable(snapshot.events, now))
        assertEquals(
            StreakRecoveryCommitter.Result.Rejected("history_unreadable"),
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "never", protection = protection(),
            ),
        )
    }

    /** Prior-day 30m completion plus the recovery-day 40m grant-bound session. */
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
}
