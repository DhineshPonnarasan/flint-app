import Foundation
import FlintCore

/// App-owned presentation for conditional free streak recovery (policy v1).
///
/// Pure mapping from durable history + the bound challenge to copy. Eligibility, progress and
/// allowance all come from `StreakRecoveryPolicy`; nothing here invents a rule.
enum StreakRecoveryPresentation {
    enum Ui: Equatable {
        case hidden
        case unavailable(message: String)
        case offer(Offer)
    }

    struct Offer: Equatable {
        let missedLabel: String
        let requiredMinutes: Int
        let progressMinutes: Int
        let expiresLabel: String
        let statusLine: String
        let protectedAppsLabel: String
        let accessibilityLabel: String
        /// Duration met while enforcement is intact. The commit still re-derives this under the
        /// host lock; this only decides whether asking is worth a write.
        let satisfied: Bool
    }

    static let title = "Streak recovery"
    static let tokenFree = "1 free recovery every 30 days"
    static let success = "Yesterday is recovered. Your streak continues."
    /// Shown while duration is met but durable commit has not yet succeeded.
    static let confirming = "Challenge met — confirming recovery…"
    static let footnote =
        "Complete the recovery challenge today to bridge yesterday. No payment or ads."
    static let protectionLocked =
        "These apps stay locked for the recovery challenge. Changing your selection elsewhere will not change this freeze."
    static let protectionDegraded =
        "Protection is off. Recovery credit pauses until shields can enforce again. Reopening Flint after an interruption also ends this challenge."
    static let emptyProtection =
        "Choose apps in your Screen Time selection before recovering. Recovery shields that established list — not a new substitute."

    static func required(_ minutes: Int) -> String {
        "Focus \(minutes) minutes today to recover yesterday"
    }

    static func protectedApps(count: Int, allowList: Bool) -> String {
        "\(allowList ? "Allow-list" : "Block-list") locked: \(count) apps"
    }

    static func ui(
        summary: FlintHistorySummary?,
        events: [FlintHistoryEvent],
        now: Date,
        calendar: Calendar,
        historyStart: Date?,
        recoveryFloorAt: Int64? = nil,
        allowanceTrusted: Bool = true,
        protection: StreakRecoveryProtection.BoundProfile? = nil,
        enforcementIntact: Bool = true
    ) -> Ui {
        // An unverifiable allowance is never presented as an offer.
        guard let summary, summary.availability != .unavailable, allowanceTrusted else { return .hidden }
        let eligibility = StreakRecoveryPolicy.evaluate(
            sessions: summary.sessions,
            events: events,
            now: now,
            calendar: calendar,
            historyStart: historyStart,
            // Degraded or unavailable history cannot prove the allowance is unused.
            readable: summary.availability == .value,
            recoveryFloorAt: recoveryFloorAt,
            allowanceTrusted: allowanceTrusted
        )
        let dateFormatter = DateFormatter()
        dateFormatter.calendar = calendar
        dateFormatter.timeZone = calendar.timeZone
        dateFormatter.dateStyle = .medium
        dateFormatter.timeStyle = .none
        let dateTimeFormatter = DateFormatter()
        dateTimeFormatter.calendar = calendar
        dateTimeFormatter.timeZone = calendar.timeZone
        dateTimeFormatter.dateStyle = .medium
        dateTimeFormatter.timeStyle = .short

        switch eligibility {
        case .unavailable(let reason):
            switch reason {
            case "token_cooldown":
                if let next = StreakRecoveryPolicy.nextTokenAvailableAt(
                    events: events, recoveryFloorAt: recoveryFloorAt) {
                    let label = dateTimeFormatter.string(
                        from: Date(timeIntervalSince1970: TimeInterval(next) / 1000))
                    return .unavailable(message: "Next free recovery available around \(label)")
                }
                return .unavailable(message:
                    "Your free recovery is cooling down. It returns 30 days after the last success.")
            case "no_prior_streak":
                return .unavailable(message:
                    "Recovery needs an active streak before the missed day.")
            default:
                return .hidden
            }

        case .offer(let challenge):
            guard let protection, !protection.isEmpty,
                  calendar.isDate(protection.recoveryDay, inSameDayAs: challenge.recoveryDay) else {
                return .unavailable(message: emptyProtection)
            }
            // Grant-only progress: sidecar qualifiedSessionIds cannot inflate the UI.
            let progressMs = StreakRecoveryProtection.focusedMs(
                on: challenge.recoveryDay,
                sessions: summary.sessions,
                calendar: calendar,
                events: events,
                fingerprint: protection.fingerprint,
                extraQualifiedIds: []
            )
            let progressMinutes = Int(progressMs / 60_000)
            let durationMet = progressMs >= challenge.requiredFocusedMs
            let status: String
            if !enforcementIntact {
                status = protectionDegraded
            } else if durationMet {
                status = confirming
            } else {
                status = "\(progressMinutes) of \(challenge.requiredMinutes) minutes completed today"
            }
            let missed = dateFormatter.string(from: challenge.missedDay)
            let recoveryDay = dateFormatter.string(from: challenge.recoveryDay)
            let apps = protectedApps(count: protection.applicationCount,
                                     allowList: protection.allowListMode)
            var label = "\(title). Missed \(missed). \(required(challenge.requiredMinutes)). "
                + "\(apps). \(tokenFree)"
            if !enforcementIntact { label += ". \(protectionDegraded)" }
            return .offer(Offer(
                missedLabel: missed,
                requiredMinutes: challenge.requiredMinutes,
                progressMinutes: min(progressMinutes, challenge.requiredMinutes),
                expiresLabel: "Expires at the end of \(recoveryDay)",
                statusLine: status,
                protectedAppsLabel: apps,
                accessibilityLabel: label,
                satisfied: durationMet && enforcementIntact
            ))
        }
    }
}
