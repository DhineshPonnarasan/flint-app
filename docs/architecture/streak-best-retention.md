# Best-streak retention and recovery ledger

Local-only durability for two derived values that outlive ordinary event retention.

## Best-streak checkpoint

`metadata.v1.json` (schema `v:1`, additive keys) carries:

| Key | Meaning |
| --- | --- |
| `bestRunDays` | Longest consecutive recorded/recovered-day run ever proven by real events. |
| `bestTailDays` | Length of the open run ending on the last day before the oldest retained day. |
| `bestTailEndDay` | Epoch day of that boundary (null when there is no open tail). |
| `bestRunVersion` | `1` for the authenticated payload. |
| `bestRunMac` | Hex HMAC-SHA256 over `v1\|createdAt\|bestRunDays\|bestTailDays\|tailEndDay`. |

**Read:** a non-zero checkpoint is used only when the MAC verifies. Otherwise it is discarded,
`bestCheckpointUnverified` is set, the aggregator shows the derived (retained-history) best, and the
streak card labels it “recent history only”.

**Compaction transaction:** read a fully readable history → compute the canonical day set
(recorded terminal zones, recovered days) → build the checkpoint (previous floor, new best, joined
tail) → **persist it** (a signing failure throws *before* any replacement) → read it back and
require the three fields to match (a failure restores the previous metadata bytes verbatim,
including the old MAC) → only then rewrite the log. A crash between the checkpoint write and the
prune leaves the full history, where the derived best already covers the checkpoint.

**Signing:** production uses an Android Keystore HMAC key with a per-install key-file fallback.
This detects corruption and casual edits; it is **not** a defence against an attacker who can read
app-private storage or instrument the process.

**Degraded history:** the checkpoint is never moved from a partial read, and the log is
normalized (re-encoded) without age/size pruning. The damaged original is retained under
`quarantine/`.

## Migration and downgrade

* **Legacy (pre-integrity) metadata:** unsigned values are not trusted. They are treated as
  absent, which can lower the reported best once; the next compaction re-signs exactly what the
  retained days prove. Nothing is inflated and nothing crashes.
* **Downgrade to an older build:** metadata stays `v:1`; unknown keys are ignored on read, and an
  older build's metadata write drops `bestRun*`/`bestRunMac`. The best then reverts to the
  retained-history value (lower, never higher). Recovery, the ledger and the history log are
  unaffected.

## Recovery ledger

`recovery-ledger.v1.json` = `{"v":1,"lastRecoveryAt":<ms>,"mac":"<hex>"}`. It is the only carrier of
the rolling 30×24 h allowance once Clear History has removed session rows; recovery rows are no
longer retained for the cooldown.

| Key | Meaning |
| --- | --- |
| `v` | Schema version. Any value other than `1` is untrusted. |
| `lastRecoveryAt` | Epoch ms of the last credited recovery, written with the device clock. |
| `mac` | Hex HMAC-SHA256 over `recovery-ledger\|v1\|<lastRecoveryAt>`. |

**Why a domain-separated payload:** the checkpoint signs `v1|createdAt|…`. Prefixing the ledger
payload with `recovery-ledger` makes the two MAC inputs disjoint, so neither signature can be
replayed as the other even though both use the same key.

**Read:** the value is trusted only when the file parses, `v == 1`, the MAC verifies and the
timestamp is positive and no more than 24 h in the future. Any other outcome is *corrupt*, and a
corrupt ledger is never treated as an unused allowance.

**Atomic, monotonic write:** the JSON (value, version and MAC) is written through the same staged
atomic replace as the history log; an older value never replaces a newer one. If signing is
unavailable the write throws instead of persisting an unauthenticated value.

**Fail-closed repair:** detection marks the store `degraded` for the rest of the process, copies the
corrupt file into `quarantine/` (content-addressed, so repeated reads make one copy), and re-seals a
new floor: the durable event floor when it is sane, otherwise the detection time. So corruption can
never refund an allowance — the worst case is a cooldown that restarts when the evidence was
already gone. If the repaired ledger cannot be signed, written and read back, the store stays
untrusted and recovery stays unavailable; Clear History cannot bypass that state because it must
also write the floor before it deletes rows.

**Why the 24 h future bound:** without it, a single bad digit could name a date decades away and
lock the user out of recovery permanently. A genuine floor is written with the device clock, so the
only way it legitimately appears in the future is a clock change. One day tolerates a manual
correction or a timezone/dst misconfiguration; anything larger is treated as corruption and
repaired. The cooldown is inherently wall-clock based: a user who moves the device clock *forward*
can still shorten it, and that is out of scope for a local, no-account store.

**Legacy unsigned ledger:** a pre-integrity `{v,lastRecoveryAt}` file has no MAC and is treated as
corrupt, never trusted. The repair re-seals the durable event floor when recovery rows still prove
it — the real cooldown is preserved, no refund — and otherwise starts a fresh cooldown at detection.

**Replay is not prevented.** The Keystore key authenticates the *value*, not the *position in
time*: there is no hardware anti-rollback counter or monotonic storage in this design, so anyone
who can write app-private storage (rooted device, OEM migration tool, instrumentation) can put back
an older, still validly signed ledger and shorten the cooldown. Standard backup replay is disabled
at the manifest level (`android:allowBackup="false"`), so Auto Backup and `adb backup` cannot restore
an older ledger. The local key-file fallback removes the Keystore binding only; the replay property
is unchanged, and that key file would travel with any app-data copy. This is an accepted limitation
of a local-first, no-account product: the MAC defends against corruption and casual edits, not
against an attacker with app-private write access or control of the device clock.

