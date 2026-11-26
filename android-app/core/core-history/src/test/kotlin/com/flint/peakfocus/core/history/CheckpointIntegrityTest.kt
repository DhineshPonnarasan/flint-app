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
 * Checkpoint integrity: a plausible edited value must be rejected exactly like an absurd one,
 * while history itself stays readable. An unsigned (pre-upgrade) checkpoint is not trusted.
 */
class CheckpointIntegrityTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val dayMs = 24L * 60 * 60 * 1000

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun at(day: LocalDate, hour: Int = 9): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun store(dir: File, clock: Clock) = FocusHistoryStore(
        directory = dir, clock = clock, retentionMs = 10 * dayMs, zone = { zone },
    )

    private fun completedRows(id: String, day: LocalDate): List<String> {
        val start = at(day)
        val end = start + 40 * 60_000L
        return listOf(
            "{\"v\":1,\"id\":\"start-$id\",\"type\":\"started\",\"session\":\"$id\",\"at\":$start," +
                "\"tz\":\"${zone.id}\",\"plannedEnd\":$end,\"tier\":\"easy\"," +
                "\"source\":\"manual\",\"name\":\"Focus\"}",
            "{\"v\":1,\"id\":\"end-$id\",\"type\":\"completed\",\"session\":\"$id\",\"at\":$end," +
                "\"tz\":\"${zone.id}\"}",
        )
    }

    private fun writeConsecutive(dir: File, days: Int) {
        dir.mkdirs()
        val rows = (0 until days).flatMap { offset ->
            completedRows("d$offset", today.minusDays((days - 1 - offset).toLong()))
        }
        File(dir, "events.v1.jsonl").writeText(rows.joinToString("\n") + "\n")
        File(dir, "metadata.v1.json").writeText(
            "{\"v\":1,\"createdAt\":${at(today.minusDays((days - 1).toLong()))}}",
        )
    }

    private fun clock() = MutableClock(at(today, 18), zone)

    private fun editMetadata(dir: File, transform: (String) -> String) {
        val file = File(dir, "metadata.v1.json")
        file.writeText(transform(file.readText()))
    }

    private fun replaceNumbers(text: String, key: String, value: String): String =
        Regex("(\"$key\":)(-?\\d+)").replace(text) { match -> match.groupValues[1] + value }

    private suspend fun compacted(dir: File): FocusHistoryStore {
        val store = store(dir, clock())
        store.load()
        return store
    }

    @Test fun signedCheckpointSurvivesCompaction() = runBlocking {
        val dir = createTempDirectory("flint-check-signed").toFile()
        writeConsecutive(dir, 70)
        val store = compacted(dir)

        assertEquals(70, store.snapshots.value.bestCheckpoint.bestRunDays)
        assertFalse(store.snapshots.value.bestCheckpointUnverified)
        val summary = FocusHistoryAggregator.summarize(store.snapshots.value, at(today, 18), zone, emptySet())
        assertEquals(70, summary.bestRecordedStreak)
    }

    @Test fun plausibleEditedValueIsRejected() = runBlocking {
        val dir = createTempDirectory("flint-check-plausible").toFile()
        writeConsecutive(dir, 70)
        compacted(dir)

        editMetadata(dir) { replaceNumbers(it, "bestRunDays", "700") }
        val reopened = compacted(dir)

        assertTrue("an edited checkpoint must be unverified", reopened.snapshots.value.bestCheckpointUnverified)
        assertEquals(0, reopened.snapshots.value.bestCheckpoint.bestRunDays)
        val summary = FocusHistoryAggregator.summarize(reopened.snapshots.value, at(today, 18), zone, emptySet())
        assertEquals("only retained history may be claimed", 10, summary.bestRecordedStreak)
    }

    @Test fun checksumMismatchIsRejected() = runBlocking {
        val dir = createTempDirectory("flint-check-mac").toFile()
        writeConsecutive(dir, 70)
        compacted(dir)

        editMetadata(dir) { text ->
            Regex("(\"bestRunMac\":\")([0-9a-f])").replace(text) { match ->
                val digit = match.groupValues[2]
                match.groupValues[1] + if (digit == "0") "1" else "0"
            }
        }
        val reopened = compacted(dir)

        assertTrue(reopened.snapshots.value.bestCheckpointUnverified)
        assertEquals(0, reopened.snapshots.value.bestCheckpoint.bestRunDays)
    }

    @Test fun missingIntegrityMetadataIsNotTrusted() = runBlocking {
        val dir = createTempDirectory("flint-check-missing").toFile()
        writeConsecutive(dir, 70)
        compacted(dir)

        editMetadata(dir) { text ->
            text.replace(Regex("\"bestRunVersion\":1,"), "")
                .replace(Regex(",\"bestRunMac\":\"[0-9a-f]*\""), "")
        }
        val reopened = compacted(dir)

        assertTrue("a pre-upgrade checkpoint has no provenance", reopened.snapshots.value.bestCheckpointUnverified)
        assertEquals(0, reopened.snapshots.value.bestCheckpoint.bestRunDays)
    }

    @Test fun malformedCheckpointFieldDoesNotDestroyMetadata() = runBlocking {
        val dir = createTempDirectory("flint-check-malformed").toFile()
        writeConsecutive(dir, 70)
        compacted(dir)
        val createdAt = File(dir, "metadata.v1.json").readText()
            .let { Regex("\"createdAt\":(\\d+)").find(it)!!.groupValues[1].toLong() }

        editMetadata(dir) { text ->
            text.replace(Regex("\"bestRunDays\":-?\\d+"), "\"bestRunDays\":\"oops\"")
        }
        val reopened = compacted(dir)

        assertTrue(reopened.snapshots.value.bestCheckpointUnverified)
        assertEquals("historyStart must survive a damaged checkpoint", createdAt, reopened.snapshots.value.historyStart)
        assertTrue("history stays readable", reopened.snapshots.value.readable)
        assertEquals(10, reopened.snapshots.value.events.count { it.type == "started" })
    }

    @Test fun explicitCompactionReSignsFromRetainedHistory() = runBlocking {
        val dir = createTempDirectory("flint-check-resign").toFile()
        writeConsecutive(dir, 70)
        compacted(dir)
        editMetadata(dir) { replaceNumbers(it, "bestRunDays", "700") }

        val reopened = compacted(dir)
        assertTrue(reopened.snapshots.value.bestCheckpointUnverified)
        assertTrue(reopened.compact())
        reopened.load()

        assertFalse("compaction re-signs what the retained days prove", reopened.snapshots.value.bestCheckpointUnverified)
        val summary = FocusHistoryAggregator.summarize(reopened.snapshots.value, at(today, 18), zone, emptySet())
        assertEquals(10, summary.bestRecordedStreak)
    }

    @Test fun unsignedZerosAreNotTreatedAsACheckpoint() = runBlocking {
        val dir = createTempDirectory("flint-check-zero").toFile()
        writeConsecutive(dir, 12)
        val store = compacted(dir)
        assertFalse(store.snapshots.value.bestCheckpointUnverified)
        assertNotNull(store.snapshots.value.bestCheckpoint)
    }
}
