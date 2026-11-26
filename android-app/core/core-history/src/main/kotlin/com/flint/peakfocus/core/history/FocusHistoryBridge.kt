package com.flint.peakfocus.core.history

import android.content.Context
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.StorageState
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Independent IO collector: enforcement never awaits history, disk sync or notifications. */
object FocusHistoryBridge {
    @Volatile private var history: FocusHistoryStore? = null
    @Volatile private var guardStore: RecoveryChallengeGuardStore? = null
    @Volatile private var reconciler: HistoryReconciler? = null
    @Volatile private var observedEvents: HistoryObservedQueue? = null
    private val deliveryHealth = HistoryDeliveryHealth()
    private var job: Job? = null
    @Volatile private var watchJob: Job? = null

    @Synchronized fun store(context: Context): FocusHistoryStore = history ?: run {
        val dir = File(context.applicationContext.filesDir, "focus-history")
        FocusHistoryStore(
            directory = dir,
            // Keystore-backed HMAC where the device supports it; a per-install key file is the
            // fallback so a checkpoint is never silently unverifiable on a healthy install.
            authenticator = FallbackCheckpointAuthenticator(
                KeystoreCheckpointAuthenticator(),
                HmacCheckpointAuthenticator.loadOrCreate(dir),
            ),
        ).also { history = it }
    }

    private fun historyDir(context: Context): File =
        File(context.applicationContext.filesDir, "focus-history")

    @Synchronized fun recoveryGuard(context: Context): RecoveryChallengeGuardStore =
        guardStore ?: RecoveryChallengeGuardStore(historyDir(context)).also { guardStore = it }

    /** App-shell probe: true only while the real service instance is connected and beating. */
    @Volatile private var enforcementProbe: () -> Boolean = { false }

    fun configureEnforcementProbe(probe: () -> Boolean) {
        enforcementProbe = probe
    }

    /** True while the app shell's live enforcement probe reports a healthy service. */
    fun enforcementReady(): Boolean = enforcementProbe()

    /**
     * Established protection = enabled permanent (non-session) block rules. History does not
     * retain pre-miss package lists, so this is the smallest defensible freeze source.
     */
    fun establishedProtectionPackages(
        rules: List<BlockRule> = when (val state = ActiveRulesHolder.dataStoreState) {
            is StorageState.Ready -> state.value
            else -> emptyList()
        },
    ): Pair<List<String>, Boolean> {
        val permanent = rules.filter { it.enabled && !it.id.startsWith(BlockRule.SESSION_ID_PREFIX) }
        if (permanent.isEmpty()) return emptyList<String>() to false
        val allowListOnly = permanent.all { it.targets.allowListMode }
        val packages = permanent.flatMap { rule -> rule.targets.apps.map { it.packageName } }
        return packages to allowListOnly
    }

    /** Result of an off-main bind; [threadName] exists so tests can prove the IO hop. */
    data class RecoveryBindingResult(
        val profile: StreakRecoveryProtection.BoundProfile?,
        val threadName: String,
    )

