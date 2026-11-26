package com.flint.peakfocus.blocking.accessibility

import android.os.SystemClock
import com.flint.peakfocus.core.model.EnforcementHeartbeat

/**
 * Process-local liveness of the real Path A service.
 *
 * The app shell reads [isFresh] to prove enforcement; the service itself calls [onConnected],
 * [beat] and [onDisconnected]. Nothing here touches disk, so an idle app performs no writes.
 */
object ServiceLiveness {
    val heartbeat = EnforcementHeartbeat(SystemClock::elapsedRealtime)

    fun isFresh(windowMs: Long = EnforcementHeartbeat.DEFAULT_FRESHNESS_MS): Boolean =
        heartbeat.isFresh(windowMs = windowMs)

    fun onConnected() {
        heartbeat.onConnected()
    }

    fun beat() {
        heartbeat.beat()
    }

    fun onDisconnected() {
        heartbeat.onDisconnected()
    }
}
