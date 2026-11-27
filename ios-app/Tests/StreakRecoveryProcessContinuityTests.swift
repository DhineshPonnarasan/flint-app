import XCTest
import FlintCore

/// Process-generation continuity for iOS streak recovery.
///
/// Mirrors Android `RecoveryContinuityProcessDeathTest`. A freeze bound by one process must not
/// be restored as healthy by another process: Screen Time authorization / ManagedSettings
/// shields may have been interrupted while Flint was terminated, which no in-process latch can
/// observe. Continuity is proven only by the durable `watchGeneration` stamped into the freeze.
///
/// A distinct injected `processGeneration` models a fresh app process deterministically; no
/// FamilyControls runtime or physical device is required for these pure-store tests.
final class StreakRecoveryProcessContinuityTests: XCTestCase {
    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/New_York")!
        return c
    }
    private let fingerprint = "v1|test|instagram"
    /// The simulator test runner has no Keychain entitlement; these tests exercise recovery
    /// continuity, not the ledger's Keychain binding.
    private let ledgerAuthenticator = HMACRecoveryLedgerAuthenticator(
        key: Data(repeating: 0x0B, count: 32))
    private var today: Date {
        calendar.date(from: DateComponents(year: 2026, month: 10, day: 28))!
    }
    private func day(_ offset: Int) -> Date {
        calendar.date(byAdding: .day, value: offset, to: today)!
    }

    private func defaults() -> UserDefaults {
        let suite = "flint.recovery.cont.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        d.removePersistentDomain(forName: suite)
        return d
    }

    private func bind(_ store: RecoveryChallengeGuardStore) {
        _ = store.ensureFrozen(
            fingerprint: fingerprint,
            applicationCount: 1,
            allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: day(-1)),
            frozenAtMs: 0
        )
    }

    /// Normal: the process that bound the freeze sees it as intact.
    func testSameProcessChallengeStaysIntact() {
        let d = defaults()
        let p1 = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P1")
        bind(p1)
        XCTAssertTrue(p1.isEnforcementIntact())
        // A second guard instance in the SAME process shares continuity (iOS creates guards
        // at several call sites) — it must not fail a live, uninterrupted challenge.
        let sameProcess = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P1")
        XCTAssertTrue(sameProcess.isEnforcementIntact())
    }

    /// Path A (Screen Time authorization) healthy on restart, but continuity unproven.
    func testRestartWhileAuthorizationHealthyFailsClosed() {
        let d = defaults()
        let p1 = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P1")
        bind(p1)
        let p2 = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P2")
        XCTAssertFalse(
            p2.isEnforcementIntact(),
            "restart must not be treated as proof of continuity even when authorization is approved"
        )
        // A third read still fails closed; the freeze is not silently rehabilitated.
        XCTAssertFalse(RecoveryChallengeGuardStore(defaults: d, processGeneration: "P3").isEnforcementIntact())
        // Prior process state was not mutated.
        XCTAssertTrue(p1.isEnforcementIntact())
    }

    /// Authorization revoked/restored while stopped: the restart gate still fails closed,
    /// and the start gate (Shortcuts / Focus Filter) durably latches and refuses.
    func testRevokedWhileStoppedThenRestoredFailsClosedAtStartGate() {
        let d = defaults()
        let p1 = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P1")
        bind(p1)
        let p2 = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P2")
        XCTAssertFalse(p2.isEnforcementIntact())
        XCTAssertThrowsError(
            try FlintSessionController.enforceRecoverySelectionGate(
                selectionFingerprint: fingerprint,
                authorizationApproved: true, // restored before restart
                guardStore: p2,
                calendar: calendar
            )
        ) { XCTAssertEqual($0 as? FlintSessionError, .recoveryProtectionInactive) }
        XCTAssertTrue(p2.current()?.enforcementBroken == true, "failure must durably latch")
        // Reconnect authorization again — still refuses; same-day rebind cannot clear it.
        XCTAssertFalse(p2.isEnforcementIntact())
        _ = p2.ensureFrozen(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: day(-1)), frozenAtMs: 1
        )
        XCTAssertTrue(p2.current()?.enforcementBroken == true)
    }

    /// Legacy freeze with no generation stamp cannot prove continuity — fail closed.
    func testLegacyFreezeWithoutGenerationFailsClosed() {
        let d = defaults()
        let legacy = StreakRecoveryProtection.BoundProfile(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: today, missedDay: day(-1), frozenAtMs: 0,
            enforcementBroken: false, watchGeneration: nil
        )
        if let data = try? JSONEncoder().encode(legacy) { d.set(data, forKey: "flint.streakRecovery.challenge.v1") }
        let store = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P2")
        XCTAssertFalse(store.isEnforcementIntact())
    }

    /// The authoritative commit gate rejects a restored freeze even when a caller supplies
    /// protectionActive=true (alternate entry point / stale UI state).
    func testCommitRejectsRestoredGenerationDespiteStaleProtectionActive() {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintCommitCont-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var clockNow = calendar.date(byAdding: .day, value: -40, to: today)!
        let store = FlintHistoryStore(
            directory: directory, clock: { clockNow }, calendar: calendar, isHost: true)
        _ = store.read() // stamp historyStart
        clockNow = calendar.date(byAdding: .hour, value: 18, to: today)!
        func seed(_ id: String, _ offset: Int, _ minutes: Int) {
            let start = calendar.date(byAdding: .day, value: offset, to: today)!
            let end = start.addingTimeInterval(TimeInterval(minutes * 60))
            XCTAssertTrue(store.append(FlintHistoryEvent(
                type: .started, session: id, at: start, timeZone: calendar.timeZone, plannedEnd: end,
                tier: "easy", source: "manual", name: "Focus", grant: fingerprint)))
            XCTAssertTrue(store.append(FlintHistoryEvent(
                type: .completed, session: id, at: end, timeZone: calendar.timeZone)))
        }
        seed("a", -2, 30)
        seed("b", 0, 40)

        // A freeze bound by another process generation, passed straight to the committer with
        // a stale protectionActive=true — the store's generation check must reject it.
        var stale = StreakRecoveryProtection.BoundProfile(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: day(-1)),
            frozenAtMs: 0, watchGeneration: "OTHER-PROCESS"
        )
        stale.qualifiedSessionIds = ["b"]
        let result = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, protection: stale, protectionActive: true)
        guard case .rejected(let reason) = result else {
            return XCTFail("expected rejection, got \(result)")
        }
        XCTAssertEqual(reason, "protection_inactive")
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 0)
    }

    /// A legacy sidecar (nil generation) must be rejected at the authoritative commit gate even
    /// with protectionActive=true. This exercises the real commit path, not just isEnforcementIntact.
    func testLegacyNilGenerationCannotProduceRecoveryEvent() {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintLegacyCommit-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var clockNow = calendar.date(byAdding: .day, value: -40, to: today)!
        let store = FlintHistoryStore(
            directory: directory, clock: { clockNow }, calendar: calendar, isHost: true)
        _ = store.read()
        clockNow = calendar.date(byAdding: .hour, value: 18, to: today)!
        func seed(_ id: String, _ offset: Int, _ minutes: Int) {
            let start = calendar.date(byAdding: .day, value: offset, to: today)!
            let end = start.addingTimeInterval(TimeInterval(minutes * 60))
            XCTAssertTrue(store.append(FlintHistoryEvent(
                type: .started, session: id, at: start, timeZone: calendar.timeZone, plannedEnd: end,
                tier: "easy", source: "manual", name: "Focus", grant: fingerprint)))
            XCTAssertTrue(store.append(FlintHistoryEvent(
                type: .completed, session: id, at: end, timeZone: calendar.timeZone)))
        }
        seed("a", -2, 30)
        seed("b", 0, 40)

        // Exactly a legacy freeze: valid profile, full duration met, but no generation stamp.
        let legacy = StreakRecoveryProtection.BoundProfile(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: day(-1)),
            frozenAtMs: 0, qualifiedSessionIds: ["b"], watchGeneration: nil
        )
        let result = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, protection: legacy, protectionActive: true)
        guard case .rejected(let reason) = result else {
            return XCTFail("legacy sidecar must be rejected at commit, got \(result)")
        }
        XCTAssertEqual(reason, "protection_inactive")
        XCTAssertEqual(
            store.read().events.filter { $0.type == .streakRecovered }.count, 0,
            "a legacy sidecar must never produce a durable recovery event"
        )
    }

    /// Control: a valid same-process generation with full duration still commits.
    func testValidSameProcessGenerationStillCommits() {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintValidCommit-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var clockNow = calendar.date(byAdding: .day, value: -40, to: today)!
        let store = FlintHistoryStore(
            directory: directory, clock: { clockNow }, calendar: calendar, isHost: true,
            ledgerAuthenticator: ledgerAuthenticator,
            liveSelectionFingerprint: { self.fingerprint })
        _ = store.read()
        clockNow = calendar.date(byAdding: .hour, value: 18, to: today)!
        func seed(_ id: String, _ offset: Int, _ minutes: Int) {
            let start = calendar.date(byAdding: .day, value: offset, to: today)!
            let end = start.addingTimeInterval(TimeInterval(minutes * 60))
            XCTAssertTrue(store.append(FlintHistoryEvent(
                type: .started, session: id, at: start, timeZone: calendar.timeZone, plannedEnd: end,
                tier: "easy", source: "manual", name: "Focus", grant: fingerprint)))
            XCTAssertTrue(store.append(FlintHistoryEvent(
                type: .completed, session: id, at: end, timeZone: calendar.timeZone)))
        }
        seed("a", -2, 30)
        seed("b", 0, 40)
        let valid = StreakRecoveryProtection.BoundProfile(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: calendar.startOfDay(for: today),
            missedDay: calendar.startOfDay(for: day(-1)),
            frozenAtMs: 0, qualifiedSessionIds: ["b"],
            watchGeneration: RecoveryChallengeGuardStore.currentProcessGeneration
        )
        let result = StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, protection: valid, protectionActive: true)
        guard case .success = result else {
            return XCTFail("valid same-process challenge must still commit, got \(result)")
        }
        XCTAssertEqual(store.read().events.filter { $0.type == .streakRecovered }.count, 1)
    }

    /// An existing durable broken state survives restart and reconnection.
    func testDurableBrokenLatchSurvivesRestart() {
        let d = defaults()
        let p1 = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P1")
        bind(p1)
        p1.markEnforcementBroken()
        let p2 = RecoveryChallengeGuardStore(defaults: d, processGeneration: "P2")
        XCTAssertTrue(p2.current()?.enforcementBroken == true)
        XCTAssertFalse(p2.isEnforcementIntact())
        // Reconnection / restart cannot clear it.
        XCTAssertThrowsError(
            try FlintSessionController.enforceRecoverySelectionGate(
                selectionFingerprint: fingerprint, authorizationApproved: true,
                guardStore: p2, calendar: calendar
            )
        )
    }

    /// A time-zone change within the same civil day must keep the durable enforcement latch.
    func testTimeZoneChangeOnTheSameCivilDayKeepsTheBrokenLatch() throws {
        let d = defaults()
        let guardStore = RecoveryChallengeGuardStore(defaults: d)
        var losAngeles = Calendar(identifier: .gregorian)
        losAngeles.timeZone = TimeZone(identifier: "America/Los_Angeles")!

        let noonNewYork = calendar.date(byAdding: .hour, value: 12, to: today)!
        let newYorkDay = calendar.startOfDay(for: noonNewYork)
        let losAngelesDay = losAngeles.startOfDay(for: noonNewYork)
        XCTAssertNotEqual(newYorkDay, losAngelesDay, "a zone change moves the start-of-day instant")
        XCTAssertTrue(calendar.isDate(newYorkDay, inSameDayAs: losAngelesDay))

        let bound = guardStore.ensureFrozen(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: newYorkDay, missedDay: newYorkDay.addingTimeInterval(-86_400),
            frozenAtMs: 0, calendar: calendar)
        XCTAssertNotNil(bound)
        guardStore.markEnforcementBroken()
        XCTAssertFalse(guardStore.isEnforcementIntact())

        // The device moves west while the civil day is unchanged; the card rebinds.
        let rebound = guardStore.ensureFrozen(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: losAngelesDay, missedDay: losAngelesDay.addingTimeInterval(-86_400),
            frozenAtMs: 0, calendar: losAngeles)
        XCTAssertEqual(rebound?.enforcementBroken, true,
                       "the same civil day must keep the existing freeze")
        XCTAssertEqual(rebound?.recoveryDay, newYorkDay,
                       "the original freeze must be reused rather than re-bound")
        XCTAssertFalse(guardStore.isEnforcementIntact())

        // The authoritative commit path must still refuse the broken challenge.
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintTzLatch-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let store = FlintHistoryStore(
            directory: directory, clock: { noonNewYork }, calendar: calendar, isHost: true,
            ledgerAuthenticator: ledgerAuthenticator,
            liveSelectionFingerprint: { self.fingerprint })
        XCTAssertEqual(
            StreakRecoveryCommitter.tryCommit(
                store: store, calendar: calendar, protection: rebound, protectionActive: true),
            .rejected(reason: "protection_inactive"))
    }

    /// A genuinely different civil day still binds a fresh, unbroken challenge.
    func testANewCivilDayBindsAFreshUnbrokenChallenge() throws {
        let d = defaults()
        let guardStore = RecoveryChallengeGuardStore(defaults: d)
        let noonNewYork = calendar.date(byAdding: .hour, value: 12, to: today)!
        let day = calendar.startOfDay(for: noonNewYork)
        let bound = guardStore.ensureFrozen(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: day, missedDay: day.addingTimeInterval(-86_400),
            frozenAtMs: 0, calendar: calendar)
        guardStore.markEnforcementBroken()

        let nextDay = calendar.date(byAdding: .day, value: 1, to: day)!
        let fresh = guardStore.ensureFrozen(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: nextDay, missedDay: day,
            frozenAtMs: 0, calendar: calendar)
        XCTAssertNotEqual(fresh?.recoveryDay, bound?.recoveryDay, "a new day must rebind")
        XCTAssertEqual(fresh?.recoveryDay, nextDay)
        XCTAssertEqual(fresh?.enforcementBroken, false)
        XCTAssertEqual(fresh?.watchGeneration,
                       RecoveryChallengeGuardStore.currentProcessGeneration)
        XCTAssertTrue(guardStore.isEnforcementIntact())
    }

    /// A profile persisted before `recoveryDayKey` existed decodes with a nil key and falls back
    /// to the calendar comparison instead of failing to decode or dropping the latch.
    func testLegacyProfileWithoutADayKeyDecodesAndKeepsTheSameDay() throws {
        let suite = "flint.recovery.legacy-key.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let day = calendar.startOfDay(for: today)
        let legacy = StreakRecoveryProtection.BoundProfile(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: day, missedDay: day.addingTimeInterval(-86_400),
            frozenAtMs: 0, qualifiedSessionIds: [], enforcementBroken: true,
            watchGeneration: RecoveryChallengeGuardStore.currentProcessGeneration)

        let encoded = try JSONEncoder().encode(legacy)
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        object.removeValue(forKey: "recoveryDayKey")
        let withoutKey = try JSONSerialization.data(withJSONObject: object)
        defaults.set(withoutKey, forKey: RecoveryChallengeGuardStore.storageKey)

        let store = RecoveryChallengeGuardStore(defaults: defaults)
        let decoded = try XCTUnwrap(store.current())
        XCTAssertNil(decoded.recoveryDayKey)
        let rebound = store.ensureFrozen(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: day, missedDay: day.addingTimeInterval(-86_400),
            frozenAtMs: 0, calendar: calendar)
        XCTAssertEqual(rebound?.enforcementBroken, true,
                       "a legacy profile must be reused, not replaced")
    }

    /// The recorded day key is Gregorian, so an alternate device calendar cannot change it.
    func testAlternateCalendarOnTheSameDayKeepsTheBrokenLatch() throws {
        let d = defaults()
        let guardStore = RecoveryChallengeGuardStore(defaults: d)
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = calendar.timeZone
        let day = calendar.startOfDay(for: today)

        let bound = guardStore.ensureFrozen(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: day, missedDay: day.addingTimeInterval(-86_400),
            frozenAtMs: 0, calendar: buddhist)
        XCTAssertNotNil(bound)
        guardStore.markEnforcementBroken()

        let rebound = guardStore.ensureFrozen(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: day, missedDay: day.addingTimeInterval(-86_400),
            frozenAtMs: 0, calendar: calendar)
        XCTAssertEqual(rebound?.recoveryDay, day)
        XCTAssertEqual(rebound?.enforcementBroken, true)
        XCTAssertFalse(guardStore.isEnforcementIntact())
    }
}
