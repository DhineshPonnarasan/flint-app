package com.flint.peakfocus.core.history

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1: an active recovery challenge must not stay eligible across Android process death.
 *
 * The in-memory watcher ([RecoveryEnforcementWatchPolicy] state and the app-shell
 * `recoveryWatchStarted` flag) is recreated on every process start, so a Path A drop+reconnect
 * that happens entirely while Flint is stopped is invisible to it. Continuity can only be
 * proven by the durable watch generation stamped into [RecoveryChallengeGuardStore]; a freeze
 * bound by a previous generation must fail closed.
 *
 * A fresh [RecoveryChallengeGuardStore] over the same directory models a new process (new
 * in-memory `watchGeneration`) reading the persisted sidecar — exactly the restart path.
 */
class RecoveryContinuityProcessDeathTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val missed = today.minusDays(1)
    private val packages = listOf("com.instagram.android")
    private val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)

    private fun at(day: LocalDate, hour: Int = 10): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun guard(dir: java.io.File) = RecoveryChallengeGuardStore(dir)

    private fun bindFresh(dir: java.io.File): RecoveryChallengeGuardStore {
        val g = guard(dir)
        assertNotNull(
            g.ensureFrozen(packages, false, today, missed, at(today)),
        )
        // This process' watch starts AFTER the freeze was bound; same generation, so no latch.
        g.noteWatchStarted()
        assertTrue("uninterrupted same-process challenge must stay intact", g.isEnforcementIntact())
        return g
    }

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun openStore(clock: MutableClock): FocusHistoryStore =
        FocusHistoryStore(createTempDirectory("flint-cont").toFile(), clock, zone = { zone })

    private suspend fun prepareStore(clock: MutableClock): FocusHistoryStore {
        val store = openStore(clock)
        clock.nowMs = at(today, 18) - 40L * 24 * 60 * 60 * 1000
        store.load()
        clock.nowMs = at(today, 18)
        return store
    }

    private suspend fun seed(store: FocusHistoryStore, id: String, day: LocalDate, minutes: Int, grant: String? = null) {
        val start = at(day, 9)
        val end = start + minutes * 60_000L
        assertTrue(store.append(HistoryEvent(
            id = "start-$id", type = "started", session = id, at = start, tz = zone.id,
            plannedEnd = end, tier = "easy", source = "manual", name = "Focus", grant = grant,
        )))
        assertTrue(store.append(HistoryEvent(id = "end-$id", type = "completed", session = id, at = end, tz = zone.id)))
    }

    private fun commit(store: FocusHistoryStore, guard: RecoveryChallengeGuardStore, callerActive: Boolean) =
        runBlocking {
            // Mirrors FocusHistoryBridge.tryCommitStreakRecovery: caller flag is ANDed with
            // the durable gate. A caller-supplied true can never bypass isEnforcementIntact().
            StreakRecoveryCommitter.tryCommit(
                store,
                zone = zone,
                recoveryId = "cont-${store.directory.hashCode()}",
                protection = guard.current(),
                protectionActive = callerActive && guard.isEnforcementIntact(),
            )
        }

    // --- Required adversarial scenarios ---

    @Test fun processRestartWhilePathAHealthyFailsClosed() {
        val dir = createTempDirectory("flint-cont-healthy").toFile()
        val before = bindFresh(dir) // P1: freeze bound, watch healthy, intact
        // P2 restart: Path A is healthy again, but the watch generation changed.
        val after = guard(dir)
        after.noteWatchStarted()
        assertFalse("restart must not be treated as proof of continuity", after.isEnforcementIntact())
        // Previous process state did not leak into the new generation.
        assertTrue(before.isEnforcementIntact())
        // Durable: a third read still sees the latch.
        assertFalse(guard(dir).isEnforcementIntact())
    }

    @Test fun pathADropReconnectEntirelyWhileStoppedFailsClosed() {
        val dir = createTempDirectory("flint-cont-gap").toFile()
        bindFresh(dir)
        // While Flint is dead, Path A drops and reconnects; no watcher observes it.
        // On restart the fresh watcher samples Path A healthy, but continuity is unverifiable.
        val restarted = guard(dir)
        restarted.noteWatchStarted()
        assertFalse(restarted.isEnforcementIntact())
        // The in-memory policy re-arms from a healthy sample but never observed the drop.
        var policy = RecoveryEnforcementWatchPolicy.State()
        policy = RecoveryEnforcementWatchPolicy.next(policy, freezeActive = true, pathAHealthy = true).state
        assertTrue(policy.observedHealthyWhileFrozen)
        assertFalse("fresh healthy sample must not clear the durable break", restarted.isEnforcementIntact())
    }

    @Test fun accessibilityRevokedWhileStoppedThenRestoredFailsClosed() {
        val dir = createTempDirectory("flint-cont-revoke").toFile()
        bindFresh(dir)
        // Permission revoked and restored while stopped; restart sees it enabled again.
        val restarted = guard(dir)
        restarted.noteWatchStarted()
        assertFalse(restarted.isEnforcementIntact())
        // Re-binding the same day after reconnect must not erase the durable latch.
        assertNotNull(
            restarted.ensureFrozen(packages, false, today, missed, at(today) + 1_000L),
        )
        assertFalse(restarted.isEnforcementIntact())
        assertFalse(guard(dir).isEnforcementIntact())
    }

    @Test fun restartWithAlreadyPersistedLatchStaysBroken() {
        val dir = createTempDirectory("flint-cont-latched").toFile()
        val first = bindFresh(dir)
        first.markEnforcementBroken() // observed mid-challenge drop in P1
        assertFalse(first.isEnforcementIntact())
        val restarted = guard(dir)
        restarted.noteWatchStarted()
        assertFalse(restarted.isEnforcementIntact())
        val again = guard(dir)
        assertFalse("no watcher or reconnect may clear a persisted latch", again.isEnforcementIntact())
    }

    @Test fun interruptedChallengeCannotBecomeEligibleJustBecausePathAReconnects() = runBlocking {
        val clock = MutableClock(0, zone)
        val store = prepareStore(clock)
        seed(store, "a", today.minusDays(2), 30)
        seed(store, "b", today, 40, grant = fingerprint)
        val dir = store.directory
        val before = bindFresh(dir)
        assertEquals(0, store.snapshots.value.events.count { it.type == "streak_recovered" })
        // Restart: generation changed; Path A healthy; caller insists protectionActive=true.
        val restarted = guard(dir)
        restarted.noteWatchStarted()
        val result = commit(store, restarted, callerActive = true)
        assertTrue("caller true must not bypass durable break, got $result", result is StreakRecoveryCommitter.Result.Rejected)
        assertEquals("protection_inactive", (result as StreakRecoveryCommitter.Result.Rejected).reason)
        assertEquals(0, store.snapshots.value.events.count { it.type == "streak_recovered" })
        assertTrue(before.isEnforcementIntact()) // prior generation state not mutated by caller
    }

    /**
     * Defense-in-depth: the durable gate itself must reject a restored old-generation freeze
     * even when [RecoveryChallengeGuardStore.noteWatchStarted] never ran on this process. This
     * models a transient sidecar read failure at startup (current() returned null, so the hook
     * bailed without latching) followed by a later successful read, or any caller that reaches
     * commit without the app-shell hook. The gate must not depend on a one-shot startup call.
     */
    @Test fun restoredFreezeFailsClosedWithoutWatchStart() {
        val dir = createTempDirectory("flint-cont-no-hook").toFile()
        val first = guard(dir)
        assertNotNull(first.ensureFrozen(packages, false, today, missed, at(today)))
        assertTrue(first.isEnforcementIntact())
        // New process generation; deliberately do NOT call noteWatchStarted().
        val restarted = guard(dir)
        assertFalse(
            "generation continuity must be checked by the durable gate, not only the startup hook",
            restarted.isEnforcementIntact(),
        )
        assertFalse(guard(dir).isEnforcementIntact())
    }

    /** Same gap on the commit path: caller-supplied true cannot bypass the gate. */
    @Test fun commitRejectedWhenWatchStartWasSkipped() = runBlocking {
        val clock = MutableClock(0, zone)
        val store = prepareStore(clock)
        seed(store, "a", today.minusDays(2), 30)
        seed(store, "b", today, 40, grant = fingerprint)
        val first = guard(store.directory)
        assertNotNull(first.ensureFrozen(packages, false, today, missed, at(today)))
        // New generation reaches commit directly, with no noteWatchStarted() call.
        val restarted = guard(store.directory)
        assertFalse(restarted.isEnforcementIntact())
        val result = commit(store, restarted, callerActive = true)
        assertTrue("expected protection_inactive rejection, got $result", result is StreakRecoveryCommitter.Result.Rejected)
        assertEquals("protection_inactive", (result as StreakRecoveryCommitter.Result.Rejected).reason)
        assertEquals(0, store.snapshots.value.events.count { it.type == "streak_recovered" })
    }

    @Test fun uninterruptedChallengeStillCommitsNormally() = runBlocking {
        val clock = MutableClock(0, zone)
        val store = prepareStore(clock)
        seed(store, "a", today.minusDays(2), 30)
        seed(store, "b", today, 40, grant = fingerprint)
        val guard = guard(store.directory)
        assertNotNull(guard.ensureFrozen(packages, false, today, missed, at(today)))
        guard.noteWatchStarted()
        assertTrue(guard.isEnforcementIntact())
        val result = commit(store, guard, callerActive = true)
        assertTrue("uninterrupted challenge must still commit, got $result", result is StreakRecoveryCommitter.Result.Success)
        assertEquals(1, store.snapshots.value.events.count { it.type == "streak_recovered" })
    }
}
