# Engineering progress

## Current phase

Phases A–C done and verified; Phase D (server sync) implemented and verified against a
loopback mock server; Phase E in progress (rules done, schedules being ported).

## Completed milestones

| Milestone | Evidence |
|---|---|
| Reference pinned: Actualist `actualist-dev` `61987e5` | `REFERENCE.md` |
| Private repo, Gradle 9.6.0 wrapper (checksum-verified), AGP 9.4.1 built-in Kotlin, Kotlin 2.4.21, compileSdk 37 / targetSdk 36 | CI |
| Sync primitives: HLC, merkle trie + murmur3, sync values, protobuf codec, AES-256-GCM/PBKDF2 | Upstream merkle vectors; Node-generated crypto vectors; byte-exact protobuf tests |
| SQLite budget database: CRDT apply (LWW, duplicates, quarantine, reserved datasets), atomic local commit + outbox, merkle persistence, restart | `BudgetDatabaseTest` (16) |
| Envelope + tracking budget math and writes (assign, move, cover, transfer-available, carryover, hold/reset) | **BudgetMonth oracle: 80 replayed cases match (2 with inherited AND-01)**; **Holds oracle 14/14**; **Tracking contract 36/36** |
| Transaction creation (manual, transfer, import, starting balance) | **Transactions oracle 21/21** (2 with Actualist's intended OP-86) |
| Rule engine (conditions, ranking, actions, formulas, BALANCE_OF, split actions, schedule date conditions) | **Rules oracle: 245/245 replayed cases match** (24 oracle-only listed) |
| Split create and edit, transaction edit/delete, account create/rename/close | `SplitEditTest`, unit tests |
| Account reconciliation (adjustment, lock, unlock) | `ReconciliationTest` (cases from Actualist's reconciliation oracle) |
| CSV export (Actual's format) and import (duplicate-skipping) | `CsvTest` incl. export→import round trip |
| Category/group create, rename, hide | `CategoryWritesTest` |
| Sync engine: push with server confirmation, pull, merkle re-pull, encryption, batching | `SyncEngineTest` (11 multi-device scenarios) |
| Actual server HTTP client, encrypted download/import, sanitizer | `ServerIntegrationTest` (loopback mock server, end to end) |
| Compose app: onboarding + demo, Budget, Spending, Accounts, Reports, Settings, Rules, transaction editor (rule preview, splits), reconcile, CSV, server sign-in/download/sync | Emulator UI tests (API 34) with screenshots |

## Latest CI

- Build and unit test: green, https://github.com/sporez/actualist-android/actions/runs/38506583444 (787 JVM tests at the previous count, 0 failures, 0 skipped; debug APK artifact `actualist-android-debug-apk`).
- Emulator (API 34, Pixel 6 profile): run https://github.com/sporez/actualist-android/actions/runs/38505552545 booted and ran 5 UI tests: 4 passed, 1 failed on a test-side finder (fixed). Re-run with 7 tests in progress: https://github.com/sporez/actualist-android/actions/runs/38506969510.

## Known failures and limitations

- Schedules: being ported (worker); not yet in the app.
- Not implemented: budget templates, bank sync (SimpleFIN), payee management/merge, transaction duplicate/merge/batch edit, rule editing, OpenID/header login, account groups, notes editing, widgets.
- Transfer payee/account changes on edit are refused rather than re-pairing the transfer.
- CSV import skips exact duplicates only; Actualist's fuzzy import-reconcile matcher and import rules are not ported.
- Plain-http servers are refused except on loopback (Android network security config matches).
- Server sync has only been exercised against the in-repo mock server, never a real Actual server (by design for this experiment).

## Parity findings

- AND-01: a refused hold refuses atomically; Actual commits the income-carryover reset first. Inherited from Actualist (its harness attributes the rows to OP-32). See `KNOWN_DIVERGENCES.md`.
- Merkle re-pull after a partial outbox batch reports a false out-of-sync; fixed here by comparing only after the last batch. Actualist's flush has the same shape (500-message batches then the re-pull loop); not verified against Actualist itself.
- Reconciliation unlock of a child under a locked parent follows Actual (no write), not Actualist's OP-19.

## Next recommended work

1. Integrate the schedules port, add a Schedules screen, replay the Schedules oracle.
2. Background sync (WorkManager) and conflict/edit UX on real servers (only after a disposable test server exists).
3. Templates and payee management.
