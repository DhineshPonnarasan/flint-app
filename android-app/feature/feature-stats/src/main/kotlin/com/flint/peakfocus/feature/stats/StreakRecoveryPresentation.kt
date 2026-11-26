package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.StreakRecoveryPolicy
import com.flint.peakfocus.core.history.StreakRecoveryProtection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** App-owned presentation for conditional free streak recovery (policy v1). */
internal sealed class StreakRecoveryUi {
    data object Hidden : StreakRecoveryUi()

    data class Unavailable(
        val reason: String,
        val message: String,
    ) : StreakRecoveryUi()

    data class Offer(
        val requiredMinutes: Int,
        val progressMinutes: Int,
        val satisfied: Boolean,
        val missedDayLabel: String,
        val expiresLabel: String,
        val statusLine: String,
        val protectedAppsLabel: String,
        val protectionLocked: Boolean,
        val contentDescription: String,
    ) : StreakRecoveryUi()
}

internal object StreakRecoveryCopy {
    const val TITLE = "Streak recovery"
    const val TOKEN_FREE = "1 free recovery every 30 days"
    const val SUCCESS = "Yesterday is recovered. Your streak continues."
    /** Shown while duration is met but the durable commit has not yet succeeded. */
    const val CONFIRMING = "Challenge met — confirming recovery…"
    const val FOOTNOTE =
        "Complete the recovery challenge today to bridge yesterday. No payment or ads."
    const val PROTECTION_LOCKED =
        "These apps stay locked for the recovery challenge. Changing your selection elsewhere will not change this freeze."
    const val PROTECTION_DEGRADED =
        "Protection is off. Re-enable accessibility access — recovery credit pauses until shields can enforce again."
    const val EMPTY_PROTECTION =
        "Choose apps in your protected selection before recovering. Recovery shields that established list — not a new substitute."
    const val CONFIRM_FAILED =
        "Recovery couldn't be confirmed. Nothing changed — your streak stays as it is."
    const val PERSIST_FAILED =
        "Recovery couldn't be saved. Nothing changed — try again later."
    const val PROTECTION_OFF =
        "Turn accessibility back on to recover a streak. Protection must be live for the whole challenge."

    fun required(minutes: Int): String = "Focus $minutes minutes today to recover yesterday"

    fun progress(done: Int, required: Int): String = "$done of $required minutes completed today"

    fun protectedApps(count: Int, allowList: Boolean): String =
        "${if (allowList) "Allow-list" else "Block-list"} locked: $count apps"
}

/** End of the recovery day, exclusive: the last millisecond before the next local midnight. */
internal fun recoveryExpiresAtEndOfDay(day: LocalDate, zone: ZoneId): Long =
    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L

/** Presentation enforcement is intact only while the durable latch and Path A are both sound. */
internal fun recoveryPresentationEnforcementIntact(durableIntact: Boolean, pathALive: Boolean): Boolean =
    durableIntact && pathALive

/** Insights surface accepts a live Path A only with grant binding and the enforcement hub. */
internal fun recoveryInsightsPathALive(
    pathALive: Boolean,
    grantBound: Boolean,
    enforcementHubLive: Boolean,
): Boolean = pathALive && grantBound && enforcementHubLive

/**
 * Pure Insights mapping for the recovery card. Uses the policy for eligibility and the
 * protection fingerprint for progress; no eligibility or credit rule is invented here.
 */