## iOS implementation

iOS keeps the same product rules and the same *semantics* (one recovery per rolling 30 × 24 h,
a provable floor, fail-closed repair), but not the same storage code: there is no Keystore, no
`AtomicFileReplace`, and the history store already has its own lock, inbox and quarantine.

`FlintHistoryStore` writes `recovery-ledger.v1.json` beside the log:

| Key | Meaning |
| --- | --- |
| `v` | Schema version; anything but `1` is untrusted. |
| `lastRecoveryAt` | Epoch ms of the last credited recovery. |
| `tag` | Hex HMAC-SHA256 over `recovery-ledger\|v1\|<lastRecoveryAt>`. |

**Key material.** A 32-byte secret in a Keychain generic-password item
(`app.flint.recoveryLedger` / `v1`) with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`:
device-only, not synced by iCloud Keychain, not migrated to a new device, and not restored by a
backup on another device. If the Keychain cannot sign, a per-install key file
(`recovery-ledger-key.v1`, file-protected) is used instead, so a device whose Keychain is
unavailable does not lose the ability to seal a recovery. The fallback is weaker: the key then
travels with any app-data copy, leaving only corruption and casual-edit detection. Keys are read
lazily, so constructing a store never touches the Keychain.

**Read.** A value is trusted only when the file parses, `v == 1`, `lastRecoveryAt > 0`, the
timestamp is no more than 24 h in the future, and the tag verifies. `FlintHistorySnapshot`
carries the combined floor (`recoveryFloorAt`) and `allowanceTrusted`.

**Repair.** A corrupt or unverifiable ledger is never read as an unused allowance. Readable
evidence is copied to `quarantine/`; the repair re-seals the durable event floor when it is sane,
otherwise the detection time, and only counts after a verified read-back. A repair that cannot be
signed or written leaves `allowanceTrusted == false`: recovery offers, commits and Clear History
are all refused, and the previous bytes are restored so a flaky signer cannot destroy authenticated
evidence. A *missing* ledger on an upgrade is seeded from the retained recovery rows, so no
allowance is lost or refunded.

**Missing ledger ≠ fresh install.** The ledger's key material is the durable installation marker:
the Keychain account is scoped to the history directory (`v1:<sha256(path)[:16]>`), and the
fallback key file lives beside the ledger. Both are created only when a ledger is sealed, and the
marker check never creates anything. If no ledger file and no recovery row exist, the marker
decides:

* `present` — a ledger was sealed here and is gone: consume the allowance from the detection time
  and require the verified repair before recovery is available again. This closes the case where a
  retention trim removed the proving row and something then removed the ledger file.
* `absent` — genuinely new: eligible, and no cooldown is written.
* `unknown` (the Keychain cannot be inspected) — the allowance is *temporarily unverifiable*: the
  card is hidden, commits and Clear History are refused, and **nothing is written**, so no cooldown
  timestamp is fabricated. A later read resolves the identity: `absent` initializes normally,
  `present` runs the detection-time repair, and `unknown` keeps failing closed.

**Clear and retention.** Clear writes/seals the floor before it deletes rows (so a clear cannot
refund), and the hard-cap/retention path refuses to prune while the allowance evidence cannot be
authenticated. Recovery rows are still retained through a clear for the in-memory views; the
ledger is what makes the allowance survive their eventual eviction. A store already marked
`degraded` with no provable floor starts a fresh cooldown at the clear instead of refunding.

**Authorization.** iOS has no armed authorization window to race. Recovery bridges are written
only by `commitStreakRecovery` inside the host lock; every `append` lands in the inbox, and the
host drain drops any `streak_recovered` row from the inbox, so a forged append never becomes
history and never degrades the store. The second of two concurrent commits is rejected by the
`already_recovered` eligibility check, and a restart cannot revive an authorization because none
is stored. The commit path re-checks `allowanceTrusted` itself, so a caller that ignores the UI
flag still cannot spend an unverifiable allowance.

**iOS threats and limits.**

* A Keychain `ThisDeviceOnly` secret authenticates the value on this device and is not synced, not
  migrated, and not restored by a backup on another device.
* The fallback key file *does* travel with an app-data copy, so a copy that includes it removes the
  corruption/casual-edit protection and leaves only the replay boundary below.
* Deleting the ledger alone (with the row already pruned) fails closed: the scoped Keychain
  identity or the key file still proves initialization.
* Deleting *every* app-private file, with the Keychain identity genuinely gone (`absent`), leaves
  no provable evidence: that state is indistinguishable from a new installation and is eligible.
  This is a documented boundary, not a claim of complete protection. If the Keychain is merely
  inaccessible (`unknown`), recovery instead stays off until the identity resolves.
* A reinstall gets a new container path, so the scoped marker does not match: the install behaves
  like a fresh one (the same outcome as Android, where uninstall deletes the Keystore key). The
  previous Keychain item is orphaned, not reused.
* A copied older, validly tagged ledger still shortens the cooldown — no anti-rollback claim.
* Clock changes still move a wall-clock allowance; out of scope are an attacker with app-private
  write access, process instrumentation, and time control.

* Normal clear, every interrupted-clear phase, over-budget reset and restart preserve the ledger.
* `lastSuccessfulRecoveryAt(events)` and the ledger are combined with `max`, so the allowance is
  never earlier than either source proves.

## Clear History

Clear resets the best-streak checkpoint and every checkpoint field in the clear intent, while the
ledger keeps the recovery cooldown. The checkpoint is reset because Clear is an explicit user
request; the allowance is preserved because it is a product rule independent of session rows.
