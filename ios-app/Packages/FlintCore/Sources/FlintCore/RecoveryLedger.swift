import Foundation
import CryptoKit
#if canImport(Security)
import Security
#endif

/// Integrity for the persisted recovery allowance (policy v1).
///
/// The allowance outlives the session rows that prove it, so it is mirrored in a small
/// authenticated ledger. A plain checksum would let anyone who can edit the app-private file
/// recompute the tag, so the tag is an HMAC-SHA256 keyed by material that does not normally
/// live beside the ledger: a Keychain item with `…ThisDeviceOnly` accessibility, with a
/// per-install key-file fallback for the (rare) case where the Keychain is unavailable.
///
/// This detects corruption and casual edits. It is **not** a defence against an attacker who can
/// read app-private storage or instrument the process, and it provides **no anti-rollback
/// protection**: an older, still validly tagged ledger can be put back (see
/// `docs/architecture/streak-best-retention.md`).
public protocol RecoveryLedgerAuthenticator {
    /// Hex HMAC of the payload, or nil when signing is unavailable (recovery then fails closed).
    func tag(for payload: String) -> String?
    func verify(payload: String, tag: String) -> Bool
    /// Whether this history directory already holds ledger key material, using no new I/O for
    /// creation. Used to prove that a ledger *was* sealed here even after the file is deleted.
    var ledgerKeyStatus: RecoveryLedgerKeyStatus { get }
}

/// Durable key-material state for one history directory.
///
/// `present` proves prior initialization; `absent` means no ledger was ever sealed here. `unknown`
/// (the Keychain cannot be inspected right now) means the allowance is *temporarily unverifiable*:
/// callers hide recovery and refuse writes, but must not invent a cooldown timestamp. The identity
/// resolves on a later read: `absent` initializes normally, `present` runs the conservative
/// missing-ledger repair, and `unknown` keeps failing closed.
public enum RecoveryLedgerKeyStatus: Equatable, Sendable {
    case present, absent, unknown
}

public extension RecoveryLedgerAuthenticator {
    /// Injected authenticators (tests, in-memory keys) are not durable installation markers.
    var ledgerKeyStatus: RecoveryLedgerKeyStatus { .absent }
}

/// Payload and on-disk shape of `recovery-ledger.v1.json`.
///
/// The payload is domain-separated from every other tag the app computes, so a recovery-ledger
/// tag can never be replayed as another artifact's tag or vice versa.
public enum RecoveryLedgerFormat {
    public static let payloadPrefix = "recovery-ledger|v1|"
    /// How far a genuine floor may appear in the future before it is treated as corrupt. A
    /// genuine floor is written with the device clock, so only a clock change can put it ahead;
    /// one day tolerates a manual correction, and anything larger must not lock recovery out
    /// for decades.
    public static let maxFutureSkewMs: Int64 = 24 * 60 * 60 * 1000

    public static func payload(_ lastRecoveryAt: Int64) -> String {
        "\(payloadPrefix)\(lastRecoveryAt)"
    }

    struct Wire: Codable {
        var v: Int
        var lastRecoveryAt: Int64
        var tag: String
    }

    static func encode(lastRecoveryAt: Int64, tag: String) throws -> Data {
        try JSONEncoder().encode(Wire(v: 1, lastRecoveryAt: lastRecoveryAt, tag: tag))
    }

    /// Trusts the value only for a supported version, a positive timestamp, a value inside the
    /// future-skew allowance, and a tag that verifies. Every other outcome is `corrupt`.
    static func decode(_ data: Data, nowMs: Int64,
                       verify: (String, String) -> Bool) -> RecoveryLedgerState {
        let limit = nowMs > Int64.max - maxFutureSkewMs ? Int64.max : nowMs + maxFutureSkewMs
        guard let wire = try? JSONDecoder().decode(Wire.self, from: data), wire.v == 1,
              wire.lastRecoveryAt > 0, wire.lastRecoveryAt <= limit,
              verify(payload(wire.lastRecoveryAt), wire.tag) else {
            return RecoveryLedgerState(lastRecoveryAt: nil, corrupt: true)
        }
        return RecoveryLedgerState(lastRecoveryAt: wire.lastRecoveryAt, corrupt: false)
    }

