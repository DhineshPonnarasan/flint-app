import Foundation

/// Domain owner for conditional free streak recovery (policy v1).
///
/// Pure date/event functions: UI and session code must never invent eligibility, progress or an
/// allowance. Everything here is derived from durable history, so a relaunch, a device-zone
/// change or a stale UI timestamp cannot change the answer.
public enum StreakRecoveryPolicy {
    public static let policyVersion = "v1"
    public static let wireType = "streak_recovered"
    public static let reason = "policy:v1"
    public static let origin = "streak_recovery"
    /// One successful free recovery per rolling 30 × 24 hours.
    public static let cooldownMs: Int64 = 30 * 24 * 60 * 60 * 1000
    public static let defaultBaselineMinutes = 30

    public struct Challenge: Equatable, Sendable {
        public var missedDay: Date
        public var recoveryDay: Date
        public var baselineMinutes: Int
        public var requiredMinutes: Int
        public var requiredFocusedMs: Int64

        public init(missedDay: Date, recoveryDay: Date, baselineMinutes: Int,
                    requiredMinutes: Int, requiredFocusedMs: Int64) {
            self.missedDay = missedDay
            self.recoveryDay = recoveryDay
            self.baselineMinutes = baselineMinutes
            self.requiredMinutes = requiredMinutes
            self.requiredFocusedMs = requiredFocusedMs
        }
    }

    public enum Eligibility: Equatable, Sendable {
        case offer(Challenge)
        case unavailable(reason: String)
    }

    public static func roundToFive(_ value: Double) -> Int {
        Int((value / 5.0).rounded()) * 5
    }

    public static func extraMinutes(baselineMinutes: Int) -> Int {
        let raw = roundToFive(0.30 * Double(max(0, baselineMinutes)))
        return min(30, max(10, raw))
    }

    public static func requiredMinutes(baselineMinutes: Int) -> Int {
        let baseline = max(1, baselineMinutes)
        return baseline + extraMinutes(baselineMinutes: baseline)
    }

    public static func requiredFocusedMs(baselineMinutes: Int) -> Int64 {
        Int64(requiredMinutes(baselineMinutes: baselineMinutes)) * 60_000
    }

    // MARK: - Durable record shape

    /// Only complete, policy-shaped recovery records may bridge a day or consume the allowance.
    ///
    /// A row is trusted only when every field the policy depends on is present and correct. A
    /// truncated or hand-written row therefore cannot credit a day, and a replay of a partial
    /// write cannot spend the token.
    public static func isWellFormedRecovery(_ event: FlintHistoryEvent) -> Bool {
        guard event.type == .streakRecovered || event.type.wireName == wireType else { return false }
        guard !event.id.isEmpty, !event.session.isEmpty else { return false }
        guard event.breakEnd != nil, event.plannedEnd != nil, event.recoveryEnd != nil else {
            return false
        }
        guard let sessions = event.sessions, !sessions.isEmpty,
              sessions.allSatisfy({ !$0.isEmpty }) else { return false }
        guard let grant = event.grant, !grant.isEmpty else { return false }
        guard let source = event.source, !source.isEmpty else { return false }
        guard event.reason == reason, event.originRoute == origin else { return false }
        return true
    }

    /// Missed days bridged by a well-formed recovery, bucketed with the zone recorded on the
    /// recovery row — not the device's current zone.
    public static func recoveredDays(events: [FlintHistoryEvent], historyStart: Date?,
                                     calendar: Calendar) -> Set<Date> {
        guard let historyStart else { return [] }
        return Set(events.compactMap { event -> Date? in
            guard isWellFormedRecovery(event), let breakEnd = event.breakEnd else { return nil }
            let day = StreakRecoveryProtection.dayAnchor(
                for: FlintHistoryEvent.date(breakEnd),
                timeZone: TimeZone(identifier: event.tz),
                calendar: calendar)
            return day >= historyStart ? day : nil
        })
    }

    /// Completed days bucketed with each session's recorded terminal zone.
    public static func completedDays(sessions: [FlintHistorySessionSummary],
                                     calendar: Calendar) -> Set<Date> {
        Set(sessions.filter { $0.outcome == .completed }.map {
            StreakRecoveryProtection.dayAnchor(for: $0.endedAt,
                                              timeZone: $0.recordedTimeZone,
                                              calendar: calendar)
        })
    }

    public static func streakDays(completed: Set<Date>, recovered: Set<Date>) -> Set<Date> {
        completed.union(recovered)
    }

