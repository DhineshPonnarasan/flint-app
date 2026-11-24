import XCTest
import FlintCore
@testable import Flint

/// Regressions for the two review findings: unreadable history is never a zero streak, and
/// committed streak days are bucketed by the zone recorded on the terminal event.
final class FlintStreakReviewFixTests: XCTestCase {
    private var ny: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        return calendar
    }

    private var tokyo: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        return calendar
    }

    private func nyDate(day: Int, hour: Int, minute: Int = 0) -> Date {
        ny.date(from: DateComponents(year: 2026, month: 10, day: day, hour: hour, minute: minute))!
    }

    private func summary(events: [FlintHistoryEvent], now: Date, calendar: Calendar,
                         availability: FlintHistoryAvailability = .value) -> FlintHistorySummary {
        FlintHistoryAggregator.summarize(
            .init(events: events,
                  historyStart: calendar.date(byAdding: .day, value: -30, to: now),
                  availability: availability),
            activeSessionID: nil,
            now: now,
            calendar: calendar,
            dayCount: 7)
    }

    private func session(day: Int, session: String) -> [FlintHistoryEvent] {
        let start = nyDate(day: day, hour: 22, minute: 0)
        return [
            FlintHistoryEvent(type: .started, session: session, at: start,
                              plannedEnd: start.addingTimeInterval(3600),
                              timeZone: ny.timeZone),
            FlintHistoryEvent(type: .completed, session: session,
                              at: nyDate(day: day, hour: 23, minute: 30),
                              timeZone: ny.timeZone),
        ]
    }

    /// A session committed at 23:30 New York is 28 Oct in Tokyo by wall clock. Bucketing by the
    /// current zone would credit 28 Oct and extend a stale streak after the device moves zones.
    func testCommittedDaysSurviveADeviceTimeZoneChange() {
        let events = session(day: 26, session: "a") + session(day: 27, session: "b")
        // Today is 29 Oct in Tokyo, so the last committed day (27 Oct NY) is two days back.
        let now = tokyo.date(from: DateComponents(year: 2026, month: 10, day: 29, hour: 12))!
        let result = summary(events: events, now: now, calendar: tokyo)
        XCTAssertEqual(result.streak, 0, "A time-zone move must not extend a stale streak")
    }

    func testSameCommittedDaysStillCountInTheOriginalZone() {
        let events = session(day: 26, session: "a") + session(day: 27, session: "b")
        let now = nyDate(day: 28, hour: 12)
        let result = summary(events: events, now: now, calendar: ny)
        XCTAssertEqual(result.streak, 2)
    }

    func testUnavailableHistoryNeverClaimsAStreak() {
        let events = session(day: 27, session: "a")
        let result = summary(events: events, now: nyDate(day: 28, hour: 12),
                             calendar: ny, availability: .unavailable)
        XCTAssertFalse(StreakPresentationPolicy.showsSheet(for: result))
        XCTAssertFalse(StreakPresentationPolicy.showsCard(for: result))
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: result), "Focus streak")
    }

    func testReadableEmptyHistoryIsALegitimateZero() {
        let result = summary(events: [], now: nyDate(day: 28, hour: 12),
                             calendar: ny, availability: .empty)
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: result),
                       "No active focus streak")
    }
}
