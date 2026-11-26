package com.flint.peakfocus.core.history

/**
 * Edge-triggered Path A health policy for mid-challenge recovery monitoring.
 *
 * Latches only after Path A was observed healthy while a freeze was active, then became
 * unhealthy. This avoids false `enforcementBroken` during cold-start UNKNOWN / pre-bind
 * detector samples. Commit and Timer still refuse Path A–down challenges without a prior
 * healthy sample in this process.
 */
object RecoveryEnforcementWatchPolicy {
    data class State(val observedHealthyWhileFrozen: Boolean = false)

    data class Decision(val state: State, val shouldLatchBroken: Boolean)

    fun next(state: State, freezeActive: Boolean, pathAHealthy: Boolean): Decision {
        if (!freezeActive) return Decision(State(false), shouldLatchBroken = false)
        if (pathAHealthy) return Decision(State(true), shouldLatchBroken = false)
        // Freeze active + Path A down.
        return Decision(
            state = state,
            shouldLatchBroken = state.observedHealthyWhileFrozen,
        )
    }
}
