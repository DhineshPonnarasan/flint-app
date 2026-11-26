import XCTest
import FlintCore

/// Deterministic coverage for the authenticated recovery-allowance ledger (policy v1).
///
/// Mirrors the verified Android `RecoveryLedgerIntegrityTest`: a valid signed ledger survives a
/// store recreation, edits/replays of the payload are rejected, corruption can never refund the
/// 30 × 24 h allowance, a failed tag write or repair keeps recovery unavailable, and neither a
/// clear nor retention loss of the proving row can reopen the allowance.
final class RecoveryLedgerTests: XCTestCase {
    private let calendar: Calendar = {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "America/New_York")!
        return value
    }()
    private let key = Data(repeating: 0x5A, count: 32)
    private let fingerprint = "v1|test|ledger"
    private let dayMs: Int64 = 24 * 60 * 60 * 1000
    private let ledgerName = "recovery-ledger.v1.json"

    private var authenticator: RecoveryLedgerAuthenticator {
        HMACRecoveryLedgerAuthenticator(key: key)
    }

    /// A signing outage: the tag can never be produced.
    private struct UnavailableAuthentication: RecoveryLedgerAuthenticator {
        func tag(for payload: String) -> String? { nil }
        func verify(payload: String, tag: String) -> Bool { false }
    }

    /// Signs with one key and verifies with another, so a write never reads back.
    private struct BrokenSigner: RecoveryLedgerAuthenticator {
        func tag(for payload: String) -> String? {
            HMACRecoveryLedgerAuthenticator(key: Data(repeating: 1, count: 32)).tag(for: payload)
        }
        func verify(payload: String, tag: String) -> Bool {
            HMACRecoveryLedgerAuthenticator(key: Data(repeating: 2, count: 32))
                .verify(payload: payload, tag: tag)
        }
    }

    private final class ClockBox {
        var now: Date
        init(_ now: Date) { self.now = now }
    }

    // MARK: - Fixtures

    private var today: Date {
        calendar.date(from: DateComponents(year: 2026, month: 10, day: 28))!
    }

    private func at(dayOffset: Int, hour: Int = 9) -> Date {
        let day = calendar.date(byAdding: .day, value: dayOffset, to: today)!
        return calendar.date(bySettingHour: hour, minute: 0, second: 0, of: day)!
    }

    private func tempDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("FlintLedger-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func ledgerURL(_ directory: URL) -> URL {
        directory.appendingPathComponent(ledgerName)
    }

    private func makeStore(_ directory: URL, _ clock: @escaping () -> Date,
                           _ auth: RecoveryLedgerAuthenticator? = nil) -> FlintHistoryStore {
        FlintHistoryStore(
            directory: directory, clock: clock, calendar: calendar, isHost: true,
            ledgerAuthenticator: auth ?? authenticator,
            liveSelectionFingerprint: { self.fingerprint })
    }

    private func protection(dayOffset: Int = 0) -> StreakRecoveryProtection.BoundProfile {
        let recovery = calendar.date(byAdding: .day, value: dayOffset, to: today)!
        let missed = calendar.date(byAdding: .day, value: dayOffset - 1, to: today)!
        return StreakRecoveryProtection.BoundProfile(
            fingerprint: fingerprint, applicationCount: 1, allowListMode: false,
            recoveryDay: calendar.startOfDay(for: recovery),
            missedDay: calendar.startOfDay(for: missed),
            frozenAtMs: 0, qualifiedSessionIds: [],
            watchGeneration: RecoveryChallengeGuardStore.currentProcessGeneration)
    }

    private func seedCompleted(_ store: FlintHistoryStore, id: String, dayOffset: Int, minutes: Int,
                               zone: TimeZone? = nil, grant: String? = nil) {
        let start = at(dayOffset: dayOffset)
        let end = start.addingTimeInterval(TimeInterval(minutes * 60))
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .started, session: id, at: start, timeZone: zone ?? calendar.timeZone,
            plannedEnd: end, tier: "easy", source: "manual", name: "Focus", grant: grant)))
        XCTAssertTrue(store.append(FlintHistoryEvent(
            type: .completed, session: id, at: end, timeZone: zone ?? calendar.timeZone)))
    }

    /// Prior-day 30m completion plus the recovery-day 40m grant-bound completion.
    private func openSeeded(_ directory: URL, now: Date,
                            _ auth: RecoveryLedgerAuthenticator? = nil) -> (FlintHistoryStore, ClockBox) {
        let box = ClockBox(calendar.date(byAdding: .day, value: -40, to: now)!)
        let store = makeStore(directory, { box.now }, auth)
        _ = store.read() // stamps historyStart before the seeded days
        box.now = now
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40, grant: fingerprint)
        _ = store.read()
        return (store, box)
    }

    private func commit(_ store: FlintHistoryStore, _ id: String) -> StreakRecoveryCommitter.Result {
        StreakRecoveryCommitter.tryCommit(
            store: store, calendar: calendar, recoveryId: id, protection: protection())
    }

    private func forgedRecovery(_ now: Date, id: String = "forged") -> FlintHistoryEvent {
        FlintHistoryEvent(
            type: .streakRecovered, session: id, at: now, id: id,
            timeZone: calendar.timeZone,
            plannedEnd: Date(timeIntervalSince1970: 40 * 60),
            source: fingerprint, name: "Streak recovery",
            breakEnd: calendar.startOfDay(for: calendar.date(byAdding: .day, value: -1, to: now)!),
            reason: StreakRecoveryPolicy.reason, originRoute: StreakRecoveryPolicy.origin,
            grant: id, sessions: ["b"],
            recoveryEnd: Date(timeIntervalSince1970: 30 * 60))
    }

    // MARK: - Ledger file helpers

    private func validTag(_ at: Int64) -> String {
        authenticator.tag(for: RecoveryLedgerFormat.payload(at))!
    }

    private func writeLedger(_ directory: URL, lastRecoveryAt: Int64, tag: String?) {
        var json = "{\"v\":1,\"lastRecoveryAt\":\(lastRecoveryAt)"
        if let tag { json += ",\"tag\":\"\(tag)\"" }
        json += "}"
        try? Data(json.utf8).write(to: ledgerURL(directory))
    }

    private func writeLedger(_ directory: URL, raw: String) {
        try? Data(raw.utf8).write(to: ledgerURL(directory))
    }

    /// Simulates the retention/hard-cap trim that can evict the row proving the allowance.
    private func removeRecoveryRows(_ directory: URL) {
        let log = directory.appendingPathComponent("events.jsonl")
        guard let data = try? Data(contentsOf: log) else { return }
        let kept = String(decoding: data, as: UTF8.self).split(separator: "\n")
            .filter { !$0.contains("streak_recovered") }
            .map { String($0) + "\n" }
        try? Data(kept.joined().utf8).write(to: log)
    }

    private func recoveredIDs(_ store: FlintHistoryStore) -> [String] {
        store.read().events.filter { $0.type == .streakRecovered }.map(\.id)
    }

    private func assertCooldownHeld(_ snapshot: FlintHistorySnapshot, nowMs: Int64,
                                    _ message: String = "") {
        XCTAssertTrue(snapshot.allowanceTrusted, message)
        XCTAssertNotNil(snapshot.recoveryFloorAt, message)
        XCTAssertFalse(
            StreakRecoveryPolicy.tokenAvailable(
                events: snapshot.events, nowMs: nowMs, recoveryFloorAt: snapshot.recoveryFloorAt),
            message)
    }

    // MARK: - Authenticated persistence

    func testSignedLedgerSurvivesStoreRecreationAndHoldsTheCooldown() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "ledger-1") else {
            return XCTFail("expected a durable recovery")
        }
        let t0 = FlintHistoryEvent.milliseconds(now)
        XCTAssertEqual(box.now, now)

        let wire = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(contentsOf: ledgerURL(directory))) as? [String: Any])
        XCTAssertEqual(wire["v"] as? Int, 1)
        XCTAssertEqual((wire["lastRecoveryAt"] as? NSNumber)?.int64Value, t0)
        XCTAssertEqual((wire["tag"] as? String)?.count, 64)

        let reopened = makeStore(directory, { box.now })
        let snapshot = reopened.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, t0)
        assertCooldownHeld(snapshot, nowMs: t0 + dayMs)
        XCTAssertEqual(
            StreakRecoveryPolicy.nextTokenAvailableAt(
                events: snapshot.events, recoveryFloorAt: snapshot.recoveryFloorAt),
            t0 + StreakRecoveryPolicy.cooldownMs)
    }

    func testModifiedTimestampWithTheOldTagIsRejectedAndRepairedFromTheRows() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)
        guard case .success = commit(store, "edit-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)

        // One hour later, same tag: the payload no longer matches.
        writeLedger(directory, lastRecoveryAt: t0 + 3_600_000, tag: validTag(t0))
        let snapshot = store.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, t0)
        assertCooldownHeld(snapshot, nowMs: t0 + dayMs)

        // The repair is a real authenticated file, not just an in-memory answer.
        let reopened = makeStore(directory, { now.addingTimeInterval(3_600) })
        XCTAssertEqual(reopened.read().recoveryFloorAt, t0)
        XCTAssertTrue(reopened.read().allowanceTrusted)
    }

    func testMissingTagCannotRefundAfterTheRowsAreGone() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "tagless-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        removeRecoveryRows(directory)
        writeLedger(directory, lastRecoveryAt: t0, tag: nil)

        // Detection two days later: the unauthenticated value is refused, not trusted.
        box.now = now.addingTimeInterval(2 * 86_400)
        let snapshot = store.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, FlintHistoryEvent.milliseconds(box.now))
        assertCooldownHeld(snapshot, nowMs: FlintHistoryEvent.milliseconds(box.now) + dayMs)
        XCTAssertNotEqual(snapshot.recoveryFloorAt, t0)
    }

    func testMalformedLedgerCannotRefund() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "malformed-1") else { return XCTFail("expected success") }
        removeRecoveryRows(directory)
        writeLedger(directory, raw: "{not json")
        box.now = now.addingTimeInterval(2 * 86_400)

        let snapshot = store.read()
        assertCooldownHeld(snapshot, nowMs: FlintHistoryEvent.milliseconds(box.now) + dayMs)
        XCTAssertNotNil(snapshot.recoveryFloorAt)
    }

    func testUnsupportedVersionCannotRefund() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "version-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        removeRecoveryRows(directory)
        writeLedger(directory, raw: "{\"v\":2,\"lastRecoveryAt\":\(t0),\"tag\":\"\(validTag(t0))\"}")
        box.now = now.addingTimeInterval(2 * 86_400)

        let snapshot = store.read()
        assertCooldownHeld(snapshot, nowMs: FlintHistoryEvent.milliseconds(box.now) + dayMs)
        XCTAssertNotEqual(snapshot.recoveryFloorAt, t0)
    }

    func testFutureTimestampBeyondTheSkewBoundIsRejectedEvenWhenTagged() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)
        guard case .success = commit(store, "future-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        let future = t0 + RecoveryLedgerFormat.maxFutureSkewMs + 60_000
        writeLedger(directory, lastRecoveryAt: future, tag: validTag(future))

        let snapshot = store.read()
        // Repaired from the durable row, never from the implausible future value.
        XCTAssertEqual(snapshot.recoveryFloorAt, t0)
        assertCooldownHeld(snapshot, nowMs: t0 + dayMs)
    }

    // MARK: - Fail-closed repair

    func testFailedTaggingKeepsRecoveryUnavailableAndPreservesEvidence() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "outage-1") else { return XCTFail("expected success") }
        removeRecoveryRows(directory)
        let corrupt = "{\"v\":1,\"lastRecoveryAt\":0,\"tag\":\"00\"}"
        writeLedger(directory, raw: corrupt)
        let evidence = try Data(contentsOf: ledgerURL(directory))

        let outage = makeStore(directory, { box.now }, UnavailableAuthentication())
        let snapshot = outage.read()
        XCTAssertFalse(snapshot.allowanceTrusted)
        XCTAssertEqual(outage.read().availability, .value, "history itself is intact")
        let preserved = try Data(contentsOf: ledgerURL(directory))
        XCTAssertTrue(preserved == evidence, "a refused repair must not destroy the evidence")

        guard case .rejected(let reason) = commit(outage, "outage-2") else {
            return XCTFail("recovery must fail closed while the allowance is unverifiable")
        }
        XCTAssertEqual(reason, "allowance_unreadable")
        XCTAssertFalse(outage.clear(keepingRunningSession: nil))
        XCTAssertEqual(recoveredIDs(outage), [])
    }

    func testBrokenSignerCannotSealTheRepair() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "broken-1") else { return XCTFail("expected success") }
        removeRecoveryRows(directory)
        writeLedger(directory, raw: "{not json")

        let broken = makeStore(directory, { box.now }, BrokenSigner())
        let snapshot = broken.read()
        XCTAssertFalse(snapshot.allowanceTrusted)
        guard case .rejected = commit(broken, "broken-2") else {
            return XCTFail("a tag that cannot be verified must not authorize recovery")
        }
        XCTAssertFalse(broken.clear(keepingRunningSession: nil))
    }

    func testUnwritableRepairCannotRefundOrClear() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "writable-1") else { return XCTFail("expected success") }
        removeRecoveryRows(directory)
        // A directory at the ledger path: readable evidence is gone and every write fails.
        try FileManager.default.removeItem(at: ledgerURL(directory))
        try FileManager.default.createDirectory(at: ledgerURL(directory), withIntermediateDirectories: true)

        let snapshot = store.read()
        XCTAssertFalse(snapshot.allowanceTrusted)
        XCTAssertFalse(store.clear(keepingRunningSession: nil))
        guard case .rejected = commit(store, "writable-2") else {
            return XCTFail("recovery must stay unavailable when no repair can be written")
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: ledgerURL(directory).path))
        XCTAssertEqual(box.now, now)
    }

    // MARK: - Clear and retention

    func testClearCannotRefundAfterTheProvingRowIsGone() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)
        guard case .success = commit(store, "clear-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        removeRecoveryRows(directory)

        XCTAssertTrue(store.clear(keepingRunningSession: nil))
        let after = store.read()
        XCTAssertEqual(after.recoveryFloorAt, t0)
        assertCooldownHeld(after, nowMs: t0 + dayMs)
        // The clear emptied the log; only the authenticated ledger can still prove the floor.
        XCTAssertEqual(after.availability, .empty)
    }

    func testRetentionLossOfTheRecoveryRowCannotRefund() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)
        guard case .success = commit(store, "retain-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        removeRecoveryRows(directory)

        let snapshot = store.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, t0)
        assertCooldownHeld(snapshot, nowMs: t0 + dayMs)
        XCTAssertEqual(recoveredIDs(store), [], "the row is gone; only the ledger holds the floor")
    }

    func testLegacyUnsignedLedgerCannotRefund() throws {
        // Rows gone: the unsigned value must not be trusted, so detection time wins.
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "legacy-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        removeRecoveryRows(directory)
        writeLedger(directory, lastRecoveryAt: t0, tag: nil)
        box.now = now.addingTimeInterval(40 * 86_400)

        let snapshot = store.read()
        XCTAssertTrue(snapshot.allowanceTrusted)
        XCTAssertNotEqual(snapshot.recoveryFloorAt, t0)
        assertCooldownHeld(snapshot, nowMs: FlintHistoryEvent.milliseconds(box.now))

        // With the durable rows still present, the real floor is preserved instead.
        let preserved = tempDirectory()
        let (second, _) = openSeeded(preserved, now: now)
        guard case .success = commit(second, "legacy-2") else { return XCTFail("expected success") }
        writeLedger(preserved, lastRecoveryAt: t0, tag: nil)
        XCTAssertEqual(second.read().recoveryFloorAt, t0)
        assertCooldownHeld(second.read(), nowMs: t0 + dayMs)
    }

    func testExactCooldownBoundaryAfterAuthenticatedPersistence() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)
        guard case .success = commit(store, "boundary-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        removeRecoveryRows(directory)

        let reopened = makeStore(directory, { now.addingTimeInterval(600) })
        let snapshot = reopened.read()
        let floor = try XCTUnwrap(snapshot.recoveryFloorAt)
        let cooldown = StreakRecoveryPolicy.cooldownMs
        XCTAssertEqual(floor, t0)
        XCTAssertFalse(StreakRecoveryPolicy.tokenAvailable(
            events: snapshot.events, nowMs: floor + cooldown - 1, recoveryFloorAt: floor))
        XCTAssertTrue(StreakRecoveryPolicy.tokenAvailable(
            events: snapshot.events, nowMs: floor + cooldown, recoveryFloorAt: floor))
        XCTAssertTrue(StreakRecoveryPolicy.tokenAvailable(
            events: snapshot.events, nowMs: floor + cooldown + 1, recoveryFloorAt: floor))
        XCTAssertEqual(StreakRecoveryPolicy.nextTokenAvailableAt(
            events: snapshot.events, recoveryFloorAt: floor), floor + cooldown)
    }

    // MARK: - Missing ledger and durable initialization

    /// Models durable key material with no ledger file: the installation marker.
    private struct MarkerAuthentication: RecoveryLedgerAuthenticator {
        let status: RecoveryLedgerKeyStatus
        private let base: HMACRecoveryLedgerAuthenticator

        init(status: RecoveryLedgerKeyStatus, key: Data) {
            self.status = status
            self.base = HMACRecoveryLedgerAuthenticator(key: key)
        }

        func tag(for payload: String) -> String? { base.tag(for: payload) }
        func verify(payload: String, tag: String) -> Bool {
            base.verify(payload: payload, tag: tag)
        }
        var ledgerKeyStatus: RecoveryLedgerKeyStatus { status }
    }

    /// Key availability that can be interrupted and restored within one test, without sleeps.
    private final class ToggleAuthentication: RecoveryLedgerAuthenticator {
        var available = true
        private let base: HMACRecoveryLedgerAuthenticator

        init(key: Data) { self.base = HMACRecoveryLedgerAuthenticator(key: key) }

        func tag(for payload: String) -> String? {
            available ? base.tag(for: payload) : nil
        }
        func verify(payload: String, tag: String) -> Bool {
            available && base.verify(payload: payload, tag: tag)
        }
        var ledgerKeyStatus: RecoveryLedgerKeyStatus {
            available ? .present : .unknown
        }
    }

    /// History that is eligible for recovery but has never produced a ledger (no recovery yet).
    private func eligibleWithoutLedger(_ directory: URL, now: Date) -> (FlintHistoryStore, ClockBox) {
        let fixture = openSeeded(directory, now: now)
        XCTAssertFalse(FileManager.default.fileExists(atPath: ledgerURL(directory).path))
        return fixture
    }

    func testFreshInstallWithNoLedgerAndNoRecoveryRemainsEligible() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = eligibleWithoutLedger(directory, now: now)

        let fresh = store.read()
        XCTAssertNil(fresh.recoveryFloorAt, "nothing proves a prior recovery")
        XCTAssertTrue(fresh.allowanceTrusted)
        XCTAssertTrue(StreakRecoveryPolicy.tokenAvailable(
            events: fresh.events, nowMs: FlintHistoryEvent.milliseconds(now)))
        guard case .success = commit(store, "fresh-1") else {
            return XCTFail("a genuinely new installation must be eligible")
        }
        XCTAssertEqual(store.read().recoveryFloorAt, FlintHistoryEvent.milliseconds(now))
    }

    func testLegacyInstallationWithoutALedgerReconstructsTheCooldownFromTheRow() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)
        guard case .success = commit(store, "legacy-row-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        // A pre-ledger build kept only the durable row.
        try FileManager.default.removeItem(at: ledgerURL(directory))

        let snapshot = store.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, t0)
        XCTAssertTrue(snapshot.allowanceTrusted)
        XCTAssertTrue(FileManager.default.fileExists(atPath: ledgerURL(directory).path),
                      "the migration re-seals the floor the row still proves")
        assertCooldownHeld(snapshot, nowMs: t0 + dayMs)
    }

    /// The confirmed refund path: the row is pruned and the ledger file is deleted, but this
    /// directory's Keychain identity still proves a ledger was sealed here.
    func testMissingLedgerWithAnExistingKeychainMarkerFailsClosed() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let marked = makeStore(directory, { box.now },
                               MarkerAuthentication(status: .present, key: key))

        let snapshot = marked.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, FlintHistoryEvent.milliseconds(now),
                       "the missing ledger is consumed from detection time")
        XCTAssertTrue(snapshot.allowanceTrusted)
        assertCooldownHeld(snapshot, nowMs: FlintHistoryEvent.milliseconds(now) + dayMs)
        guard case .rejected(let reason) = commit(marked, "marker-1") else {
            return XCTFail("a deleted ledger must not read as an unused allowance")
        }
        XCTAssertEqual(reason, "token_cooldown")
        XCTAssertTrue(FileManager.default.fileExists(atPath: ledgerURL(directory).path),
                      "the repair is durable")
    }

    func testMissingLedgerWithAnExistingFallbackKeyFailsClosed() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        // What a Keychain-outage installation leaves behind: the per-install key file only.
        let fileKey = FileKeyRecoveryLedgerAuthenticator(
            fileURL: directory.appendingPathComponent("recovery-ledger-key.v1"))
        _ = fileKey.tag(for: "warm-up")
        XCTAssertEqual(fileKey.ledgerKeyStatus, .present)

        let marked = makeStore(directory, { box.now }, fileKey)
        let snapshot = marked.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, FlintHistoryEvent.milliseconds(now))
        assertCooldownHeld(snapshot, nowMs: FlintHistoryEvent.milliseconds(now) + dayMs)
        guard case .rejected(let reason) = commit(marked, "filekey-1") else {
            return XCTFail("the fallback key must also prove prior initialization")
        }
        XCTAssertEqual(reason, "token_cooldown")
    }

    /// Documented boundary: with the row, the ledger *and* all key material gone, the app cannot
    /// distinguish this from a new installation. This test pins that honest limitation.
    func testMissingLedgerAfterAllLocalEvidenceIsGoneIsIndistinguishableFromFresh() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let noMarker = makeStore(directory, { box.now },
                                 MarkerAuthentication(status: .absent, key: key))

        let snapshot = noMarker.read()
        XCTAssertNil(snapshot.recoveryFloorAt)
        XCTAssertTrue(snapshot.allowanceTrusted)
        guard case .success = commit(noMarker, "all-gone-1") else {
            return XCTFail("no durable evidence remains: this is indistinguishable from fresh")
        }
    }

    /// `.unknown` is "temporarily unverifiable", not "unused": hide recovery and write nothing.
    func testMissingLedgerWithUnknownKeyMaterialHidesRecovery() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let unknown = makeStore(directory, { box.now },
                                MarkerAuthentication(status: .unknown, key: key))

        let snapshot = unknown.read()
        XCTAssertNil(snapshot.recoveryFloorAt, "no cooldown timestamp may be fabricated")
        XCTAssertFalse(snapshot.allowanceTrusted)
        XCTAssertFalse(FileManager.default.fileExists(atPath: ledgerURL(directory).path),
                       "an unverifiable allowance must not be sealed")
        XCTAssertFalse(unknown.read().allowanceTrusted)
        XCTAssertNil(unknown.read().recoveryFloorAt)
    }

    func testMissingLedgerWithUnknownKeyMaterialRejectsTheCommit() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let unknown = makeStore(directory, { box.now },
                                MarkerAuthentication(status: .unknown, key: key))
        XCTAssertFalse(unknown.read().allowanceTrusted)

        guard case .rejected(let reason) = commit(unknown, "unknown-commit") else {
            return XCTFail("an unverifiable allowance must not authorize recovery")
        }
        XCTAssertEqual(reason, "allowance_unreadable")
        XCTAssertEqual(recoveredIDs(unknown), [])
        XCTAssertFalse(unknown.clear(keepingRunningSession: nil),
                       "clear must not treat an unverifiable allowance as unused")
        XCTAssertFalse(FileManager.default.fileExists(atPath: ledgerURL(directory).path))
    }

    func testUnknownKeyMaterialResolvingToAbsentInitializesNormally() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let unknown = makeStore(directory, { box.now },
                                MarkerAuthentication(status: .unknown, key: key))
        XCTAssertFalse(unknown.read().allowanceTrusted)

        let resolved = makeStore(directory, { box.now },
                                 MarkerAuthentication(status: .absent, key: key))
        let snapshot = resolved.read()
        XCTAssertNil(snapshot.recoveryFloorAt)
        XCTAssertTrue(snapshot.allowanceTrusted)
        guard case .success = commit(resolved, "resolved-absent") else {
            return XCTFail("an absent identity is a genuine new initialization")
        }
        XCTAssertEqual(resolved.read().recoveryFloorAt, FlintHistoryEvent.milliseconds(now))
    }

    func testUnknownKeyMaterialResolvingToPresentRepairsAtDetection() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let unknown = makeStore(directory, { box.now },
                                MarkerAuthentication(status: .unknown, key: key))
        XCTAssertFalse(unknown.read().allowanceTrusted)

        let resolved = makeStore(directory, { box.now },
                                 MarkerAuthentication(status: .present, key: key))
        let snapshot = resolved.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, FlintHistoryEvent.milliseconds(now))
        XCTAssertTrue(snapshot.allowanceTrusted)
        assertCooldownHeld(snapshot, nowMs: FlintHistoryEvent.milliseconds(now) + dayMs)
        guard case .rejected(let reason) = commit(resolved, "resolved-present") else {
            return XCTFail("the repaired cooldown must be enforced")
        }
        XCTAssertEqual(reason, "token_cooldown")
    }

    func testUnknownKeyMaterialAcrossRestartStaysUnavailable() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let unknown = makeStore(directory, { box.now },
                                MarkerAuthentication(status: .unknown, key: key))
        XCTAssertFalse(unknown.read().allowanceTrusted)

        let restarted = makeStore(directory, { box.now.addingTimeInterval(120) },
                                  MarkerAuthentication(status: .unknown, key: key))
        let after = restarted.read()
        XCTAssertFalse(after.allowanceTrusted)
        XCTAssertNil(after.recoveryFloorAt)
        XCTAssertFalse(FileManager.default.fileExists(atPath: ledgerURL(directory).path))
        guard case .rejected = commit(restarted, "restart-unknown") else {
            return XCTFail("an unresolved identity must keep failing closed")
        }
    }

    /// The policy result, the card, and the commit gate must give the same answer.
    func testPolicyAndCommitAgreeWhenTheAllowanceIsUnverifiable() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let unknown = makeStore(directory, { box.now },
                                MarkerAuthentication(status: .unknown, key: key))

        let snapshot = unknown.read()
        let sessions = FlintHistoryAggregator.sessionSummaries(
            snapshot.events, activeSessionID: nil, now: now)
        let eligibility = StreakRecoveryPolicy.evaluate(
            sessions: sessions, events: snapshot.events, now: now, calendar: calendar,
            historyStart: snapshot.historyStart, readable: snapshot.availability == .value,
            recoveryFloorAt: snapshot.recoveryFloorAt,
            allowanceTrusted: snapshot.allowanceTrusted)
        XCTAssertEqual(eligibility, .unavailable(reason: "allowance_unreadable"))
        guard case .rejected(let reason) = commit(unknown, "agree-1") else {
            return XCTFail("the policy and the commit gate must agree")
        }
        XCTAssertEqual(reason, "allowance_unreadable")
    }

    func testTemporaryKeyUnavailabilityDoesNotResetAnEstablishedCooldown() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let auth = ToggleAuthentication(key: key)
        let (store, box) = openSeeded(directory, now: now, auth)
        guard case .success = commit(store, "toggle-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        let sealed = try Data(contentsOf: ledgerURL(directory))

        auth.available = false
        let outage = makeStore(directory, { box.now }, auth)
        let during = outage.read()
        XCTAssertFalse(during.allowanceTrusted, "an unreadable tag must fail closed")
        XCTAssertFalse(outage.clear(keepingRunningSession: nil))
        let afterOutage = try Data(contentsOf: ledgerURL(directory))
        XCTAssertTrue(afterOutage == sealed, "the outage must not rewrite the evidence")

        auth.available = true
        let recovered = makeStore(directory, { box.now }, auth)
        let after = recovered.read()
        XCTAssertEqual(after.recoveryFloorAt, t0, "the original cooldown is preserved")
        assertCooldownHeld(after, nowMs: t0 + dayMs)
    }

    func testRestartAfterMissingLedgerDetectionKeepsTheRepairedCooldown() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (_, box) = eligibleWithoutLedger(directory, now: now)
        let marked = makeStore(directory, { box.now },
                               MarkerAuthentication(status: .present, key: key))
        XCTAssertEqual(marked.read().recoveryFloorAt, FlintHistoryEvent.milliseconds(now))

        let restarted = makeStore(directory, { box.now.addingTimeInterval(60) },
                                  MarkerAuthentication(status: .present, key: key))
        let after = restarted.read()
        XCTAssertEqual(after.recoveryFloorAt, FlintHistoryEvent.milliseconds(now))
        XCTAssertTrue(after.allowanceTrusted)
        assertCooldownHeld(after, nowMs: FlintHistoryEvent.milliseconds(now) + dayMs)
    }

    func testFailedRepairDoesNotReplaceAValidLedger() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "restore-1") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        let sealed = try Data(contentsOf: ledgerURL(directory))

        let broken = makeStore(directory, { box.now }, BrokenSigner())
        XCTAssertFalse(broken.read().allowanceTrusted)
        let preserved = try Data(contentsOf: ledgerURL(directory))
        XCTAssertTrue(preserved == sealed, "a failed repair must restore the authenticated bytes")

        let recovered = makeStore(directory, { box.now })
        XCTAssertEqual(recovered.read().recoveryFloorAt, t0)
        XCTAssertTrue(recovered.read().allowanceTrusted)
    }

    func testCompactionCannotPruneTheOnlyRecoveryProof() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "retain-proof") else { return XCTFail("expected success") }

        let broken = makeStore(directory, { box.now }, BrokenSigner())
        XCTAssertFalse(broken.compactIfNeeded(), "untrusted allowance blocks destructive pruning")
        XCTAssertEqual(recoveredIDs(broken), ["retain-proof"],
                       "the row proving the allowance must survive")
    }

    func testUntrustedAllowanceIsRejectedAtCommitEvenIfTheUiIsBypassed() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, box) = openSeeded(directory, now: now)
        guard case .success = commit(store, "bypass-0") else { return XCTFail("expected success") }
        removeRecoveryRows(directory)
        writeLedger(directory, raw: "{not json")

        // The UI reads `allowanceTrusted`; a caller that ignores it still hits the commit gate.
        let outage = makeStore(directory, { box.now }, UnavailableAuthentication())
        let snapshot = outage.read()
        XCTAssertFalse(snapshot.allowanceTrusted)
        guard case .rejected(let reason) = commit(outage, "bypass-1") else {
            return XCTFail("the commit path must not depend on the UI's trust flag")
        }
        XCTAssertEqual(reason, "allowance_unreadable")
        XCTAssertEqual(recoveredIDs(outage), [])
    }

    // MARK: - Authorization and concurrency

    func testForgedAppendBeforeTheCommitLeavesTheStoreHealthy() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)

        XCTAssertFalse(FileManager.default.fileExists(atPath: ledgerURL(directory).path))
        XCTAssertTrue(store.append(forgedRecovery(now)), "the inert row lands in the inbox")
        let afterForged = store.read()
        XCTAssertEqual(afterForged.events.filter { $0.type == .streakRecovered }.count, 0)
        XCTAssertEqual(afterForged.availability, .value)
        XCTAssertTrue(afterForged.allowanceTrusted)
        XCTAssertFalse(FileManager.default.fileExists(atPath: ledgerURL(directory).path),
                       "a refused row must not touch the allowance")

        guard case .success = commit(store, "legit-1") else {
            return XCTFail("a refused forged append must not block the real commit")
        }
        XCTAssertEqual(recoveredIDs(store), ["legit-1"])
        XCTAssertEqual(store.read().recoveryFloorAt, FlintHistoryEvent.milliseconds(now))
    }

    func testForgedAppendAfterTheCommitPreservesTheRecovery() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)
        guard case .success = commit(store, "legit-2") else { return XCTFail("expected success") }
        let t0 = FlintHistoryEvent.milliseconds(now)
        let sealed = try Data(contentsOf: ledgerURL(directory))

        XCTAssertTrue(store.append(forgedRecovery(now, id: "forged-late")))
        let after = store.read()
        XCTAssertEqual(recoveredIDs(store), ["legit-2"])
        XCTAssertEqual(after.availability, .value)
        XCTAssertEqual(after.recoveryFloorAt, t0)
        let afterBytes = try Data(contentsOf: ledgerURL(directory))
        XCTAssertTrue(afterBytes == sealed, "a refused row must not rewrite the allowance")
        assertCooldownHeld(after, nowMs: t0 + dayMs)
    }

    func testConcurrentCommitsConsumeTheAllowanceOnce() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let (store, _) = openSeeded(directory, now: now)
        var results = [StreakRecoveryCommitter.Result?](repeating: nil, count: 2)
        let slots = NSLock()
        DispatchQueue.concurrentPerform(iterations: 2) { index in
            let result = commit(store, "race-\(index)")
            slots.lock(); results[index] = result; slots.unlock()
        }
        let successes = results.compactMap { $0 }.filter {
            if case .success = $0 { return true }
            return false
        }.count
        XCTAssertEqual(successes, 1)
        XCTAssertEqual(recoveredIDs(store).count, 1)
        let snapshot = store.read()
        XCTAssertEqual(snapshot.recoveryFloorAt, FlintHistoryEvent.milliseconds(now))
        XCTAssertTrue(snapshot.allowanceTrusted)
    }

    func testRejectedAuthorizationCannotBeRevivedByRestart() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let box = ClockBox(calendar.date(byAdding: .day, value: -40, to: now)!)
        let host = makeStore(directory, { box.now })
        _ = host.read()
        box.now = now
        seedCompleted(host, id: "a", dayOffset: -2, minutes: 30)
        _ = host.read()
        // No recovery-day completion yet: the attempt is refused and consumes nothing.
        guard case .rejected = commit(host, "refused-1") else {
            return XCTFail("expected rejection without a qualifying completion")
        }

        // A forged row published by an extension is dropped by every host drain, before and
        // after a restart.
        let extensionStore = FlintHistoryStore(directory: directory, clock: { box.now },
                                               calendar: calendar, isHost: false,
                                               ledgerAuthenticator: authenticator)
        XCTAssertTrue(extensionStore.append(forgedRecovery(now, id: "forged-restart")))

        let restarted = makeStore(directory, { box.now })
        let after = restarted.read()
        XCTAssertEqual(after.events.filter { $0.type == .streakRecovered }.count, 0)
        XCTAssertEqual(after.availability, .value)
        XCTAssertTrue(after.allowanceTrusted)
        XCTAssertNil(after.recoveryFloorAt)

        seedCompleted(restarted, id: "b", dayOffset: 0, minutes: 40, grant: fingerprint)
        guard case .success = commit(restarted, "legit-restart") else {
            return XCTFail("the refused authorization must not block the real commit")
        }
        XCTAssertEqual(recoveredIDs(restarted), ["legit-restart"])
    }

    func testRecoveryIsDistinctFromCompletedHistoryAndKeepsTheRecordedZone() throws {
        let directory = tempDirectory()
        let now = at(dayOffset: 0, hour: 18)
        let box = ClockBox(calendar.date(byAdding: .day, value: -40, to: now)!)
        let store = makeStore(directory, { box.now })
        _ = store.read()
        box.now = now
        seedCompleted(store, id: "a", dayOffset: -2, minutes: 30)
        let pacific = TimeZone(identifier: "America/Los_Angeles")!
        seedCompleted(store, id: "b", dayOffset: 0, minutes: 40, zone: pacific, grant: fingerprint)
        guard case .success = commit(store, "zone-1") else { return XCTFail("expected success") }

        let snapshot = store.read()
        let bridge = try XCTUnwrap(snapshot.events.first { $0.type == .streakRecovered })
        XCTAssertEqual(bridge.tz, pacific.identifier,
                       "the bridge records the terminal session's zone")
        // The missed civil day is re-expressed in the bridge's own recorded zone.
        var pacificCalendar = calendar
        pacificCalendar.timeZone = pacific
        let missedInZone = pacificCalendar.date(
            from: DateComponents(year: 2026, month: 10, day: 27))!
        XCTAssertEqual(bridge.breakEnd, FlintHistoryEvent.milliseconds(missedInZone))

        let summary = FlintHistoryAggregator.summarize(
            snapshot, activeSessionID: nil, now: now, calendar: calendar, dayCount: 7)
        let missed = summary.days.first { calendar.isDate($0.start, inSameDayAs: at(dayOffset: -1)) }
        XCTAssertEqual(missed?.recovered, true)
        XCTAssertEqual(missed?.completed, 0, "a recovery never fabricates a completed session")
        XCTAssertFalse(summary.sessions.contains { $0.id == "zone-1" })
    }
}
