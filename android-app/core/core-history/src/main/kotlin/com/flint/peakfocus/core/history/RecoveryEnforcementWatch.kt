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
    ) {
        var state = RecoveryEnforcementWatchPolicy.State(
            observedHealthyWhileFrozen = initiallyHealthy,
        )
        var sample = 0
        while (sample < iterations) {
            // Nothing to watch without a bound challenge; do not even read enforcement health.
            val frozen = freezeActive()
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