    /**
     * Bind the recovery freeze away from the UI thread. Composition must never perform this
     * durable write, and callers must not treat a null profile as "challenge ready".
     */
    suspend fun bindRecoveryProtectionOnIo(
        context: Context,
        challenge: StreakRecoveryPolicy.Challenge,
        now: Long = System.currentTimeMillis(),
    ): RecoveryBindingResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val profile = bindRecoveryProtection(context, challenge, now)
        RecoveryBindingResult(profile, Thread.currentThread().name)
    }

    /** Freeze established targets once for the active recovery offer day. */
    fun bindRecoveryProtection(
        context: Context,
        challenge: StreakRecoveryPolicy.Challenge,
        now: Long = System.currentTimeMillis(),
        rules: List<BlockRule>? = null,
    ): StreakRecoveryProtection.BoundProfile? {
        val (packages, allowList) = if (rules != null) establishedProtectionPackages(rules)
        else establishedProtectionPackages()
        return recoveryGuard(context).ensureFrozen(
            packageNames = packages,
            allowListMode = allowList,
            recoveryDay = challenge.recoveryDay,
            missedDay = challenge.missedDay,
            frozenAt = now,
            // A new challenge needs fresh independent enforcement, not a Settings string.
            healthyNow = enforcementProbe(),
        )
    }

    /** Commit a recovery bridge only after a durable history append. */
    suspend fun tryCommitStreakRecovery(
        context: Context,
        zone: ZoneId = ZoneId.systemDefault(),
        protectionActive: Boolean = true,
    ): StreakRecoveryCommitter.Result {
        val guard = recoveryGuard(context)
        val protection = guard.current()
        // Credit requires the frozen protection to still be what is enforced *now*: changing the
        // permanent rules or the selection mid-challenge earns nothing, even if the session
        // started while the freeze was intact.
        val (packages, allowList) = establishedProtectionPackages()
        val liveMatch = protection != null &&
            packages.isNotEmpty() &&
            StreakRecoveryProtection.matches(packages, allowList, protection.fingerprint)
        return StreakRecoveryCommitter.tryCommit(
            store = store(context),
            zone = zone,
            activeSessionIds = activeSessionIds?.value.orEmpty(),
            protection = protection,
            // Commit-time enforcement proof: the service must still be live *now*, the durable
            // latch must be clean, and the frozen protection must still be what is enforced.
            protectionActive = protectionActive && enforcementProbe() &&
                guard.isEnforcementIntact() && liveMatch,
            onCommitted = { protection?.recoveryDay?.let { guard.clearIfDay(it) } },
        )
    }

    fun markRecoveryEnforcementBroken(context: Context) {
        recoveryGuard(context).markEnforcementBroken()
    }

    /** Active freeze for Timer lock / Insights copy. Clears itself after the recovery day. */
    fun activeRecoveryProtection(context: Context, today: LocalDate): StreakRecoveryProtection.BoundProfile? {
        val guard = recoveryGuard(context).current() ?: return null
        if (guard.recoveryDay != today) {
            recoveryGuard(context).clear()
            return null
        }
        return guard
    }

    val activeSessionIds: StateFlow<Set<String>>? get() = reconciler?.activeSessionIds

    fun noteOpenIntercepted(ruleId: String?, at: Long) { observedEvents?.offer(HistoryObservedEvent.Open(ruleId, at)) }
    fun noteBreakGranted(ruleId: String?, until: Long, at: Long) { observedEvents?.offer(HistoryObservedEvent.Break(ruleId, at, until)) }
    fun noteEndRoute(ruleId: String, route: String, at: Long) { observedEvents?.offer(HistoryObservedEvent.End(ruleId, at, route)) }
    fun noteLifecycleEventsDropped() { deliveryHealth.noteEventsDropped() }

    suspend fun clear(context: Context): Boolean {
        val observer = reconciler ?: return false
        if (!observer.ready.value || observer.diagnostic.value != null) return false
        return store(context).clear {
            // Read fresh owner authority under clear's history lock. A lagging observer
            // or a past logical plan must not discard an actually running STARTED.
            val latest = historyRulesSnapshot(ActiveRulesHolder.dataStoreState)
            if (latest !is HistoryRulesSnapshot.Ready) null else latest.rules
                .filter { it.isActiveAt(System.currentTimeMillis()) }.mapTo(mutableSetOf()) { it.id }
        }
    }

    suspend fun retry(context: Context): Boolean = withContext(Dispatchers.IO) {
        val observer = reconciler ?: return@withContext false
        try {
            store(context).load()
            val state = ActiveRulesHolder.dataStoreState
            observer.collectSnapshots(flowOf(historyRulesSnapshot(state)))
            state is StorageState.Ready && observer.ready.value && store(context).snapshots.value.readable
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    /** UI clock ticks re-bucket at midnight and after travel without disk reads on main. */
    fun summaries(context: Context): Flow<FocusSummary?> {
        val observer = reconciler ?: return flowOf(null)
        val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
        return deliveryHealth.applyTo(combine(store(context).snapshots, observer.activeSessionIds, observer.ready, observer.diagnostic, ticker) {
                snapshot, active, ready, diagnostic, now ->
            when {
                !ready && diagnostic == null -> null
                !ready -> FocusSummary(HistoryAvailability.UNAVAILABLE, emptyList(), emptyList(), 0)
                else -> FocusHistoryAggregator.summarize(
                    snapshot.copy(degraded = snapshot.degraded || diagnostic != null), now, ZoneId.systemDefault(), active)
            }
        }).flowOn(Dispatchers.Default)
    }

    @Synchronized fun start(
        context: Context,
        scope: CoroutineScope,
        enforcementProbe: () -> Boolean = { false },
    ) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        configureEnforcementProbe(enforcementProbe)
        val store = store(app)
        val observer = HistoryReconciler(store, authoritativeSnapshot = {
            historyRulesSnapshot(ActiveRulesHolder.dataStoreState)
        }, recoveryGrant = {
            // A challenge only credits sessions that started under the frozen selection. The
            // freeze must still be live for today AND the established protection must still
            // match its fingerprint; otherwise the start is recorded without a grant.
            val freeze = activeRecoveryProtection(app, LocalDate.now())
            if (freeze == null) {
                null
            } else {
                val (packages, allowList) = establishedProtectionPackages()
                freeze.fingerprint.takeIf {
                    StreakRecoveryProtection.matches(packages, allowList, freeze.fingerprint)
                }
            }
        }).also { reconciler = it }
        observedEvents = HistoryObservedQueue(scope, HistoryObservedWriter(store)::record)
        FocusCompletionNotifier.start(app, scope, store, observer.ready)
        job = scope.launch(Dispatchers.IO) {
            store.load()
            val rules = historyOwnerSnapshots()
            observer.collectSnapshots(rules.withDeadlineObservations(
                hasTerminal = { id -> store.snapshots.value.events.any { it.session == id && it.knownType?.terminal == true } },
                hasStarted = { id -> store.snapshots.value.events.any { it.session == id && it.knownType == HistoryEventType.STARTED } },
            ))
        }
        // Path A can drop while the challenge runs (accessibility turned off). One healthy sample
        // followed by a drop permanently voids the day's credit; re-enabling cannot restore it.
        watchJob = scope.launch(Dispatchers.IO) {
            // Off the main thread, and before the first health sample: a freeze bound by a
            // previous process cannot prove continuity, so it is latched first.
            val guard = recoveryGuard(app)
            guard.noteWatchStarted()
            RecoveryEnforcementWatch.watch(
                guard = guard,
                freezeActive = { activeRecoveryProtection(app, LocalDate.now()) != null },
                pathAHealthy = enforcementProbe,
                // The bind already verified enforcement (healthyNow), so the first drop must
                // latch instead of waiting for a later healthy sample.
                initiallyHealthy = guard.isEnforcementIntact(),
            )
        }
    }
}
