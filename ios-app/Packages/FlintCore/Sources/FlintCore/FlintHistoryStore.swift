import Foundation
import CryptoKit
import Darwin

public enum FlintHistoryAvailability: Equatable, Sendable { case unavailable, degraded, empty, value }

public struct FlintHistorySnapshot: Sendable {
    public var events: [FlintHistoryEvent]
    public var historyStart: Date?
    public var availability: FlintHistoryAvailability
    /// Last credited recovery: the max of durable rows and the authenticated ledger floor.
    public var recoveryFloorAt: Int64?
    /// False when the allowance's evidence cannot be authenticated. Recovery must stay off:
    /// an unverifiable ledger is never an unused allowance.
    public var allowanceTrusted: Bool
    public init(events: [FlintHistoryEvent], historyStart: Date?, availability: FlintHistoryAvailability,
                recoveryFloorAt: Int64? = nil, allowanceTrusted: Bool = true) {
        self.events = events; self.historyStart = historyStart; self.availability = availability
        self.recoveryFloorAt = recoveryFloorAt; self.allowanceTrusted = allowanceTrusted
    }
}

/// Every process publishes small atomic inbox files; only the host rewrites the main
/// log and metadata. Extensions can read a bounded snapshot without mutation. There are
/// no cross-process file locks to survive suspension. Host
/// instances share an in-process mutex; lifecycle callers never wait for host maintenance.
public final class FlintHistoryStore: @unchecked Sendable {
    public static let didChangeNotification = Notification.Name("FlintHistoryDidChange")
    public static let softLimit = 1_048_576
    public static let compactedLimit = 4 * 1_048_576
    public static let hardLimit = 5 * 1_048_576
    public static let inboxLimit = 1000
    public static let recordLimit = 16_384
    public static let readOnlyRecordLimit = 20_000
    public static let readOnlyDecodedByteLimit = 4 * 1_048_576
    public static let shared = FlintHistoryStore(directory: FileManager.default
        .containerURL(forSecurityApplicationGroupIdentifier: FlintGroupStore.appGroupID)?
        .appendingPathComponent("FlintHistory", isDirectory: true),
        isHost: Bundle.main.bundleURL.pathExtension == "app",
        currentSessionID: { FlintGroupStore()?.loadActiveSession()?.id })

    public let directory: URL?
    private let isHost: Bool
    private let clock: () -> Date
    private let calendar: Calendar
    private let currentSessionID: () -> String?
    private let maintenanceHook: () -> Void
    private let metadataWriteHook: () throws -> Void
    private let quarantineMoveHook: () -> Void
    private let ledgerAuthenticator: RecoveryLedgerAuthenticator
    /// Live Screen Time selection fingerprint. Production reads the App Group; tests inject a
    /// matching value so the commit path can be exercised without system tokens.
    private let liveSelectionFingerprint: () -> String?
    private let mutex: NSLock
    // Accessed only while the per-directory host mutex is held. A clear supersedes
    // any internal drain notification so observers see the final committed state once.
    private var mutationReason: String?
    private let fm = FileManager.default
    private enum Failure: Error { case unavailable, io, capacity }

    private struct Metadata: Codable {
        var v = 1
        var createdAt: Int64
        var clearedAt: Int64?
        var lastCompacted: Int64?
        var degraded: Bool? = false
        var lostAllReadableEvents: Bool?
        var pendingClear: Bool?
        // Retained after commit too, to accept delayed inbox events from that session.
        var keepSession: String?
        var canPersist = true
        var historyStart: Date { FlintHistoryEvent.date(max(createdAt, clearedAt ?? createdAt)) }
        private enum CodingKeys: String, CodingKey {
            case v, createdAt, clearedAt, lastCompacted, degraded, lostAllReadableEvents, pendingClear, keepSession
        }
    }
    private struct Line {
        let event: FlintHistoryEvent?
        let raw: Data
        let session: String
        let at: Date?
        let id: String?
    }
    private struct Loaded {
        var lines: [Line]
        var bad: Bool
        var canRewrite: Bool
        var unavailable: Bool = false
    }
    private struct InboxEntry { let url: URL; let line: Line }
    private typealias Inbox = (entries: [InboxEntry], bad: Bool)

    /// Hooks let tests pause maintenance or inject destination-specific metadata failures.
    public init(directory: URL?, clock: @escaping () -> Date = Date.init, calendar: Calendar = .current,
                isHost: Bool = true, currentSessionID: @escaping () -> String? = { nil },
                maintenanceHook: @escaping () -> Void = {}, metadataWriteHook: @escaping () throws -> Void = {},
                quarantineMoveHook: @escaping () -> Void = {},
                ledgerAuthenticator: RecoveryLedgerAuthenticator? = nil,
                liveSelectionFingerprint: (() -> String?)? = nil) {
        self.directory = directory; self.clock = clock; self.calendar = calendar; self.isHost = isHost
        self.currentSessionID = currentSessionID; self.maintenanceHook = maintenanceHook
        self.metadataWriteHook = metadataWriteHook
        self.quarantineMoveHook = quarantineMoveHook
        // Keychain first, with a per-install key file for environments where it cannot sign.
        // Both are lazy: constructing a store (or an extension's read-only store) is I/O free.
        self.ledgerAuthenticator = ledgerAuthenticator ?? FallbackRecoveryLedgerAuthenticator(
            primary: KeychainRecoveryLedgerAuthenticator(
                account: KeychainRecoveryLedgerAuthenticator.account(for: directory)),
            fallback: FileKeyRecoveryLedgerAuthenticator(
                fileURL: directory?.appendingPathComponent("recovery-ledger-key.v1")))
        let productionSelection: () -> String? = {
            #if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
            guard let selection = FlintGroupStore()?.loadSelection() else { return nil }
            return StreakRecoveryProtection.fingerprint(of: selection)
            #else
            return nil
            #endif
        }
        self.liveSelectionFingerprint = liveSelectionFingerprint ?? productionSelection
        mutex = FlintHistoryHostLocks.lock(for: directory?.standardizedFileURL.path ?? "unavailable")
    }