    // MARK: - Allowance

    public static func lastSuccessfulRecoveryAt(events: [FlintHistoryEvent]) -> Int64? {
        events.filter(isWellFormedRecovery).map(\.at).max()
    }

    /// Last credited recovery, combining the durable rows with the authenticated ledger floor.
    ///
    /// The ledger carries the allowance once retention, a clear or an over-budget repair has
    /// removed the row that proved it. `max` guarantees the allowance is never earlier than
    /// either source proves.
    public static func lastRecoveryFloor(events: [FlintHistoryEvent],
                                         recoveryFloorAt: Int64? = nil) -> Int64? {
        let eventsFloor = lastSuccessfulRecoveryAt(events: events)
        let combined = max(eventsFloor ?? Int64.min, recoveryFloorAt ?? Int64.min)
        return combined == Int64.min ? nil : combined
    }

    /// The allowance derives from the last valid durable recovery — never a separate counter.
    public static func tokenAvailable(events: [FlintHistoryEvent], nowMs: Int64,
                                      recoveryFloorAt: Int64? = nil) -> Bool {
        guard let last = lastRecoveryFloor(events: events, recoveryFloorAt: recoveryFloorAt) else {
            return true
        }
        return nowMs - last >= cooldownMs
    }

    /// Exact instant the free recovery allowance returns: last success + 30 × 24h wall clock.
    public static func nextTokenAvailableAt(events: [FlintHistoryEvent],
                                            recoveryFloorAt: Int64? = nil) -> Int64? {
        guard let last = lastRecoveryFloor(events: events, recoveryFloorAt: recoveryFloorAt) else {
            return nil
        }
        let next = last &+ cooldownMs
        return next >= last ? next : nil
    }

    // MARK: - Challenge shape

    /// Baseline = longest completed session wall span (minutes) on the prior streak day, bucketed
    /// by each session's recorded terminal zone (never the device's current zone).
    public static func baselineMinutes(for day: Date, sessions: [FlintHistorySessionSummary],
                                       calendar: Calendar,
                                       fallback: Int = defaultBaselineMinutes) -> Int {
        let lengths = sessions.filter { isOnRecordedDay($0, day: day, calendar: calendar) }
            .map { max(1, Int(($0.endedAt.timeIntervalSince1970 - $0.startedAt.timeIntervalSince1970) / 60)) }
        return lengths.max() ?? fallback
    }

    /// Progress uses net focused time (breaks and passes removed), matching Insights.
    public static func focusedMs(on day: Date, sessions: [FlintHistorySessionSummary],
                                 calendar: Calendar) -> Int64 {
        sessions.filter { isOnRecordedDay($0, day: day, calendar: calendar) }
            .reduce(Int64(0)) { partial, session in
                partial + Int64(max(0, session.focusedSeconds) * 1000)
            }
    }

    /// Membership test shared by baseline, progress and contributor selection. `day` is an anchor
    /// in `calendar`'s zone; the session is placed by its own recorded terminal zone.
    static func isOnRecordedDay(_ session: FlintHistorySessionSummary, day: Date,
                                calendar: Calendar) -> Bool {
        guard session.outcome == .completed else { return false }
        return StreakRecoveryProtection.dayAnchor(for: session.endedAt,
                                                  timeZone: session.recordedTimeZone,
                                                  calendar: calendar) == day
    }

    public static func evaluate(sessions: [FlintHistorySessionSummary],
                                events: [FlintHistoryEvent],
                                now: Date,
                                calendar: Calendar,
                                historyStart: Date?,
                                readable: Bool,
                                recoveryFloorAt: Int64? = nil,
                                allowanceTrusted: Bool = true) -> Eligibility {
        // An allowance whose evidence cannot be authenticated is never offered, even when the
        // history itself is readable. The commit path enforces the same condition.
        guard allowanceTrusted else { return .unavailable(reason: "allowance_unreadable") }
        guard readable else { return .unavailable(reason: "history_unreadable") }
        guard let historyStart else { return .unavailable(reason: "before_history_start") }
        let today = calendar.startOfDay(for: now)
        guard let missed = calendar.date(byAdding: .day, value: -1, to: today) else {
            return .unavailable(reason: "calendar_error")
        }
        if missed < historyStart { return .unavailable(reason: "before_history_start") }
        let completed = completedDays(sessions: sessions, calendar: calendar)
        let recovered = recoveredDays(events: events, historyStart: historyStart, calendar: calendar)
        if recovered.contains(missed) { return .unavailable(reason: "already_recovered") }
        if completed.contains(missed) { return .unavailable(reason: "no_missed_day") }
        guard let prior = calendar.date(byAdding: .day, value: -1, to: missed) else {
            return .unavailable(reason: "calendar_error")
        }
        let bearing = streakDays(completed: completed, recovered: recovered)
        // Recovery needs a streak to bridge to; an idle history has nothing to preserve.
        if prior < historyStart || !bearing.contains(prior) {
            return .unavailable(reason: "no_prior_streak")
        }
        let nowMs = FlintHistoryEvent.milliseconds(now)
        if !tokenAvailable(events: events, nowMs: nowMs, recoveryFloorAt: recoveryFloorAt) {
            return .unavailable(reason: "token_cooldown")
        }
        let baseline = baselineMinutes(for: prior, sessions: sessions, calendar: calendar)
        let required = requiredMinutes(baselineMinutes: baseline)
        return .offer(Challenge(
            missedDay: missed,
            recoveryDay: today,
            baselineMinutes: baseline,
            requiredMinutes: required,
            requiredFocusedMs: Int64(required) * 60_000
        ))
    }

