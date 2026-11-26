package com.flint.peakfocus.core.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Domain owner for conditional free streak recovery (policy v1).
 * Pure functions — no I/O. UI and stores must not invent eligibility.
 *
 * Spec: `docs/research/streak-recovery/recovery-policy-spec.md`
 */
object StreakRecoveryPolicy {
    const val POLICY_VERSION = "v1"
    const val WIRE_TYPE = "streak_recovered"
    const val REASON = "policy:v1"
    const val ORIGIN = "streak_recovery"
    const val COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000
    const val DEFAULT_BASELINE_MINUTES = 30
    private const val MIN_EXTRA = 10
    private const val MAX_EXTRA = 30

    data class Challenge(
        val missedDay: LocalDate,
        val recoveryDay: LocalDate,
        val baselineMinutes: Int,
        val requiredMinutes: Int,
        val requiredFocusedMs: Long,
    )

    sealed class Eligibility {
        data class Offer(val challenge: Challenge) : Eligibility()
        data class Unavailable(val reason: String) : Eligibility()
    }

    fun roundToFive(value: Double): Int = (value / 5.0).roundToInt() * 5

    fun extraMinutes(baselineMinutes: Int): Int {
        val raw = roundToFive(0.30 * baselineMinutes.coerceAtLeast(0))
        return raw.coerceIn(MIN_EXTRA, MAX_EXTRA)
    }

    fun requiredMinutes(baselineMinutes: Int): Int {
        val baseline = baselineMinutes.coerceAtLeast(1)
        return baseline + extraMinutes(baseline)
    }

    fun requiredFocusedMs(baselineMinutes: Int): Long = requiredMinutes(baselineMinutes) * 60_000L

