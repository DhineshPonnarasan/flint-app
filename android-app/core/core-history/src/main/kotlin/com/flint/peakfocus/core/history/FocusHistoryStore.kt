package com.flint.peakfocus.core.history

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class HistoryClearPhase { BEFORE_INTENT, INTENT_COMMITTED, LOG_REPLACED, METADATA_COMMITTED }

/** App-private, single-process log. All instances share the lock; no enforcement caller waits here. */
class FocusHistoryStore(
    val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val clearPhase: (HistoryClearPhase) -> Unit = {},
    /** Retention window for ordinary compaction; injectable so tests stay fast. */
    private val retentionMs: Long = RETENTION_MS,
    /** Checkpoint integrity; production injects a Keystore-backed HMAC. */
    private val authenticator: CheckpointAuthenticator = HmacCheckpointAuthenticator.loadOrCreate(directory),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val log = File(directory, "events.v1.jsonl")
    private val metadata = File(directory, "metadata.v1.json")
    /**
     * Minimal durable record of the last successful recovery. It exists so clearing history can
     * never refund the 30-day allowance without retaining session details.
     */
    private val ledgerFile = File(directory, "recovery-ledger.v1.json")
    private val state = MutableStateFlow(HistorySnapshot())
    val snapshots: StateFlow<HistorySnapshot> = state.asStateFlow()
    private var lastCompaction: Long? = null
    private var degraded = false
    /** One-shot gate: only StreakRecoveryCommitter may append a recovery bridge. */
    private val recoveryCommitAuthorized = AtomicBoolean(false)

    suspend fun load(): HistorySnapshot = operation {
        val snapshot = readLocked()
        // Migration/repair: a ledger written by an older build (or a failed commit write) is
        // seeded from the durable recovery rows before any clear can drop them.
        repairLedgerLocked(snapshot)
        compactIfNeeded(snapshot)
    }.let { state.value }

    /** Authoritative wall clock for recovery eligibility; never trust a UI-supplied timestamp. */
    fun millis(): Long = clock.millis()

    suspend fun append(event: HistoryEvent): Boolean = update { listOf(event) }

    /** Reconcile against disk while holding the same lock used by every append/clear. */
    suspend fun update(events: (HistorySnapshot) -> List<HistoryEvent>): Boolean =
        operation { updateLocked(events) }

    /**
     * Recovery commits authorize, validate and write inside one store-lock acquisition.
     * No other append can observe an armed gate between authorization and persistence.
     */
    internal suspend fun updateRecovery(
        events: (HistorySnapshot) -> List<HistoryEvent>,
    ): Boolean = operation {
        recoveryCommitAuthorized.set(true)
        try { updateLocked(events) } finally { recoveryCommitAuthorized.set(false) }
    }

    private fun updateLocked(events: (HistorySnapshot) -> List<HistoryEvent>): Boolean {
        var snapshot = readLocked()
        val existing = snapshot.events.mapTo(mutableSetOf()) { it.id }
        val additions = events(snapshot).filter { existing.add(it.id) }
        val accepted = snapshot.events.toMutableList()
        val terminals = snapshot.events.filter { it.knownType?.terminal == true }
            .groupBy { it.session }.toMutableMap()
        val writes = linkedMapOf<String, ByteArray>()
        var replacedTerminal = false
        var total = 0L
        additions.forEach { original ->
            val encoded = HistoryCodec.encode(original)
            require('\n' !in encoded) { "An event must occupy one JSONL record" }
            require(utf8Size(encoded) + 1 <= MAX_RECORD_BYTES) { "Event exceeds append budget" }
            val bytes = (encoded + "\n").toByteArray(Charsets.UTF_8)
            total += bytes.size
            require(total <= MAX_BATCH_BYTES) { "Batch exceeds append budget" }
            val event = HistoryCodec.decode(encoded, forAppend = true)
            if (event.knownType == HistoryEventType.COMPLETED) {
                require(event.at <= clock.millis()) { "Completion cannot be in the future" }
                val plan = accepted.filter { it.session == event.session && it.at <= event.at && it.knownType in setOf(
                    HistoryEventType.STARTED, HistoryEventType.RESCHEDULED) }.sortedBy { it.at }.lastOrNull()
                require(plan == null || (plan.plannedEnd != null && event.at >= plan.plannedEnd)) {
                    "Completion cannot precede its effective logical deadline"
                }
            }
            if (event.knownType == HistoryEventType.STREAK_RECOVERED ||
                event.type == StreakRecoveryPolicy.WIRE_TYPE
            ) {
                // A refused recovery write is a rejected request, not store corruption: nothing
                // has been written yet, so it must never degrade authenticated history — that
                // would let one forged append block the legitimate commit behind it.
                if (!recoveryCommitAuthorized.get()) {
                    throw RecoveryWriteRejected("streak_recovered requires StreakRecoveryCommitter")
                }
                if (!StreakRecoveryPolicy.isWellFormedRecovery(event)) {
                    throw RecoveryWriteRejected("streak_recovered requires policy metadata")
                }
                val missed = StreakRecoveryPolicy.recoveryDay(event, zone()) ?: error("Missed recovery day")
                val duplicate = accepted.any {
                    StreakRecoveryPolicy.isWellFormedRecovery(it) &&
                        StreakRecoveryPolicy.recoveryDay(it, zone()) == missed
                }
                if (duplicate) throw RecoveryWriteRejected("Missed day already recovered")
            }
            if (event.knownType?.terminal == true) {
                val prior = terminals[event.session].orEmpty()
                if (prior.isNotEmpty()) {
                    // First explicit outcome wins. A missing-row observation cannot
                    // outrank it, regardless of delivery order or wall timestamps.
                    if (event.observationTerminal || prior.any { !it.observationTerminal }) return@forEach
                    accepted.removeAll { it.session == event.session && it.knownType?.terminal == true }
                    prior.forEach { writes.remove(it.id) }
                    replacedTerminal = true
                }
                terminals[event.session] = listOf(event)
            }
            accepted += event
            writes[event.id] = bytes
        }
        require(accepted.size <= MAX_RECORDS) { "History record budget exceeded" }
        require(accepted.sumOf { it.sessions?.size ?: 0 } <= MAX_REFERENCES_TOTAL) { "History reference budget exceeded" }
        // Validation is all-or-nothing. Repair only after every candidate passed validation.
        if (snapshot.degraded || replacedTerminal) rewriteLog(accepted)
        else if (writes.size > 1) atomicWrite(log) { stream ->
            log.inputStream().use { it.copyTo(stream) }
            writes.values.forEach(stream::write)
        } else if (writes.isNotEmpty()) FileOutputStream(log, true).use { stream ->
            stream.write(writes.values.single()); stream.flush(); stream.fd.sync()
        }
        snapshot = readLocked()
        // Durable allowance mirror: written with the recovery, so a later clear can never
        // refund it. The event itself remains the authoritative record until then.
        additions.asSequence().filter { StreakRecoveryPolicy.isWellFormedRecovery(it) }
            .maxByOrNull { it.at }?.let { event ->
                try {
                    writeLedgerLocked(event.at)
                } catch (_: Exception) {
                    // The recovery is durable but its allowance mirror is not: fail closed so a
                    // later clear starts a fresh cooldown instead of refunding it.
                    degraded = true
                    runCatching { writeMetadata(readMetadata().copy(degraded = true)) }
                }
            }
        compactIfNeeded(snapshot)
        return true
    }

    /** The caller supplies owner-active identities, independently of history's planned endpoints. */
    suspend fun clear(activeSessionIds: Set<String>): Boolean = clear { activeSessionIds }

    /** Re-read owner activity under the history lock; unknown activity never authorizes clear. */
    suspend fun clear(activeSessionIds: () -> Set<String>?): Boolean {
        var ownerReady = true
        val committed = operation {
            val active = try { activeSessionIds() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            if (active == null) { ownerReady = false; return@operation }
            val snapshot = readLocked()
            val running = snapshot.events.groupBy { it.session }.filter { (id, events) ->
                id in active && events.any { it.knownType == HistoryEventType.STARTED } &&
                    events.none { it.knownType?.terminal == true }
            }.keys
            val kept = retainForSessions(snapshot.events, running)
            // The allowance survives a clear through the minimal ledger, not by retaining
            // session details. Compute it from the events *before* they are deleted; a corrupt
            // ledger — or a degraded store whose mirror could not be written — restarts the
            // cooldown instead of refunding it.
            val old = readMetadata()
            val ledger = trustedLedger(snapshot.events)
            val floor = maxOf(
                ledger.at ?: Long.MIN_VALUE,
                StreakRecoveryPolicy.lastSuccessfulRecoveryAt(snapshot.events) ?: Long.MIN_VALUE,
            ).takeIf { it != Long.MIN_VALUE }
                ?: if (ledger.corrupt || (ledger.at == null && old.degraded)) clock.millis() else null
            if (floor != null) writeLedgerLocked(floor)
            val retained = kept
            // A rollback cannot put already committed records after the clear boundary.
            val recordedThrough = snapshot.events.filter { it.knownType != null }.maxOfOrNull {
                maxOf(it.at, it.observedAt ?: it.at)
            } ?: Long.MIN_VALUE
            val at = maxOf(old.cleared ?: Long.MIN_VALUE, clock.millis(), recordedThrough)
            clearPhase(HistoryClearPhase.BEFORE_INTENT)
            val intent = old.copy(
                pendingClear = at,
                keepSessions = running,
                // The clear resets best anyway; zeroing it here keeps the intent writable even
                // during a checkpoint-signing outage, without touching the current checkpoint
                // file (the intent is a separate write and the reset is the product rule).
                bestRunDays = 0,
                bestTailDays = 0,
                bestTailEndDay = null,
            )
            writeMetadata(intent)
            clearPhase(HistoryClearPhase.INTENT_COMMITTED)
            rewriteLog(retained)
            clearPhase(HistoryClearPhase.LOG_REPLACED)
            degraded = false
            writeMetadata(intent.copy(cleared = at, degraded = false, pendingClear = null,
                keepSessions = emptySet(), bestRunDays = 0, bestTailDays = 0, bestTailEndDay = null))
            clearPhase(HistoryClearPhase.METADATA_COMMITTED)
            state.value = readLocked()
        }
        return committed && ownerReady
    }

    suspend fun compact(): Boolean = operation { compactLocked(readLocked()) }

    private suspend fun operation(block: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            try {
                block(); true
            } catch (e: CancellationException) {
                throw e
            } catch (_: RecoveryWriteRejected) {
                // Expected refusal of a privileged write. No byte reached the log, so this is
                // not corruption: the store stays healthy and a pending legitimate commit can
                // still proceed. Fail-closed behaviour is unchanged — the event is not written.
                false
            } catch (e: Exception) {
                degraded = true
                state.value = state.value.copy(degraded = true, diagnostic = e.javaClass.simpleName)
                false
            }
        }
    }

    private fun readLocked(): HistorySnapshot {
        check(directory.isDirectory || directory.mkdirs()) { "History directory unavailable" }
        // A pending backup that cannot be restored is unreadable history, not an empty log.
        check(AtomicFileReplace.recover(log)) { "History recovery pending" }
        check(AtomicFileReplace.recover(metadata)) { "Metadata recovery pending" }
        val meta = readMetadata()
        val createdAt = meta.created
        val clearedAt = meta.cleared
        lastCompaction = meta.compactedDay
        degraded = degraded || meta.degraded
        if (!log.exists()) syncWrite(log, byteArrayOf())
        if (log.length() > READ_CAP) {
            // Preserve the original before resetting. Never allocate an unbounded external file.
            return resetOverBudgetLog(meta)
        }
        val events = mutableListOf<HistoryEvent>()
        var bad = false
        // Count before materializing any record/ownership graph. Quarantine externally
        // dense logs before a degraded reset, so subsequent sessions can still be recorded.
        var records = 0
        log.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer); if (count < 0) break
                repeat(count) { if (buffer[it] == '\n'.code.toByte()) records++ }
            }
        }
        if (records > MAX_RECORDS) {
            return resetOverBudgetLog(meta)
        }
        // JSONL is LF-delimited. Raw CR is valid JSON whitespace and must survive verbatim.
        var references = 0
        try { log.bufferedReader().use { reader ->
            val buffer = CharArray(8192); val line = StringBuilder()
            while (true) {
                val count = reader.read(buffer); if (count < 0) break
                var start = 0
                repeat(count) { index ->
                    if (buffer[index] == '\n') {
                        line.append(buffer, start, index - start)
                        try {
                            val event = HistoryCodec.decode(line.toString())
                            references += event.sessions?.size ?: 0
                            if (references > MAX_REFERENCES_TOTAL) throw ReferenceBudgetExceeded()
                            events += event
                            if (event.opaque && event.v == 1 && HistoryEventType.fromWire(event.type) != null) bad = true
                        } catch (e: ReferenceBudgetExceeded) { throw e } catch (_: Exception) { bad = true }
                        line.setLength(0); start = index + 1
                    }
                }
                line.append(buffer, start, count - start)
                check(line.length <= READ_CAP)
            }
            if (line.isNotEmpty()) bad = true
        } } catch (_: ReferenceBudgetExceeded) { return resetOverBudgetLog(meta) }
        if (bad) {
            degraded = true
            quarantine(log)
            writeMetadata(meta.copy(degraded = true))
        }
        if (meta.pendingClear != null) {
            // Same allowance rule as a normal clear: preserve the floor from the ledger and any
            // recovery rows that still exist, then delete the rows.
            val ledger = trustedLedger(events)
            val floor = maxOf(
                ledger.at ?: Long.MIN_VALUE,
                StreakRecoveryPolicy.lastSuccessfulRecoveryAt(events) ?: Long.MIN_VALUE,
            ).takeIf { it != Long.MIN_VALUE }
                ?: if (ledger.corrupt || (ledger.at == null && meta.degraded)) clock.millis() else null
            if (floor != null) writeLedgerLocked(floor)
            rewriteLog(retainForSessions(events, meta.keepSessions))
            // The clear repairs history and re-seals the allowance mirror, so the store leaves the
            // degraded state only when the ledger is authenticated now (trustedLedger threw above
            // if it could not be repaired).
            degraded = ledger.corrupt
            // A clear must also reset the best-streak checkpoint, even when it is being recovered
            // after an interrupted clear; otherwise an empty history would still claim a best.
            writeMetadata(meta.copy(cleared = maxOf(meta.cleared ?: Long.MIN_VALUE, meta.pendingClear),
                degraded = ledger.corrupt, pendingClear = null, keepSessions = emptySet(),
                bestRunDays = 0, bestTailDays = 0, bestTailEndDay = null))
            return readLocked()
        }
        val ledger = trustedLedger(events)
        if (ledger.corrupt) degraded = true
        val eventsFloor = StreakRecoveryPolicy.lastSuccessfulRecoveryAt(events)
        val floor = maxOf(ledger.at ?: Long.MIN_VALUE, eventsFloor ?: Long.MIN_VALUE)
        return HistorySnapshot(events, maxOf(createdAt, clearedAt ?: createdAt),
            readable = !bad || events.isNotEmpty(), degraded = degraded,
            diagnostic = if (degraded) "Some history couldn't be read" else null,
            clearedAt = clearedAt,
            recoveryFloorAt = floor.takeIf { it != Long.MIN_VALUE },
            bestCheckpoint = BestStreakCheckpoint(
                meta.bestRunDays, meta.bestTailDays, meta.bestTailEndDay,
            ),
            bestCheckpointUnverified = !meta.bestRunVerified).also { state.value = it }
    }

    private data class Metadata(
        val created: Long, val cleared: Long? = null, val compactedDay: Long? = null, val degraded: Boolean = false,
        val pendingClear: Long? = null, val keepSessions: Set<String> = emptySet(),
        /** Best-streak compaction checkpoint; derived, never a live counter. */
        val bestRunDays: Int = 0,
        val bestTailDays: Int = 0,
        val bestTailEndDay: Long? = null,
        /** False when a non-zero checkpoint exists without a valid integrity tag. */
        val bestRunVerified: Boolean = true,
    )

    private class ReferenceBudgetExceeded : Exception()

    /**
     * A privileged recovery write refused before any byte was written (missing authorization,
     * malformed payload, or an already-recovered day). Distinct from an I/O or integrity failure
     * so that an ordinary rejected authorization attempt never degrades the store.
     */
    private class RecoveryWriteRejected(message: String) : Exception(message)
    private fun resetOverBudgetLog(meta: Metadata): HistorySnapshot {
        quarantine(log)
        atomicWrite(log, byteArrayOf())
        degraded = true
        writeMetadata(meta.copy(degraded = true, pendingClear = null, keepSessions = emptySet()))
        return readLocked()
    }

    private fun readMetadata(): Metadata {
        if (!metadata.exists()) {
            // A pre-existing log without metadata must not invent an earlier history start.
            writeMetadata(Metadata(clock.millis()))
        }
        if (metadata.length() > HistoryCodec.MATERIALIZATION_LIMIT) {
            quarantine(metadata, "metadata")
            degraded = true
            return Metadata(clock.millis(), degraded = true).also { writeMetadata(it) }
        }
        val bytes = metadata.readBytes()
        return try {
            val fields = FlatJson.decode(bytes.toString(Charsets.UTF_8))
            require(fields["v"] == 1L)
            fun long(key: String) = fields[key]?.let { it as? Long ?: error("Invalid $key") }
            val created = long("createdAt") ?: error("History metadata unavailable")
            val pending = long("pendingClear")
            val retained = fields["keepSessions"]
            require(pending == null || retained is List<*>) { "Invalid clear intent" }
            val keep = retained?.let { value ->
                (value as? List<*>)?.map { (it as? String)?.takeIf(String::isNotBlank)
                    ?: error("Invalid retained session") }?.toSet() ?: error("Invalid retained sessions")
            }.orEmpty()
            val flag = fields["degraded"]?.let { it as? Boolean ?: error("Invalid diagnostic") } ?: false
            // Checkpoint fields are parsed defensively: a damaged checkpoint must discard only
            // the checkpoint, never historyStart, and must never fabricate a best.
            fun longOrNull(key: String): Long? = runCatching { long(key) }.getOrNull()
            val runDaysRaw = longOrNull("bestRunDays") ?: 0L
            val tailDaysRaw = longOrNull("bestTailDays") ?: 0L
            val tailEndRaw = longOrNull("bestTailEndDay")
            val mac = fields["bestRunMac"] as? String
            val version = longOrNull("bestRunVersion") ?: 0L
            val hasValues = runDaysRaw != 0L || tailDaysRaw != 0L
            val verified = !hasValues || (
                mac != null && version == 1L &&
                    authenticator.verify(
                        checkpointPayload(created, runDaysRaw, tailDaysRaw, tailEndRaw), mac,
                    )
                )
            val checkpoint = if (verified) HistoryBestStreak.sanitized(
                bestRunDays = runDaysRaw.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                tailDays = tailDaysRaw.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                tailEndDay = tailEndRaw,
            ) else BestStreakCheckpoint()
            Metadata(
                created, long("clearedAt"), long("compactedDay"), flag, pending, keep,
                checkpoint.bestRunDays, checkpoint.tailDays, checkpoint.tailEndDay, verified,
            )
        } catch (e: Exception) {
            quarantine(bytes, "metadata")
            // Neither creation nor clear can be trusted. Never invent an earlier historyStart.
            degraded = true
            Metadata(clock.millis(), degraded = true).also { writeMetadata(it) }
        }
    }

    private fun checkpointPayload(created: Long, runDays: Long, tailDays: Long, tailEnd: Long?): String =
        "v1|$created|$runDays|$tailDays|${tailEnd ?: "-"}"

    /**
     * Thrown *before* any metadata replacement when a non-empty checkpoint cannot be
     * authenticated. Callers must abort rather than persist zeros over a valid checkpoint.
     */
    private class CheckpointAuthenticationUnavailable : IllegalStateException(
        "Best-streak checkpoint could not be authenticated",
    )

    private fun writeMetadata(meta: Metadata) {
        val hasValues = meta.bestRunDays != 0 || meta.bestTailDays != 0
        // A non-empty checkpoint is written only with a valid MAC. A signing outage must abort
        // the caller (compaction then never prunes), never overwrite the previous checkpoint.
        val mac = if (hasValues) {
            val payload = checkpointPayload(
                meta.created, meta.bestRunDays.toLong(), meta.bestTailDays.toLong(), meta.bestTailEndDay,
            )
            authenticator.sign(payload) ?: throw CheckpointAuthenticationUnavailable()
        } else {
            null
        }
        atomicWrite(metadata, FlatJson.encode(linkedMapOf(
            "v" to 1L, "createdAt" to meta.created, "clearedAt" to meta.cleared,
            "compactedDay" to meta.compactedDay, "degraded" to meta.degraded,
            "pendingClear" to meta.pendingClear, "keepSessions" to meta.keepSessions.toList(),
            "bestRunVersion" to if (mac != null) 1L else 0L,
            "bestRunDays" to meta.bestRunDays.toLong(),
            "bestTailDays" to meta.bestTailDays.toLong(),
            "bestTailEndDay" to meta.bestTailEndDay,
            "bestRunMac" to mac,
        )).toByteArray(Charsets.UTF_8))
    }

    // MARK: recovery ledger

    private data class LedgerRead(val at: Long?, val corrupt: Boolean)

    /** Domain-separated so a ledger MAC can never be confused with a checkpoint MAC. */
    private fun ledgerPayload(at: Long): String = "recovery-ledger|v1|$at"

    /**
     * Trusts [LedgerRead.at] only for a supported version, a present MAC that verifies, a
     * positive timestamp and a value not further than [LEDGER_FUTURE_SKEW_MS] in the future.
     * Everything else is returned as corrupt so the caller can fail closed.
     */
    private fun readLedger(): LedgerRead {
        if (!ledgerFile.isFile) return LedgerRead(null, false)
        return try {
            val json = FlatJson.decode(ledgerFile.readText())
            if ((json["v"] as? Long) != 1L) return LedgerRead(null, true)
            val at = json["lastRecoveryAt"] as? Long ?: return LedgerRead(null, true)
            val mac = json["mac"] as? String ?: return LedgerRead(null, true)
            if (at <= 0L) return LedgerRead(null, true)
            // A genuine floor is written with the device clock, so it can only be "future" after
            // a clock rollback. One day tolerates a manual/timezone change; beyond that the value
            // is untrusted and repaired fail-closed.
            if (at > clock.millis() + LEDGER_FUTURE_SKEW_MS) return LedgerRead(null, true)
            if (!authenticator.verify(ledgerPayload(at), mac)) return LedgerRead(null, true)
            LedgerRead(at, false)
        } catch (_: Exception) {
            LedgerRead(null, true)
        }
    }

    /** Monotonic: an older mirror never replaces a newer one. Throws when it cannot be signed. */
    private fun writeLedgerLocked(at: Long) {
        val current = readLedger().at ?: Long.MIN_VALUE
        if (at <= current) return
        val mac = authenticator.sign(ledgerPayload(at))
            ?: throw CheckpointAuthenticationUnavailable()
        atomicWrite(
            ledgerFile,
            FlatJson.encode(linkedMapOf("v" to 1L, "lastRecoveryAt" to at, "mac" to mac))
                .toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Fail-closed repair of an untrusted ledger.
     *
     * Evidence is quarantined first. The replacement floor is the durable event floor when it is
     * sane, otherwise "now": the allowance is treated as consumed from detection, so corruption
     * can never refund it. The store stays `degraded` for the rest of the process even when the
     * repair succeeds, because the authenticated mirror was lost. If the replacement cannot be
     * signed, written and read back, the ledger stays untrusted and recovery stays unavailable.
     */
    private fun trustedLedger(events: List<HistoryEvent>): LedgerRead {
        val read = readLedger()
        if (!read.corrupt) return read
        degraded = true
        runCatching { quarantine(ledgerFile.readBytes(), "recovery-ledger") }
        val now = clock.millis()
        val eventsFloor = StreakRecoveryPolicy.lastSuccessfulRecoveryAt(events)
            ?.takeIf { it in 1..(now + LEDGER_FUTURE_SKEW_MS) }
        return try {
            writeLedgerLocked(eventsFloor ?: now)
            readLedger().also { if (it.corrupt) degraded = true }
        } catch (_: Exception) {
            degraded = true
            LedgerRead(null, true)
        }
    }

    /** Seed/repair the ledger from durable recovery rows (upgrade or a failed mirror write). */
    private fun repairLedgerLocked(snapshot: HistorySnapshot) {
        val ledger = readLedger()
        if (ledger.corrupt) return
        val eventsFloor = StreakRecoveryPolicy.lastSuccessfulRecoveryAt(snapshot.events) ?: return
        if (ledger.at == null || eventsFloor > ledger.at) {
            runCatching { writeLedgerLocked(eventsFloor) }
        }
    }

    private fun compactIfNeeded(snapshot: HistorySnapshot) {
        val today = clock.instant().atZone(zone()).toLocalDate().toEpochDay()
        if (lastCompaction != today || log.length() > SOFT_CAP) compactLocked(snapshot)
    }

    private fun compactLocked(snapshot: HistorySnapshot) {
        val zoneId = zone()
        val startDate = Instant.ofEpochMilli(snapshot.historyStart).atZone(zoneId).toLocalDate()
        val cutoff = clock.millis() - retentionMs
        val groups = snapshot.events.groupBy { it.session }
        val retainedIds = groups.filterValues { events -> events.maxOf { it.at } >= cutoff }.keys.toMutableSet()
        // A global pass belongs to its operation and every affected session. Keep that
        // shared record until all retained owners disappear, including during hard-cap trim.
        val owners = groupOwners(groups)
        val dependents = mutableMapOf<String, MutableList<String>>()
        owners.forEach { (group, ids) -> ids.forEach { dependents.getOrPut(it) { mutableListOf() }.add(group) } }
        val references = owners.mapValues { (_, ids) -> ids.count { it in retainedIds } }.toMutableMap()
        val sizes = groups.mapValues { (_, events) -> events.sumOf { utf8Size(HistoryCodec.encode(it)) + 1 } }
        var size = groups.keys.sumOf { if (references.getValue(it) > 0) sizes.getValue(it) else 0 }
        groups.entries.sortedBy { (_, events) -> events.maxOf { it.at } }.forEach { (id, _) ->
            if (size > HARD_CAP && retainedIds.remove(id)) dependents[id].orEmpty().forEach { group ->
                val remaining = references.getValue(group) - 1
                references[group] = remaining
                if (remaining == 0) size -= sizes.getValue(group)
            }
        }
        val kept = snapshot.events.filter { references.getValue(it.session) > 0 }
        val today = clock.instant().atZone(zoneId).toLocalDate().toEpochDay()
        val before = readMetadata()

        // A partially readable log still gets the ordinary repair rewrite, but its day set is
        // incomplete, so it must never move the best-streak checkpoint.
        val checkpoint: BestStreakCheckpoint? = if (snapshot.readable && !snapshot.degraded) {
            val allDays = streakDays(snapshot.events, zoneId, startDate)
            val keptDays = streakDays(kept, zoneId, startDate)
            val candidate = HistoryBestStreak.checkpoint(
                previous = snapshot.bestCheckpoint,
                days = allDays,
                historyStart = startDate,
                oldestRetained = keptDays.minOrNull(),
            )
            // The candidate is justified by construction (real days + the previously proven
            // floor). Reject only implausible values: an over-strict ceiling would abort
            // retention forever on a legitimate run that crosses the boundary.
            if (candidate.bestRunDays !in 0..HistoryBestStreak.MAX_PLAUSIBLE_DAYS ||
                candidate.tailDays !in 0..candidate.bestRunDays
            ) {
                return
            }
            candidate
        } else {
            null
        }

        // Keep the exact authenticated bytes so a failed read-back can restore them verbatim
        // (including the previous MAC) instead of re-signing under a degraded authenticator.
        val previousMetadata = if (metadata.isFile) {
            runCatching { metadata.readBytes() }.getOrNull()
        } else {
            null
        }
        val next = if (checkpoint != null) {
            before.copy(
                compactedDay = today,
                degraded = degraded,
                bestRunDays = checkpoint.bestRunDays,
                bestTailDays = checkpoint.tailDays,
                bestTailEndDay = checkpoint.tailEndDay,
            )
        } else {
            before.copy(compactedDay = today, degraded = true)
        }
        // Transaction: checkpoint first (throws when it cannot be authenticated, before any
        // replacement), validate it, and only then destroy event rows.
        writeMetadata(next)
        if (checkpoint != null) {
            val written = readMetadata()
            val valid = written.bestRunDays == checkpoint.bestRunDays &&
                written.bestTailDays == checkpoint.tailDays &&
                written.bestTailEndDay == checkpoint.tailEndDay
            if (!valid) {
                // A checkpoint we cannot verify must never replace the authoritative history.
                if (previousMetadata != null) {
                    runCatching { atomicWrite(metadata, previousMetadata) }
                } else {
                    runCatching { writeMetadata(before) }
                }
                return
            }
        }
        if (checkpoint == null) {
            // Degraded read: never prune a partial view. Normalize the parsed rows only; the
            // original damaged log is preserved in quarantine.
            rewriteLog(snapshot.events)
        } else {
            rewriteLog(kept)
        }
        lastCompaction = today
        state.value = readLocked()
    }

    /** Canonical streak days (recorded terminal zone) for one event view. */
    private fun streakDays(events: List<HistoryEvent>, zoneId: ZoneId, startDate: LocalDate) =
        StreakRecoveryPolicy.completedDays(
            FocusHistoryAggregator.sessions(events, clock.millis(), emptySet()), zoneId,
        ) + StreakRecoveryPolicy.recoveredDays(events, zoneId, startDate)

    private fun rewriteLog(events: List<HistoryEvent>) = atomicWrite(log) { stream ->
        stream.writer(Charsets.UTF_8).apply {
            events.forEach { write(HistoryCodec.encode(it)); write("\n") }; flush()
        }
    }

    private fun groupOwners(groups: Map<String, List<HistoryEvent>>): Map<String, Set<String>> =
        groups.mapValues { (id, group) -> buildSet {
            add(id)
            group.filter { it.knownType == HistoryEventType.PASS_USED }.forEach { addAll(it.sessions.orEmpty()) }
        } }
    private fun retainForSessions(events: List<HistoryEvent>, retained: Set<String>): List<HistoryEvent> {
        val owners = groupOwners(events.groupBy { it.session })
        return events.filter { event -> owners.getValue(event.session).any { it in retained } }.map { event ->
            if (event.knownType != HistoryEventType.PASS_USED || event.sessions == null) return@map event
            val references = event.sessions.filter { it in retained }
            if (references == event.sessions) return@map event
            // A shared pass may survive for a running session; clear the other IDs
            // while retaining unrelated forward-compatible fields in its envelope.
            val line = event.originalLine?.let { original ->
                FlatJson.encode(FlatJson.decode(original).toMutableMap().apply { put("sessions", references) })
            }
            event.copy(sessions = references, originalLine = line)
        }
    }

    private fun quarantine(bytes: ByteArray, label: String = "events") {
        val source = File(directory, "quarantine-source.tmp")
        try { syncWrite(source, bytes); quarantine(source, label) } finally { source.delete() }
    }

    private fun quarantine(source: File, label: String = "events") {
        val dir = File(directory, "quarantine")
        check(dir.isDirectory || dir.mkdirs())
        fun digest(file: File): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(8192)
                while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest()
        }
        val hash = digest(source)
        val digest = hash.joinToString("") { "%02x".format(it) }
        // Filename includes content digest so reopening the same corrupt log never makes another copy.
        val existing = dir.listFiles().orEmpty().firstOrNull { it.name.endsWith("-$digest.jsonl") }
        if (existing == null || existing.length() != source.length() || !digest(existing).contentEquals(hash)) {
            atomicWrite(existing ?: File(dir, "${clock.millis()}-$label-$digest.jsonl")) { output ->
                source.inputStream().use { it.copyTo(output) }
            }
        }
        dir.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedByDescending { it.lastModified() }
            .drop(3).forEach { check(it.delete()) }
    }

    private fun syncWrite(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        atomicWrite(file) { it.write(bytes) }
    }
    private fun atomicWrite(file: File, write: (FileOutputStream) -> Unit) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { write(it); it.flush(); it.fd.sync() }
            // minSdk 23: java.nio.file is API 26+. Stage and verify the bytes
            // before replacing, and never truncate the live history file.
            AtomicFileReplace.replace(temporary, file)
        } finally {
            temporary.delete()
        }
    }

    private fun utf8Size(text: String): Long {
        var bytes = 0L; var index = 0
        while (index < text.length) {
            val c = text[index++]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> { index++; 4 }
                c.isSurrogate() -> 1 // UTF-8 encoder's replacement byte
                else -> 3
            }
        }
        return bytes
    }

    companion object {
        private val processMutex = Mutex()
        const val RETENTION_MS = 400L * 24 * 60 * 60 * 1000
        /** How far a genuine ledger floor may appear in the future before it is untrusted. */
        private const val LEDGER_FUTURE_SKEW_MS = 24L * 60 * 60 * 1000
        const val SOFT_CAP = 1024 * 1024
        const val HARD_CAP = 5 * 1024 * 1024
        const val READ_CAP = 2L * HARD_CAP
        const val MAX_RECORD_BYTES = 64 * 1024
        const val MAX_BATCH_BYTES = 1024 * 1024
        const val MAX_RECORDS = 20_000
        const val MAX_REFERENCES_TOTAL = 20_000
    }
}
