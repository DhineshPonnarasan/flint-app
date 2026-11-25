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

    private func date(_ calendar: Calendar, day: Int, hour: Int, minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 10, day: day,
                                           hour: hour, minute: minute))!
    }

    private func summarize(_ events: [FlintHistoryEvent], now: Date, calendar: Calendar,
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

    /// A one-hour session completed on `day` in New York, with its zone recorded on both events.
    private func session(day: Int, hour: Int = 22, id: String) -> [FlintHistoryEvent] {
        let start = date(ny, day: day, hour: hour)
        let end = start.addingTimeInterval(3600)
        return session(id: id, start: start, end: end, startZone: ny.timeZone, endZone: ny.timeZone)
    }

    private func session(id: String, start: Date, end: Date,
                         startZone: TimeZone, endZone: TimeZone) -> [FlintHistoryEvent] {
        [
            FlintHistoryEvent(type: .started, session: id, at: start, timeZone: startZone,
                              plannedEnd: end, tier: "easy", source: "manual", name: "Focus"),
            FlintHistoryEvent(type: .completed, session: id, at: end, timeZone: endZone),
        ]
    }

    /// Two New York evenings completed days 26 and 27. Moving the device to Tokyo (where it is
    /// already 29 Oct) must not shift those completions forward and keep a stale streak alive.
    func testDeviceZoneMoveDoesNotExtendAStaleStreak() {
        let events = session(day: 26, id: "a") + session(day: 27, id: "b")
        let result = summarize(events, now: date(tokyo, day: 29, hour: 12), calendar: tokyo)
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 2)
    }

    /// Two consecutive recorded days (27 Oct 22:00 and 28 Oct 01:00 New York) collapse onto one
    /// Tokyo day if the current zone is used. With recorded zones they stay consecutive.
    func testRecordedDaysStayConsecutiveAfterAZoneMove() {
        let events = session(day: 27, hour: 22, id: "c1") + session(day: 28, hour: 1, id: "c2")
        let now = date(ny, day: 28, hour: 12)
        let nySummary = summarize(events, now: now, calendar: ny)
        let tokyoSummary = summarize(events, now: now, calendar: tokyo)
        XCTAssertEqual(nySummary.streak, 2)
        XCTAssertEqual(nySummary.bestRecordedStreak, 2)
        XCTAssertEqual(tokyoSummary.streak, 2)
        XCTAssertEqual(tokyoSummary.bestRecordedStreak, 2)
    }

    /// The same recorded days count the same in either device zone.
    func testSameRecordedDaysCountInEitherZone() {
        let events = session(day: 27, hour: 9, id: "s1") + session(day: 28, hour: 9, id: "s2")
        let now = date(ny, day: 28, hour: 12)
        let nySummary = summarize(events, now: now, calendar: ny)
        let tokyoSummary = summarize(events, now: now, calendar: tokyo)
        XCTAssertEqual(nySummary.streak, 2)
        XCTAssertEqual(tokyoSummary.streak, 2)
        XCTAssertEqual(nySummary.bestRecordedStreak, 2)
        XCTAssertEqual(tokyoSummary.bestRecordedStreak, 2)
    }

    /// A completion made just after midnight in an ahead zone names a recorded day after device
    /// "today". Clamping keeps the streak and the device-day chart in agreement instead of a
    /// false zero from skipping today.
    func testCompletionRecordedAheadOfDeviceZoneCountsToday() {
        let start = date(tokyo, day: 29, hour: 0)
        let end = start.addingTimeInterval(3600)
        let events = session(id: "ahead", start: start, end: end,
                             startZone: tokyo.timeZone, endZone: tokyo.timeZone)
        let result = summarize(events, now: date(ny, day: 28, hour: 12), calendar: ny)
        XCTAssertEqual(result.streak, 1)
        XCTAssertEqual(result.days.last?.completed, 1)
    }

    /// Parity: Kotlin `aheadRecordedDaysKeepBestStreak`; a best streak must not decrease when
    /// the recorded day is ahead of the device day.
    func testAheadRecordedDaysKeepBestStreak() {
        let first = session(id: "a", start: date(tokyo, day: 28, hour: 11),
                            end: date(tokyo, day: 28, hour: 12),
                            startZone: tokyo.timeZone, endZone: tokyo.timeZone)
        let second = session(id: "b", start: date(tokyo, day: 29, hour: 0),
                             end: date(tokyo, day: 29, hour: 1),
                             startZone: tokyo.timeZone, endZone: tokyo.timeZone)
        let result = summarize(first + second, now: date(ny, day: 28, hour: 20), calendar: ny)
        XCTAssertEqual(result.streak, 2)
        XCTAssertEqual(result.bestRecordedStreak, 2)
        XCTAssertEqual(result.days.last?.completed, 1)
    }

    /// Parity: Kotlin `gapInRecordedDaysDoesNotInventCurrentStreak`.
    func testGapInRecordedDaysDoesNotInventCurrentStreak() {
        let first = session(id: "a", start: date(tokyo, day: 27, hour: 22),
                            end: date(tokyo, day: 27, hour: 23),
                            startZone: tokyo.timeZone, endZone: tokyo.timeZone)
        let second = session(id: "b", start: date(tokyo, day: 29, hour: 0),
                             end: date(tokyo, day: 29, hour: 0, minute: 30),
                             startZone: tokyo.timeZone, endZone: tokyo.timeZone)
        let result = summarize(first + second, now: date(ny, day: 28, hour: 20), calendar: ny)
        XCTAssertEqual(result.streak, 1)
        XCTAssertEqual(result.bestRecordedStreak, 1)
    }

    /// Parity: Kotlin `farFutureRecordedDayDoesNotSuppressYesterdayStreak`. A corrupt or
    /// previously-fast terminal two days ahead must not consume the yesterday grace day.
    func testFarFutureRecordedDayDoesNotSuppressYesterdayStreak() {
        let yesterday = session(day: 27, id: "a")
        let corrupt = session(id: "b", start: date(ny, day: 27, hour: 22),
                              end: date(ny, day: 30, hour: 0, minute: 30),
                              startZone: ny.timeZone, endZone: ny.timeZone)
        let result = summarize(yesterday + corrupt, now: date(ny, day: 28, hour: 12), calendar: ny)
        XCTAssertEqual(result.streak, 1)
        XCTAssertEqual(result.bestRecordedStreak, 1)
    }

    func testUnavailableHistoryNeverClaimsAStreak() {
        let events = session(day: 27, id: "a")
        let result = summarize(events, now: date(ny, day: 28, hour: 12),
                               calendar: ny, availability: .unavailable)
        // Salvaged events may still aggregate, but nothing unreadable is ever presented.
        XCTAssertFalse(StreakPresentationPolicy.showsSheet(for: result))
        XCTAssertFalse(StreakPresentationPolicy.showsCard(for: result))
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: result), "Focus streak")
        XCTAssertNil(result.days.last?.focusedSeconds)
    }

    func testEmptyHistoryIsReadableAndShowsAZeroStreak() {
        let result = summarize([], now: date(ny, day: 28, hour: 12),
                               calendar: ny, availability: .empty)
        XCTAssertEqual(result.streak, 0)
        XCTAssertTrue(StreakPresentationPolicy.showsCard(for: result))
        XCTAssertTrue(StreakPresentationPolicy.showsSheet(for: result))
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: result),
                       "No active focus streak")
    }
}
