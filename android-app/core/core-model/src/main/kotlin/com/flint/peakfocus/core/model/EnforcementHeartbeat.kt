package com.flint.peakfocus.core.model

/**
 * Liveness of the real AccessibilityService instance, not the Settings toggle.
 *
 * The service records a connection token and refreshes it on a cheap in-memory beat; callers ask
 * whether that beat is fresh. Everything is monotonic ([elapsedMs] is expected to be
 * `SystemClock.elapsedRealtime`), so a wall-clock change cannot make a dead service look alive.
 *
 * No accessibility event content is read, stored or transmitted: this is a timestamp only.
 */
class EnforcementHeartbeat(
    private val elapsedMs: () -> Long,
    private val freshnessMs: Long = DEFAULT_FRESHNESS_MS,
) {
    data class Snapshot(
        val fresh: Boolean,
        val connected: Boolean,
        val instanceToken: Long,
        val lastBeatMs: Long,
    )

    private var connected = false
    private var token = 0L
    private var lastBeatMs = Long.MIN_VALUE

    /** A new service instance: fresh token, fresh beat. */
    @Synchronized fun onConnected(now: Long = elapsedMs()): Long {
        token += 1
        connected = true
        lastBeatMs = now
        return token
    }

    /** Periodic in-memory beat; ignored unless a service instance is connected. */
    @Synchronized fun beat(now: Long = elapsedMs()) {
        if (connected) lastBeatMs = now
    }

    @Synchronized fun onDisconnected() {
        connected = false
        lastBeatMs = Long.MIN_VALUE
    }

    /**
     * Fresh only while connected and beating within [freshnessMs]. A clock that moves backwards
     * fails closed instead of extending the window.
     */
    @Synchronized fun isFresh(now: Long = elapsedMs(),
                              windowMs: Long = freshnessMs): Boolean {
        if (!connected || lastBeatMs == Long.MIN_VALUE) return false
        val age = now - lastBeatMs
        return age in 0..windowMs
    }

    @Synchronized fun snapshot(now: Long = elapsedMs()): Snapshot =
        Snapshot(isFresh(now), connected, token, lastBeatMs)

    companion object {
        /** Service beats every 30 s; three missed beats void enforcement. */
        const val DEFAULT_FRESHNESS_MS = 90_000L
    }
}