    static func hexData(_ hex: String) -> Data? {
        guard !hex.isEmpty, hex.count % 2 == 0 else { return nil }
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }
}

/// Result of reading the ledger: a nil value with `corrupt == false` means "no ledger yet".
public struct RecoveryLedgerState: Equatable, Sendable {
    public let lastRecoveryAt: Int64?
    public let corrupt: Bool
    public init(lastRecoveryAt: Int64?, corrupt: Bool) {
        self.lastRecoveryAt = lastRecoveryAt; self.corrupt = corrupt
    }
}

/// Deterministic HMAC authenticator. Used for the key-file fallback and by tests.
public struct HMACRecoveryLedgerAuthenticator: RecoveryLedgerAuthenticator {
    private let key: SymmetricKey

    public init(key: Data) { self.key = SymmetricKey(data: key) }

    public func tag(for payload: String) -> String? {
        Self.tag(payload: payload, key: key)
    }

    public func verify(payload: String, tag: String) -> Bool {
        Self.verify(payload: payload, tag: tag, key: key)
    }

    static func tag(payload: String, key: SymmetricKey) -> String? {
        let mac = HMAC<SHA256>.authenticationCode(for: Data(payload.utf8), using: key)
        return mac.map { String(format: "%02x", $0) }.joined()
    }

    static func verify(payload: String, tag: String, key: SymmetricKey) -> Bool {
        guard let data = RecoveryLedgerFormat.hexData(tag) else { return false }
        return HMAC<SHA256>.isValidAuthenticationCode(
            data, authenticating: Data(payload.utf8), using: key)
    }
}

/// Per-install secret in the Keychain (`…ThisDeviceOnly`: not synced, not migrated to a new
/// device, not restored from a backup on another device). The key is fetched lazily, so building
/// a store — or an extension's read-only view — never touches the Keychain.
public final class KeychainRecoveryLedgerAuthenticator: RecoveryLedgerAuthenticator, @unchecked Sendable {
    private let service: String
    private let account: String
    private let lock = NSLock()
    private var cached: SymmetricKey?

    public init(service: String = "app.flint.recoveryLedger", account: String = "v1") {
        self.service = service
        self.account = account
    }

    /// One Keychain identity per history directory. A deleted ledger in *this* directory is then
    /// distinguishable from a genuinely fresh installation elsewhere (production has exactly one
    /// directory per installation; tests use isolated directories).
    public static func account(for directory: URL?) -> String {
        guard let directory else { return "v1" }
        let digest = SHA256.hash(data: Data(directory.standardizedFileURL.path.utf8))
        return "v1:" + digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// Reads, never creates: a fresh install must not gain a durable marker just by launching.
    public var ledgerKeyStatus: RecoveryLedgerKeyStatus {
        lock.lock(); defer { lock.unlock() }
        if cached != nil { return .present }
        #if canImport(Security)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess {
            return (item as? Data)?.count == 32 ? .present : .unknown
        }
        return status == errSecItemNotFound ? .absent : .unknown
        #else
        return .unknown
        #endif
    }

    public func tag(for payload: String) -> String? {
        guard let key = key() else { return nil }
        return HMACRecoveryLedgerAuthenticator.tag(payload: payload, key: key)
    }

    public func verify(payload: String, tag: String) -> Bool {
        guard let key = key() else { return false }
        return HMACRecoveryLedgerAuthenticator.verify(payload: payload, tag: tag, key: key)
    }

    private func key() -> SymmetricKey? {
        lock.lock(); defer { lock.unlock() }
        if let cached = cached { return cached }
        let loaded = Self.loadKey(service: service, account: account)
        cached = loaded
        return loaded
    }

