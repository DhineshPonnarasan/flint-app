package com.flint.peakfocus.core.history

import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
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
}
