package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.StreakRecoveryPolicy
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Durable recovery binding must be a side effect off the UI thread, never part of composition,
 * and a preview/test process without a live enforcement probe must not create a freeze.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryBindingIoTest {
    @Test fun bindingRunsOffTheCallingThread() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val day = LocalDate.now()
        val challenge = StreakRecoveryPolicy.Challenge(
            missedDay = day.minusDays(1),
            recoveryDay = day,
            baselineMinutes = 30,
            requiredMinutes = 40,
            requiredFocusedMs = 40 * 60_000L,
        )
        val caller = Thread.currentThread().name
        val result = FocusHistoryBridge.bindRecoveryProtectionOnIo(context, challenge)

        assertNotEquals("the bind must hop off the caller thread", caller, result.threadName)
        assertFalse("the bind must not run on a main thread", result.threadName.contains("main", ignoreCase = true))
    }

    @Test fun bindingFailsClosedWithoutALiveEnforcementProbe() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val day = LocalDate.now()
        val challenge = StreakRecoveryPolicy.Challenge(
            missedDay = day.minusDays(1),
            recoveryDay = day,
            baselineMinutes = 30,
            requiredMinutes = 40,
            requiredFocusedMs = 40 * 60_000L,
        )
        val result = FocusHistoryBridge.bindRecoveryProtectionOnIo(context, challenge)

        assertNull("no probe means no challenge", result.profile)
        assertFalse(
            "a preview/test must never create durable protection state",
            File(context.filesDir, "focus-history/recovery-challenge.v1.json").exists(),
        )
    }
}