    public static func isSatisfied(sessions: [FlintHistorySessionSummary], challenge: Challenge,
                                   calendar: Calendar) -> Bool {
        focusedMs(on: challenge.recoveryDay, sessions: sessions, calendar: calendar)
            >= challenge.requiredFocusedMs
    }

    /// Grant-bound satisfaction: only focus earned under the frozen selection counts.
    public static func isSatisfied(
        sessions: [FlintHistorySessionSummary],
        challenge: Challenge,
        calendar: Calendar,
        events: [FlintHistoryEvent],
        fingerprint: String,
        extraQualifiedIds: Set<String> = []
    ) -> Bool {
        StreakRecoveryProtection.focusedMs(
            on: challenge.recoveryDay,
            sessions: sessions,
            calendar: calendar,
            events: events,
            fingerprint: fingerprint,
            extraQualifiedIds: extraQualifiedIds
        ) >= challenge.requiredFocusedMs
    }

    // MARK: - Durable record

    /// Builds the bridge row. `source` is the frozen selection fingerprint (the credit evidence);
    /// `sessions` names the contributors so the bridge is auditable without fabricating a
    /// completed session.
    public static func buildRecoveryEvent(challenge: Challenge,
                                          sessions: [FlintHistorySessionSummary],
                                          now: Date,
                                          calendar: Calendar,
                                          timeZone: TimeZone,
                                          recoveryId: String = UUID().uuidString,
                                          events: [FlintHistoryEvent] = [],
                                          fingerprint: String? = nil,
                                          extraQualifiedIds: Set<String> = []) -> FlintHistoryEvent {
        let contributors: [String]
        if let fingerprint, !fingerprint.isEmpty {
            let qualified = StreakRecoveryProtection.qualifyingSessionIds(
                events: events, fingerprint: fingerprint, extra: extraQualifiedIds)
            contributors = sessions.filter {
                qualified.contains($0.id) && isOnRecordedDay($0, day: challenge.recoveryDay, calendar: calendar)
            }.map(\.id)
        } else {
            contributors = sessions.filter {
                isOnRecordedDay($0, day: challenge.recoveryDay, calendar: calendar)
            }.map(\.id)
        }
        // Record the missed **civil day in the bridge's own zone**, matching Android. Encoding a
        // device-zone midnight and then reading it back in the recorded zone can shift the day by
        // one whenever the two zones differ (travel, DST edge), which would bridge the wrong day.
        var bridgeCalendar = calendar
        bridgeCalendar.timeZone = timeZone
        let missedComponents = calendar.dateComponents([.year, .month, .day], from: challenge.missedDay)
        let missedInBridgeZone = bridgeCalendar.date(from: missedComponents) ?? challenge.missedDay
        return FlintHistoryEvent(
            type: .streakRecovered,
            session: recoveryId,
            at: now,
            id: recoveryId,
            timeZone: timeZone,
            plannedEnd: Date(timeIntervalSince1970: TimeInterval(challenge.requiredFocusedMs) / 1000),
            source: fingerprint,
            name: "Streak recovery",
            breakEnd: missedInBridgeZone,
            reason: reason,
            originRoute: origin,
            grant: recoveryId,
            sessions: contributors,
            recoveryEnd: Date(timeIntervalSince1970: TimeInterval(challenge.baselineMinutes * 60)),
            timeConfidence: .certain
        )
    }
}
