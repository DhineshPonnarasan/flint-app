package com.flint.peakfocus.core.history

import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryChallengeGuardStoreTest {
    private val day = LocalDate.of(2026, 11, 20)
    private val missed = LocalDate.of(2026, 11, 19)

    @Test fun midChallengeBreakLatchesAndSurvivesReread() {
        val dir = createTempDirectory("flint-guard").toFile()
        val store = RecoveryChallengeGuardStore(dir)
        val bound = store.ensureFrozen(
            packageNames = listOf("com.android.chrome"),
            allowListMode = false,
            recoveryDay = day,
            missedDay = missed,
            frozenAt = 1_795_000_000_000L,
        )
        assertNotNull(bound)
        assertTrue(store.isEnforcementIntact())
        store.markEnforcementBroken()
        assertFalse(store.isEnforcementIntact())
        assertNotNull(store.current())
        val reopened = RecoveryChallengeGuardStore(dir)
        assertNotNull(reopened.current())
        assertFalse(reopened.isEnforcementIntact())
    }

    @Test fun sameDayEnsureFrozenDoesNotClearEnforcementBroken() {
        val dir = createTempDirectory("flint-guard-reconnect").toFile()
        val store = RecoveryChallengeGuardStore(dir)
        store.ensureFrozen(
            packageNames = listOf("com.android.chrome"),
            allowListMode = false,
            recoveryDay = day,
            missedDay = missed,
            frozenAt = 1_795_000_000_000L,
        )
        store.markEnforcementBroken()
        assertFalse(store.isEnforcementIntact())
        // Reconnect / re-bind same day must not erase the durable latch.
        val again = store.ensureFrozen(
            packageNames = listOf("com.android.chrome"),
            allowListMode = false,
            recoveryDay = day,
            missedDay = missed,
            frozenAt = 1_795_000_000_200L,
        )
        assertNotNull(again)
        assertFalse(store.isEnforcementIntact())
        val reopened = RecoveryChallengeGuardStore(dir)
        assertFalse(reopened.isEnforcementIntact())
    }

    @Test fun duplicateMarkBrokenIsIdempotent() {
        val dir = createTempDirectory("flint-guard-idem").toFile()
        val store = RecoveryChallengeGuardStore(dir)
        store.ensureFrozen(
            packageNames = listOf("com.android.chrome"),
            allowListMode = false,
            recoveryDay = day,
            missedDay = missed,
            frozenAt = 1_795_000_000_000L,
        )
        store.markEnforcementBroken()
        store.markEnforcementBroken()
        assertFalse(store.isEnforcementIntact())
        assertNotNull(store.current())
    }

    @Test fun firstFreezeWinsAgainstLaterPackageSubstitution() {
        val dir = createTempDirectory("flint-guard-sub").toFile()
        val store = RecoveryChallengeGuardStore(dir)
        val chrome = store.ensureFrozen(
            packageNames = listOf("com.android.chrome"),
            allowListMode = false,
            recoveryDay = day,
            missedDay = missed,
            frozenAt = 1_795_000_000_000L,
        )!!
        val substituted = store.ensureFrozen(
            packageNames = listOf("com.google.android.calendar"),
            allowListMode = false,
            recoveryDay = day,
            missedDay = missed,
            frozenAt = 1_795_000_000_100L,
        )!!
        assertEquals(chrome.fingerprint, substituted.fingerprint)
        assertEquals(listOf("com.android.chrome"), substituted.packageNames)
        assertEquals(
            StreakRecoveryProtection.fingerprint(listOf("com.android.chrome"), false),
            store.current()!!.fingerprint,
        )
        val reopened = RecoveryChallengeGuardStore(dir)
        assertEquals(listOf("com.android.chrome"), reopened.current()!!.packageNames)
    }
}
