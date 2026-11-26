import Foundation

/// Durable commit path for streak recovery. Success UI must wait for `.success`.
/// Token consumption occurs only after a durable host write succeeds.
///
/// Eligibility, progress and the allowance are always derived from durable store events inside
/// the host lock — never from caller-supplied session lists or UI clocks.
///
/// A frozen `StreakRecoveryProtection.BoundProfile` is required. Only sessions whose `started`
/// row carries the freeze fingerprint in `grant` contribute focused time.
public enum StreakRecoveryCommitter {
    public enum Result: Equatable, Sendable {
        case success(event: FlintHistoryEvent, challenge: StreakRecoveryPolicy.Challenge)
        case rejected(reason: String)
        case persistFailed
    }

    /// - Parameters:
    ///   - now: ignored for eligibility; the store clock inside the commit is authoritative.
    ///   - protectionActive: whether OS enforcement for the freeze is live right now. False
    ///     rejects with `protection_inactive`, so a mid-challenge revoke cannot award recovery.
    public static func tryCommit(
        store: FlintHistoryStore,
        now: Date? = nil,
        calendar: Calendar = .current,
        activeSessionID: String? = nil,
        recoveryId: String? = nil,
        protection: StreakRecoveryProtection.BoundProfile? = nil,
        protectionActive: Bool = true
    ) -> Result {
        // `now` is ignored; the store clock inside hostOperation is authoritative.
        store.commitStreakRecovery(
            now: nil,
            calendar: calendar,
            activeSessionID: activeSessionID,
            recoveryId: recoveryId,
            protection: protection,
            protectionActive: protectionActive
        )
    }
}
