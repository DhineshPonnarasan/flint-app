package com.flint.peakfocus.core.history

import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Durable freeze of the established protection profile for one recovery challenge.
 * First successful bind wins; later mutations to the live blocklist cannot replace it.
 *
 * Enforcement breaks are latched in a **separate durable record**, keyed by challenge id *and*
 * recovery day. Re-enabling accessibility, restarting Flint, moving the system clock or clearing
 * a stale profile can never revive a broken challenge; the same day cannot be bound again at all.
 * Only a later day may start a new challenge, and that requires independent, fresh verification
 * ([ensureFrozen]'s `healthyNow`).
 *
 * Continuity is additionally proven by the [watchGeneration] stamped at bind time: a freeze bound
 * by a previous process fails closed, because no in-process latch can prove what happened while
 * Flint was dead.
 */
class RecoveryChallengeGuardStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val profileFile = File(directory, "recovery-challenge.v1.json")
    private val latchFile = File(directory, "recovery-enforcement-latch.v1.json")
    private val cached = AtomicReference<StreakRecoveryProtection.BoundProfile?>(null)
    /** Identity of the process that can vouch for continuity of the active freeze. */
    private val watchGeneration: String = UUID.randomUUID().toString()
    private var storedWatchGeneration: String? = null

    private data class Latch(val challengeId: String, val day: LocalDate?, val atMs: Long)

    /** Durable latch; kept in its own file so clearing a stale profile cannot erase it. */
    private var latch: Latch? = readLatch()

    @Synchronized
    fun current(): StreakRecoveryProtection.BoundProfile? {
        cached.get()?.let { return it }
        val loaded = readProfile()
        cached.set(loaded)
        return loaded
    }

    /** The challenge id whose enforcement was latched broken (audit/read-only). */
    @Synchronized
    fun latchedChallenge(): String? = latch?.challengeId

    /**
     * True only when a freeze exists, neither its challenge nor its recovery day is latched
     * broken, and its continuity was stamped by this process generation.
     */
    @Synchronized
    fun isEnforcementIntact(): Boolean {
        val profile = current() ?: return false
        // A legacy freeze has no challenge identity, so continuity cannot be scoped to it.
        if (profile.challengeId.isEmpty()) return false
        val latched = latch
        if (latched != null) {
            if (latched.challengeId == profile.challengeId) return false
            // A clock/date change must not allow the same broken day to be re-bound.
            if (latched.day != null && latched.day == profile.recoveryDay) return false
        }
        return storedWatchGeneration == watchGeneration
    }

    /**
     * Atomic view for the enforcement watcher: which challenge is bound and whether its
     * continuity is intact. Reading the profile and the continuity under one lock avoids a
     * seed race when a bind lands between two watcher samples.
     */
    data class ActiveBinding(val challengeId: String, val intact: Boolean)

    @Synchronized
    internal fun activeBinding(): ActiveBinding? {
        val profile = current() ?: return null
        return ActiveBinding(profile.challengeId, isEnforcementIntact())
    }

    /**
     * Called once by the app shell at process start, before it samples health.
     *
     * A freeze bound by a previous process generation cannot prove that enforcement stayed
     * intact while Flint was dead. Fail closed by latching that challenge durably.
     */
    @Synchronized
    fun noteWatchStarted() {
        val existing = current() ?: return
        if (existing.challengeId.isNotEmpty() && latch?.challengeId == existing.challengeId) return
        val owner = storedWatchGeneration
        if (existing.challengeId.isEmpty() || owner == null || owner != watchGeneration) {
            latch(existing)
            persistLatch()
        }
    }

    /** Latch the *active* challenge broken; idempotent. Returns true when durably persisted. */
    @Synchronized
    fun markEnforcementBroken(): Boolean {
        val existing = current() ?: return true
        latch(existing)
        return persistLatch()
    }

    private fun latch(profile: StreakRecoveryProtection.BoundProfile) {
        val existing = latch
        if (existing != null && existing.challengeId == profile.challengeId &&
            existing.day == profile.recoveryDay
        ) {
            return
        }
        latch = Latch(profile.challengeId, profile.recoveryDay, clock())
    }

    /**
     * Best-effort durable write. On failure the in-memory latch still fails the challenge closed,
     * and the watcher retries this on its next sample.
     */
    private fun persistLatch(): Boolean {
        val pending = latch ?: return true
        return try {
            writeLatch(pending)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Bind the challenge for [recoveryDay], or return the already-bound profile.
     *
     * A freeze for the same day is returned unchanged, so a picker edit cannot re-point a running
     * challenge and a same-day rebind cannot clear a latch. Binding a *new* challenge requires
     * [healthyNow], and a day that was already latched broken can never be bound again.
     */
    @Synchronized
    fun ensureFrozen(
        packageNames: Collection<String>,
        allowListMode: Boolean,
        recoveryDay: LocalDate,
        missedDay: LocalDate,
        frozenAt: Long,
        healthyNow: Boolean = true,
    ): StreakRecoveryProtection.BoundProfile? {
        val existing = current()
        if (existing != null && existing.recoveryDay == recoveryDay) return existing
        // No new challenge without proof that enforcement is live right now.
        if (!healthyNow) return null
        // A broken day stays broken, whatever the clock says.
        if (latch?.day == recoveryDay) return null
        if (existing != null) {
            // Replace the profile but keep the durable latch record.
            cached.set(null)
            storedWatchGeneration = null
        }
        val bound = StreakRecoveryProtection.bind(
            packageNames = packageNames,
            allowListMode = allowListMode,
            recoveryDay = recoveryDay,
            missedDay = missedDay,
            frozenAt = frozenAt,
            challengeId = UUID.randomUUID().toString(),
        ) ?: return null
        storedWatchGeneration = watchGeneration
        writeProfile(bound)
        cached.set(bound)
        return bound
    }

    /** Drop the active profile. The enforcement latch is intentionally preserved. */
    @Synchronized
    fun clear() {
        cached.set(null)
        storedWatchGeneration = null
        if (profileFile.exists()) profileFile.delete()
    }

    /** Drop a stale freeze after the recovery day ends or a successful commit. */
    @Synchronized
    fun clearIfDay(day: LocalDate) {
        val existing = current() ?: return
        if (existing.recoveryDay == day) clear()
    }

    private fun readProfile(): StreakRecoveryProtection.BoundProfile? {
        if (!profileFile.isFile) return null
        return try {
            val json = FlatJson.decode(profileFile.readText())
            if ((json["v"] as? Long) != 1L) return null
            @Suppress("UNCHECKED_CAST")
            val packages = (json["packages"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val fingerprint = json["fingerprint"] as? String ?: return null
            val bound = StreakRecoveryProtection.bind(
                packageNames = packages,
                allowListMode = json["allowListMode"] as? Boolean ?: false,
                recoveryDay = LocalDate.parse(json["recoveryDay"] as String),
                missedDay = LocalDate.parse(json["missedDay"] as String),
                frozenAt = json["frozenAt"] as Long,
                challengeId = json["challengeId"] as? String ?: "",
            )
            storedWatchGeneration = json["watchGeneration"] as? String
            // Legacy sidecars carried only a boolean latch inside the profile.
            if (latch == null && json["enforcementBroken"] == true) {
                val legacy = Latch(bound?.challengeId ?: "", bound?.recoveryDay, clock())
                latch = legacy
                runCatching { writeLatch(legacy) }
            }
            bound?.takeIf { it.fingerprint == fingerprint }
        } catch (_: Exception) {
            null
        }
    }

    private fun readLatch(): Latch? {
        if (!latchFile.isFile) return null
        return try {
            val json = FlatJson.decode(latchFile.readText())
            if ((json["v"] as? Long) != 1L) return null
            val id = json["challengeId"] as? String ?: return null
            val day = (json["recoveryDay"] as? String)?.let { LocalDate.parse(it) }
            Latch(id, day, json["atMs"] as? Long ?: 0L)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLatch(next: Latch) {
        val payload = FlatJson.encode(
            linkedMapOf(
                "v" to 1L,
                "challengeId" to next.challengeId,
                "recoveryDay" to next.day?.toString(),
                "atMs" to next.atMs,
            ),
        )
        writeAtomically(latchFile, payload)
    }

    private fun writeProfile(profile: StreakRecoveryProtection.BoundProfile) {
        val payload = FlatJson.encode(
            linkedMapOf(
                "v" to 1L,
                "fingerprint" to profile.fingerprint,
                "allowListMode" to profile.allowListMode,
                "recoveryDay" to profile.recoveryDay.toString(),
                "missedDay" to profile.missedDay.toString(),
                "frozenAt" to profile.frozenAt,
                "packages" to profile.packageNames,
                "challengeId" to profile.challengeId,
                "watchGeneration" to storedWatchGeneration,
                // Legacy readers only understand the boolean.
                "enforcementBroken" to (latch?.challengeId == profile.challengeId ||
                    latch?.day == profile.recoveryDay),
            ),
        )
        writeAtomically(profileFile, payload)
    }

    private fun writeAtomically(target: File, payload: String) {
        val tmp = File(directory, "${target.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(target)) {
            target.writeText(payload)
            tmp.delete()
        }
    }
}
