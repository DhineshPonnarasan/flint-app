package com.flint.peakfocus.core.history

import kotlinx.coroutines.delay

/**
 * Process-lifetime watcher for Path A (accessibility) continuity during a recovery challenge.
 *
 * A challenge is only trustworthy if enforcement stayed up for its whole duration. A single
 * sample at commit time cannot prove that, so the app shell samples while the challenge is bound
 * and this loop latches the durable freeze on the first healthy → unhealthy edge. Latching is
 * permanent for the day (fail closed): a re-enabled service does not prove the gap was enforced.
 *
 * The loop is a plain suspend function so tests can drive the exact edge sequences without a
 * device or a running service.
 */
object RecoveryEnforcementWatch {
    const val DEFAULT_INTERVAL_MS = 15_000L

    /**
     * @param freezeActive whether a bound challenge exists right now.
     * @param pathAHealthy current accessibility/enforcement health.
     * @param iterations number of samples; production runs until cancelled.
     */
    suspend fun watch(
        guard: RecoveryChallengeGuardStore,
        freezeActive: () -> Boolean,
        pathAHealthy: () -> Boolean,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        iterations: Int = Int.MAX_VALUE,
        /**
         * Seed for "observed healthy while frozen". The bind itself verifies enforcement
         * (`healthyNow`), so passing true closes the cold-start window in which the first drop
         * would otherwise be unlatched.
         */
        initiallyHealthy: Boolean = false,
        /**
         * Identity and verified-at-bind health of the active freeze. A new challenge id re-seeds
         * the policy from the bind's verification, so a freeze bound *after* the watcher started
         * latches its first drop instead of waiting for a later healthy sample. Old callers that
         * only pass [freezeActive] keep the previous behaviour.
         */
        binding: () -> RecoveryChallengeGuardStore.ActiveBinding? = { null },
    ) {
        var state = RecoveryEnforcementWatchPolicy.State(
            observedHealthyWhileFrozen = initiallyHealthy,
        )
        var lastChallengeId: String? = null
        var sample = 0
        while (sample < iterations) {
            val frozen = freezeActive()
            val active = if (frozen) binding() else null
            if (active != null && active.challengeId != lastChallengeId) {
                // `ensureFrozen` only writes a profile after verifying enforcement, so the bind is
                // this challenge's first healthy observation. A stale profile from a previous
                // process is not intact; seeding false keeps the durable latch in force.
                state = RecoveryEnforcementWatchPolicy.State(
                    observedHealthyWhileFrozen = active.intact,
                )
            }
            lastChallengeId = active?.challengeId
            // Nothing to watch without a bound challenge; do not even read enforcement health.
            val decision = RecoveryEnforcementWatchPolicy.next(
                state = state,
                freezeActive = frozen,
                pathAHealthy = frozen && pathAHealthy(),
            )
            state = decision.state
            // Idempotent in the store; a latched freeze never un-latches this day.
            if (decision.shouldLatchBroken) {
                // Idempotent and failure-tolerant: the in-memory latch already fails the
                // challenge closed, and every sample retries the durable write. A storage error
                // must never terminate monitoring.
                guard.markEnforcementBroken()
            }
            sample++
            if (sample < iterations) delay(intervalMs)
        }
    }
}
