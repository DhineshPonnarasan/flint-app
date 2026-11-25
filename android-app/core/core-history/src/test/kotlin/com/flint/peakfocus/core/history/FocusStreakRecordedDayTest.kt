package com.flint.peakfocus.core.history

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Recorded-zone streak attribution, mirroring iOS `FlintStreakReviewFixTests` and the S17
 * fixtures in `FlintHistoryStreakLifecycleTests`.
 *
 * Product rule: a completed session belongs to the civil day in the timezone recorded on its
 * terminal event. Changing the device zone later never re-attributes that day. The chart's
 * [FocusDay] counts keep the established device-local presentation policy.
 */
class FocusStreakRecordedDayTest {
    private val ny = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val utc = ZoneOffset.UTC
    private val hour = 3_600_000L

    private fun local(zone: ZoneId, date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, start: Long, end: Long, startZone: ZoneId, endZone: ZoneId) = listOf(
        HistoryEvent(
            type = HistoryEventType.STARTED.wireName, session = id, at = start, tz = startZone.id,
            plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
        ),
        HistoryEvent(type = HistoryEventType.COMPLETED.wireName, session = id, at = end, tz = endZone.id),
    )

    /** A one-hour completed session recorded entirely in New York. */
    private fun nySession(id: String, date: String, time: String): List<HistoryEvent> {
        val start = local(ny, date, time)
        return session(id, start, start + hour, ny, ny)
    }

    private fun summarize(events: List<HistoryEvent>, now: Long, inZone: ZoneId): FocusSummary =
        FocusHistoryAggregator.summarize(
            HistorySnapshot(events, now - 30L * 24 * hour, true), now, inZone, emptySet(),
        )

    /** Parity: iOS `testDeviceZoneMoveDoesNotExtendAStaleStreak`. */
    @Test fun deviceZoneMoveDoesNotExtendAStaleStreak() {
        val events = nySession("a", "2026-10-26", "22:00:00") + nySession("b", "2026-10-27", "22:00:00")
        val result = summarize(events, now = local(tokyo, "2026-10-29", "12:00:00"), inZone = tokyo)
        assertEquals(0, result.streak)
        assertEquals(2, result.bestRecordedStreak)
    }

    /** Parity: iOS `testRecordedDaysStayConsecutiveAfterAZoneMove`. */
    @Test fun recordedDaysStayConsecutiveAfterAZoneMove() {
        val events = nySession("c1", "2026-10-27", "22:00:00") + nySession("c2", "2026-10-28", "01:00:00")
        val now = local(ny, "2026-10-28", "12:00:00")
        val inNy = summarize(events, now, ny)
        val inTokyo = summarize(events, now, tokyo)
        assertEquals(2, inNy.streak)
        assertEquals(2, inNy.bestRecordedStreak)
        assertEquals(2, inTokyo.streak)
        assertEquals(2, inTokyo.bestRecordedStreak)
    }

    /** Parity: iOS `testSameRecordedDaysCountInEitherZone`. */
    @Test fun sameRecordedDaysCountInEitherZone() {
        val events = nySession("s1", "2026-10-27", "09:00:00") + nySession("s2", "2026-10-28", "09:00:00")
        val now = local(ny, "2026-10-28", "12:00:00")
        assertEquals(2, summarize(events, now, ny).streak)
        assertEquals(2, summarize(events, now, tokyo).streak)
        assertEquals(2, summarize(events, now, ny).bestRecordedStreak)
        assertEquals(2, summarize(events, now, tokyo).bestRecordedStreak)
    }

    /** The terminal event's zone wins over the zone recorded when the session started. */
    @Test fun completionUsesTerminalEventZoneNotStartedZone() {
        val start = local(ny, "2026-10-27", "10:00:00")
        val end = local(tokyo, "2026-10-28", "00:30:00")
        val events = session("trip", start, end, ny, tokyo)
        val result = summarize(events, now = local(ny, "2026-10-29", "12:00:00"), inZone = ny)
        assertEquals("Asia/Tokyo", result.sessions.single().terminalZone?.id)
        assertEquals(1, result.streak)
        assertEquals(1, result.bestRecordedStreak)
    }

    /** Parity: iOS `testCompletionRecordedAheadOfDeviceZoneCountsToday`. */
    @Test fun midnightAheadZoneCompletionCountsToday() {
        val start = local(tokyo, "2026-10-29", "00:00:00")
        val end = local(tokyo, "2026-10-29", "01:00:00")
        val events = session("ahead", start, end, tokyo, tokyo)
        val result = summarize(events, now = local(ny, "2026-10-28", "12:00:00"), inZone = ny)
        assertEquals(1, result.streak)
        assertEquals(1, result.days.last().completed)
    }

