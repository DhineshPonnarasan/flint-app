package com.flint.peakfocus.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure liveness semantics for the accessibility service heartbeat. */
class EnforcementHeartbeatTest {
    private var now = 1_000L
    private val heartbeat = EnforcementHeartbeat(elapsedMs = { now }, freshnessMs = 90_000L)

    @Test fun neverConnectedIsNotFresh() {
        assertFalse(heartbeat.isFresh())
    }

    @Test fun connectionCreatesAFreshHeartbeat() {
        heartbeat.onConnected()
        assertTrue(heartbeat.isFresh())
        assertEquals(1L, heartbeat.snapshot().instanceToken)
    }

    @Test fun beatsKeepItFreshWithinTheWindow() {
        heartbeat.onConnected()
        now += 89_000L
        assertTrue("inside the freshness window", heartbeat.isFresh())
        now += 2_000L
        assertFalse("freshness is bounded by the last beat", heartbeat.isFresh())
        heartbeat.beat()
        assertTrue(heartbeat.isFresh())
    }

    @Test fun staleBeatFailsClosed() {
        heartbeat.onConnected()
        // Three missed 30 s beats.
        now += 90_001L
        assertFalse("a service that stopped beating is not enforcing", heartbeat.isFresh())
    }

    @Test fun disconnectionInvalidatesImmediately() {
        heartbeat.onConnected()
        heartbeat.onDisconnected()
        assertFalse(heartbeat.isFresh())
    }

    @Test fun clockRollbackFailsClosed() {
        heartbeat.onConnected()
        now -= 5_000L
        assertFalse("a backwards clock must not extend the window", heartbeat.isFresh())
    }

    @Test fun reconnectStartsAFreshToken() {
        heartbeat.onConnected()
        heartbeat.onDisconnected()
        val token = heartbeat.onConnected()
        assertEquals(2L, token)
        assertTrue(heartbeat.isFresh())
    }

    @Test fun beatWithoutAConnectionDoesNothing() {
        heartbeat.beat()
        assertFalse(heartbeat.isFresh())
        assertEquals(Long.MIN_VALUE, heartbeat.snapshot().lastBeatMs)
    }
}
