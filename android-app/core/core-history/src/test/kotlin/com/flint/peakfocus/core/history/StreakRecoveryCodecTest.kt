package com.flint.peakfocus.core.history

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakRecoveryCodecTest {
    private val zone = ZoneId.of("America/New_York")
    private val event = HistoryEvent(
        id = "rec-1",
        type = HistoryEventType.STREAK_RECOVERED.wireName,
        session = "rec-1",
        at = 1_795_000_000_000L,
        tz = zone.id,
        plannedEnd = 40 * 60_000L,
        breakEnd = LocalDate.of(2026, 10, 27).atStartOfDay(zone).toInstant().toEpochMilli(),
        recoveryEnd = 30 * 60_000L,
        sessions = listOf("session-a"),
        source = "fingerprint-v2",
        grant = "rec-1",
        reason = StreakRecoveryPolicy.REASON,
        originRoute = StreakRecoveryPolicy.ORIGIN,
    )

    @Test fun recoverySchemaRoundTripsEveryRequiredField() {
        val decoded = HistoryCodec.decode(HistoryCodec.encode(event), forAppend = true)
        assertTrue(StreakRecoveryPolicy.isWellFormedRecovery(decoded))
        assertEquals(event.plannedEnd, decoded.plannedEnd)
        assertEquals(event.recoveryEnd, decoded.recoveryEnd)
        assertEquals(event.sessions, decoded.sessions)
        assertEquals(event.source, decoded.source)
        assertEquals(event.breakEnd, decoded.breakEnd)
    }

    @Test fun recoveryWithoutRequiredPolicyFieldsIsRejectedOnRead() {
        val encoded = HistoryCodec.encode(event.copy(source = null))
        assertThrows(IllegalArgumentException::class.java) {
            HistoryCodec.decode(encoded, forAppend = true)
        }
        val noSessions = HistoryCodec.encode(event.copy(sessions = null))
        assertThrows(IllegalArgumentException::class.java) {
            HistoryCodec.decode(noSessions, forAppend = true)
        }
        val noPlanned = HistoryCodec.encode(event.copy(plannedEnd = null))
        assertThrows(IllegalArgumentException::class.java) {
            HistoryCodec.decode(noPlanned, forAppend = true)
        }
    }
}
