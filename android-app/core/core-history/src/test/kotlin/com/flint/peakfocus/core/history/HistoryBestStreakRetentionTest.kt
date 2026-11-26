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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Historical best streak across ordinary retention compaction.
 *
 * The checkpoint must restore exactly what the dropped rows proved — never more — while current
 * streak continues across the boundary and a clear still resets it.
 */
class HistoryBestStreakRetentionTest {
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

    private fun store(dir: File, clock: Clock, retentionDays: Long) = FocusHistoryStore(
        directory = dir,
        clock = clock,
        retentionMs = retentionDays * dayMs,
        zone = { zone },
    )

    private fun completedRows(id: String, day: LocalDate, minutes: Int = 40): List<String> {
        val start = at(day)
        val end = start + minutes * 60_000L
        return listOf(
            "{\"v\":1,\"id\":\"start-$id\",\"type\":\"started\",\"session\":\"$id\",\"at\":$start," +
                "\"tz\":\"${zone.id}\",\"plannedEnd\":$end,\"tier\":\"easy\"," +
                "\"source\":\"manual\",\"name\":\"Focus\"}",
            "{\"v\":1,\"id\":\"end-$id\",\"type\":\"completed\",\"session\":\"$id\",\"at\":$end," +
                "\"tz\":\"${zone.id}\"}",
        )
    }

    /** A bridge row whose missed day is [missedDay], written on [writtenDay]. */
    private fun recoveryRow(id: String, writtenDay: LocalDate, missedDay: LocalDate): String {
        val written = at(writtenDay, 18)
        val missed = missedDay.atStartOfDay(zone).toInstant().toEpochMilli()
        return "{\"v\":1,\"id\":\"$id\",\"type\":\"streak_recovered\",\"session\":\"$id\",\"at\":$written," +
            "\"tz\":\"${zone.id}\",\"breakEnd\":$missed,\"plannedEnd\":2400000," +
            "\"recoveryEnd\":1800000,\"sessions\":[\"$id\"],\"source\":\"$fingerprint\"," +
            "\"grant\":\"$id\",\"reason\":\"policy:v1\",\"originRoute\":\"streak_recovery\"}"
    }

    private fun writeLog(dir: File, rows: List<String>) {
        dir.mkdirs()
        File(dir, "events.v1.jsonl").writeText(rows.joinToString("\n") + "\n")
        // Persist an honest historyStart (the earliest record), not "now": otherwise every
        // seeded day would fall before it and no streak would count.
        val earliest = Regex("\"at\":(\\d+)").find(rows.first())?.groupValues?.get(1)?.toLong()
        if (earliest != null) {
            File(dir, "metadata.v1.json").writeText("{\"v\":1,\"createdAt\":$earliest}")
        }
    }

    /** Consecutive completed days ending today. */
    private fun consecutiveRows(days: Int, endingOn: LocalDate = today): List<String> =
        (0 until days).flatMap { offset ->
            completedRows("d$offset", endingOn.minusDays((days - 1 - offset).toLong()))
        }

    private fun clockAt(day: LocalDate = today, hour: Int = 18) =
        MutableClock(at(day, hour), zone)

    private fun summary(store: FocusHistoryStore, now: Long = at(today, 18)) =
        FocusHistoryAggregator.summarize(store.snapshots.value, now, zone, emptySet())

    @Test fun oneHundredTwentyConsecutiveDaysSurviveCompaction() = runBlocking {
        val dir = createTempDirectory("flint-best-120").toFile()
        writeLog(dir, consecutiveRows(120))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 30)
        store.load()

