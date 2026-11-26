import XCTest
import FlintCore
@testable import Flint

/// The recovery card must never offer a challenge when the allowance's evidence cannot be
/// authenticated. The commit path re-checks the same flag (see `RecoveryLedgerTests`).
final class RecoveryAllowancePresentationTests: XCTestCase {
    private let calendar: Calendar = {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "America/New_York")!
        return value
    }()

    private func emptyValueSummary() -> FlintHistorySummary {
        FlintHistorySummary(availability: .value, sessions: [], days: [], streak: 0,
                            bestRecordedStreak: 0)
    }

    func testUnverifiableAllowanceHidesTheOffer() {
        let now = calendar.date(
            from: DateComponents(year: 2026, month: 10, day: 28, hour: 18))!
        let historyStart = calendar.date(byAdding: .day, value: -10, to: now)!

        let untrusted = StreakRecoveryPresentation.ui(
            summary: emptyValueSummary(), events: [], now: now, calendar: calendar,
            historyStart: historyStart, recoveryFloorAt: nil, allowanceTrusted: false,
            protection: nil, enforcementIntact: true)
        XCTAssertEqual(untrusted, .hidden)

        // Control: identical inputs with a trusted allowance surface the streak requirement,
        // proving the card is not merely hidden for an unrelated reason.
        let trusted = StreakRecoveryPresentation.ui(
            summary: emptyValueSummary(), events: [], now: now, calendar: calendar,
            historyStart: historyStart, recoveryFloorAt: nil, allowanceTrusted: true,
            protection: nil, enforcementIntact: true)
        XCTAssertNotEqual(trusted, .hidden)
    }
}
