package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.history.FocusDay
import com.flint.peakfocus.core.history.FocusInterval
import com.flint.peakfocus.core.history.FocusSession
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.SessionOutcome
import com.flint.peakfocus.core.history.StreakRecoveryPolicy
import com.flint.peakfocus.core.history.StreakRecoveryProtection
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakRecoveryPresentationTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val historyStart = today.minusDays(30)
    private val locale = Locale.US
    private val packages = listOf("com.instagram.android")
    private val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)
    private val protection = StreakRecoveryProtection.bind(
        packages, false, today, today.minusDays(1), 0L,
    )!!

    private fun at(day: LocalDate, hour: Int = 9): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, day: LocalDate, minutes: Int): FocusSession {
        val start = at(day)
        val end = start + minutes * 60_000L
        return FocusSession(
            id = id, name = "Focus", start = start, end = end, plannedEnd = end,
            outcome = SessionOutcome.COMPLETED, inferred = false,
            intervals = listOf(FocusInterval(start, end)), breaks = null,
            opensIntercepted = null, terminalRecorded = true,
        )
    }

    @Test fun offerWhenExactlyOneMiss() {
        val summary = FocusSummary(
            availability = HistoryAvailability.VALUE,
            sessions = listOf(session("a", today.minusDays(2), 30)),
            days = listOf(FocusDay(today, 0)),
            streak = 0,
        )
        val ui = streakRecoveryUi(
            summary, emptyList(), at(today, 18), zone, historyStart, protection, locale,
        )
        assertTrue(ui is StreakRecoveryUi.Offer)
        val offer = ui as StreakRecoveryUi.Offer
        assertEquals(40, offer.requiredMinutes)
        assertEquals(false, offer.satisfied)
        assertTrue(offer.protectedAppsLabel.contains("1 apps"))
        assertTrue(offer.protectionLocked)
    }

    @Test fun emptyProtectionSurfacesUnavailable() {
        val summary = FocusSummary(
            availability = HistoryAvailability.VALUE,
            sessions = listOf(session("a", today.minusDays(2), 30)),
            days = listOf(FocusDay(today, 0)),
            streak = 0,
        )
        val ui = streakRecoveryUi(
            summary, emptyList(), at(today, 18), zone, historyStart, protection = null, locale = locale,
        )
        val unavailable = ui as StreakRecoveryUi.Unavailable
        assertEquals("empty_protection", unavailable.reason)
        assertEquals(StreakRecoveryCopy.EMPTY_PROTECTION, unavailable.message)
    }

    @Test fun recoveredDayKindInStreakStrip() {
        val summary = FocusSummary(
            availability = HistoryAvailability.VALUE,
            sessions = listOf(session("a", today.minusDays(2), 30), session("b", today, 40)),
            days = listOf(
                FocusDay(today.minusDays(2), 30 * 60_000L, completed = 1),
                FocusDay(today.minusDays(1), 0, recovered = true),
                FocusDay(today, 40 * 60_000L, completed = 1),
            ),
            streak = 3,
            bestRecordedStreak = 3,
        )
        val ui = focusStreakUi(summary, locale) as FocusStreakUi.Visible
        assertEquals(FocusStreakDayKind.RECOVERED, ui.days[1].kind)
        assertTrue(ui.days[1].contentDescription.contains("recovered"))
    }

    @Test fun formulaMatchesSharedVectors() {
        assertEquals(25, StreakRecoveryPolicy.requiredMinutes(15))
        assertEquals(40, StreakRecoveryPolicy.requiredMinutes(30))
        assertEquals(65, StreakRecoveryPolicy.requiredMinutes(50))
        assertEquals(115, StreakRecoveryPolicy.requiredMinutes(90))
        assertEquals(150, StreakRecoveryPolicy.requiredMinutes(120))
    }

    @Test fun hidesWhenNoMiss() {
        val summary = FocusSummary(
            availability = HistoryAvailability.VALUE,
            sessions = listOf(session("a", today.minusDays(1), 30)),
            days = listOf(FocusDay(today, 0)),
            streak = 1,
        )
        val ui = streakRecoveryUi(
            summary, emptyList(), at(today, 18), zone, historyStart, protection, locale,
        )
        assertEquals(StreakRecoveryUi.Hidden, ui)
    }

    @Test fun surfacesThirtyDayCooldownCopy() {
        val previousAt = at(today.minusDays(5), 18)
        val previous = HistoryEvent(
            type = StreakRecoveryPolicy.WIRE_TYPE,
            session = "old",
            at = previousAt,
            tz = zone.id,
            breakEnd = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli(),
            plannedEnd = 40 * 60_000L,
            recoveryEnd = 30 * 60_000L,
            sessions = listOf("contributor"),
            source = "fingerprint",
            reason = StreakRecoveryPolicy.REASON,
            originRoute = StreakRecoveryPolicy.ORIGIN,
            grant = "old",
        )
        val summary = FocusSummary(
            availability = HistoryAvailability.VALUE,
            sessions = listOf(session("a", today.minusDays(2), 30)),
            days = listOf(FocusDay(today, 0)),
            streak = 0,
        )
        val ui = streakRecoveryUi(
            summary, listOf(previous), at(today, 18), zone, historyStart, protection, locale,
        )
        val unavailable = ui as StreakRecoveryUi.Unavailable
        assertEquals("token_cooldown", unavailable.reason)
        assertTrue(unavailable.message.startsWith("Next free recovery available around"))
    }

    @Test fun progressStatusUsesQualifiedSessionsOnly() {
        val start = at(today)
        val end = start + 15 * 60_000L
        val events = listOf(
            HistoryEvent(
                id = "start-b", type = "started", session = "b", at = start, tz = zone.id,
                plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
                grant = fingerprint,
            ),
            HistoryEvent(id = "end-b", type = "completed", session = "b", at = end, tz = zone.id),
        )
        val summary = FocusSummary(
            availability = HistoryAvailability.VALUE,
            sessions = listOf(
                session("a", today.minusDays(2), 30),
                session("b", today, 15),
            ),
            days = listOf(FocusDay(today, 15 * 60_000L, completed = 1)),
            streak = 0,
        )
        val ui = streakRecoveryUi(
            summary, events, at(today, 18), zone, historyStart, protection, locale,
        ) as StreakRecoveryUi.Offer
        assertEquals(15, ui.progressMinutes)
        assertEquals(false, ui.satisfied)
        assertEquals(StreakRecoveryCopy.progress(15, 40), ui.statusLine)
        assertTrue(ui.expiresLabel.contains("Expires at the end of"))
    }

    @Test fun recoveryExpiresAtEndOfRecoveryDayBoundary() {
        val end = recoveryExpiresAtEndOfDay(today, zone)
        val nextStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(nextStart - 1, end)
        assertTrue(at(today, 23) <= end)
        assertTrue(at(today.plusDays(1), 0) > end)
    }

    @Test fun degradedEnforcementNeverShowsConfirmingOrSatisfied() {
        val start = at(today)
        val end = start + 40 * 60_000L
        val events = listOf(
            HistoryEvent(
                id = "start-b", type = "started", session = "b", at = start, tz = zone.id,
                plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
                grant = fingerprint,
            ),
            HistoryEvent(id = "end-b", type = "completed", session = "b", at = end, tz = zone.id),
        )
        val summary = FocusSummary(
            availability = HistoryAvailability.VALUE,
            sessions = listOf(
                session("a", today.minusDays(2), 30),
                session("b", today, 40),
            ),
            days = listOf(FocusDay(today, 40 * 60_000L, completed = 1)),
            streak = 0,
        )
        val intact = streakRecoveryUi(
            summary, events, at(today, 18), zone, historyStart, protection, locale,
            enforcementIntact = true,
        ) as StreakRecoveryUi.Offer
        assertTrue(intact.satisfied)
        assertEquals(StreakRecoveryCopy.CONFIRMING, intact.statusLine)

        val broken = streakRecoveryUi(
            summary, events, at(today, 18), zone, historyStart, protection, locale,
            enforcementIntact = false,
        ) as StreakRecoveryUi.Offer
        assertEquals(false, broken.satisfied)
        assertEquals(StreakRecoveryCopy.PROTECTION_DEGRADED, broken.statusLine)
        assertTrue(broken.contentDescription.contains("Protection is off"))
    }

    @Test fun healthyThenDegradedThenReconnectStaysDegraded() {
        val start = at(today)
        val end = start + 40 * 60_000L
        val events = listOf(
            HistoryEvent(
                id = "start-b", type = "started", session = "b", at = start, tz = zone.id,
                plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
                grant = fingerprint,
            ),
            HistoryEvent(id = "end-b", type = "completed", session = "b", at = end, tz = zone.id),
        )
        val summary = FocusSummary(
            availability = HistoryAvailability.VALUE,
            sessions = listOf(
                session("a", today.minusDays(2), 30),
                session("b", today, 40),
            ),
            days = listOf(FocusDay(today, 40 * 60_000L, completed = 1)),
            streak = 0,
        )
        fun offer(durable: Boolean, pathA: Boolean) = streakRecoveryUi(
            summary, events, at(today, 18), zone, historyStart, protection, locale,
            enforcementIntact = recoveryPresentationEnforcementIntact(durable, pathA),
        ) as StreakRecoveryUi.Offer

        val healthy = offer(durable = true, pathA = true)
        assertEquals(true, healthy.satisfied)
        assertEquals(StreakRecoveryCopy.CONFIRMING, healthy.statusLine)

        val degraded = offer(durable = true, pathA = false)
        assertEquals(false, degraded.satisfied)
        assertEquals(StreakRecoveryCopy.PROTECTION_DEGRADED, degraded.statusLine)

        // Latch written; Path A returns — presentation must stay degraded for the day.
        val reconnected = offer(durable = false, pathA = true)
        assertEquals(false, reconnected.satisfied)
        assertEquals(StreakRecoveryCopy.PROTECTION_DEGRADED, reconnected.statusLine)
        assertEquals(false, recoveryPresentationEnforcementIntact(durableIntact = false, pathALive = true))
    }

    @Test fun insightsPathALiveRequiresGrantBindingAndHub() {
        assertEquals(false, recoveryInsightsPathALive(true, true, false))
        assertEquals(false, recoveryInsightsPathALive(true, false, true))
        assertEquals(false, recoveryInsightsPathALive(false, true, true))
        assertEquals(true, recoveryInsightsPathALive(true, true, true))
    }
}
