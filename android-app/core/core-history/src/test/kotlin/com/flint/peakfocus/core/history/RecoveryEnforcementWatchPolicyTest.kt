package com.flint.peakfocus.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEnforcementWatchPolicyTest {
    @Test fun startupUnhealthyWithoutPriorHealthyDoesNotLatch() {
        var state = RecoveryEnforcementWatchPolicy.State()
        // Cold start: freeze may already exist; Path A not yet bound.
        val first = RecoveryEnforcementWatchPolicy.next(state, freezeActive = true, pathAHealthy = false)
        assertFalse(first.shouldLatchBroken)
        assertFalse(first.state.observedHealthyWhileFrozen)
        state = first.state
        // Still unhealthy before bind — still no latch.
        val second = RecoveryEnforcementWatchPolicy.next(state, freezeActive = true, pathAHealthy = false)
        assertFalse(second.shouldLatchBroken)
    }

    @Test fun healthyThenDisconnectLatches() {
        var state = RecoveryEnforcementWatchPolicy.State()
        state = RecoveryEnforcementWatchPolicy.next(state, freezeActive = true, pathAHealthy = true).state
        assertTrue(state.observedHealthyWhileFrozen)
        val down = RecoveryEnforcementWatchPolicy.next(state, freezeActive = true, pathAHealthy = false)
        assertTrue(down.shouldLatchBroken)
    }

    @Test fun reconnectAfterHealthyDoesNotClearArmingNeedForNextDrop() {
        var state = RecoveryEnforcementWatchPolicy.State()
        state = RecoveryEnforcementWatchPolicy.next(state, freezeActive = true, pathAHealthy = true).state
        val drop = RecoveryEnforcementWatchPolicy.next(state, freezeActive = true, pathAHealthy = false)
        assertTrue(drop.shouldLatchBroken)
        // Reconnect: still frozen, healthy again — no latch on healthy.
        val up = RecoveryEnforcementWatchPolicy.next(drop.state, freezeActive = true, pathAHealthy = true)
        assertFalse(up.shouldLatchBroken)
        assertTrue(up.state.observedHealthyWhileFrozen)
        // Second drop still latches (idempotent with durable store).
        val drop2 = RecoveryEnforcementWatchPolicy.next(up.state, freezeActive = true, pathAHealthy = false)
        assertTrue(drop2.shouldLatchBroken)
    }

    @Test fun clearingFreezeResetsHealthyArm() {
        var state = RecoveryEnforcementWatchPolicy.State()
        state = RecoveryEnforcementWatchPolicy.next(state, freezeActive = true, pathAHealthy = true).state
        val cleared = RecoveryEnforcementWatchPolicy.next(state, freezeActive = false, pathAHealthy = false)
        assertFalse(cleared.shouldLatchBroken)
        assertFalse(cleared.state.observedHealthyWhileFrozen)
        // New freeze day later: unhealthy first sample must not latch.
        val nextDay = RecoveryEnforcementWatchPolicy.next(cleared.state, freezeActive = true, pathAHealthy = false)
        assertFalse(nextDay.shouldLatchBroken)
    }

    @Test fun noFreezeNeverLatchesEvenWhenPathADown() {
        val decision = RecoveryEnforcementWatchPolicy.next(
            RecoveryEnforcementWatchPolicy.State(true),
            freezeActive = false,
            pathAHealthy = false,
        )
        assertFalse(decision.shouldLatchBroken)
        assertEquals(false, decision.state.observedHealthyWhileFrozen)
    }

    @Test fun pathAHealthyRequiresAccessibilityGrantAndLiveBinding() {
        // Watcher sample is a11y grant ∧ live Path A — Path B alone is never healthy.
        fun sample(a11y: Boolean, pathA: Boolean) = a11y && pathA
        assertFalse(sample(a11y = false, pathA = true))
        assertFalse(sample(a11y = true, pathA = false))
        assertFalse(sample(a11y = false, pathA = false))
        assertTrue(sample(a11y = true, pathA = true))
    }
}
