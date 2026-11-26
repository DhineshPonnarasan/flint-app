package com.flint.peakfocus.core.history

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakRecoveryPolicyTest {
    private val ny = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val kiritimati = ZoneId.of("Pacific/Kiritimati")
    private val today = LocalDate.of(2026, 10, 28)
    private val start = today.minusDays(30)

    private fun at(day: LocalDate, hour: Int = 9): Long =
        day.atTime(hour, 0).atZone(ny).toInstant().toEpochMilli()

    private fun session(
        id: String,
        day: LocalDate,
        minutes: Int = 40,
        terminalZone: ZoneId? = ny,
        zone: ZoneId = ny,
    ): FocusSession {
        val startMs = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val endMs = startMs + minutes * 60_000L
        return FocusSession(
            id = id, name = "Focus", start = startMs, end = endMs, plannedEnd = endMs,
            outcome = SessionOutcome.COMPLETED, inferred = false,
            intervals = listOf(FocusInterval(startMs, endMs)), breaks = null, opensIntercepted = null,
            terminalZone = terminalZone,
        )
    }

    private fun recoveryEvent(
        id: String,
        missed: LocalDate,
        atMs: Long,
        tz: ZoneId = ny,
    ): HistoryEvent {
        val missedStart = missed.atStartOfDay(tz).toInstant().toEpochMilli()
        return HistoryEvent(
            id = id, type = HistoryEventType.STREAK_RECOVERED.wireName, session = id,
            at = atMs, tz = tz.id, plannedEnd = 40 * 60_000L,
            breakEnd = missedStart, recoveryEnd = 30 * 60_000L,
            sessions = listOf("contributor"), grant = id, source = "fingerprint",
            reason = StreakRecoveryPolicy.REASON, originRoute = StreakRecoveryPolicy.ORIGIN,
        )
    }

    @Test fun formulaUsesBaselinePlusClampedExtra() {
        assertEquals(40, StreakRecoveryPolicy.requiredMinutes(30))
        assertEquals(20, StreakRecoveryPolicy.requiredMinutes(10))
        assertEquals(130, StreakRecoveryPolicy.requiredMinutes(100))
        assertEquals(40 * 60_000L, StreakRecoveryPolicy.requiredFocusedMs(30))
    }

    @Test fun oneMissedDayAfterAStreakIsEligible() {
        val prior = session("prior", today.minusDays(2), minutes = 30)
        val eligibility = StreakRecoveryPolicy.evaluate(
            listOf(prior), emptyList(), at(today, 18), ny, start, readable = true,
        )
        val offer = eligibility as? StreakRecoveryPolicy.Eligibility.Offer
        assertNotNull(offer)
        assertEquals(today.minusDays(1), offer!!.challenge.missedDay)
        assertEquals(today, offer.challenge.recoveryDay)
        assertEquals(40, offer.challenge.requiredMinutes)
    }

    @Test fun twoMissedDaysAreNotEligible() {
        val eligibility = StreakRecoveryPolicy.evaluate(
            emptyList(), emptyList(), at(today, 18), ny, start, readable = true,
        )
        assertEquals(
            "no_prior_streak",
            (eligibility as StreakRecoveryPolicy.Eligibility.Unavailable).reason,
        )
    }

    @Test fun cooldownBoundaryIsExact() {
        val last = at(today.minusDays(20), 9)
        val events = listOf(recoveryEvent("rec", today.minusDays(21), last))
        assertFalse(StreakRecoveryPolicy.tokenAvailable(events, last + StreakRecoveryPolicy.COOLDOWN_MS - 1))
        assertTrue(StreakRecoveryPolicy.tokenAvailable(events, last + StreakRecoveryPolicy.COOLDOWN_MS))
        assertTrue(StreakRecoveryPolicy.tokenAvailable(events, last + StreakRecoveryPolicy.COOLDOWN_MS + 1))
    }

    @Test fun secondRecoveryInsideCooldownIsBlocked() {
        val last = at(today.minusDays(2), 9)
        val events = listOf(recoveryEvent("rec", today.minusDays(3), last))
        val prior = session("prior", today.minusDays(2), minutes = 30)
        val eligibility = StreakRecoveryPolicy.evaluate(
            listOf(prior), events, at(today, 18), ny, start, readable = true,
        )
        assertEquals(
            "token_cooldown",
            (eligibility as StreakRecoveryPolicy.Eligibility.Unavailable).reason,
        )
    }

    @Test fun recoveredDayUsesTheRecordedEventZone() {
        val atMs = LocalDateTime.of(2026, 10, 27, 2, 0).atZone(kiritimati)
            .toInstant().toEpochMilli()
        val event = recoveryEvent("rec", LocalDate.of(2026, 10, 28), atMs, kiritimati)
        val days = StreakRecoveryPolicy.recoveredDays(listOf(event), ny, start)
        // 02:00 Kiritimati is the previous civil day in New York; the recorded zone wins.
        assertTrue(LocalDate.of(2026, 10, 28) in days)
        assertFalse(LocalDate.of(2026, 10, 27) in days)
    }

    @Test fun completedDayUsesTheRecordedTerminalZone() {
        val instant = LocalDateTime.of(2026, 10, 27, 23, 30).atZone(ny)
            .toInstant().toEpochMilli()
        val recorded = FocusSession(
            id = "s", name = "Focus", start = instant - 60_000, end = instant, plannedEnd = instant,
            outcome = SessionOutcome.COMPLETED, inferred = false,
            intervals = listOf(FocusInterval(instant - 60_000, instant)), breaks = null,
            opensIntercepted = null, terminalZone = tokyo,
        )
        val days = StreakRecoveryPolicy.completedDays(listOf(recorded), ny)
        assertEquals(setOf(LocalDate.of(2026, 10, 28)), days)
    }

    @Test fun malformedRecoveryNeverCounts() {
        val event = recoveryEvent("bad", today.minusDays(1), at(today, 18)).copy(
            source = null, sessions = null, plannedEnd = null,
        )
        assertFalse(StreakRecoveryPolicy.isWellFormedRecovery(event))
        assertTrue(StreakRecoveryPolicy.recoveredDays(listOf(event), ny, start).isEmpty())
    }

    @Test fun recoveryBridgeBridgesOneDayWithoutFabricatingCompletion() {
        val prior = session("prior", today.minusDays(2), minutes = 30)
        val recovery = recoveryEvent(
            "rec", today.minusDays(1), at(today, 9),
        )
        val snapshot = HistorySnapshot(
            events = listOf(
                HistoryEvent(
                    id = "start", type = HistoryEventType.STARTED.wireName, session = prior.id,
                    at = prior.start, tz = ny.id, plannedEnd = prior.end, tier = "easy",
                    source = "manual", name = "Focus",
                ),
                HistoryEvent(
                    id = "end", type = HistoryEventType.COMPLETED.wireName, session = prior.id,
                    at = prior.end, tz = ny.id,
                ),
                recovery,
            ),
            historyStart = start.atStartOfDay(ny).toInstant().toEpochMilli(),
            readable = true,
        )
        val summary = FocusHistoryAggregator.summarize(snapshot, at(today, 18), ny, emptySet())
        assertEquals(2, summary.streak)
        assertEquals(2, summary.bestRecordedStreak)
        val missed = summary.days.single { it.date == today.minusDays(1) }
        assertEquals(0, missed.completed)
        assertTrue(missed.recovered)
    }

    @Test fun laterCompletionAfterExpiryStartsAtOne() {
        val events = listOf(
            HistoryEvent(
                id = "start", type = HistoryEventType.STARTED.wireName, session = "new",
                at = at(today, 9), tz = ny.id, plannedEnd = at(today, 10),
                tier = "easy", source = "manual", name = "Focus",
            ),
            HistoryEvent(
                id = "end", type = HistoryEventType.COMPLETED.wireName, session = "new",
                at = at(today, 10), tz = ny.id,
            ),
        )
        val snapshot = HistorySnapshot(
            events, start.atStartOfDay(ny).toInstant().toEpochMilli(), readable = true,
        )
        assertEquals(1, FocusHistoryAggregator.summarize(snapshot, at(today, 18), ny, emptySet()).streak)
    }

    @Test fun sixtyConsecutiveDaysExtendToSixtyOne() {
        val first = today.minusDays(60)
        val events = (0..60).flatMap { offset ->
            val day = first.plusDays(offset.toLong())
            val startMs = day.atTime(9, 0).atZone(ny).toInstant().toEpochMilli()
            val endMs = startMs + 40 * 60_000L
            listOf(
                HistoryEvent(
                    id = "start-$offset", type = HistoryEventType.STARTED.wireName,
                    session = "day-$offset", at = startMs, tz = ny.id, plannedEnd = endMs,
                    tier = "easy", source = "manual", name = "Focus",
                ),
                HistoryEvent(
                    id = "end-$offset", type = HistoryEventType.COMPLETED.wireName,
                    session = "day-$offset", at = endMs, tz = ny.id,
                ),
            )
        }
        val snapshot = HistorySnapshot(
            events, first.minusDays(5).atStartOfDay(ny).toInstant().toEpochMilli(), readable = true,
        )
        val summary = FocusHistoryAggregator.summarize(snapshot, at(today, 18), ny, emptySet())
        assertEquals(61, summary.streak)
        assertEquals(61, summary.bestRecordedStreak)
    }
}