internal fun streakRecoveryUi(
    summary: FocusSummary?,
    events: List<HistoryEvent>,
    now: Long,
    zone: ZoneId,
    historyStart: LocalDate?,
    protection: StreakRecoveryProtection.BoundProfile?,
    locale: Locale = Locale.getDefault(),
    enforcementIntact: Boolean = true,
    recoveryFloorAt: Long? = null,
    enforcementReady: Boolean = true,
): StreakRecoveryUi {
    if (summary == null || summary.availability == HistoryAvailability.UNAVAILABLE) {
        return StreakRecoveryUi.Hidden
    }
    if (historyStart == null) return StreakRecoveryUi.Hidden

    val eligibility = StreakRecoveryPolicy.evaluate(
        summary.sessions,
        events,
        now,
        zone,
        historyStart,
        // Degraded or unavailable history cannot prove the allowance is unused: fail closed.
        readable = summary.availability == HistoryAvailability.VALUE,
        recoveryFloorAt = recoveryFloorAt,
    )
    val dateLabel = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val dateTimeLabel = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)

    return when (eligibility) {
        is StreakRecoveryPolicy.Eligibility.Unavailable -> when (eligibility.reason) {
            "token_cooldown" -> {
                val next = StreakRecoveryPolicy.nextTokenAvailableAt(events, recoveryFloorAt)
                val label = next?.let { dateTimeLabel.format(Instant.ofEpochMilli(it).atZone(zone)) }
                if (label != null) {
                    StreakRecoveryUi.Unavailable(
                        "token_cooldown",
                        "Next free recovery available around $label",
                    )
                } else {
                    StreakRecoveryUi.Unavailable(
                        "token_cooldown",
                        "Your free recovery is cooling down. It returns 30 days after the last success.",
                    )
                }
            }
            "no_prior_streak" -> StreakRecoveryUi.Unavailable(
                "no_prior_streak",
                "Recovery needs an active streak before the missed day.",
            )
            else -> StreakRecoveryUi.Hidden
        }

        is StreakRecoveryPolicy.Eligibility.Offer -> {
            val challenge = eligibility.challenge
            if (protection == null || protection.isEmpty || protection.recoveryDay != challenge.recoveryDay) {
                if (!enforcementReady) {
                    StreakRecoveryUi.Unavailable("protection_off", StreakRecoveryCopy.PROTECTION_OFF)
                } else {
                    StreakRecoveryUi.Unavailable("empty_protection", StreakRecoveryCopy.EMPTY_PROTECTION)
                }
            } else {
                // Grant-only progress: sidecar qualifiedSessionIds cannot inflate the UI.
                val progressMs = StreakRecoveryProtection.focusedMsOnDay(
                    sessions = summary.sessions,
                    day = challenge.recoveryDay,
                    zone = zone,
                    events = events,
                    fingerprint = protection.fingerprint,
                )
                val progressMinutes = (progressMs / 60_000L).toInt()
                val durationMet = progressMs >= challenge.requiredFocusedMs
                val missed = dateLabel.format(challenge.missedDay)
                val recoveryDay = dateLabel.format(challenge.recoveryDay)
                val apps = StreakRecoveryCopy.protectedApps(
                    protection.packageNames.size,
                    protection.allowListMode,
                )
                val status = when {
                    !enforcementIntact -> StreakRecoveryCopy.PROTECTION_DEGRADED
                    durationMet -> StreakRecoveryCopy.CONFIRMING
                    else -> StreakRecoveryCopy.progress(progressMinutes, challenge.requiredMinutes)
                }
                var description = "${StreakRecoveryCopy.TITLE}. Missed $missed. " +
                    "${StreakRecoveryCopy.required(challenge.requiredMinutes)}. $apps. " +
                    StreakRecoveryCopy.TOKEN_FREE
                if (!enforcementIntact) description += ". ${StreakRecoveryCopy.PROTECTION_DEGRADED}"
                StreakRecoveryUi.Offer(
                    requiredMinutes = challenge.requiredMinutes,
                    progressMinutes = minOf(progressMinutes, challenge.requiredMinutes),
                    satisfied = durationMet && enforcementIntact,
                    missedDayLabel = missed,
                    expiresLabel = "Expires at the end of $recoveryDay",
                    statusLine = status,
                    protectedAppsLabel = apps,
                    protectionLocked = enforcementIntact,
                    contentDescription = description,
                )
            }
        }
    }
}