    fun date(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** Only policy-shaped recovery bridges count toward continuity / token state. */
    fun isWellFormedRecovery(event: HistoryEvent): Boolean {
        if (event.knownType != HistoryEventType.STREAK_RECOVERED && event.type != WIRE_TYPE) return false
        return event.breakEnd != null &&
            event.plannedEnd != null &&
            event.recoveryEnd != null &&
            !event.sessions.isNullOrEmpty() &&
            !event.grant.isNullOrBlank() &&
            !event.source.isNullOrBlank() &&
            event.reason == REASON &&
            event.originRoute == ORIGIN &&
            event.id.isNotBlank()
    }

    /** Civil day recorded on the session terminal, falling back only for legacy records. */
    fun recordedDay(session: FocusSession, fallbackZone: ZoneId): LocalDate =
        date(session.end, session.terminalZone ?: fallbackZone)

    /** Civil day recorded on a recovery bridge, falling back only for legacy records. */
    fun recoveryDay(event: HistoryEvent, fallbackZone: ZoneId): LocalDate? {
        val at = event.breakEnd ?: return null
        val recorded = runCatching { ZoneId.of(event.tz) }.getOrNull() ?: fallbackZone
        return date(at, recorded)
    }

    fun recoveredDays(events: List<HistoryEvent>, zone: ZoneId, historyStart: LocalDate): Set<LocalDate> =
        events.asSequence()
            .filter(::isWellFormedRecovery)
            .mapNotNull { event -> recoveryDay(event, zone) }
            .filter { it >= historyStart }
            .toSet()

    fun completedDays(sessions: List<FocusSession>, zone: ZoneId): Set<LocalDate> =
        sessions.filter { it.outcome == SessionOutcome.COMPLETED }
            .map { recordedDay(it, zone) }.toSet()

    fun streakDays(completed: Set<LocalDate>, recovered: Set<LocalDate>): Set<LocalDate> =
        completed + recovered

    fun lastSuccessfulRecoveryAt(events: List<HistoryEvent>): Long? =
        events.asSequence()
            .filter(::isWellFormedRecovery)
            .map { it.at }
            .maxOrNull()

    /**
     * Latest recovery evidence, including the durable ledger. The ledger preserves the
     * allowance after Clear History has removed every recovery row.
     */
    fun allowanceAt(events: List<HistoryEvent>, recoveryFloorAt: Long? = null): Long? =
        listOfNotNull(lastSuccessfulRecoveryAt(events), recoveryFloorAt).maxOrNull()

    fun tokenAvailable(events: List<HistoryEvent>, now: Long, recoveryFloorAt: Long? = null): Boolean {
        val last = allowanceAt(events, recoveryFloorAt) ?: return true
        return now - last >= COOLDOWN_MS
    }

    /**
     * Exact instant the free recovery allowance returns after a successful commit.
     * Boundary is [lastSuccessfulRecoveryAt] + [COOLDOWN_MS] wall-clock milliseconds
     * (30 × 24h), independent of local timezone and DST transitions.
     */
    fun nextTokenAvailableAt(events: List<HistoryEvent>, recoveryFloorAt: Long? = null): Long? {
        val last = allowanceAt(events, recoveryFloorAt) ?: return null
        val next = last + COOLDOWN_MS
        return if (next < last) null else next // overflow guard
    }

    /**
     * Baseline = longest completed-session **wall span** (end − start, minutes) on [day].
     * Intentional: progress credits net [focusedMs] (breaks/passes excluded), but the Model 2
     * baseline mirrors the user's committed session length. Both platforms share this split.
     */
    fun baselineMinutesForDay(
        sessions: List<FocusSession>,
        day: LocalDate,
        zone: ZoneId,
        fallback: Int = DEFAULT_BASELINE_MINUTES,
    ): Int {
        val lengths = sessions.asSequence()
            .filter { it.outcome == SessionOutcome.COMPLETED && recordedDay(it, zone) == day }
            .map { ((it.end - it.start).coerceAtLeast(0L) / 60_000L).toInt().coerceAtLeast(1) }
            .toList()
        return lengths.maxOrNull() ?: fallback
    }

    /** Progress uses net focusedMs (breaks/passes already removed), matching Insights aggregation. */
    fun focusedMsOnDay(sessions: List<FocusSession>, day: LocalDate, zone: ZoneId): Long =
        sessions.asSequence()
            .filter { it.outcome == SessionOutcome.COMPLETED && recordedDay(it, zone) == day }
            .sumOf { it.focusedMs.coerceAtLeast(0L) }

    fun evaluate(
        sessions: List<FocusSession>,
        events: List<HistoryEvent>,
        now: Long,
        zone: ZoneId,
        historyStart: LocalDate,
        readable: Boolean,
        recoveryFloorAt: Long? = null,
    ): Eligibility {
        if (!readable) return Eligibility.Unavailable("history_unreadable")
        val today = date(now, zone)
        val missed = today.minusDays(1)
        if (missed < historyStart) return Eligibility.Unavailable("before_history_start")
        val completed = completedDays(sessions, zone)
        val recovered = recoveredDays(events, zone, historyStart)
        if (missed in recovered) return Eligibility.Unavailable("already_recovered")
        if (missed in completed) return Eligibility.Unavailable("no_missed_day")
        // Exactly one miss: yesterday incomplete; day before yesterday must be streak-bearing.
        val prior = missed.minusDays(1)
        val streakBearing = streakDays(completed, recovered)
        if (prior < historyStart || prior !in streakBearing) {
            return Eligibility.Unavailable("no_prior_streak")
        }
        // Two+ misses: if prior-1 also empty of streak days, gap is wider than one day
        // (already covered by prior not in streakBearing). Also reject if today-2 missing
        // while we somehow had prior — standard one-day window is today = day after miss.
        if (!tokenAvailable(events, now, recoveryFloorAt)) return Eligibility.Unavailable("token_cooldown")
        val baseline = baselineMinutesForDay(sessions, prior, zone)
        val required = requiredMinutes(baseline)
        return Eligibility.Offer(
            Challenge(
                missedDay = missed,
                recoveryDay = today,
                baselineMinutes = baseline,
                requiredMinutes = required,
                requiredFocusedMs = required * 60_000L,
            ),
        )
    }

    fun progressMs(sessions: List<FocusSession>, challenge: Challenge, zone: ZoneId): Long =
        focusedMsOnDay(sessions, challenge.recoveryDay, zone)

    /**
     * Recovery progress under a frozen protection fingerprint. Only completed sessions whose
     * `started.grant` matches [fingerprint] count — substituted app lists never qualify.
     */
    fun progressMs(
        sessions: List<FocusSession>,
        challenge: Challenge,
        zone: ZoneId,
        events: List<HistoryEvent>,
        fingerprint: String,
    ): Long = StreakRecoveryProtection.focusedMsOnDay(
        sessions = sessions,
        day = challenge.recoveryDay,
        zone = zone,
        events = events,
        fingerprint = fingerprint,
    )

    fun isSatisfied(sessions: List<FocusSession>, challenge: Challenge, zone: ZoneId): Boolean =
        progressMs(sessions, challenge, zone) >= challenge.requiredFocusedMs

    fun isSatisfied(
        sessions: List<FocusSession>,
        challenge: Challenge,
        zone: ZoneId,
        events: List<HistoryEvent>,
        fingerprint: String,
    ): Boolean = progressMs(sessions, challenge, zone, events, fingerprint) >= challenge.requiredFocusedMs

    fun buildRecoveryEvent(
        challenge: Challenge,
        sessions: List<FocusSession>,
        now: Long,
        zone: ZoneId,
        recoveryId: String = UUID.randomUUID().toString(),
        events: List<HistoryEvent> = emptyList(),
        fingerprint: String? = null,
    ): HistoryEvent {
        val contributors = if (fingerprint.isNullOrBlank()) {
            sessions.filter {
                it.outcome == SessionOutcome.COMPLETED && recordedDay(it, zone) == challenge.recoveryDay
            }.map { it.id }
        } else {
            val qualified = StreakRecoveryProtection.qualifyingSessionIds(events, fingerprint)
            sessions.filter {
                it.outcome == SessionOutcome.COMPLETED &&
                    it.id in qualified &&
                    recordedDay(it, zone) == challenge.recoveryDay
            }.map { it.id }
        }
        val missedStart = challenge.missedDay.atStartOfDay(zone).toInstant().toEpochMilli()
        return HistoryEvent(
            id = recoveryId,
            type = WIRE_TYPE,
            session = recoveryId,
            at = now,
            tz = zone.id,
            breakEnd = missedStart,
            plannedEnd = challenge.requiredFocusedMs,
            recoveryEnd = challenge.baselineMinutes * 60_000L,
            sessions = contributors,
            reason = REASON,
            originRoute = ORIGIN,
            grant = recoveryId,
            name = "Streak recovery",
            // Opaque protection fingerprint for audit; not required for well-formedness.
            source = fingerprint,
            timeConfidence = HistoryEventTimeConfidence.Certain,
        )
    }
}
