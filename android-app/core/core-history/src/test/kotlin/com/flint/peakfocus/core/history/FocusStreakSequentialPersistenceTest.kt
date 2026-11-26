package com.flint.peakfocus.core.history

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Acceptance matrix §B: real production-store round-trips for Day 1 → Day 2 → Day 3.
 *
 * Unlike S19 (bulk-write then one reopen), each day is appended, the store is closed,
 * a **new** [FocusHistoryStore] is constructed over the same directory, and the streak
 * is re-derived from disk before the next day is written. This is the force-stop /
 * relaunch path that must never silently reset Day 3 → Day 1.
 *
 * Companion: iOS `FlintHistoryStreakLifecycleTests.testSequentialDay1Day2Day3SurviveEachReopen`.
 */
class FocusStreakSequentialPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val zone = ZoneId.of("America/New_York")
    private val hour = 3_600_000L
    private val day0Noon = LocalDateTime.parse("2026-10-26T12:00:00").atZone(zone).toInstant().toEpochMilli()

    private fun at(day: LocalDate, hourOfDay: Int): Long =
        day.atTime(hourOfDay, 0).atZone(zone).toInstant().toEpochMilli()

    private fun started(day: LocalDate, id: String) = HistoryEvent(
        type = HistoryEventType.STARTED.wireName, session = id, at = at(day, 9), tz = zone.id,
        plannedEnd = at(day, 9) + hour, tier = "easy", source = "manual", name = "Focus",
    )

    private fun completed(day: LocalDate, id: String) = HistoryEvent(
        type = HistoryEventType.COMPLETED.wireName, session = id, at = at(day, 10), tz = zone.id,
    )

    private fun clockAt(epochMs: Long): Clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(epochMs)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun summarize(snapshot: HistorySnapshot, now: Long): FocusSummary =
        FocusHistoryAggregator.summarize(snapshot, now, zone, emptySet())

    @Test fun day1Day2Day3EachSurviveForceStopRelaunch() = runBlocking {
        val directory = temporary.newFolder()
        val d1 = LocalDate.of(2026, 10, 26)
        val d2 = LocalDate.of(2026, 10, 27)
        val d3 = LocalDate.of(2026, 10, 28)

        // Stamp historyStart well before Day 1 so retention/historyStart cannot clip the run.
        FocusHistoryStore(directory, clockAt(day0Noon - 40L * 24 * hour)) { zone }.load()

        // Day 1
        FocusHistoryStore(directory, clockAt(at(d1, 12))) { zone }.also { store ->
            assertTrue(store.append(started(d1, "day1")))
            assertTrue(store.append(completed(d1, "day1")))
        }
        FocusHistoryStore(directory, clockAt(at(d1, 18))) { zone }.load().let { snap ->
            val summary = summarize(snap, at(d1, 18))
            assertEquals(1, summary.streak)
            assertEquals(1, summary.bestRecordedStreak)
            assertEquals(1, snap.events.count { it.knownType == HistoryEventType.COMPLETED })
        }

        // Day 2
        FocusHistoryStore(directory, clockAt(at(d2, 12))) { zone }.also { store ->
            assertTrue(store.append(started(d2, "day2")))
            assertTrue(store.append(completed(d2, "day2")))
        }
        FocusHistoryStore(directory, clockAt(at(d2, 18))) { zone }.load().let { snap ->
            val summary = summarize(snap, at(d2, 18))
            assertEquals(2, summary.streak)
            assertEquals(2, summary.bestRecordedStreak)
            assertEquals(2, snap.events.count { it.knownType == HistoryEventType.COMPLETED })
        }

        // Day 3
        FocusHistoryStore(directory, clockAt(at(d3, 12))) { zone }.also { store ->
            assertTrue(store.append(started(d3, "day3")))
            assertTrue(store.append(completed(d3, "day3")))
        }
        FocusHistoryStore(directory, clockAt(at(d3, 18))) { zone }.load().let { snap ->
            val summary = summarize(snap, at(d3, 18))
            assertEquals(3, summary.streak)
            assertEquals(3, summary.bestRecordedStreak)
            assertEquals(3, snap.events.count { it.knownType == HistoryEventType.COMPLETED })
            // Raw durable evidence: three completed terminals, three distinct local days.
            val completedDays = snap.events.filter { it.knownType == HistoryEventType.COMPLETED }
                .map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
                .toSet()
            assertEquals(setOf(d1, d2, d3), completedDays)
        }
    }

    @Test fun sameDaySecondCompletionDoesNotAdvancePastOneUntilNextDay() = runBlocking {
        val directory = temporary.newFolder()
        val d1 = LocalDate.of(2026, 10, 26)
        FocusHistoryStore(directory, clockAt(day0Noon - 40L * 24 * hour)) { zone }.load()
        FocusHistoryStore(directory, clockAt(at(d1, 12))) { zone }.also { store ->
            assertTrue(store.append(started(d1, "a")))
            assertTrue(store.append(completed(d1, "a")))
            assertTrue(store.append(started(d1, "b")))
            assertTrue(store.append(completed(d1, "b")))
        }
        val summary = summarize(
            FocusHistoryStore(directory, clockAt(at(d1, 20))) { zone }.load(),
            at(d1, 20),
        )
        assertEquals(1, summary.streak)
        assertEquals(1, summary.bestRecordedStreak)
    }
}
