package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A signing outage must never destroy an authenticated best-streak checkpoint, and compaction
 * must never prune history it cannot checkpoint.
 */
class CheckpointSigningFailureTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 28)
    private val dayMs = 24L * 60 * 60 * 1000
    private val key = ByteArray(32) { 7 }

    private class MutableClock(var nowMs: Long, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    /** Signing can be switched off without touching verification. */
    private class ToggleAuthenticator(private val delegate: CheckpointAuthenticator) : CheckpointAuthenticator {
        @Volatile var failSign = false
        override fun sign(payload: String): String? = if (failSign) null else delegate.sign(payload)
        override fun verify(payload: String, mac: String): Boolean = delegate.verify(payload, mac)
    }

    /** Signs a MAC that its own verifier rejects: models an unverifiable write. */
    private class LyingSigner(private val delegate: CheckpointAuthenticator) : CheckpointAuthenticator {
        override fun sign(payload: String): String = "0".repeat(64)
        override fun verify(payload: String, mac: String): Boolean = delegate.verify(payload, mac)
    }

    private object AlwaysFailing : CheckpointAuthenticator {
        override fun sign(payload: String): String? = null
        override fun verify(payload: String, mac: String): Boolean = false
    }

    private fun at(day: LocalDate, hour: Int = 9): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun clock() = MutableClock(at(today, 18), zone)

    private fun store(dir: File, authenticator: CheckpointAuthenticator, retentionDays: Long = 400) =
        FocusHistoryStore(
            directory = dir,
            clock = clock(),
            retentionMs = retentionDays * dayMs,
            authenticator = authenticator,
            zone = { zone },
        )

    private fun completedRows(id: String, day: LocalDate): List<String> {
        val start = at(day)
        val end = start + 40 * 60_000L
        return listOf(
            "{\"v\":1,\"id\":\"start-$id\",\"type\":\"started\",\"session\":\"$id\",\"at\":$start," +
                "\"tz\":\"${zone.id}\",\"plannedEnd\":$end,\"tier\":\"easy\"," +
                "\"source\":\"manual\",\"name\":\"Focus\"}",
            "{\"v\":1,\"id\":\"end-$id\",\"type\":\"completed\",\"session\":\"$id\",\"at\":$end," +
                "\"tz\":\"${zone.id}\"}",
        )
    }

    private fun writeConsecutive(dir: File, days: Int) {
        dir.mkdirs()
        val rows = (0 until days).flatMap { offset ->
            completedRows("d$offset", today.minusDays((days - 1 - offset).toLong()))
        }
        File(dir, "events.v1.jsonl").writeText(rows.joinToString("\n") + "\n")
        File(dir, "metadata.v1.json").writeText(
            "{\"v\":1,\"createdAt\":${at(today.minusDays((days - 1).toLong()))}}",
        )
    }

    private fun best(store: FocusHistoryStore): Int =
        FocusHistoryAggregator.summarize(store.snapshots.value, at(today, 18), zone, emptySet())
            .bestRecordedStreak

    @Test fun signingOutagePreservesTheAuthenticatedCheckpointAndHistory() = runBlocking {
        val dir = createTempDirectory("flint-sign-outage").toFile()
        writeConsecutive(dir, 420)
        val auth = ToggleAuthenticator(HmacCheckpointAuthenticator(key))
        val store = store(dir, auth)
        store.load()
        assertEquals(420, best(store))
        assertFalse(store.snapshots.value.bestCheckpointUnverified)

        val metadataBefore = File(dir, "metadata.v1.json").readBytes()
        val logBefore = File(dir, "events.v1.jsonl").readBytes()

        auth.failSign = true
        assertFalse("compaction must fail safely", store.compact())
        assertArrayEquals("the checkpoint must be byte-identical", metadataBefore, File(dir, "metadata.v1.json").readBytes())
        assertArrayEquals("history must be byte-identical", logBefore, File(dir, "events.v1.jsonl").readBytes())
        store.load()
        assertEquals("the old checkpoint still proves 420", 420, best(store))
        assertFalse(store.snapshots.value.bestCheckpointUnverified)

        auth.failSign = false
        assertTrue("compaction resumes once signing recovers", store.compact())
        store.load()
        assertEquals(420, best(store))
        assertFalse(store.snapshots.value.bestCheckpointUnverified)

        // A repeated retry must not inflate the floor.
        assertTrue(store.compact())
        store.load()
        assertEquals(420, best(store))
        assertTrue(store.snapshots.value.bestCheckpoint.bestRunDays <= 420)
    }

    @Test fun totalAuthenticationFailureRefusesToPrune() = runBlocking {
        val dir = createTempDirectory("flint-sign-none").toFile()
        writeConsecutive(dir, 70)
        val store = store(dir, FallbackCheckpointAuthenticator(AlwaysFailing, AlwaysFailing), retentionDays = 10)
        store.load()

        // No pruning happened: all 70 days are still on disk and every event is present.
        assertEquals(70, store.snapshots.value.events.count { it.type == "started" })
        assertEquals(140, store.snapshots.value.events.size)
        assertEquals("only retained history may be claimed", 70, best(store))
        assertEquals(0, store.snapshots.value.bestCheckpoint.bestRunDays)
        assertFalse(store.snapshots.value.bestCheckpointUnverified)
    }

    @Test fun keystoreFailureFallsBackToTheLocalKey() = runBlocking {
        val fallback = HmacCheckpointAuthenticator(key)
        val auth = FallbackCheckpointAuthenticator(AlwaysFailing, fallback)

        val mac = auth.sign("payload")
        assertNotNull("the fallback must sign when the primary cannot", mac)
        assertTrue(auth.verify("payload", mac!!))
        assertNull(AlwaysFailing.sign("payload"))

        val dir = createTempDirectory("flint-sign-fallback").toFile()
        writeConsecutive(dir, 70)
        val store = store(dir, auth, retentionDays = 10)
        store.load()
        assertEquals(70, best(store))
        assertFalse(store.snapshots.value.bestCheckpointUnverified)
    }

    @Test fun unverifiableWriteIsRolledBackByteForByte() = runBlocking {
        val dir = createTempDirectory("flint-sign-rollback").toFile()
        writeConsecutive(dir, 420)
        val real = HmacCheckpointAuthenticator(key)
        val first = store(dir, real)
        first.load()
        assertEquals(420, best(first))
        val validMetadata = File(dir, "metadata.v1.json").readBytes()
        val validLog = File(dir, "events.v1.jsonl").readBytes()

        // The signer produces a MAC its verifier rejects: the read-back must fail validation.
        val lying = store(dir, LyingSigner(real))
        lying.load()
        assertTrue(lying.compact())

        assertArrayEquals(
            "the previous authenticated checkpoint must be restored verbatim",
            validMetadata, File(dir, "metadata.v1.json").readBytes(),
        )
        assertArrayEquals("history must not be pruned on a failed checkpoint", validLog, File(dir, "events.v1.jsonl").readBytes())
        val reopened = store(dir, real)
        reopened.load()
        assertEquals(420, best(reopened))
        assertFalse(reopened.snapshots.value.bestCheckpointUnverified)
    }

    @Test fun emptyHistoryWithoutACheckpointDoesNotNeedSigning() = runBlocking {
        val dir = createTempDirectory("flint-sign-empty").toFile()
        val store = store(dir, AlwaysFailing)
        store.load()
        assertTrue(store.compact())
        assertEquals(0, store.snapshots.value.events.size)
        assertEquals(0, store.snapshots.value.bestCheckpoint.bestRunDays)
        assertEquals(0, best(store))
        assertFalse(store.snapshots.value.bestCheckpointUnverified)
    }

    @Test fun degradedHistoryIsNormalizedButNeverPruned() = runBlocking {
        val dir = createTempDirectory("flint-sign-degraded").toFile()
        writeConsecutive(dir, 120)
        File(dir, "events.v1.jsonl").appendText("{\"id\":\"truncated\",\"type\":\"streak_rec\n")
        val store = store(dir, HmacCheckpointAuthenticator(key), retentionDays = 10)
        store.load()

        assertTrue("the corrupt log must be flagged", store.snapshots.value.degraded)
        assertEquals("no partial pruning while degraded", 120, store.snapshots.value.events.count { it.type == "started" })
        assertEquals(120, best(store))
        assertTrue(File(dir, "quarantine").listFiles()?.isNotEmpty() == true)
    }

    @Test fun cooldownBoundaryIsExactIncludingTheLedgerFloor() {
        val last = at(today, 18)
        val cooldown = StreakRecoveryPolicy.COOLDOWN_MS
        assertFalse(StreakRecoveryPolicy.tokenAvailable(emptyList(), last + cooldown - 1, last))
        assertTrue(StreakRecoveryPolicy.tokenAvailable(emptyList(), last + cooldown, last))
        assertTrue(StreakRecoveryPolicy.tokenAvailable(emptyList(), last + cooldown + 1, last))
        assertEquals(last + cooldown, StreakRecoveryPolicy.nextTokenAvailableAt(emptyList(), last))
    }

    @Test fun clearFailingBeforeTheIntentChangesNothing() = runBlocking {
        val dir = createTempDirectory("flint-sign-clear").toFile()
        val now = at(today, 18)
        val clock = MutableClock(now - 40 * dayMs, zone)
        val packages = listOf("com.instagram.android")
        val fingerprint = StreakRecoveryProtection.fingerprint(packages, false)
        val auth = HmacCheckpointAuthenticator(key)
        val store = FocusHistoryStore(
            directory = dir,
            clock = clock,
            authenticator = auth,
            zone = { zone },
        )
        store.load()
        clock.nowMs = now
        val priorStart = at(today.minusDays(2))
        val priorEnd = priorStart + 30 * 60_000L
        assertTrue(store.append(HistoryEvent(
            id = "start-a", type = "started", session = "a", at = priorStart, tz = zone.id,
            plannedEnd = priorEnd, tier = "easy", source = "manual", name = "Focus", grant = fingerprint,
        )))
        assertTrue(store.append(HistoryEvent(
            id = "end-a", type = "completed", session = "a", at = priorEnd, tz = zone.id,
        )))
        val todayStart = at(today)
        val todayEnd = todayStart + 40 * 60_000L
        assertTrue(store.append(HistoryEvent(
            id = "start-b", type = "started", session = "b", at = todayStart, tz = zone.id,
            plannedEnd = todayEnd, tier = "easy", source = "manual", name = "Focus", grant = fingerprint,
        )))
        assertTrue(store.append(HistoryEvent(
            id = "end-b", type = "completed", session = "b", at = todayEnd, tz = zone.id,
        )))
        val protection = StreakRecoveryProtection.bind(
            packages, false, today, today.minusDays(1), now,
        )!!
        assertTrue(
            StreakRecoveryCommitter.tryCommit(
                store, zone = zone, recoveryId = "rec", protection = protection,
            ) is StreakRecoveryCommitter.Result.Success,
        )

        val metadataBefore = File(dir, "metadata.v1.json").readBytes()
        val logBefore = File(dir, "events.v1.jsonl").readBytes()
        val ledgerBefore = File(dir, "recovery-ledger.v1.json").readBytes()

        val failing = FocusHistoryStore(
            directory = dir,
            clock = clock,
            clearPhase = { phase ->
                if (phase == HistoryClearPhase.BEFORE_INTENT) throw IllegalStateException("crash")
            },
            authenticator = auth,
            zone = { zone },
        )
        assertFalse(failing.clear(emptySet()))
        assertArrayEquals(metadataBefore, File(dir, "metadata.v1.json").readBytes())
        assertArrayEquals(logBefore, File(dir, "events.v1.jsonl").readBytes())
        assertArrayEquals(ledgerBefore, File(dir, "recovery-ledger.v1.json").readBytes())

        val floor = failing.snapshots.value.recoveryFloorAt
        assertNotNull(floor)
        assertFalse(StreakRecoveryPolicy.tokenAvailable(failing.snapshots.value.events, now + dayMs, floor))
    }
}