    /** Parity: iOS `testAheadRecordedDaysKeepBestStreak`; a best streak must not decrease. */
    @Test fun aheadRecordedDaysKeepBestStreak() {
        val first = session(
            "a", local(tokyo, "2026-10-28", "11:00:00"), local(tokyo, "2026-10-28", "12:00:00"),
            tokyo, tokyo,
        )
        val second = session(
            "b", local(tokyo, "2026-10-29", "00:00:00"), local(tokyo, "2026-10-29", "01:00:00"),
            tokyo, tokyo,
        )
        val result = summarize(first + second, now = local(ny, "2026-10-28", "20:00:00"), inZone = ny)
        assertEquals(2, result.streak)
        assertEquals(2, result.bestRecordedStreak)
        assertEquals(1, result.days.last().completed)
    }

    /** Parity: iOS `testGapInRecordedDaysDoesNotInventCurrentStreak`. */
    @Test fun gapInRecordedDaysDoesNotInventCurrentStreak() {
        val first = session(
            "a", local(tokyo, "2026-10-27", "22:00:00"), local(tokyo, "2026-10-27", "23:00:00"),
            tokyo, tokyo,
        )
        val second = session(
            "b", local(tokyo, "2026-10-29", "00:00:00"), local(tokyo, "2026-10-29", "00:30:00"),
            tokyo, tokyo,
        )
        val result = summarize(first + second, now = local(ny, "2026-10-28", "20:00:00"), inZone = ny)
        assertEquals(1, result.streak)
        assertEquals(1, result.bestRecordedStreak)
    }

    /** Parity: iOS `testFarFutureRecordedDayDoesNotSuppressYesterdayStreak`. */
    @Test fun farFutureRecordedDayDoesNotSuppressYesterdayStreak() {
        val yesterday = nySession("a", "2026-10-27", "22:00:00")
        // A previously-fast clock recorded a terminal two days ahead of the device day.
        val corrupt = session(
            "b", local(ny, "2026-10-27", "22:00:00"), local(ny, "2026-10-30", "00:30:00"), ny, ny,
        )
        val result = summarize(
            yesterday + corrupt, now = local(ny, "2026-10-28", "12:00:00"), inZone = ny,
        )
        assertEquals(1, result.streak)
        assertEquals(1, result.bestRecordedStreak)
    }

    /** Duplicated and skipped local hours must not move a completion to another civil day. */
    @Test fun daylightSavingTransitionsKeepTheRecordedCivilDay() {
        // Fall back: 2026-11-01 repeats 01:00-02:00 America/New_York.
        val fall = nySession("f1", "2026-10-31", "22:00:00") + nySession("f2", "2026-11-01", "01:30:00")
        val fallNow = local(ny, "2026-11-02", "12:00:00")
        assertEquals(2, summarize(fall, fallNow, ny).streak)
        assertEquals(2, summarize(fall, fallNow, utc).streak)

        // Spring forward: 2026-03-08 has no 02:00-03:00 America/New_York.
        val spring = nySession("p1", "2026-03-07", "22:00:00") + nySession("p2", "2026-03-08", "03:30:00")
        val springNow = local(ny, "2026-03-09", "12:00:00")
        assertEquals(2, summarize(spring, springNow, ny).streak)
        assertEquals(2, summarize(spring, springNow, utc).streak)
    }

    /** Chart day counts stay device-local even while the streak keeps recorded days. */
    @Test fun chartStaysDeviceLocalWhileStreakUsesRecordedDay() {
        val events = nySession("c1", "2026-10-27", "22:00:00") + nySession("c2", "2026-10-28", "01:00:00")
        val result = summarize(events, now = local(ny, "2026-10-28", "12:00:00"), inZone = tokyo)
        assertEquals(2, result.streak)
        assertEquals(2, result.days.single { it.date == LocalDate.of(2026, 10, 28) }.completed)
        assertEquals(0, result.days.last().completed)
    }

    /** Historical records that cannot name a resolvable zone keep the device-day fallback. */
    @Test fun unresolvableRecordedZoneFallsBackToDeviceDay() {
        val start = local(ny, "2026-10-27", "22:00:00")
        val end = start + hour
        val events = listOf(
            HistoryEvent(
                type = HistoryEventType.STARTED.wireName, session = "legacy", at = start, tz = ny.id,
                plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
            ),
            HistoryEvent(type = HistoryEventType.COMPLETED.wireName, session = "legacy", at = end, tz = "Not/AZone"),
        )
        val result = summarize(events, now = local(ny, "2026-10-28", "12:00:00"), inZone = ny)
        assertNull(result.sessions.single().terminalZone)
        assertEquals(1, result.streak)
    }

    /** The terminal zone must survive persistence, not just in-memory construction. */
    @Test fun codecPersistsTheTerminalZone() {
        val event = HistoryEvent(
            type = HistoryEventType.COMPLETED.wireName, session = "s",
            at = local(tokyo, "2026-10-28", "23:30:00"), tz = tokyo.id,
        )
        assertEquals(tokyo.id, HistoryCodec.decode(HistoryCodec.encode(event)).tz)
    }
}