        assertEquals(30, store.snapshots.value.events.count { it.type == "started" })
        assertEquals(120, summary(store).bestRecordedStreak)
        assertEquals("current streak continues across the boundary", 120, summary(store).streak)
    }

    @Test fun threeHundredSixtyFiveConsecutiveDaysSurviveCompaction() = runBlocking {
        val dir = createTempDirectory("flint-best-365").toFile()
        writeLog(dir, consecutiveRows(365))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 30)
        store.load()

        assertEquals(365, summary(store).bestRecordedStreak)
        assertEquals(365, summary(store).streak)
    }

    @Test fun moreThanFourHundredDaysSurviveOrdinaryAgeRetention() = runBlocking {
        val dir = createTempDirectory("flint-best-420").toFile()
        writeLog(dir, consecutiveRows(420))
        val clock = clockAt()
        // Default 400-day window: only the oldest 20 days are pruned.
        val store = FocusHistoryStore(directory = dir, clock = clock, zone = { zone })
        store.load()

        assertEquals(400, store.snapshots.value.events.count { it.type == "started" })
        assertEquals(420, summary(store).bestRecordedStreak)
        assertEquals(420, summary(store).streak)
    }

    @Test fun sixtyCompactedPlusTenContiguousRetainedIsSeventy() = runBlocking {
        val dir = createTempDirectory("flint-best-70").toFile()
        writeLog(dir, consecutiveRows(70))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 10)
        store.load()

        assertEquals(10, store.snapshots.value.events.count { it.type == "started" })
        assertEquals(70, summary(store).bestRecordedStreak)
        val checkpoint = store.snapshots.value.bestCheckpoint
        assertEquals(60, checkpoint.tailDays)
        assertTrue(checkpoint.bestRunDays <= 70)
    }

    @Test fun recoveryBridgeInsideCompactedHistoryCounts() = runBlocking {
        val dir = createTempDirectory("flint-best-bridge").toFile()
        // 70 consecutive streak days where day -60 is bridged by a recovery row written on -59.
        val rows = mutableListOf<String>()
        for (offset in 0 until 70) {
            val day = today.minusDays((69 - offset).toLong())
            if (day == today.minusDays(60)) continue
            rows += completedRows("d$offset", day)
        }
        rows += recoveryRow("bridge", today.minusDays(59), today.minusDays(60))
        writeLog(dir, rows)

        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 10)
        store.load()

        assertEquals("the bridged day is part of the run", 70, summary(store).bestRecordedStreak)
        assertEquals(70, summary(store).streak)
    }

    @Test fun sameDayDuplicatesDoNotInflateTheCheckpoint() = runBlocking {
        val dir = createTempDirectory("flint-best-dup").toFile()
        val rows = (0 until 10).flatMap { offset ->
            val day = today.minusDays((9 - offset).toLong())
            (0 until 3).flatMap { copy -> completedRows("d$offset-$copy", day, 20) }
        }
        writeLog(dir, rows)
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 3)
        store.load()

        assertEquals(10, summary(store).bestRecordedStreak)
        assertEquals(10, summary(store).streak)
    }

    @Test fun compactionIsIdempotentAndNeverClaimsMoreThanProven() = runBlocking {
        val dir = createTempDirectory("flint-best-idem").toFile()
        writeLog(dir, consecutiveRows(70))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 10)
        store.load()
        val firstBest = summary(store).bestRecordedStreak
        val firstCount = store.snapshots.value.events.size

        assertTrue(store.compact())
        store.load()
        assertEquals(firstBest, summary(store).bestRecordedStreak)
        assertEquals(firstCount, store.snapshots.value.events.size)
        assertTrue(
            "checkpoint must never exceed the proven best",
            store.snapshots.value.bestCheckpoint.bestRunDays <= firstBest,
        )
        assertTrue(store.snapshots.value.bestCheckpoint.tailDays <= firstBest)
    }

    @Test fun interruptedCompactionLeavesHistoryAndBestIntact() = runBlocking {
        val dir = createTempDirectory("flint-best-crash").toFile()
        writeLog(dir, consecutiveRows(70))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 10)

        // Fail only the event-log replacement: the checkpoint write succeeds, pruning does not.
        val originalRename = AtomicFileReplace.rename
        AtomicFileReplace.rename = { from, to ->
            if (to.name == "events.v1.jsonl") false else originalRename(from, to)
        }
        try {
            store.load()
        } finally {
            AtomicFileReplace.rename = originalRename
        }
        // Nothing was pruned, and the derived best still covers the full history.
        val unpruned = store(dir, clock, retentionDays = 10)
        unpruned.load()
        assertEquals(70, summary(unpruned).bestRecordedStreak)

        // A later compaction completes and keeps the same best.
        assertTrue(unpruned.compact())
        unpruned.load()
        assertEquals(10, unpruned.snapshots.value.events.count { it.type == "started" })
        assertEquals(70, summary(unpruned).bestRecordedStreak)
    }

    @Test fun corruptCheckpointCannotInflateBest() = runBlocking {
        val dir = createTempDirectory("flint-best-corrupt").toFile()
        writeLog(dir, consecutiveRows(12))
        val clock = clockAt()
        val warm = store(dir, clock, retentionDays = 30)
        warm.load()
        val metadata = File(dir, "metadata.v1.json")
        val created = at(today.minusDays(11))
        metadata.writeText(
            "{\"v\":1,\"createdAt\":$created,\"bestRunDays\":999999,\"bestTailDays\":999999," +
                "\"bestTailEndDay\":999999}",
        )

        val store = store(dir, clock, retentionDays = 30)
        store.load()
        // The absurd value is discarded; the checkpoint can only hold what the days prove.
        assertEquals(12, summary(store).bestRecordedStreak)
        assertTrue(store.snapshots.value.bestCheckpoint.bestRunDays <= 12)
    }

    @Test fun clearResetsBestAndStreak() = runBlocking {
        // The recovery-allowance half of "clear" is covered by
        // StreakRecoveryIntegrityTest.clearingHistoryDoesNotRefundTheFreeRecovery.
        val dir = createTempDirectory("flint-best-clear").toFile()
        writeLog(dir, consecutiveRows(70))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 10)
        store.load()
        assertEquals(70, summary(store).bestRecordedStreak)

        assertTrue(store.clear(emptySet()))
        store.load()
        assertEquals(0, summary(store).bestRecordedStreak)
        assertEquals(0, summary(store).streak)
    }

    @Test fun bestComesFromTheCheckpointWhenTheRunEndedDaysAgo() = runBlocking {
        val dir = createTempDirectory("flint-best-past").toFile()
        // A 70-day run that ended three days ago: the current streak is 0 today, so the best can
        // only come from the pruned history's checkpoint.
        writeLog(dir, consecutiveRows(70, endingOn = today.minusDays(3)))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 10)
        store.load()

        val result = summary(store)
        assertEquals(0, result.streak)
        assertEquals(70, result.bestRecordedStreak)
    }

    @Test fun twoSequentialCompactionsContinueTheRun() = runBlocking {
        val dir = createTempDirectory("flint-best-twice").toFile()
        writeLog(dir, consecutiveRows(70))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 10)
        store.load()
        assertEquals(70, summary(store).bestRecordedStreak)

        // A new day extends the same run after one boundary has already moved.
        val tomorrow = today.plusDays(1)
        clock.nowMs = at(tomorrow, 18)
        val start = at(tomorrow)
        val end = start + 40 * 60_000L
        assertTrue(store.append(HistoryEvent(
            id = "start-d70", type = "started", session = "d70", at = start, tz = zone.id,
            plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
        )))
        assertTrue(store.append(HistoryEvent(
            id = "end-d70", type = "completed", session = "d70", at = end, tz = zone.id,
        )))
        store.load()

        val result = summary(store, now = at(tomorrow, 18))
        assertEquals("the run continues across two compaction boundaries", 71, result.streak)
        assertEquals(71, result.bestRecordedStreak)
    }

    @Test fun interruptedClearDoesNotRestoreTheBest() = runBlocking {
        val dir = createTempDirectory("flint-best-clearcrash").toFile()
        writeLog(dir, consecutiveRows(70))
        val clock = clockAt()
        val store = FocusHistoryStore(
            directory = dir,
            clock = clock,
            retentionMs = 10 * dayMs,
            clearPhase = { phase ->
                if (phase == HistoryClearPhase.LOG_REPLACED) throw IllegalStateException("crash")
            },
            zone = { zone },
        )
        store.load()
        assertEquals(70, summary(store).bestRecordedStreak)

        // The clear intent is durable but the final metadata write never ran.
        assertFalse(store.clear(emptySet()))
        val recovered = store(dir, clock, retentionDays = 10)
        recovered.load()
        assertEquals("a completed clear must not leave a best behind", 0, summary(recovered).bestRecordedStreak)
        assertEquals(0, summary(recovered).streak)
    }

    @Test fun corruptMetadataWithTruncatedJsonIsQuarantined() = runBlocking {
        val dir = createTempDirectory("flint-best-badmeta").toFile()
        writeLog(dir, consecutiveRows(5))
        val clock = clockAt()
        val warm = store(dir, clock, retentionDays = 30)
        warm.load()
        File(dir, "metadata.v1.json").writeText("{\"v\":1,\"createdAt\":")

        val store = store(dir, clock, retentionDays = 30)
        store.load()
        assertTrue("metadata loss must be surfaced", store.snapshots.value.degraded)
        assertTrue(DirectoryQuarantine(dir).hasAny())
        // Recovery from metadata loss never invents an earlier historyStart, so at most today
        // can be credited; the point is that it fails safely instead of crashing or inflating.
        assertTrue(summary(store).bestRecordedStreak <= 1)
    }

    @Test fun checkpointWriteFailureLeavesHistoryIntact() = runBlocking {
        val dir = createTempDirectory("flint-best-metafail").toFile()
        writeLog(dir, consecutiveRows(70))
        val clock = clockAt()
        val store = store(dir, clock, retentionDays = 10)

        val originalRename = AtomicFileReplace.rename
        AtomicFileReplace.rename = { from, to ->
            if (to.name == "metadata.v1.json") false else originalRename(from, to)
        }
        try {
            store.load()
        } finally {
            AtomicFileReplace.rename = originalRename
        }
        // The checkpoint must not exist without the pruned history it describes.
        assertEquals(140, store.snapshots.value.events.size)
        assertEquals(70, summary(store).bestRecordedStreak)

        // A later load compacts normally and keeps the same best.
        val reopened = store(dir, clock, retentionDays = 10)
        reopened.load()
        assertEquals(10, reopened.snapshots.value.events.count { it.type == "started" })
        assertEquals(70, summary(reopened).bestRecordedStreak)
    }

    /** Small helper so the test does not depend on the store's private quarantine layout. */
    private class DirectoryQuarantine(private val dir: File) {
        fun hasAny(): Boolean = File(dir, "quarantine")
            .listFiles()?.isNotEmpty() == true
    }
}
