package com.flint.peakfocus.core.history

import java.security.MessageDigest
import java.time.LocalDate

/**
 * Architecture (protected streak recovery):
 *
 * Decision: freeze the user's **established** protection targets at recovery-offer bind time,
 * reuse the existing timer/session blocking engine for the challenge duration (no second
 * arbitrary penalty), and credit only sessions whose opaque target fingerprint matches the
 * freeze. Do not infer distraction from app category or usage surveillance.
 *
 * Why not "extend the last pre-miss session profile"? Focus history does not retain package
 * lists, so a pre-miss snapshot is unavailable without a new always-on selection log. The
 * smallest defensible change is freezing the current enabled permanent block-rule targets
 * (the established protection configuration) when the offer binds.
 *
 * Rolling 30-day token: [StreakRecoveryPolicy.COOLDOWN_MS] wall-clock milliseconds after the
 * last well-formed `streak_recovered.at` (timezone-independent duration; DST does not stretch
 * or shrink the cooldown).
 */
object StreakRecoveryProtection {
    /** v2: SHA-256 digest so history `started.grant` never embeds raw package names. */
    const val FINGERPRINT_VERSION = "v2"

    data class BoundProfile(
        val fingerprint: String,
        val packageNames: List<String>,
        val allowListMode: Boolean,
        val recoveryDay: LocalDate,
        val missedDay: LocalDate,
        val frozenAt: Long,
        /** Identity of this challenge; a latch is scoped to it, not to the device date. */
        val challengeId: String = "",
    ) {
        val isEmpty: Boolean get() = packageNames.isEmpty()
    }

    /**
     * Opaque, order-independent fingerprint. Raw packages stay only in the guard sidecar;
     * history stores this digest in `started.grant`.
     */
    fun fingerprint(packageNames: Collection<String>, allowListMode: Boolean): String {
        val normalized = packageNames.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
        val mode = if (allowListMode) "a" else "b"
        val material = "$FINGERPRINT_VERSION|$mode|${normalized.joinToString("\u0001")}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun bind(
        packageNames: Collection<String>,
        allowListMode: Boolean,
        recoveryDay: LocalDate,
        missedDay: LocalDate,
        frozenAt: Long,
        challengeId: String = java.util.UUID.randomUUID().toString(),
    ): BoundProfile? {
        val normalized = packageNames.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
        if (normalized.isEmpty()) return null
        return BoundProfile(
            fingerprint = fingerprint(normalized, allowListMode),
            packageNames = normalized,
            allowListMode = allowListMode,
            recoveryDay = recoveryDay,
            missedDay = missedDay,
            frozenAt = frozenAt,
            challengeId = challengeId,
        )
    }

    fun matches(
        packageNames: Collection<String>,
        allowListMode: Boolean,
        expectedFingerprint: String,
    ): Boolean = fingerprint(packageNames, allowListMode) == expectedFingerprint

    /** Session IDs whose `started` event carries the frozen protection fingerprint in `grant`. */
    fun qualifyingSessionIds(events: List<HistoryEvent>, fingerprint: String): Set<String> {
        if (fingerprint.isBlank()) return emptySet()
        return events.asSequence()
            .filter {
                it.knownType == HistoryEventType.STARTED &&
                    it.grant == fingerprint &&
                    it.session.isNotBlank()
            }
            .map { it.session }
            .toSet()
    }

    fun focusedMsOnDay(
        sessions: List<FocusSession>,
        day: java.time.LocalDate,
        zone: java.time.ZoneId,
        events: List<HistoryEvent>,
        fingerprint: String,
    ): Long {
        val qualified = qualifyingSessionIds(events, fingerprint)
        if (qualified.isEmpty()) return 0L
        return sessions.asSequence()
            .filter {
                it.outcome == SessionOutcome.COMPLETED &&
                    it.id in qualified &&
                    StreakRecoveryPolicy.recordedDay(it, zone) == day
            }
            .sumOf { it.focusedMs.coerceAtLeast(0L) }
    }
}