    private static func loadKey(service: String, account: String) -> SymmetricKey? {
        #if canImport(Security)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data, data.count == 32 {
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound else { return nil }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            return nil
        }
        let data = Data(bytes)
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data,
        ]
        let added = SecItemAdd(add as CFDictionary, nil)
        if added == errSecSuccess { return SymmetricKey(data: data) }
        if added == errSecDuplicateItem {
            var existing: CFTypeRef?
            if SecItemCopyMatching(query as CFDictionary, &existing) == errSecSuccess,
               let stored = existing as? Data, stored.count == 32 {
                return SymmetricKey(data: stored)
            }
        }
        return nil
        #else
        return nil
        #endif
    }
}

/// Per-install key file used only when the Keychain cannot sign. It lives beside the ledger, so
/// an app-data copy carries the key with it: the fallback keeps corruption and casual edits
/// detectable but not an attacker who copies app-private storage (the same boundary Android
/// documents for its key-file fallback).
public struct FileKeyRecoveryLedgerAuthenticator: RecoveryLedgerAuthenticator {
    private let fileURL: URL?

    public init(fileURL: URL?) { self.fileURL = fileURL }

    /// The key file's existence *is* the durable marker: it is only created when a ledger is
    /// sealed, and it survives deletion of the ledger alone.
    public var ledgerKeyStatus: RecoveryLedgerKeyStatus {
        guard let fileURL else { return .absent }
        return FileManager.default.fileExists(atPath: fileURL.path) ? .present : .absent
    }

    public func tag(for payload: String) -> String? {
        guard let key = key() else { return nil }
        return HMACRecoveryLedgerAuthenticator.tag(payload: payload, key: key)
    }

    public func verify(payload: String, tag: String) -> Bool {
        guard let key = key() else { return false }
        return HMACRecoveryLedgerAuthenticator.verify(payload: payload, tag: tag, key: key)
    }

    private func key() -> SymmetricKey? {
        guard let fileURL else { return nil }
        if let existing = try? Data(contentsOf: fileURL), existing.count == 32 {
            return SymmetricKey(data: existing)
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        #if canImport(Security)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            return nil
        }
        #else
        for index in bytes.indices { bytes[index] = UInt8.random(in: UInt8.min ... UInt8.max) }
        #endif
        let data = Data(bytes)
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            #if os(iOS)
            try data.write(to: fileURL,
                           options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try data.write(to: fileURL, options: .atomic)
            #endif
        } catch { return nil }
        return SymmetricKey(data: data)
    }
}

/// Keychain primary with a per-install key-file fallback. `verify` accepts either, so a device
/// whose Keychain becomes unavailable does not silently invalidate an earlier ledger.
public struct FallbackRecoveryLedgerAuthenticator: RecoveryLedgerAuthenticator {
    private let primary: RecoveryLedgerAuthenticator
    private let fallback: RecoveryLedgerAuthenticator

    public init(primary: RecoveryLedgerAuthenticator, fallback: RecoveryLedgerAuthenticator) {
        self.primary = primary; self.fallback = fallback
    }

    public func tag(for payload: String) -> String? {
        primary.tag(for: payload) ?? fallback.tag(for: payload)
    }

    public func verify(payload: String, tag: String) -> Bool {
        primary.verify(payload: payload, tag: tag) || fallback.verify(payload: payload, tag: tag)
    }

    /// Present when either source holds material; unknown when neither holds material but one
    /// cannot be inspected (never treated as proof).
    public var ledgerKeyStatus: RecoveryLedgerKeyStatus {
        let primaryStatus = primary.ledgerKeyStatus
        if primaryStatus == .present { return .present }
        let fallbackStatus = fallback.ledgerKeyStatus
        if fallbackStatus == .present { return .present }
        if primaryStatus == .unknown || fallbackStatus == .unknown { return .unknown }
        return .absent
    }
}
