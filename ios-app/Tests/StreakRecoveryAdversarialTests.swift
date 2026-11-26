import XCTest
import FlintCore

/// Adversarial regression coverage mirroring Android `StreakRecoveryAdversarialTest`.
final class StreakRecoveryAdversarialTests: XCTestCase {
    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/New_York")!
        return c
    }
    private let fingerprint = "v1|test|instagram"
    private func protection(dayOffset: Int = 0) -> StreakRecoveryProtection.BoundProfile {
        let recovery = calendar.date(byAdding: .day, value: dayOffset, to: today)!
        let missed = calendar.date(byAdding: .day, value: dayOffset - 1, to: today)!
        return StreakRecoveryProtection.BoundProfile(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: calendar.startOfDay(for: recovery),
            missedDay: calendar.startOfDay(for: missed),
            frozenAtMs: 0, qualifiedSessionIds: ["b", "resume", "pass-sess"],
            watchGeneration: RecoveryChallengeGuardStore.currentProcessGeneration
        )
    }

    private var today: Date {
        calendar.date(from: DateComponents(year: 2026, month: 10, day: 28))!
    }

    private func at(dayOffset: Int, hour: Int = 9, minute: Int = 0) -> Date {
        let day = calendar.date(byAdding: .day, value: dayOffset, to: today)!
        return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day)!
    }

    private func openStore(now: Date) -> (FlintHistoryStore, () -> Void) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintRecoveryAdv-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var clockNow = calendar.date(byAdding: .day, value: -40, to: now)!
        // The store still requires a live selection that matches the frozen fingerprint; the
        // seam models "the picker has not changed" without needing system tokens.
        let store = FlintHistoryStore(
            directory: directory, clock: { clockNow }, calendar: calendar, isHost: true,
            liveSelectionFingerprint: { self.fingerprint })
        _ = store.read() // stamp historyStart early
        clockNow = now
        return (store, { clockNow = now })
    }

    private func seedCompleted(_ store: FlintHistoryStore, id: String, dayOffset: Int, minutes: Int) {
        let start = at(dayOffset: dayOffset)
        let end = start.addingTimeInterval(TimeInterval(minutes * 60))
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .started, session: id, at: start, timeZone: calendar.timeZone, plannedEnd: end,
            tier: "easy", source: "manual", name: "Focus", grant: fingerprint
        )))
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .completed, session: id, at: end, timeZone: calendar.timeZone)))
        _ = store.read()
    }

    func testForgedCallerSessionsCannotCreateRecovery() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        // No recovery-day completion on disk.
        let result = StreakRecoveryCommitter.tryCommit(store: store, calendar: calendar, protection: protection())
        guard case .rejected = result else {
            return XCTFail("expected rejection, got \(result)")
        }
        let events = store.read().events.filter { $0.type == .streakRecovered }
        XCTAssertEqual(events.count, 0)
    }

    func testLegitimateDurableSessionsAllowRecovery() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        let result = StreakRecoveryCommitter.tryCommit(store: store, calendar: calendar, recoveryId: "ok-1", protection: protection())
        guard case .success = result else {
            return XCTFail("expected success, got \(result)")
        }
        let recovered = store.read().events.filter { $0.type == .streakRecovered }
        XCTAssertEqual(recovered.count, 1)
        XCTAssertTrue(StreakRecoveryPolicy.isWellFormedRecovery(recovered[0]))
    }

    func testExpiredRecoveryRejectedDespiteStaleUiNow() {
        var clockNow = at(dayOffset: 0, hour: 18)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintRecoveryExp-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let early = calendar.date(byAdding: .day, value: -40, to: clockNow)!
        var stamp = early
        let store = FlintHistoryStore(
            directory: directory, clock: { stamp }, calendar: calendar, isHost: true,
            liveSelectionFingerprint: { self.fingerprint })
        _ = store.read()
        stamp = clockNow
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        // Advance authoritative clock past midnight.
        stamp = at(dayOffset: 1, hour: 1)
        let result = StreakRecoveryCommitter.tryCommit(
            store: store,
            now: clockNow, // stale UI claim — ignored
            calendar: calendar,
            protection: protection()
        )
        guard case .rejected = result else {
            return XCTFail("expected rejection after midnight, got \(result)")
        }
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 0)
    }

    func testConcurrentCommitsConsumeAtMostOneToken() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        // Genuinely concurrent: the store's host mutex must admit exactly one durable bridge.
        var results = [StreakRecoveryCommitter.Result?](repeating: nil, count: 4)
        let slots = NSLock()
        DispatchQueue.concurrentPerform(iterations: 4) { idx in
            let result = StreakRecoveryCommitter.tryCommit(
                store: store, calendar: calendar, recoveryId: "race-\(idx)", protection: protection())
            slots.lock(); results[idx] = result; slots.unlock()
        }
        let successes = results.compactMap { $0 }.filter {
            if case .success = $0 { return true }
            return false
        }.count
        XCTAssertEqual(successes, 1)
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 1)
    }

    func testInboxForgedRecoveryDroppedOnDrain() {
        let now = at(dayOffset: 0, hour: 18)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintRecoveryInbox-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var stamp = calendar.date(byAdding: .day, value: -40, to: now)!
        let host = FlintHistoryStore(directory: directory, clock: { stamp }, calendar: calendar, isHost: true)
        _ = host.read()
        stamp = now
        seedCompleted(host, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(host, id: "b", dayOffset: 0, minutes: 40)
        let extensionStore = FlintHistoryStore(
            directory: directory, clock: { stamp }, calendar: calendar, isHost: false)
        let missed = calendar.startOfDay(for: at(dayOffset: -1))
        let forged = FlintHistoryEvent(
            type: .streakRecovered, session: "forged", at: now, id: "forged",
            timeZone: calendar.timeZone, plannedEnd: Date(timeIntervalSince1970: 40 * 60),
            source: fingerprint, name: "Streak recovery", breakEnd: missed,
            reason: StreakRecoveryPolicy.reason, originRoute: StreakRecoveryPolicy.origin,
            grant: "forged", sessions: ["b"],
            recoveryEnd: Date(timeIntervalSince1970: 30 * 60)
        )
        XCTAssertTrue(extensionStore.append(forged))
        // Host drain must drop inbox streak_recovered.
        let snap = host.read()
        XCTAssertEqual(snap.events.filter { $0.type == .streakRecovered }.count, 0)
        XCTAssertTrue(StreakRecoveryPolicy.tokenAvailable(
            events: snap.events, nowMs: FlintHistoryEvent.milliseconds(now)))
    }

    func testProgressUsesNetFocusedTimeNotWallDuration() {
        let start = at(dayOffset: 0, hour: 9)
        let end = start.addingTimeInterval(40 * 60)
        let breakStart = start.addingTimeInterval(10 * 60)
        let breakEnd = breakStart.addingTimeInterval(15 * 60)
        // Wall span 40m with 15m break punched out of intervals → 25m focused.
        let session = FlintHistorySessionSummary(
            id: "b", name: "Focus", startedAt: start, endedAt: end,
            outcome: .completed, inferred: false, route: nil,
            intervals: [
                DateInterval(start: start, end: breakStart),
                DateInterval(start: breakEnd, end: end),
            ]
        )
        let challenge = StreakRecoveryPolicy.Challenge(
            missedDay: at(dayOffset: -1),
            recoveryDay: calendar.startOfDay(for: today),
            baselineMinutes: 30,
            requiredMinutes: 40,
            requiredFocusedMs: 40 * 60_000
        )
        let progress = StreakRecoveryPolicy.focusedMs(on: challenge.recoveryDay, sessions: [session], calendar: calendar)
        XCTAssertEqual(progress, 25 * 60_000)
        XCTAssertFalse(StreakRecoveryPolicy.isSatisfied(sessions: [session], challenge: challenge, calendar: calendar))
        XCTAssertLessThan(progress, 40 * 60_000)
    }

    func testDurableSuccessSurvivesReopen() {
        let now = at(dayOffset: 0, hour: 18)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintRecoveryPersist-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var stamp = calendar.date(byAdding: .day, value: -40, to: now)!
        let store = FlintHistoryStore(
            directory: directory, clock: { stamp }, calendar: calendar, isHost: true,
            liveSelectionFingerprint: { self.fingerprint })
        _ = store.read()
        stamp = now
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        guard case .success = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "persist-1", protection: protection()
        ) else {
            return XCTFail("expected success")
        }
        let reopened = FlintHistoryStore(directory: directory, clock: { stamp }, calendar: calendar, isHost: true)
        let snap = reopened.read()
        XCTAssertEqual(snap.events.filter { $0.type == .streakRecovered }.count, 1)
        let summary = FlintHistoryAggregator.summarize(snap, activeSessionID: nil, now: now, calendar: calendar, dayCount: 7)
        XCTAssertEqual(summary.streak, 3)
        let missed = summary.days.first { calendar.isDate($0.start, inSameDayAs: at(dayOffset: -1)) }
        XCTAssertEqual(missed?.completed, 0)
        XCTAssertEqual(missed?.recovered, true)
    }

    func testMalformedRecoveryDoesNotAffectRecoveredDays() {
        let now = at(dayOffset: 0, hour: 18)
        let bad = FlintHistoryEvent(
            type: .streakRecovered, session: "bad", at: now, id: "bad",
            timeZone: calendar.timeZone, breakEnd: calendar.startOfDay(for: at(dayOffset: -1))
            // missing reason / originRoute
        )
        XCTAssertFalse(StreakRecoveryPolicy.isWellFormedRecovery(bad))
        let recovered = StreakRecoveryPolicy.recoveredDays(
            events: [bad], historyStart: calendar.date(byAdding: .day, value: -40, to: today),
            calendar: calendar)
        XCTAssertTrue(recovered.isEmpty)
        // A malformed row must neither credit the day nor charge the allowance.
        XCTAssertNil(StreakRecoveryPolicy.lastSuccessfulRecoveryAt(events: [bad]))
        XCTAssertTrue(StreakRecoveryPolicy.tokenAvailable(
            events: [bad], nowMs: FlintHistoryEvent.milliseconds(now)))
    }

    /// A user-initiated "clear focus history" must not refund the 30-day allowance.
    func testClearingHistoryDoesNotRefundTheFreeRecovery() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        guard case .success = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "clear-1", protection: protection()
        ) else {
            return XCTFail("expected success")
        }
        XCTAssertFalse(StreakRecoveryPolicy.tokenAvailable(
            events: store.read().events, nowMs: FlintHistoryEvent.milliseconds(now)))

        XCTAssertTrue(store.clear(keepingRunningSession: nil))
        let after = store.read()
        XCTAssertTrue(after.events.contains { $0.type == .streakRecovered })
        XCTAssertFalse("a clear must not refund the allowance", StreakRecoveryPolicy.tokenAvailable(
            events: after.events,
            nowMs: FlintHistoryEvent.milliseconds(now.addingTimeInterval(86_400))))
        let summary = FlintHistoryAggregator.summarize(
            after, activeSessionID: nil, now: now, calendar: calendar)
        XCTAssertEqual(summary.streak, 0)
    }

    /// One unreadable row must fail closed, not silently re-open the free allowance.    func testDegradedHistoryCannotCommitRecovery() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        guard let directory = store.directory else { return XCTFail("expected a directory") }
        let log = directory.appendingPathComponent("events.jsonl")
        if let handle = try? FileHandle(forWritingTo: log) {
            handle.seekToEndOfFile()
            handle.write(Data("{not json\n".utf8))
            try? handle.close()
        }
        XCTAssertEqual(store.read().availability, .degraded)

        let result = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "degraded-1", protection: protection())
        guard case .rejected(let reason) = result else {
            return XCTFail("expected rejection on degraded history, got \(result)")
        }
        XCTAssertEqual(reason, "history_unreadable")
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 0)
    }

    func testCancelledEarlyAttemptDoesNotConsumeTokenThenLaterCompletionRecovers() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        let cancelStart = at(dayOffset: 0, hour: 9)
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .started, session: "cancel", at: cancelStart,
            timeZone: calendar.timeZone,
            plannedEnd: cancelStart.addingTimeInterval(40 * 60),
            tier: "easy", source: "manual", name: "Focus", grant: fingerprint
        )))
        _ = store.read()
        guard case .rejected = StreakRecoveryCommitter.tryCommit(store: store, calendar: calendar, protection: protection()) else {
            return XCTFail("expected rejection before resume")
        }
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 0)
        seedCompleted(store, id: "resume", dayOffset: 0, minutes: 40)
        guard case .success = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "after-cancel", protection: protection()
        ) else {
            return XCTFail("expected success after resume")
        }
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 1)
    }

    func testDuplicateCommitAfterSuccessIsRejected() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        guard case .success = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "once", protection: protection()
        ) else {
            return XCTFail("expected first success")
        }
        guard case .rejected = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "twice", protection: protection()
        ) else {
            return XCTFail("expected duplicate rejection")
        }
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 1)
    }

    func testSelectionGateMatchingFrozenSelectionAllowsStart() {
        let freeze = protection()
        let gate = StreakRecoveryProtection.SelectionGate.evaluate(
            freeze: freeze,
            selectionFingerprint: fingerprint,
            authorizationApproved: true,
            requireFreeze: true
        )
        XCTAssertEqual(gate, .allow)
    }

    func testSelectionGateRejectsSubstitutionMismatchAndMissingFreeze() {
        let freeze = protection()
        let substituted = StreakRecoveryProtection.SelectionGate.evaluate(
            freeze: freeze,
            selectionFingerprint: "v1|substituted|other",
            authorizationApproved: true,
            requireFreeze: true
        )
        guard case .refuse(let reason) = substituted else {
            return XCTFail("expected refuse for substitution")
        }
        XCTAssertEqual(reason, StreakRecoveryProtection.SelectionGate.mismatchedSelection)

        let missing = StreakRecoveryProtection.SelectionGate.evaluate(
            freeze: nil,
            selectionFingerprint: fingerprint,
            authorizationApproved: true,
            requireFreeze: true
        )
        guard case .refuse(let missingReason) = missing else {
            return XCTFail("expected refuse for missing freeze")
        }
        XCTAssertEqual(missingReason, StreakRecoveryProtection.SelectionGate.missingFreeze)
    }

    func testSelectionGateRejectsMismatchedFingerprintAndRevokedAuthorization() {
        var freeze = protection()
        freeze.enforcementBroken = true
        let broken = StreakRecoveryProtection.SelectionGate.evaluate(
            freeze: freeze,
            selectionFingerprint: fingerprint,
            authorizationApproved: true,
            requireFreeze: true
        )
        guard case .refuse(let brokenReason) = broken else {
            return XCTFail("expected refuse for broken enforcement")
        }
        XCTAssertEqual(brokenReason, StreakRecoveryProtection.SelectionGate.enforcementBroken)

        let revoked = StreakRecoveryProtection.SelectionGate.evaluate(
            freeze: protection(),
            selectionFingerprint: fingerprint,
            authorizationApproved: false,
            requireFreeze: true
        )
        guard case .refuse(let revokedReason) = revoked else {
            return XCTFail("expected refuse for revoked authorization")
        }
        XCTAssertEqual(revokedReason, StreakRecoveryProtection.SelectionGate.authorizationRevoked)
    }

    func testCommitAfterSelectionSubstitutionAppendsNoRecovery() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        // Recovery-day session stamped with a substituted fingerprint — must not qualify.
        let start = at(dayOffset: 0)
        let end = start.addingTimeInterval(40 * 60)
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .started, session: "sub", at: start, timeZone: calendar.timeZone, plannedEnd: end,
            tier: "easy", source: "manual", name: "Focus", grant: "v1|substituted|other"
        )))
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .completed, session: "sub", at: end, timeZone: calendar.timeZone)))
        _ = store.read()
        let result = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "sub-1", protection: protection()
        )
        guard case .rejected(let reason) = result else {
            return XCTFail("expected rejection after substitution, got \(result)")
        }
        XCTAssertEqual(reason, "insufficient_duration")
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 0)
    }

    func testEnforcementBrokenLatchRejectsCommitAndSurvivesReread() {
        let suite = "flint.recovery.adv.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = RecoveryChallengeGuardStore(defaults: defaults)
        let bound = store.ensureFrozen(
            fingerprint: fingerprint,
            applicationCount: 1,
            allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: at(dayOffset: -1)),
            frozenAtMs: 0
        )
        XCTAssertNotNil(bound)
        XCTAssertTrue(store.isEnforcementIntact())
        store.markEnforcementBroken()
        XCTAssertFalse(store.isEnforcementIntact())
        XCTAssertTrue(store.current()?.enforcementBroken == true)

        let reopened = RecoveryChallengeGuardStore(defaults: defaults)
        XCTAssertFalse(reopened.isEnforcementIntact())

        let now = at(dayOffset: 0, hour: 18)
        let (history, _) = openStore(now: now)
        seedCompleted(history, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(history, id: "b", dayOffset: 0, minutes: 40)
        var brokenProtection = protection()
        brokenProtection.enforcementBroken = true
        let result = StreakRecoveryCommitter.tryCommit(
            store: history,
            calendar: calendar,
            recoveryId: "broken-1",
            protection: brokenProtection,
            protectionActive: false
        )
        guard case .rejected(let reason) = result else {
            return XCTFail("expected protection_inactive, got \(result)")
        }
        XCTAssertEqual(reason, "protection_inactive")
        XCTAssertEqual(history.read().events.filter { $0.type == .streakRecovered }.count, 0)
    }

    func testProtectionInactiveDespiteMetDurationAppendsNoRecovery() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        let result = StreakRecoveryCommitter.tryCommit(
            store: store,
            calendar: calendar,
            recoveryId: "inactive-1",
            protection: protection(),
            protectionActive: false
        )
        guard case .rejected(let reason) = result else {
            return XCTFail("expected rejection, got \(result)")
        }
        XCTAssertEqual(reason, "protection_inactive")
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 0)
    }

    func testPollutedQualifiedSessionIdsCannotBypassMissingGrant() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        // Recovery-day completion with NO matching grant fingerprint.
        let start = at(dayOffset: 0)
        let end = start.addingTimeInterval(40 * 60)
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .started, session: "polluted", at: start, timeZone: calendar.timeZone, plannedEnd: end,
            tier: "easy", source: "manual", name: "Focus", grant: nil
        )))
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .completed, session: "polluted", at: end, timeZone: calendar.timeZone)))
        _ = store.read()
        var polluted = protection()
        polluted.qualifiedSessionIds = ["polluted"]
        let result = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "polluted-1",
            protection: polluted, protectionActive: true
        )
        guard case .rejected(let reason) = result else {
            return XCTFail("expected rejection for sidecar-only qualify, got \(result)")
        }
        XCTAssertEqual(reason, "insufficient_duration")
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 0)
    }

    func testStructEnforcementBrokenRejectsEvenWhenCallerPassesProtectionActive() {
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openStore(now: now)
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40)
        var broken = protection()
        broken.enforcementBroken = true
        let result = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: "struct-broken",
            protection: broken, protectionActive: true
        )
        guard case .rejected(let reason) = result else {
            return XCTFail("expected protection_inactive, got \(result)")
        }
        XCTAssertEqual(reason, "protection_inactive")
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 0)
    }

    func testCentralSelectionGateAllowsMatchingAndRejectsMismatchRevokeBroken() {
        let suite = "flint.recovery.gate.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let guardStore = RecoveryChallengeGuardStore(defaults: defaults)
        XCTAssertNoThrow(
            try FlintSessionController.enforceRecoverySelectionGate(
                selectionFingerprint: fingerprint,
                authorizationApproved: true,
                guardStore: guardStore,
                calendar: calendar
            ),
            "no freeze → gate is a no-op"
        )
        _ = guardStore.ensureFrozen(
            fingerprint: fingerprint,
            applicationCount: 1,
            allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: at(dayOffset: -1)),
            frozenAtMs: 0
        )
        XCTAssertNoThrow(
            try FlintSessionController.enforceRecoverySelectionGate(
                selectionFingerprint: fingerprint,
                authorizationApproved: true,
                guardStore: guardStore,
                calendar: calendar
            )
        )
        XCTAssertThrowsError(
            try FlintSessionController.enforceRecoverySelectionGate(
                selectionFingerprint: "v1|other",
                authorizationApproved: true,
                guardStore: guardStore,
                calendar: calendar
            )
        ) { error in
            XCTAssertEqual(error as? FlintSessionError, .recoveryProtectionInactive)
        }
        XCTAssertThrowsError(
            try FlintSessionController.enforceRecoverySelectionGate(
                selectionFingerprint: fingerprint,
                authorizationApproved: false,
                guardStore: guardStore,
                calendar: calendar
            )
        ) { error in
            XCTAssertEqual(error as? FlintSessionError, .recoveryProtectionInactive)
        }
        XCTAssertFalse(guardStore.isEnforcementIntact(), "revoke must durable-latch broken")
        // Reconnect auth alone must not clear the latch — gate still refuses.
        XCTAssertThrowsError(
            try FlintSessionController.enforceRecoverySelectionGate(
                selectionFingerprint: fingerprint,
                authorizationApproved: true,
                guardStore: guardStore,
                calendar: calendar
            )
        )
    }

    func testSameDayEnsureFrozenDoesNotClearEnforcementBroken() {
        let suite = "flint.recovery.rebind.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = RecoveryChallengeGuardStore(defaults: defaults)
        _ = store.ensureFrozen(
            fingerprint: fingerprint,
            applicationCount: 1,
            allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: at(dayOffset: -1)),
            frozenAtMs: 0
        )
        store.markEnforcementBroken()
        _ = store.ensureFrozen(
            fingerprint: fingerprint,
            applicationCount: 1,
            allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: at(dayOffset: -1)),
            frozenAtMs: 1
        )
        XCTAssertFalse(store.isEnforcementIntact())
        XCTAssertEqual(
            FlintSessionError.recoveryProtectionInactive.errorDescription?.isEmpty,
            false,
            "new error case must have localized copy for exhaustive UI surfaces"
        )
    }
}