    /// Lock-free durable ingress, also used by the host. No metadata/log access or scans.
    @discardableResult public func append(_ event: FlintHistoryEvent) -> Bool {
        do {
            guard directory != nil else { throw Failure.unavailable }
            try ensureDirectory(directory!)
            let inbox = path("inbox")
            try ensureDirectory(inbox)
            let committed = try fm.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }
            guard committed.count < Self.inboxLimit else { throw Failure.capacity }
            let data = try encodedLine(event)
            guard data.count <= Self.recordLimit else { throw Failure.capacity }
            // A publication-order suffix resolves same-millisecond events before drain.
            let order = String(format: "%020llu", DispatchTime.now().uptimeNanoseconds)
            let name = "\(event.at)-\(order)-\(UUID().uuidString).json"
            try atomicWrite(data, to: inbox.appendingPathComponent(name))
            return true
        } catch { recordIngressFailure(); return false }
    }

    public func read() -> FlintHistorySnapshot {
        hostOperation(fallback: .init(events: [], historyStart: nil, availability: .unavailable)) {
            let inbox = readInbox()
            var loaded = readMain(inbox: inbox)
            var metadata = recoverMetadata(lines: loaded.lines, inbox: inbox)
            recoverClear(&metadata, loaded: &loaded)
            drain(&metadata, loaded: &loaded, inbox: inbox)
            let ledger = reconcileLedger(events: loaded.lines.compactMap(\.event))
            return snapshot(loaded, metadata: metadata, ledger: ledger)
        }
    }

    /// A nonmutating extension view: 64 KiB streaming input, one <=16 KiB line,
    /// <=20,000 supported records and <=4 MiB of their encoded bytes (including
    /// duplicate ingress). Unknown/future envelopes are discarded immediately.
    /// Main/inbox/metadata and traversal bounds also apply; exceeding ANY budget
    /// returns unavailable with no partial events. These are allocation/operation
    /// bounds, not a filesystem deadline or a measured WidgetKit RSS guarantee.
    public func readOnlySnapshot() -> FlintHistorySnapshot {
        let unavailable = FlintHistorySnapshot(events: [], historyStart: nil, availability: .unavailable)
        guard let directory,
              let attributes = try? fm.attributesOfItem(atPath: directory.path),
              attributes[.type] as? FileAttributeType == .typeDirectory else { return unavailable }
        var bad = false, exhausted = false
        var decodedCount = 0, decodedBytes = 0
        let decoder = JSONDecoder()
        // Only the supported DTO survives this closure; no raw host Line or digest.
        func decode(_ raw: Data, into events: inout [FlintHistoryEvent]) -> Bool {
            guard let envelope = try? decoder.decode(ReadOnlyEnvelope.self, from: raw) else {
                bad = true; return true
            }
            if (envelope.v ?? 0) > 1 { return true }
            if let type = envelope.type,
               !FlintHistoryEventType.knownTypes.contains(where: { $0.wireName == type }) { return true }
            guard let event = try? decoder.decode(FlintHistoryEvent.self, from: raw), event.isSupported else {
                bad = true; return true
            }
            guard decodedCount < Self.readOnlyRecordLimit,
                  decodedBytes + raw.count + 1 <= Self.readOnlyDecodedByteLimit else { return false }
            decodedCount += 1; decodedBytes += raw.count + 1
            events.append(event)
            return true
        }
        // Read inbox before main: drain commits the log before unlinking inbox files.
        let urls = readOnlyInboxURLs(bad: &bad, exhausted: &exhausted)
        if exhausted { return unavailable }
        var inbox: [FlintHistoryEvent] = []
        for url in urls {
            let result = readOnlyLines(url, limit: Self.recordLimit, singleLine: true) { decode($0, into: &inbox) }
            if result == .exhausted { return unavailable }
            if result == .damaged { bad = true }
        }
        // A committed recovery bridge lives in the main log only; extension ingress can never
        // contribute one, even to a read-only view.
        inbox.removeAll { $0.type == .streakRecovered }
        var events: [FlintHistoryEvent] = []
        let mainResult = readOnlyLines(path("events.jsonl"), limit: Self.hardLimit) { decode($0, into: &events) }
        if mainResult == .exhausted { return unavailable }
        if mainResult == .damaged { bad = true }
        let damagedRecords = bad
        // Read clear/loss intent after the records, including during host recovery.
        var metadata: Metadata?
        switch readOnlyFile(path("metadata.json"), limit: 65_536) {
        case .exhausted: return unavailable
        case .data(let data):
            if let decoded = try? decoder.decode(Metadata.self, from: data), decoded.v == 1,
               [decoded.createdAt, decoded.clearedAt].compactMap({ $0 }).allSatisfy({
                   $0 >= -62_135_596_800_000 && $0 <= 253_402_300_799_000
               }) { metadata = decoded } else { bad = true }
        case .missing, .failed: bad = true
        }
        func kept(_ event: FlintHistoryEvent) -> Bool {
            guard let keep = metadata?.keepSession else { return false }
            return event.session == keep || event.sessions?.contains(keep) == true
        }
        if metadata?.pendingClear == true {
            events.removeAll { !kept($0) }; bad = true
        }
        let admittedStarts = Self.admittedStartIDs(events + inbox, boundary: metadata?.clearedAt, keep: metadata?.keepSession)
        func visible(_ event: FlintHistoryEvent) -> Bool {
            guard let boundary = metadata?.clearedAt else { return true }
            if kept(event) { return true }
            // The allowance survives a clear; the bridged day is re-filtered by historyStart.
            if StreakRecoveryPolicy.isWellFormedRecovery(event) { return true }
            if Self.uncertainNonFocus(event) { return Self.referencesStart(event, in: admittedStarts) }
            return event.at >= boundary
        }
        events.removeAll { !visible($0) }
        var ids = Set<String>()
        events.removeAll { !ids.insert($0.id).inserted }
        for event in inbox where visible(event) && ids.insert(event.id).inserted { events.append(event) }
        var diagnosticError = false
        let diagnostics = fm.enumerator(at: path("diagnostics"), includingPropertiesForKeys: nil,
                                        options: [.skipsSubdirectoryDescendants], errorHandler: { _, error in
            if (error as NSError).code != NSFileReadNoSuchFileError { diagnosticError = true }
            return false
        })
        let hasDiagnostic = diagnostics?.nextObject() != nil
        bad = bad || diagnosticError || hasDiagnostic
        let degraded = bad || metadata?.degraded == true || metadata?.lostAllReadableEvents == true
        let lost = events.isEmpty && (damagedRecords || hasDiagnostic || diagnosticError ||
                                     metadata?.lostAllReadableEvents == true)
        return .init(events: events, historyStart: metadata?.historyStart,
                     availability: lost ? .unavailable : degraded ? .degraded :
                        events.contains { $0.type == .started } ? .value : .empty,
                     // The extension view cannot read the Keychain, so it never vouches for the
                     // allowance. Only the host's verified read may offer a recovery.
                     allowanceTrusted: false)
    }

    private struct ReadOnlyEnvelope: Decodable {
        let v: Int?
        let type: String?
        private enum CodingKeys: String, CodingKey { case v, type }
        init(from decoder: Decoder) throws {
            let fields = try decoder.container(keyedBy: CodingKeys.self)
            v = try fields.decodeIfPresent(Int.self, forKey: .v)
            // A higher version can change every other field's shape.
            type = (v ?? 0) > 1 ? nil : try fields.decodeIfPresent(String.self, forKey: .type)
        }
    }
    private enum ReadOnlyLines: Equatable { case missing, success, damaged, exhausted }
    private func readOnlyLines(_ url: URL, limit: Int, singleLine: Bool = false,
                               consume: (Data) -> Bool) -> ReadOnlyLines {
        let fd = open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { return errno == ENOENT ? .missing : .damaged }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close() }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_size >= 0 else { return .damaged }
        guard info.st_size <= limit else { return .exhausted }
        var line = Data(), bytes = 0, lines = 0
        do {
            while true {
                let chunk = try handle.read(upToCount: min(65_536, limit + 1 - bytes)) ?? Data()
                if chunk.isEmpty { return line.isEmpty && (!singleLine || lines == 1) ? .success : .damaged }
                bytes += chunk.count
                guard bytes <= limit else { return .exhausted }
                var start = chunk.startIndex
                for end in chunk.indices where chunk[end] == 10 {
                    guard line.count + end - start < Self.recordLimit else { return .exhausted }
                    line.append(contentsOf: chunk[start..<end])
                    lines += 1
                    guard !singleLine || lines == 1 else { return .exhausted }
                    // Foundation decoding temporaries are released per line, including
                    // dense future envelopes that never consume the decoded-event budget.
                    guard autoreleasepool(invoking: { consume(line) }) else { return .exhausted }
                    line.removeAll(keepingCapacity: true)
                    start = chunk.index(after: end)
                }
                guard line.count + chunk.endIndex - start < Self.recordLimit else { return .exhausted }
                line.append(contentsOf: chunk[start..<chunk.endIndex])
            }
        } catch { return .damaged }
    }

    // Used only for small metadata, never for the event log or inbox.
    private enum ReadOnlyFile { case missing, data(Data), failed, exhausted }
    private func readOnlyFile(_ url: URL, limit: Int) -> ReadOnlyFile {
        let fd = open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { return errno == ENOENT ? .missing : .failed }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close() }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_size >= 0 else { return .failed }
        guard info.st_size <= limit else { return .exhausted }
        do {
            var data = Data()
            while data.count <= limit {
                let chunk = try handle.read(upToCount: min(65_536, limit + 1 - data.count)) ?? Data()
                if chunk.isEmpty { return .data(data) }
                data.append(chunk)
            }
            return .exhausted
        } catch { return .failed }
    }

    private func readOnlyInboxURLs(bad: inout Bool, exhausted: inout Bool) -> [URL] {
        var failed = false
        guard let enumerator = fm.enumerator(at: path("inbox"), includingPropertiesForKeys: nil,
                                            options: [.skipsSubdirectoryDescendants], errorHandler: { _, error in
            if (error as NSError).code != NSFileReadNoSuchFileError { failed = true }
            return false
        }) else { bad = bad || failed; return [] }
        var urls: [URL] = [], visited = 0
        while let url = enumerator.nextObject() as? URL {
            visited += 1
            guard visited <= Self.inboxLimit * 2 else { exhausted = true; break }
            guard url.pathExtension == "json" else { continue }
            guard urls.count < Self.inboxLimit else { exhausted = true; break }
            urls.append(url)
        }
        bad = bad || failed
        urls.sort {
            let a = $0.lastPathComponent.split(separator: "-").dropFirst().first ?? ""
            let b = $1.lastPathComponent.split(separator: "-").dropFirst().first ?? ""
            return a == b ? $0.lastPathComponent < $1.lastPathComponent : a < b
        }
        return urls
    }

    /// Host-only; daily retention or >1 MiB triggers maintenance, with headroom below 5 MiB.
    @discardableResult public func compactIfNeeded() -> Bool {
        hostOperation(fallback: false) {
            let inbox = readInbox()
            var loaded = readMain(inbox: inbox)
            var metadata = recoverMetadata(lines: loaded.lines, inbox: inbox)
            recoverClear(&metadata, loaded: &loaded)
            drain(&metadata, loaded: &loaded, inbox: inbox)
            let events = loaded.lines.compactMap(\.event)
            let ledger = reconcileLedger(events: events)
            // Never prune the row that proves the allowance while its evidence cannot be
            // authenticated: that would turn a failed repair into a refund.
            if !ledger.trusted, StreakRecoveryPolicy.lastSuccessfulRecoveryAt(events: events) != nil {
                return false
            }
            maintenanceHook()
            let sameDay = metadata.lastCompacted.map { calendar.isDate(FlintHistoryEvent.date($0), inSameDayAs: clock()) } ?? false
            if sameDay && byteCount(loaded.lines) <= Self.softLimit { return !loaded.unavailable }
            guard loaded.canRewrite, !loaded.unavailable, metadata.pendingClear != true else { return false }
            let retained = retainedLines(loaded.lines, keeping: currentSessionID(), target: Self.compactedLimit)
            guard byteCount(retained) <= Self.hardLimit else { return false }
            do {
                try replaceLog(retained)
                markChanged("compact") // The visible replacement already committed, even if metadata fails.
                metadata.lastCompacted = FlintHistoryEvent.milliseconds(clock())
                metadata.degraded = metadata.degraded == true || loaded.bad
                try saveMetadata(metadata)
                return true
            } catch { return false }
        }
    }

    @discardableResult public func clear(keepingRunningSession sessionID: String?) -> Bool {
        hostOperation(fallback: false) {
            let inbox = readInbox()
            var loaded = readMain(inbox: inbox)
            var metadata = recoverMetadata(lines: loaded.lines, inbox: inbox)
            recoverClear(&metadata, loaded: &loaded)
            drain(&metadata, loaded: &loaded, inbox: inbox)
            // The allowance must be durable before the rows that prove it are deleted. An
            // unverifiable allowance is never cleared into a refund.
            let ledger = reconcileLedger(events: loaded.lines.compactMap(\.event))
            guard ledger.trusted else { return false }
            let eventsFloor = StreakRecoveryPolicy.lastSuccessfulRecoveryAt(
                events: loaded.lines.compactMap(\.event))
            let proved = max(ledger.floor ?? Int64.min, eventsFloor ?? Int64.min)
            // A degraded store with no provable floor restarts the cooldown instead of refunding.
            let floor: Int64? = proved != Int64.min ? proved
                : (metadata.degraded == true ? FlintHistoryEvent.milliseconds(clock()) : nil)
            if let floor {
                do {
                    try writeLedger(floor, replacing: readLedger())
                    let written = readLedger()
                    guard !written.corrupt, written.lastRecoveryAt == floor else { return false }
                } catch { return false }
            }
            metadata.clearedAt = FlintHistoryEvent.milliseconds(clock())
            metadata.pendingClear = true; metadata.keepSession = sessionID
            do {
                try saveMetadata(metadata) // durable intent before the destructive replacement
                // Include readable inbox data if a drain failed. Its files stay until a later
                // idempotent drain; clear recovery will filter them by this durable boundary.
                // Recovery evidence outlives a clear: the rolling allowance must not be
                // refunded by deleting history. The bridged day itself falls before the new
                // historyStart, so it is not resurrected as a streak day.
                try replaceLog(loaded.lines.filter {
                    belongs($0, to: sessionID)
                        || ($0.event.map(StreakRecoveryPolicy.isWellFormedRecovery) ?? false)
                })
                metadata.pendingClear = nil; metadata.degraded = false
                metadata.lostAllReadableEvents = nil; metadata.lastCompacted = nil
                try saveMetadata(metadata)
                markChanged("clear")
                return true
            } catch { return false }
        }
    }

    /// Authoritative recovery commit. Authorization, eligibility, progress, duplicate checks and
    /// the durable append all happen inside one host-lock acquisition, so no other append can slip
    /// between validation and persistence. Success is reported only after `appendMain` fsyncs.
    public func commitStreakRecovery(
        now: Date?, calendar: Calendar, activeSessionID: String?, recoveryId: String?,
        protection: StreakRecoveryProtection.BoundProfile?, protectionActive: Bool
    ) -> StreakRecoveryCommitter.Result {
        hostOperation(fallback: StreakRecoveryCommitter.Result.rejected(reason: "not_host")) {
            guard protectionActive, let protection else {
                return .rejected(reason: "protection_inactive")
            }
            // The freeze must be complete, unlatched, and bound by this very process.
            guard !protection.isEmpty, !protection.enforcementBroken,
                  let generation = protection.watchGeneration,
                  generation == RecoveryChallengeGuardStore.currentProcessGeneration else {
                return .rejected(reason: "protection_inactive")
            }
            // The frozen protection must still be what is enforced now: a picker change during
            // the challenge earns nothing, even for a session that started under the freeze.
            #if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
            guard let liveSelection = liveSelectionFingerprint(),
                  liveSelection == protection.fingerprint else {
                return .rejected(reason: "protection_mismatch")
            }
            #endif
            let inbox = readInbox()
            var loaded = readMain(inbox: inbox)
            var metadata = recoverMetadata(lines: loaded.lines, inbox: inbox)
            recoverClear(&metadata, loaded: &loaded)
            drain(&metadata, loaded: &loaded, inbox: inbox)
            let ledger = reconcileLedger(events: loaded.lines.compactMap(\.event))
            guard ledger.trusted else { return .rejected(reason: "allowance_unreadable") }
            let current = snapshot(loaded, metadata: metadata, ledger: ledger)
            guard current.availability != .unavailable else {
                return .rejected(reason: "history_unreadable")
            }
            let commitNow = clock()
            let sessions = FlintHistoryAggregator.sessionSummaries(
                current.events, activeSessionID: activeSessionID, now: commitNow)
            let challenge: StreakRecoveryPolicy.Challenge
            switch StreakRecoveryPolicy.evaluate(
                sessions: sessions, events: current.events, now: commitNow, calendar: calendar,
                historyStart: current.historyStart,
                // Partially corrupt history cannot prove the allowance is unused: fail closed.
                readable: current.availability == .value,
                recoveryFloorAt: ledger.floor,
                allowanceTrusted: ledger.trusted
            ) {
            case .unavailable(let reason):
                return .rejected(reason: reason)
            case .offer(let offered):
                challenge = offered
            }
            guard calendar.isDate(protection.recoveryDay, inSameDayAs: challenge.recoveryDay),
                  calendar.isDate(protection.missedDay, inSameDayAs: challenge.missedDay) else {
                return .rejected(reason: "protection_mismatch")
            }
            // Grant-bound progress. The caller's sidecar ids are deliberately not passed, so a
            // polluted list cannot inflate credit the durable rows do not support.
            let progress = StreakRecoveryProtection.focusedMs(
                on: challenge.recoveryDay, sessions: sessions, calendar: calendar,
                events: current.events, fingerprint: protection.fingerprint,
                extraQualifiedIds: [])
            guard progress >= challenge.requiredFocusedMs else {
                return .rejected(reason: "insufficient_duration")
            }
            let bridgeID = (recoveryId?.isEmpty == false) ? recoveryId! : UUID().uuidString
            guard !current.events.contains(where: { $0.id == bridgeID }) else {
                return .rejected(reason: "duplicate_id")
            }
            let zone = Self.recoveryZone(sessions: sessions, challenge: challenge, calendar: calendar)
            let event = StreakRecoveryPolicy.buildRecoveryEvent(
                challenge: challenge, sessions: sessions, now: commitNow, calendar: calendar,
                timeZone: zone, recoveryId: bridgeID, events: current.events,
                fingerprint: protection.fingerprint, extraQualifiedIds: [])
            guard StreakRecoveryPolicy.isWellFormedRecovery(event) else {
                return .rejected(reason: "malformed_recovery")
            }
            do {
                let raw = try JSONEncoder().encode(event)
                try appendMain([Line(event: event, raw: raw, session: event.session,
                                     at: event.date, id: event.id)])
            } catch {
                return .persistFailed
            }
            do {
                try writeLedger(FlintHistoryEvent.milliseconds(commitNow), replacing: readLedger())
            } catch {
                // The recovery is durable but its allowance mirror is not. Degrade so a later
                // clear or retention repair starts a fresh cooldown instead of refunding it.
                metadata.degraded = true
                try? saveMetadata(metadata)
            }
            markChanged("recovery")
            return .success(event: event, challenge: challenge)
        }
    }

    /// Recorded terminal zone of the last qualifying completion, so the bridge day is bucketable
    /// in the same zone the completion was recorded in.
    private static func recoveryZone(sessions: [FlintHistorySessionSummary],
                                     challenge: StreakRecoveryPolicy.Challenge,
                                     calendar: Calendar) -> TimeZone {
        let contributors = sessions.filter {
            StreakRecoveryPolicy.isOnRecordedDay($0, day: challenge.recoveryDay, calendar: calendar)
        }
        return contributors.max(by: { $0.endedAt < $1.endedAt })?.recordedTimeZone ?? calendar.timeZone
    }

    private func hostOperation<T>(fallback: T, _ body: () -> T) -> T {
        guard isHost, let directory else { return fallback }
        mutex.lock()
        mutationReason = nil
        do { try ensureDirectory(directory) } catch { mutex.unlock(); return fallback }
        reclaimAbandonedTemps()
        let result = body()
        let reason = mutationReason
        mutationReason = nil
        mutex.unlock()
        if let reason { postChange(reason) }
        return result
    }

    private func markChanged(_ reason: String) {
        if mutationReason != "clear" { mutationReason = reason }
    }

    /// Reconciliation publishes inferred records via atomic ingress without a host
    /// lock. Notify once after those writes commit; extensions never post this signal.
    func didCommitReconciliation() { postChange("reconcile") }

    private func postChange(_ reason: String) {
        guard isHost else { return }
        NotificationCenter.default.post(name: Self.didChangeNotification, object: self,
                                        userInfo: ["reason": reason])
    }

    /// Only the host reclaims crash-left staging files. Recent staging files may belong to
    /// another live publisher, and never consume the committed-record inbox quota.
    private func reclaimAbandonedTemps() {
        let cutoff = clock().addingTimeInterval(-600)
        for folder in [directory!, path("inbox"), path("diagnostics"), path("quarantine")] {
            let files = (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey])) ?? []
            for file in files where file.lastPathComponent.hasPrefix(".tmp-") {
                guard let values = try? file.resourceValues(forKeys: [.contentModificationDateKey, .isRegularFileKey]),
                      values.isRegularFile == true, let modified = values.contentModificationDate,
                      modified < cutoff else { continue }
                try? fm.removeItem(at: file)
            }
        }
    }

    private func recoverMetadata(lines: [Line], inbox: Inbox) -> Metadata {
        let url = path("metadata.json")
        let exists = fm.fileExists(atPath: url.path)
        if exists,
           let attributes = try? fm.attributesOfItem(atPath: url.path),
           (attributes[.size] as? NSNumber)?.intValue ?? Int.max <= 65_536,
           attributes[.type] as? FileAttributeType == .typeRegular,
           let data = try? Data(contentsOf: url),
           let metadata = try? JSONDecoder().decode(Metadata.self, from: data), metadata.v == 1,
           [metadata.createdAt, metadata.clearedAt].compactMap({ $0 }).allSatisfy({
               $0 >= -62_135_596_800_000 && $0 <= 253_402_300_799_000
           }) {
            return metadata
        }
        let saved = !exists || quarantineFile(url, moving: true, readLimit: 65_536)
        let first = (lines + inbox.entries.map(\.line)).compactMap(\.at).min() ?? clock()
        var metadata = Metadata(createdAt: FlintHistoryEvent.milliseconds(first), degraded: exists || !lines.isEmpty)
        metadata.canPersist = saved
        try? saveMetadata(metadata)
        return metadata
    }

    // MARK: Recovery allowance ledger

    private struct LedgerEvidence {
        let floor: Int64?
        let trusted: Bool
    }

    /// Verifies the allowance evidence, repairing a corrupt or lagging ledger before any use.
    ///
    /// A corrupt file is never read as an unused allowance: readable evidence is quarantined, then
    /// the floor is re-sealed from the durable event floor when it is sane, otherwise from the
    /// detection time. A repair only counts after a verified read-back; otherwise `trusted` is
    /// false and recovery stays off. The corrupt file itself is never deleted or moved here, so a
    /// repair that cannot be written leaves the evidence in place.
    private func reconcileLedger(events: [FlintHistoryEvent]) -> LedgerEvidence {
        let nowMs = FlintHistoryEvent.milliseconds(clock())
        let limit = nowMs > Int64.max - RecoveryLedgerFormat.maxFutureSkewMs
            ? Int64.max : nowMs + RecoveryLedgerFormat.maxFutureSkewMs
        let eventsFloor = StreakRecoveryPolicy.lastSuccessfulRecoveryAt(events: events)
            .flatMap { $0 > 0 && $0 <= limit ? $0 : nil }
        let read = readLedger()
        if !read.corrupt {
            // A pre-ledger install (no file) or a mirror that fell behind the rows: re-seal what
            // the durable rows already prove, so the allowance cannot be lost to a later trim.
            if let floor = eventsFloor, floor > (read.lastRecoveryAt ?? Int64.min) {
                return sealLedger(floor, replacing: read)
            }
            // The ledger file was deleted (or never restored): this directory's key material
            // decides whether one was ever sealed. `present` repairs at the detection time,
            // `unknown` hides recovery without writing, `absent` is a genuine fresh start.
            if read.lastRecoveryAt == nil, eventsFloor == nil {
                switch ledgerAuthenticator.ledgerKeyStatus {
                case .present:
                    return sealLedger(nowMs, replacing: read)
                case .unknown:
                    // The key exists but cannot be inspected right now (Keychain unavailable).
                    // The allowance is *unverifiable*, not unused: hide recovery and write
                    // nothing, so no cooldown timestamp is fabricated. A later read resolves the
                    // identity and initializes (absent) or repairs (present).
                    return LedgerEvidence(floor: nil, trusted: false)
                case .absent:
                    break
                }
            }
            let combined = max(read.lastRecoveryAt ?? Int64.min, eventsFloor ?? Int64.min)
            return LedgerEvidence(floor: combined == Int64.min ? nil : combined, trusted: true)
        }
        if let data = try? Data(contentsOf: path("recovery-ledger.v1.json")) {
            _ = quarantineData(data, kind: "recovery-ledger")
        }
        return sealLedger(eventsFloor ?? nowMs, replacing: read)
    }

    /// Writes the floor, then requires the value and tag to read back. A failed write or a tag
    /// mismatch restores the previous bytes (when there were any) and leaves the allowance
    /// untrusted instead of trusting an in-memory value or destroying authenticated evidence.
    private func sealLedger(_ floor: Int64, replacing current: RecoveryLedgerState) -> LedgerEvidence {
        let previous = try? Data(contentsOf: path("recovery-ledger.v1.json"))
        do {
            try writeLedger(floor, replacing: current)
            let written = readLedger()
            guard !written.corrupt, written.lastRecoveryAt == floor else {
                restoreLedger(previous)
                return LedgerEvidence(floor: floor, trusted: false)
            }
            return LedgerEvidence(floor: floor, trusted: true)
        } catch {
            restoreLedger(previous)
            return LedgerEvidence(floor: floor, trusted: false)
        }
    }

    /// Best-effort restore of the bytes a failed repair replaced.
    private func restoreLedger(_ previous: Data?) {
        guard let previous else { return }
        try? atomicWrite(previous, to: path("recovery-ledger.v1.json"))
    }

    private func readLedger() -> RecoveryLedgerState {
        let url = path("recovery-ledger.v1.json")
        guard fm.fileExists(atPath: url.path) else {
            return RecoveryLedgerState(lastRecoveryAt: nil, corrupt: false)
        }
        guard let attributes = try? fm.attributesOfItem(atPath: url.path),
              (attributes[.size] as? NSNumber)?.intValue ?? Int.max <= 65_536,
              attributes[.type] as? FileAttributeType == .typeRegular,
              let data = try? Data(contentsOf: url) else {
            return RecoveryLedgerState(lastRecoveryAt: nil, corrupt: true)
        }
        let nowMs = FlintHistoryEvent.milliseconds(clock())
        return RecoveryLedgerFormat.decode(data, nowMs: nowMs) { payload, tag in
            ledgerAuthenticator.verify(payload: payload, tag: tag)
        }
    }

    /// Monotonic: an older mirror never replaces a newer one. Throws when it cannot be tagged.
    private func writeLedger(_ at: Int64, replacing current: RecoveryLedgerState) throws {
        guard at > 0 else { throw Failure.io }
        if let existing = current.lastRecoveryAt, at <= existing { return }
        guard let tag = ledgerAuthenticator.tag(for: RecoveryLedgerFormat.payload(at)) else {
            throw Failure.io
        }
        let data = try RecoveryLedgerFormat.encode(lastRecoveryAt: at, tag: tag)
        try atomicWrite(data, to: path("recovery-ledger.v1.json"))
    }

    private func recoverClear(_ metadata: inout Metadata, loaded: inout Loaded) {        guard metadata.pendingClear == true else { return }
        let kept = loaded.lines.filter { belongs($0, to: metadata.keepSession) }
        do {
            try replaceLog(kept)
            var committed = metadata
            committed.pendingClear = nil; committed.degraded = false; committed.lostAllReadableEvents = nil
            try saveMetadata(committed)
            markChanged("clear")
            metadata = committed
            loaded = Loaded(lines: kept, bad: false, canRewrite: true)
        } catch {
            // Display only the retained generation even if the clear cannot commit yet.
            loaded.lines = kept; loaded.bad = true; loaded.canRewrite = false
        }
    }

    private func drain(_ metadata: inout Metadata, loaded: inout Loaded, inbox: Inbox) {
        loaded.bad = loaded.bad || inbox.bad
        var ids = Set(loaded.lines.compactMap(\.id))
        var additions: [Line] = []
        let currentEvents = loaded.lines.compactMap(\.event)
        let incomingEvents = inbox.entries.compactMap { $0.line.event }
        let admittedStarts = Self.admittedStartIDs(currentEvents + incomingEvents,
                                                 boundary: metadata.clearedAt, keep: metadata.keepSession)
        for entry in inbox.entries {
            let line = entry.line
            // A recovery bridge is only ever written by the host's locked commit path straight
            // into the main log. An inbox row claiming to be one is forged and must not be
            // drained into history (existing tests cover the extension-published case).
            if line.event?.type == .streakRecovered { continue }
            if let clearedAt = metadata.clearedAt, !belongs(line, to: metadata.keepSession) {
                if let event = line.event, Self.uncertainNonFocus(event) {
                    // No raw timestamp can distinguish late uncertainty from cleared
                    // history. Only an independently retained/new STARTED admits it.
                    if !Self.referencesStart(event, in: admittedStarts) { continue }
                } else if line.at.map({ FlintHistoryEvent.milliseconds($0) < clearedAt }) ?? true { continue }
            }
            if let id = line.id, !ids.insert(id).inserted { continue }
            additions.append(line)
        }
        let combined = loaded.lines + additions
        var committed = false
        if metadata.pendingClear != true, !loaded.unavailable {
            do {
                let bytes = fileSize(path("events.jsonl")) + byteCount(additions) + 2
                if bytes > Self.hardLimit {
                    guard loaded.canRewrite else { throw Failure.capacity }
                    let retained = retainedLines(combined, keeping: currentSessionID(), target: Self.compactedLimit)
                    guard byteCount(retained) <= Self.hardLimit else { throw Failure.capacity }
                    try replaceLog(retained)
                    loaded.lines = retained
                    metadata.lastCompacted = FlintHistoryEvent.milliseconds(clock())
                } else {
                    try appendMain(additions)
                    loaded.lines = combined
                }
                committed = true
            } catch { loaded.lines = combined; loaded.bad = true }
        } else { loaded.lines = combined }
        if committed {
            if !additions.isEmpty { markChanged("drain") }
            // Sync/rename finished first. If deletion is interrupted, event IDs dedupe the retry.
            for entry in inbox.entries { try? fm.removeItem(at: entry.url) }
        }
        let diagnostics = (try? fm.contentsOfDirectory(at: path("diagnostics"), includingPropertiesForKeys: nil)) ?? []
        loaded.bad = loaded.bad || !diagnostics.isEmpty
        if loaded.bad {
            metadata.degraded = true
            metadata.lostAllReadableEvents = loaded.lines.compactMap(\.event).isEmpty
        }
        if (try? saveMetadata(metadata)) != nil {
            for url in diagnostics { try? fm.removeItem(at: url) }
        }
    }

    private func readMain(inbox: Inbox) -> Loaded {
        let url = path("events.jsonl")
        guard fm.fileExists(atPath: url.path) else { return Loaded(lines: [], bad: false, canRewrite: true) }
        guard let data = try? Data(contentsOf: url) else {
            // Readers do not take the host mutex. Publish loss before removing the only
            // evidence, so a suspended recovery cannot turn unreadable history into zero.
            // Include already-published ingress when reconstructing a missing or
            // damaged boundary; recovery time must not hide older readable history.
            var metadata = recoverMetadata(lines: [], inbox: inbox)
            metadata.degraded = true; metadata.lostAllReadableEvents = true
            do { try saveMetadata(metadata) }
            catch { return Loaded(lines: [], bad: true, canRewrite: false, unavailable: true) }
            let saved = quarantineFile(url, moving: true)
            if saved { quarantineMoveHook() }
            return Loaded(lines: [], bad: true, canRewrite: saved, unavailable: !saved)
        }
        var lines: [Line] = [], bad = false, start = data.startIndex
        for end in data.indices where data[end] == 10 {
            if let line = parse(data.subdata(in: start..<end)) { lines.append(line) } else { bad = true }
            start = data.index(after: end)
        }
        if start < data.endIndex { bad = true }
        let safe = !bad || quarantineData(data, kind: "events")
        return Loaded(lines: lines, bad: bad, canRewrite: safe)
    }

    private func readInbox() -> Inbox {
        let folder = path("inbox")
        guard let urls = try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return ([], false) }
        // Monotonic publication-order field, then filename, is the initial file order for ties.
        let sorted = urls.filter { $0.pathExtension == "json" }.sorted {
            let a = $0.lastPathComponent.split(separator: "-").dropFirst().first ?? ""
            let b = $1.lastPathComponent.split(separator: "-").dropFirst().first ?? ""
            return a == b ? $0.lastPathComponent < $1.lastPathComponent : a < b
        }
        var entries: [InboxEntry] = [], bad = false
        for url in sorted {
            guard fileSize(url) <= Self.recordLimit, let data = try? Data(contentsOf: url),
                  data.last == 10, let line = parse(Data(data.dropLast())) else {
                bad = true; _ = quarantineFile(url, moving: true); continue
            }
            entries.append(InboxEntry(url: url, line: line))
        }
        return (entries, bad)
    }

    private func parse(_ raw: Data) -> Line? {
        guard let object = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] else { return nil }
        let event = try? JSONDecoder().decode(FlintHistoryEvent.self, from: raw)
        let supported = event?.isSupported == true
        let future = (object["v"] as? Int ?? 0) > 1
        let unknown: Bool
        if let name = object["type"] as? String {
            unknown = !FlintHistoryEventType.knownTypes.contains { $0.wireName == name }
        } else { unknown = false }
        guard supported || future || unknown else { return nil }
        let session = object["session"] as? String ?? "opaque:\(digest(raw))"
        let civilAt = (object["at"] as? Int64).flatMap { at -> Date? in
            guard at >= -62_135_596_800_000, at <= 253_402_300_799_000 else { return nil }
            return FlintHistoryEvent.date(at)
        }
        return Line(event: supported ? event : nil, raw: raw, session: session,
                    at: civilAt, id: object["id"] as? String)
    }

    private static func uncertainNonFocus(_ event: FlintHistoryEvent) -> Bool {
        (event.timeConfidence?.isUncertain == true || event.reason == "time_uncertain") &&
            [.breakStarted, .breakEnded, .passUsed, .protectionDegraded, .protectionRestored].contains(event.type)
    }

    private static func admittedStartIDs(_ events: [FlintHistoryEvent], boundary: Int64?, keep: String?) -> Set<String> {
        var result = Set<String>()
        guard let boundary else { return result }
        for event in events where event.type == .started {
            if event.session == keep || event.at >= boundary { result.insert(event.session) }
        }
        return result
    }

    private static func referencesStart(_ event: FlintHistoryEvent, in sessions: Set<String>) -> Bool {
        event.type == .passUsed ? event.sessions?.contains(where: sessions.contains) == true : sessions.contains(event.session)
    }

    private func retainedLines(_ lines: [Line], keeping running: String?, target: Int) -> [Line] {
        let cutoff = calendar.date(byAdding: .day, value: -400, to: clock()) ?? clock()
        // A global pass is part of every affected session's evidence. Evict connected
        // sessions together so retaining a start never loses its pass subtraction.
        var parents: [String: String] = [:]
        func root(_ id: String) -> String {
            var current = id
            while let parent = parents[current], parent != current { current = parent }
            return current
        }
        for line in lines where line.event?.type == .passUsed {
            for id in line.event?.sessions ?? [] { parents[root(id)] = root(line.session) }
        }
        let runningGroup = running.map(root)
        let groups = Dictionary(grouping: lines, by: { root($0.session) })
        var retained = Set(groups.filter { id, group in
            id == runningGroup || group.contains { $0.at.map { $0 >= cutoff } ?? true }
        }.keys)
        var total = lines.filter { retained.contains(root($0.session)) }.reduce(0) { $0 + $1.raw.count + 1 }
        let oldest = retained.filter { $0 != runningGroup }.sorted {
            let a = groups[$0]!.compactMap(\.at).max() ?? .distantFuture
            let b = groups[$1]!.compactMap(\.at).max() ?? .distantFuture
            return a == b ? $0 < $1 : a < b
        }
        for id in oldest where total > target { retained.remove(id); total -= byteCount(groups[id]!) }
        return lines.filter { retained.contains(root($0.session)) }
    }

    private func snapshot(_ loaded: Loaded, metadata: Metadata, ledger: LedgerEvidence) -> FlintHistorySnapshot {
        let events = loaded.lines.compactMap(\.event)
        let unavailable = loaded.unavailable || ((loaded.bad || metadata.lostAllReadableEvents == true) && events.isEmpty)
        let availability: FlintHistoryAvailability = unavailable ? .unavailable :
            loaded.bad || metadata.degraded == true ? .degraded :
            events.contains { $0.type == .started } ? .value : .empty
        return .init(events: events, historyStart: metadata.historyStart, availability: availability,
                     recoveryFloorAt: ledger.floor, allowanceTrusted: ledger.trusted)
    }

    private func appendMain(_ lines: [Line]) throws {
        guard !lines.isEmpty else { return }
        let url = path("events.jsonl")
        let fd = open(url.path, O_CREAT | O_RDWR | O_APPEND, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw Failure.io }; defer { close(fd) }
        try protect(url)
        let size = lseek(fd, 0, SEEK_END)
        if size > 0 {
            var tail: UInt8 = 0
            guard pread(fd, &tail, 1, size - 1) == 1 else { throw Failure.io }
            if tail != 10 { try writeOnce(Data([0, 10]), fd: fd) }
        }
        for line in lines { var data = line.raw; data.append(10); try writeOnce(data, fd: fd) }
        guard fsync(fd) == 0 else { throw Failure.io }
    }

    private func encodedLine(_ event: FlintHistoryEvent) throws -> Data {
        var data = try JSONEncoder().encode(event)
        if (event.type == .started || event.type == .rescheduled), event.plannedEnd == nil,
           var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            object["plannedEnd"] = NSNull()
            data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        }
        data.append(10); return data
    }
    private func saveMetadata(_ metadata: Metadata) throws {
        guard metadata.canPersist else { throw Failure.io }
        try metadataWriteHook()
        try atomicWrite(JSONEncoder().encode(metadata), to: path("metadata.json"))
    }
    private func replaceLog(_ lines: [Line]) throws {
        var data = Data(); for line in lines { data.append(line.raw); data.append(10) }
        try atomicWrite(data, to: path("events.jsonl"))
    }
    private func byteCount(_ lines: [Line]) -> Int { lines.reduce(0) { $0 + $1.raw.count + 1 } }
    private func belongs(_ line: Line, to session: String?) -> Bool {
        guard let session else { return false }
        return line.session == session || line.event?.sessions?.contains(session) == true
    }
    private func fileSize(_ url: URL) -> Int {
        (try? fm.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue ?? 0
    }
    private func path(_ name: String) -> URL { directory!.appendingPathComponent(name) }
    private func ensureDirectory(_ url: URL) throws {
        try fm.createDirectory(at: url, withIntermediateDirectories: true); try protect(url)
    }
    private func protect(_ url: URL) throws {
        #if os(iOS)
        try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        #endif
    }
    private func atomicWrite(_ data: Data, to url: URL) throws {
        let temp = url.deletingLastPathComponent().appendingPathComponent(".tmp-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: temp) }
        #if os(iOS)
        try data.write(to: temp, options: .completeFileProtectionUntilFirstUserAuthentication)
        #else
        try data.write(to: temp)
        #endif
        try protect(temp)
        let fd = open(temp.path, O_RDONLY)
        guard fd >= 0 else { throw Failure.io }
        let result = fsync(fd); close(fd)
        guard result == 0, rename(temp.path, url.path) == 0 else { throw Failure.io }
    }
    private func writeOnce(_ data: Data, fd: Int32) throws {
        guard data.withUnsafeBytes({ Darwin.write(fd, $0.baseAddress, $0.count) }) == data.count else { throw Failure.io }
    }

    private func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    private func quarantineData(_ data: Data, kind: String) -> Bool {
        do {
            let folder = path("quarantine"); try ensureDirectory(folder)
            let fingerprint = digest(data)
            let existing = try fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
            if !existing.contains(where: { $0.lastPathComponent.contains(fingerprint) }) {
                try atomicWrite(data, to: folder.appendingPathComponent("\(FlintHistoryEvent.milliseconds(clock()))-\(kind)-\(fingerprint).jsonl"))
            }
            pruneQuarantine(); return true
        } catch { return false }
    }
    private func quarantineFile(_ url: URL, moving: Bool, readLimit: Int = FlintHistoryStore.hardLimit) -> Bool {
        // Metadata/log files that cannot be read are moved without allocating their contents.
        // Readable bounded files share digest deduplication and all copies share pruning.
        if fileSize(url) <= readLimit, let data = try? Data(contentsOf: url) {
            guard quarantineData(data, kind: url.lastPathComponent) else { return false }
            if moving { do { try fm.removeItem(at: url) } catch { return false } }
            return true
        }
        do {
            let folder = path("quarantine"); try ensureDirectory(folder)
            let dest = folder.appendingPathComponent("\(FlintHistoryEvent.milliseconds(clock()))-unreadable-\(UUID().uuidString)")
            try fm.moveItem(at: url, to: dest); try protect(dest); pruneQuarantine(); return true
        } catch { return false }
    }
    private func pruneQuarantine() {
        guard let files = try? fm.contentsOfDirectory(at: path("quarantine"), includingPropertiesForKeys: [.creationDateKey]) else { return }
        let sorted = files.sorted {
            ((try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast) <
            ((try? $1.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast)
        }
        for old in sorted.prefix(max(0, sorted.count - 3)) { try? fm.removeItem(at: old) }
    }
    private func recordIngressFailure() {
        guard directory != nil else { return }
        // A small per-process counter; no log/metadata read and no shared-process lock.
        let count = FlintHistoryHostLocks.nextFailureCount()
        let folder = path("diagnostics")
        try? ensureDirectory(folder)
        try? atomicWrite(Data("{\"dropped\":\(count)}".utf8), to: folder.appendingPathComponent("ingress-\(getpid()).json"))
    }
}

private enum FlintHistoryHostLocks {
    static let guardLock = NSLock()
    static var locks: [String: NSLock] = [:]
    static var failures = 0
    static func lock(for path: String) -> NSLock {
        guardLock.lock(); defer { guardLock.unlock() }
        if let lock = locks[path] { return lock }
        let lock = NSLock(); locks[path] = lock; return lock
    }
    static func nextFailureCount() -> Int {
        guardLock.lock(); defer { guardLock.unlock() }
        failures += 1; return failures
    }
}

public final class FlintHistorySink: FlintSessionEventSink, @unchecked Sendable {
    public static let shared = FlintHistorySink(store: .shared)
    public let store: FlintHistoryStore
    private let isExtension: Bool
    private let activity: ((@escaping (Bool) -> Void) -> Void)?
    private let queue = DispatchQueue(label: "app.flint.history", qos: .utility)
    public init(store: FlintHistoryStore, isExtension: Bool = Bundle.main.bundlePath.hasSuffix(".appex"),
                clock _: @escaping () -> Date = Date.init,
                activity: ((@escaping (Bool) -> Void) -> Void)? = nil) {
        self.store = store; self.isExtension = isExtension
        self.activity = activity
    }
    public func handle(_ event: FlintSessionLifecycleEvent) {
        // The lifecycle owner gates completion at its logical deadline. Rechecking the
        // original wall end (or this reader's clock) can discard an accepted transition
        // after a clock rollback, including one committed by another process.
        var record = FlintHistoryEvent(event)
        if case .started = event, record.grant == nil {
            record.grant = Self.recoveryGrant()
        }
        // Durable ingress precedes return in every process. Expiration can skip later
        // maintenance, but cannot discard this accepted lifecycle transition.
        store.append(record)
    }

    /// The frozen protection a session began under. Streak recovery credits only sessions whose
    /// `grant` equals that fingerprint, so this row is the production binding between "protection
    /// was enforced" and "this focus time may bridge a missed day".
    ///
    /// Deliberately independent of per-session snapshot write order: the binding is the live
    /// freeze plus the established picker selection it was computed from. A picker change makes
    /// the fingerprints differ, so later sessions stop earning credit instead of inheriting it.
    private static func recoveryGrant() -> String? {
        #if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
        guard let freeze = RecoveryChallengeGuardStore().current(), !freeze.isEmpty,
              let selection = FlintGroupStore()?.loadSelection(),
              StreakRecoveryProtection.fingerprint(of: selection) == freeze.fingerprint else {
            return nil
        }
        return freeze.fingerprint
        #else
        return nil
        #endif
    }
    /// Host work is ordered and off the enforcement/UI thread. Activity protection improves
    /// completion odds only; atomic inbox publication and idempotent drain provide correctness.
    public func perform(_ operation: @escaping (FlintHistoryStore) -> Void) {
        guard !isExtension else { return }
        queue.async { [store, activity] in
            let done = DispatchSemaphore(value: 0)
            let state = FlintHistoryActivity()
            let body: (Bool) -> Void = { expired in
                if expired { if state.cancelBeforeStart() { done.signal() }; return }
                guard state.begin() else { return }
                operation(store); done.signal()
            }
            if let activity { activity(body) }
            else {
                #if os(iOS)
                ProcessInfo.processInfo.performExpiringActivity(withReason: "Maintain local focus history", using: body)
                #else
                body(false)
                #endif
            }
            done.wait() // worker queue only; no cross-process resource is locked
        }
    }
}

private final class FlintHistoryActivity: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false, began = false
    func begin() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !cancelled else { return false }; began = true; return true
    }
    func cancelBeforeStart() -> Bool {
        lock.lock(); defer { lock.unlock() }
        cancelled = true; return !began
    }
}
