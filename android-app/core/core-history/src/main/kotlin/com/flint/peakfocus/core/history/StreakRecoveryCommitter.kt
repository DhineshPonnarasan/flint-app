package com.flint.peakfocus.core.history

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Durable commit path for streak recovery. Success UI must wait for [Result.Success].
 * Token consumption occurs only when the recovery event is durably appended.
 *
 * Eligibility and progress are always derived from durable store events inside the
 * store lock — never from caller-supplied session lists or UI clocks.
 *
 * A frozen [StreakRecoveryProtection.BoundProfile] is required. Only sessions whose
 * `started.grant` matches the freeze fingerprint contribute focused time.
 */
object StreakRecoveryCommitter {
    sealed class Result {
        data class Success(val event: HistoryEvent, val challenge: StreakRecoveryPolicy.Challenge) : Result()
        data class Rejected(val reason: String) : Result()
        data object PersistFailed : Result()
    }

    /**
     * @param now ignored for eligibility; kept only for binary compatibility. Commit uses
     * [FocusHistoryStore.millis] as the sole authoritative clock.
     * @param protection frozen established protection; null rejects with `protection_required`.
     * @param protectionActive whether OS enforcement for the freeze is currently live
     * (Accessibility on Android / Screen Time on iOS). False rejects with `protection_inactive`
     * so a mid-challenge permission revoke cannot still award recovery.
     */
    suspend fun tryCommit(
        store: FocusHistoryStore,
        now: Long = 0L,
        zone: ZoneId = ZoneId.systemDefault(),
        activeSessionIds: Set<String> = emptySet(),
        recoveryId: String? = null,
        protection: StreakRecoveryProtection.BoundProfile? = null,
        protectionActive: Boolean = true,
        onCommitted: (() -> Unit)? = null,
    ): Result {
        var built: HistoryEvent? = null
        var lockedChallenge: StreakRecoveryPolicy.Challenge? = null
        var rejectReason: String? = null
        // Stable id for the bridge row: a replay of the same id must never double-credit.
        val targetId = recoveryId ?: UUID.randomUUID().toString()
        val ok = store.updateRecovery { snapshot ->
            val commitNow = store.millis()
            val historyStart = if (snapshot.historyStart > 0) {
                Instant.ofEpochMilli(snapshot.historyStart).atZone(zone).toLocalDate()
            } else {
                rejectReason = "before_history_start"
                return@updateRecovery emptyList()
            }
            if (protection == null || protection.isEmpty) {
                rejectReason = if (protection == null) "protection_required" else "empty_protection"
                return@updateRecovery emptyList()
            }
            if (!protectionActive) {
                rejectReason = "protection_inactive"
                return@updateRecovery emptyList()
            }
            val sessions = FocusHistoryAggregator.sessions(snapshot.events, commitNow, activeSessionIds)
            // Fail closed on partially corrupt history: a lost recovery row must not be mistaken
            // for an available allowance, so only a fully readable snapshot may authorize credit.
            when (val again = StreakRecoveryPolicy.evaluate(
                sessions,
                snapshot.events,
                commitNow,
                zone,
                historyStart,
                snapshot.readable && !snapshot.degraded,
                recoveryFloorAt = snapshot.recoveryFloorAt,
            )) {
                is StreakRecoveryPolicy.Eligibility.Unavailable -> {
                    rejectReason = again.reason
                    emptyList()
                }
                is StreakRecoveryPolicy.Eligibility.Offer -> {
                    if (protection.recoveryDay != again.challenge.recoveryDay ||
                        protection.missedDay != again.challenge.missedDay
                    ) {
                        rejectReason = "protection_mismatch"
                        emptyList()
                    } else if (!StreakRecoveryPolicy.isSatisfied(
                            sessions,
                            again.challenge,
                            zone,
                            snapshot.events,
                            protection.fingerprint,
                        )
                    ) {
                        rejectReason = "insufficient_duration"
                        emptyList()
                    } else {
                        if (snapshot.events.any { it.id == targetId }) {
                            rejectReason = "duplicate_id"
                            return@updateRecovery emptyList()
                        }
                        val event = StreakRecoveryPolicy.buildRecoveryEvent(
                            challenge = again.challenge,
                            sessions = sessions,
                            now = commitNow,
                            zone = zone,
                            recoveryId = targetId,
                            events = snapshot.events,
                            fingerprint = protection.fingerprint,
                        )
                        built = event
                        lockedChallenge = again.challenge
                        listOf(event)
                    }
                }
            }
        }
        val event = built
        val challenge = lockedChallenge
        return when {
            !ok -> Result.PersistFailed
            // Durable success only: the published snapshot is replaced after the append commits,
            // so an id the store coalesced away must never be reported as a recovery.
            event != null && challenge != null &&
                store.snapshots.value.events.any { it.id == event.id } -> {
                onCommitted?.invoke()
                Result.Success(event, challenge)
            }
            event != null && challenge != null -> Result.Rejected("duplicate_id")
            else -> Result.Rejected(rejectReason ?: "race_rejected")
        }
    }
}
