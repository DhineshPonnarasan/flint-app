import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import CryptoKit
import FamilyControls
#endif

/// Enforcement binding for conditional free streak recovery (policy v1).
///
/// Recovery credit is granted only for focus time the app can prove was spent under the frozen
/// Screen Time selection that the challenge bound. The durable evidence is the `grant` stamp on
/// each session's `started` history row: it names the target set the shield was built from, so a
/// later picker edit — or a caller-supplied sidecar list — cannot retroactively qualify time.
///
/// The profile/gate types are Foundation-only so they stay usable (and testable) where the
/// Screen Time frameworks are unavailable; only token fingerprinting needs `FamilyControls`.
public enum StreakRecoveryProtection {
    /// The immutable selection a recovery challenge was started under, plus its durable liveness.
    public struct BoundProfile: Equatable, Sendable, Codable {
        public var fingerprint: String
        public var applicationCount: Int
        public var allowListMode: Bool
        public var recoveryDay: Date
        public var missedDay: Date
        public var frozenAtMs: Int64
        /// Sidecar bookkeeping written at session start. Never used as credit evidence: the
        /// authoritative commit path qualifies sessions from durable `grant` stamps only.
        public var qualifiedSessionIds: [String]
        /// Durable latch: enforcement was observed broken (revoked authorization, restart with
        /// unproven continuity, or a substituted selection). A reconnect never clears it.
        public var enforcementBroken: Bool
        /// Process generation that bound the freeze. `nil` on legacy profiles, which fail closed.
        public var watchGeneration: String?
        /// Civil day (`yyyy-MM-dd`) recorded at bind, independent of the start-of-day instant.
        /// A time-zone change on the same day keeps this key, so the broken latch survives;
        /// legacy profiles have no key and fall back to a calendar comparison.
        public var recoveryDayKey: String?

        /// Nothing was frozen yet (no fingerprint, or no shielded surface to enforce).
        public var isEmpty: Bool { fingerprint.isEmpty || applicationCount <= 0 }

        public init(fingerprint: String, applicationCount: Int, allowListMode: Bool,
                    recoveryDay: Date, missedDay: Date, frozenAtMs: Int64,
                    qualifiedSessionIds: [String] = [], enforcementBroken: Bool = false,
                    watchGeneration: String? = nil, recoveryDayKey: String? = nil) {
            self.fingerprint = fingerprint
            self.applicationCount = applicationCount
            self.allowListMode = allowListMode
            self.recoveryDay = recoveryDay
            self.missedDay = missedDay
            self.frozenAtMs = frozenAtMs
            self.qualifiedSessionIds = qualifiedSessionIds
            self.enforcementBroken = enforcementBroken
            self.watchGeneration = watchGeneration
            self.recoveryDayKey = recoveryDayKey
        }
    }

    /// Start-gate decision for a session that would run during a recovery challenge.
    public enum SelectionGate: Equatable, Sendable {
        case allow
        case refuse(reason: String)

        public static let missingFreeze = "recovery_freeze_missing"
        public static let mismatchedSelection = "recovery_selection_mismatch"
        public static let enforcementBroken = "recovery_enforcement_broken"
        public static let authorizationRevoked = "recovery_authorization_revoked"

        /// `requireFreeze` distinguishes "no challenge is running" (allow) from "a challenge must
        /// bind this start" (refuse): the refresh path tolerates no freeze, the start path does not.
        public static func evaluate(freeze: BoundProfile?, selectionFingerprint: String,
                                    authorizationApproved: Bool,
                                    requireFreeze: Bool) -> SelectionGate {
            guard let freeze else {
                return requireFreeze ? .refuse(reason: missingFreeze) : .allow
            }
            // A broken latch outranks authorization: reconnecting Screen Time is not proof that
            // the interrupted challenge was continuously enforced.
            if freeze.enforcementBroken { return .refuse(reason: enforcementBroken) }
            guard let generation = freeze.watchGeneration,
                  generation == RecoveryChallengeGuardStore.currentProcessGeneration else {
                return .refuse(reason: enforcementBroken)
            }
            guard authorizationApproved else { return .refuse(reason: authorizationRevoked) }
            guard !selectionFingerprint.isEmpty, freeze.fingerprint == selectionFingerprint else {
                return .refuse(reason: mismatchedSelection)
            }
            return .allow
        }
    }

    // MARK: - Durable credit evidence

    /// Sessions whose durable `started` row carries this challenge's frozen fingerprint.
    public static func qualifyingSessionIds(events: [FlintHistoryEvent], fingerprint: String,
                                            extra: Set<String> = []) -> Set<String> {
        // An empty fingerprint cannot qualify anything: never let `extra` become an escape hatch.
        guard !fingerprint.isEmpty else { return [] }
        var result = extra
        for event in events where event.type == .started && event.grant == fingerprint {
            result.insert(event.session)
        }
        return result
    }

    /// Net focused milliseconds on `day` from sessions that both completed and were qualified by
    /// the frozen grant stamp. Progress is net focus (breaks removed), never wall-clock span.
    public static func focusedMs(on day: Date, sessions: [FlintHistorySessionSummary],
                                 calendar: Calendar, events: [FlintHistoryEvent],
                                 fingerprint: String,
                                 extraQualifiedIds: Set<String> = []) -> Int64 {
        let qualified = qualifyingSessionIds(events: events, fingerprint: fingerprint,
                                             extra: extraQualifiedIds)
        guard !qualified.isEmpty else { return 0 }
        return sessions.reduce(Int64(0)) { total, session in
            guard session.outcome == .completed, qualified.contains(session.id) else { return total }
            let anchor = dayAnchor(for: session.endedAt, timeZone: session.recordedTimeZone,
                                   calendar: calendar)
            guard anchor == day else { return total }
            return total + Int64(max(0, session.focusedSeconds) * 1000)
        }
    }

    /// Maps an instant to a local-day anchor using the zone recorded with it, expressed in the
    /// device calendar. A later device-zone change therefore cannot move the day.
    static func dayAnchor(for date: Date, timeZone: TimeZone?, calendar: Calendar) -> Date {
        guard let timeZone else { return calendar.startOfDay(for: date) }
        var source = calendar
        source.timeZone = timeZone
        let components = source.dateComponents([.year, .month, .day], from: date)
        return calendar.date(from: components) ?? calendar.startOfDay(for: date)
    }

    // MARK: - Token fingerprinting

    #if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
    /// Stable digest of a selection's tokens. Must stay byte-identical to
    /// `FlintSessionShielding.fingerprint(of:)`, which stamps the start record's scope.
    public static func fingerprint(of selection: FamilyActivitySelection) -> String {
        let encoder = JSONEncoder()
        func encoded<T: Encodable>(_ tokens: Set<T>, _ tag: String) -> [String] {
            tokens.map { tag + ((try? encoder.encode($0))?.base64EncodedString() ?? "?") }
        }
        let parts = (encoded(selection.applicationTokens, "a:") + encoded(selection.categoryTokens, "c:")
                     + encoded(selection.webDomainTokens, "w:")
                     + ["entireCategory:\(selection.includeEntireCategory)"]).sorted()
        let digest = SHA256.hash(data: Data(parts.joined(separator: "\n").utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Every shielded surface counts as one protected item in the UI label.
    public static func applicationCount(of selection: FamilyActivitySelection) -> Int {
        selection.applicationTokens.count + selection.categoryTokens.count
            + selection.webDomainTokens.count
    }
    #endif
}
