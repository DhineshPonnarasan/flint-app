package com.flint.peakfocus.blocking.accessibility

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The system can interrupt an accessibility service's feedback without unbinding it. That state
 * cannot prove enforcement, so it must invalidate liveness immediately and fail a recovery
 * challenge closed until a fresh connection.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceInterruptLivenessTest {
    @After fun tearDown() {
        ServiceLiveness.onDisconnected()
    }

    @Test fun interruptInvalidatesTheHeartbeatImmediately() {
        ServiceLiveness.onConnected()
        assertTrue(ServiceLiveness.isFresh())

        val service = Robolectric.buildService(FlintAccessibilityService::class.java).create().get()
        service.onInterrupt()

        assertFalse("an interrupted service can no longer prove enforcement", ServiceLiveness.isFresh())
    }

    @Test fun aFreshConnectionReArmsLiveness() {
        val service = Robolectric.buildService(FlintAccessibilityService::class.java).create().get()
        service.onInterrupt()
        assertFalse(ServiceLiveness.isFresh())

        ServiceLiveness.onConnected()
        assertTrue("only a new service instance re-arms liveness", ServiceLiveness.isFresh())
    }
}
