package com.flint.peakfocus.core.history

import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live Path A (accessibility) continuity for a bound recovery challenge.
 *
 * The app shell now drives exactly this loop, so these tests exercise the real edge sequences:
 * a healthy sample followed by a drop must latch permanently, while a cold-start unhealthy
 * sample must not (there is nothing to compare it to yet).
 */
class RecoveryEnforcementWatchTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val packages = listOf("com.instagram.android")

    private fun bind(guard: RecoveryChallengeGuardStore) {
        assertNotNull(guard.ensureFrozen(packages, false, today, today.minusDays(1), 0L))
    }

    private fun durableBroken(dir: File): Boolean {
        // The latch lives in its own file so clearing a stale profile cannot erase it.
        val file = File(dir, "recovery-enforcement-latch.v1.json")
        if (!file.isFile) return false
        return FlatJson.decode(file.readText())["challengeId"] != null
    }

    @Test fun healthyThenDropLatchesPermanently() = runBlocking {
        val dir = createTempDirectory("flint-watch-edge").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        bind(guard)
        assertTrue(guard.isEnforcementIntact())

        val samples = listOf(true, true, false).iterator()
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { samples.next() },
            intervalMs = 0,
            iterations = 3,
        )

        assertFalse("an observed drop must void the day", guard.isEnforcementIntact())
        assertTrue("the latch must be durable, not in-memory only", durableBroken(dir))
    }

    @Test fun dropBeforeAnyHealthySampleDoesNotLatch() = runBlocking {
        val dir = createTempDirectory("flint-watch-cold").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        bind(guard)

        val samples = listOf(false, false).iterator()
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { samples.next() },
            intervalMs = 0,
            iterations = 2,
        )

        assertTrue("a cold-start down sample is not proof of a mid-challenge gap", guard.isEnforcementIntact())
        assertFalse(durableBroken(dir))
    }

    @Test fun healthySamplesKeepTheChallengeIntact() = runBlocking {
        val dir = createTempDirectory("flint-watch-ok").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        bind(guard)

        val samples = listOf(true, true, true).iterator()
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { samples.next() },
            intervalMs = 0,
            iterations = 3,
        )

        assertTrue(guard.isEnforcementIntact())
        assertFalse(durableBroken(dir))
    }

    @Test fun reEnabledHealthCannotClearTheLatch() = runBlocking {
        val dir = createTempDirectory("flint-watch-reenable").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        bind(guard)

        val samples = listOf(true, false, true, true).iterator()
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { samples.next() },
            intervalMs = 0,
            iterations = 4,
        )

        assertFalse("re-enabling accessibility is not proof the gap was enforced", guard.isEnforcementIntact())
        assertTrue(durableBroken(dir))
    }

    @Test fun withoutAFreezeNothingIsSampledOrLatched() = runBlocking {
        val dir = createTempDirectory("flint-watch-idle").toFile()
        val guard = RecoveryChallengeGuardStore(dir)

        var sampled = false
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { false },
            pathAHealthy = { sampled = true; false },
            intervalMs = 0,
            iterations = 3,
        )

        assertNull(guard.current())
        assertFalse("an idle watcher must not sample or write", sampled)
        assertFalse(durableBroken(dir))
    }

    @Test fun aBindVerifiedChallengeLatchesItsFirstDrop() = runBlocking {
        val dir = createTempDirectory("flint-watch-seeded").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        bind(guard)
        // The bind verified enforcement (healthyNow), so the very first unhealthy sample must
        // latch instead of waiting for a later healthy sample.
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { false },
            intervalMs = 0,
            iterations = 1,
            initiallyHealthy = true,
        )
        assertFalse("a drop right after binding must void the day", guard.isEnforcementIntact())
        assertTrue(durableBroken(dir))
    }

    @Test fun latchWriteFailureKeepsMonitoringAndRetries() = runBlocking {
        val dir = createTempDirectory("flint-watch-writefail").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        bind(guard)
        val latchPath = File(dir, "recovery-enforcement-latch.v1.json")
        assertTrue("a directory at the latch path blocks the atomic write", latchPath.mkdirs())

        // The watcher itself runs while persistence is impossible: it must not die, and the
        // in-memory latch must still fail the challenge closed.
        val failing = listOf(false, false).iterator()
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { failing.next() },
            intervalMs = 0,
            iterations = 2,
            initiallyHealthy = true,
        )
        assertFalse(guard.isEnforcementIntact())
        assertFalse(durableBroken(dir))

        // Storage recovers; the next sample persists the already-broken challenge.
        assertTrue(latchPath.delete())
        val recovered = listOf(false).iterator()
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { recovered.next() },
            intervalMs = 0,
            iterations = 1,
            initiallyHealthy = true,
        )
        assertTrue("the latch must eventually persist", durableBroken(dir))
        assertFalse(guard.isEnforcementIntact())
    }

    @Test fun latchedBreakSurvivesAReopenedStore() = runBlocking {
        val dir = createTempDirectory("flint-watch-reopen").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        bind(guard)
        val samples = listOf(true, false).iterator()
        RecoveryEnforcementWatch.watch(
            guard = guard,
            freezeActive = { true },
            pathAHealthy = { samples.next() },
            intervalMs = 0,
            iterations = 2,
        )

        // A new instance models the next process reading the same sidecar.
        val reopened = RecoveryChallengeGuardStore(dir)
        assertFalse(reopened.isEnforcementIntact())
        assertTrue(durableBroken(dir))
    }

    /**
     * Production wiring: the watcher starts before any challenge exists, and a bind later feeds
     * `binding = { guard.activeBinding() }`. A drop that lands before the watcher's next sample
     * must still latch, because the bind itself verified enforcement.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aChallengeBoundAfterWatcherStartLatchesItsFirstDrop() = runTest {
        val dir = createTempDirectory("flint-watch-late-bind").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        var healthy = true
        val job = launch {
            RecoveryEnforcementWatch.watch(
                guard = guard,
                freezeActive = { guard.current() != null },
                pathAHealthy = { healthy },
                intervalMs = 1_000,
                iterations = 3,
                binding = { guard.activeBinding() },
            )
        }
        runCurrent() // sample 1: no freeze, exactly like app startup
        assertNull(guard.current())

        assertNotNull(guard.ensureFrozen(packages, false, today, today.minusDays(1), 0L))
        healthy = false // the service drops before the watcher's next sample
        advanceTimeBy(1_000)
        runCurrent() // sample 2 must seed from the bind and latch the drop

        assertFalse(
            "a challenge bound after watcher start must latch its first drop",
            guard.isEnforcementIntact(),
        )
        assertTrue(durableBroken(dir))
        advanceUntilIdle()
        job.join()
    }

    /** A new challenge seeds its own state instead of inheriting the previous day's break. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aNewChallengeSeedsItsOwnStateInsteadOfInheritingTheLatch() = runTest {
        val dir = createTempDirectory("flint-watch-reseed").toFile()
        val guard = RecoveryChallengeGuardStore(dir)
        var healthy = true
        val job = launch {
            RecoveryEnforcementWatch.watch(
                guard = guard,
                freezeActive = { guard.current() != null },
                pathAHealthy = { healthy },
                intervalMs = 1_000,
                iterations = 6,
                binding = { guard.activeBinding() },
            )
        }
        runCurrent()
        assertNotNull(guard.ensureFrozen(packages, false, today, today.minusDays(1), 0L))
        healthy = false
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(guard.isEnforcementIntact())

        // A genuinely new day binds a fresh challenge while enforcement is healthy.
        healthy = true
        val next = today.plusDays(1)
        assertNotNull(guard.ensureFrozen(packages, false, next, today, 0L))
        assertTrue("a new day must not inherit the previous day's latch", guard.isEnforcementIntact())
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(guard.isEnforcementIntact())

        // Its own drop must latch too.
        healthy = false
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(guard.isEnforcementIntact())
        advanceUntilIdle()
        job.join()
    }

    /** A freeze bound by a previous process is never seeded healthy, even while Path A is up. */
    @Test fun aPreviousProcessFreezeIsNeverSeededAsHealthy() = runBlocking {
        val dir = createTempDirectory("flint-watch-stale").toFile()
        val first = RecoveryChallengeGuardStore(dir)
        assertNotNull(first.ensureFrozen(packages, false, today, today.minusDays(1), 0L))

        val second = RecoveryChallengeGuardStore(dir)
        second.noteWatchStarted() // a previous generation cannot prove continuity
        RecoveryEnforcementWatch.watch(
            guard = second,
            freezeActive = { second.current() != null },
            pathAHealthy = { true },
            intervalMs = 0,
            iterations = 2,
            binding = { second.activeBinding() },
        )
        assertFalse("a stale freeze stays broken", second.isEnforcementIntact())
        assertTrue(durableBroken(dir))
    }
}
