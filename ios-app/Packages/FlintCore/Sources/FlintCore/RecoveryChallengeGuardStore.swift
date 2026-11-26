import Foundation

/// Durable owner of the active streak-recovery challenge binding.
///
/// The freeze is written to the App Group suite so a challenge cannot be re-pointed at a
/// different Screen Time selection later in the same day, and so an observed enforcement
/// failure latches durably instead of disappearing with the UI that noticed it.
///
/// Continuity is proven only by the `watchGeneration` stamped into the profile. A profile bound
/// by another process is *not* healthy: Screen Time authorization or ManagedSettings shields may
/// have been interrupted while Flint was terminated, and no in-process latch can observe that.
/// Such a challenge fails closed rather than crediting time it cannot prove was enforced.
public final class RecoveryChallengeGuardStore: @unchecked Sendable {
    /// Storage key is part of the on-disk contract (legacy profiles decode under it).
    public static let storageKey = "flint.streakRecovery.challenge.v1"

    /// One identifier per app process. Injected in tests to model a fresh process deterministically.
    public static let currentProcessGeneration = UUID().uuidString

    private let defaults: UserDefaults
    private let processGeneration: String
    private let key: String
    private let lock = NSLock()

    public init(defaults: UserDefaults = UserDefaults(suiteName: FlintGroupStore.appGroupID) ?? .standard,
                processGeneration: String = RecoveryChallengeGuardStore.currentProcessGeneration,
                key: String = RecoveryChallengeGuardStore.storageKey) {
        self.defaults = defaults
        self.processGeneration = processGeneration
        self.key = key
    }

    /// The bound challenge, or nil when no freeze is stored (or it cannot be decoded).
    public func current() -> StreakRecoveryProtection.BoundProfile? {
        lock.lock(); defer { lock.unlock() }
        return loadLocked()
    }

    /// True only when this process bound the freeze and no failure was observed since.
    public func isEnforcementIntact() -> Bool {
        guard let profile = current(), !profile.enforcementBroken,
              let generation = profile.watchGeneration else { return false }
        return generation == processGeneration
    }

    /// Binds the challenge for `recoveryDay`, or returns the already-bound profile.
    ///
    /// A freeze for the same day is returned unchanged: the original fingerprint, item count and
    /// process generation all win. That is what stops a picker edit from re-pointing a running
    /// challenge at a weaker selection, and it means a same-day rebind can never clear a durable
    /// broken latch or rehabilitate an interrupted process.
    @discardableResult
    public func ensureFrozen(fingerprint: String, applicationCount: Int, allowListMode: Bool,
                             recoveryDay: Date, missedDay: Date,
                             frozenAtMs: Int64) -> StreakRecoveryProtection.BoundProfile? {
        guard !fingerprint.isEmpty, applicationCount > 0 else { return nil }
        lock.lock(); defer { lock.unlock() }
        if let existing = loadLocked(), existing.recoveryDay == recoveryDay { return existing }
        let fresh = StreakRecoveryProtection.BoundProfile(
            fingerprint: fingerprint,
            applicationCount: applicationCount,
            allowListMode: allowListMode,
            recoveryDay: recoveryDay,
            missedDay: missedDay,
            frozenAtMs: frozenAtMs,
            qualifiedSessionIds: [],
            enforcementBroken: false,
            watchGeneration: processGeneration
        )
        saveLocked(fresh)
        return fresh
    }

    /// Records that a session started under the frozen selection. Bookkeeping only: the
    /// authoritative commit path qualifies sessions from durable history `grant` stamps.
    public func qualify(sessionID: String, fingerprint: String) {
        guard !sessionID.isEmpty, !fingerprint.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        guard var profile = loadLocked(), profile.fingerprint == fingerprint,
              !profile.enforcementBroken else { return }
        guard !profile.qualifiedSessionIds.contains(sessionID) else { return }
        profile.qualifiedSessionIds.append(sessionID)
        if profile.qualifiedSessionIds.count > 64 {
            profile.qualifiedSessionIds.removeFirst(profile.qualifiedSessionIds.count - 64)
        }
        saveLocked(profile)
    }

    /// Durably latches broken enforcement. Nothing in this type clears it except binding a new
    /// challenge on a later day.
    public func markEnforcementBroken() {
        lock.lock(); defer { lock.unlock() }
        guard var profile = loadLocked(), !profile.enforcementBroken else { return }
        profile.enforcementBroken = true
        saveLocked(profile)
    }

    /// Drops the freeze once its recovery day is over, or right after a successful commit for
    /// that day. A profile for another day is left alone: `ensureFrozen` replaces it when the next
    /// challenge binds.
    public func clearIfDay(_ day: Date, calendar: Calendar) {
        lock.lock(); defer { lock.unlock() }
        guard let profile = loadLocked() else { return }
        if calendar.isDate(profile.recoveryDay, inSameDayAs: day) { saveLocked(nil) }
    }

    // MARK: - Persistence (call with `lock` held)

    private func loadLocked() -> StreakRecoveryProtection.BoundProfile? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(StreakRecoveryProtection.BoundProfile.self, from: data)
    }

    private func saveLocked(_ profile: StreakRecoveryProtection.BoundProfile?) {
        guard let profile else {
            defaults.removeObject(forKey: key)
            return
        }
        guard let data = try? JSONEncoder().encode(profile) else { return }
        defaults.set(data, forKey: key)
    }
}
