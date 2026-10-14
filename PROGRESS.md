# Engineering progress

## Current phase

Phases A–D done and verified; Phase E (secondary features) largely done: rules, schedules,
reconciliation, CSV, payees, bulk month actions. Remaining work is listed below.

## Completed milestones

| Milestone | Evidence |
|---|---|
| Reference pinned: Actualist `actualist-dev` `61987e5`; Actual `v26.10.0` (`2bebdbaa`) source read for the bulk actions | `REFERENCE.md` |
| Private repo, Gradle 9.6.0 wrapper (checksum-verified), AGP 9.4.1 built-in Kotlin, Kotlin 2.4.21, compileSdk 37 / targetSdk 36 / minSdk 26 | CI |
| Sync primitives: HLC, merkle trie + murmur3, sync values, protobuf codec, AES-256-GCM/PBKDF2 | Upstream merkle vectors; Node-generated crypto vectors; byte-exact protobuf tests |
| SQLite budget database: CRDT apply (LWW, duplicates, quarantine, reserved datasets), atomic local commit + outbox, merkle persistence, restart | `BudgetDatabaseTest` (16) |
| Envelope + tracking budget math and writes (assign, move, cover, transfer-available, carryover, hold/reset) | **BudgetMonth oracle: 85 replayed cases match** (OP-32 note cells and 2 cases with inherited AND-01 excused); **Holds oracle 14/14**; **Tracking contract 36/36** |
| Bulk month actions Actualist lacks: copy previous month, zero, 3-month average, copy to year end, cover overbudgeted | Ported from Actual's `actions.ts`; the 5 formerly oracle-only BudgetMonth cases now replay and match |
| Transaction creation (manual, transfer, import, starting balance) | **Transactions oracle 21/21** (2 with Actualist's intended OP-86) |
| Rule engine (conditions, ranking, actions, formulas, BALANCE_OF, split actions, schedule date conditions) | **Rules oracle: 245/245 replayed cases match** (24 oracle-only listed) |
| Schedules (recurrence, status, create/post/skip/complete/delete, advancement) | **Schedules oracle: recurrence 136/136, status 49/49, stateful 66/66** (OP-51/52/53 as in Actualist) |
| Split create and edit, transaction edit/delete, account create/rename/close | `SplitEditTest`, unit tests |
| Reconciliation (adjustment, lock, unlock; rules run on the adjustment) | `ReconciliationTest` (cases from Actualist's reconciliation oracle) |
| CSV export (Actual's format) and import (duplicate-skipping, rules run on each row) | `CsvTest` incl. export→import round trip and rules on import |
| Categories/groups create, rename, hide; payee rename and merge | `CategoryWritesTest`, `PayeeWritesTest` |
| Sync engine: push with server confirmation, pull, merkle re-pull, encryption, batching | `SyncEngineTest` (11 multi-device scenarios) |
| Actual server HTTP client, encrypted download/import, sanitizer | `ServerIntegrationTest` (JVM loopback server, end to end) |
| Compose app: onboarding + demo, Budget, Spending, Accounts, Reports, Settings, Rules, Payees, Schedules, editor (rule preview, splits), reconcile, CSV, server sign-in/download/sync, auto-flush and sync on resume | Emulator UI tests (API 34) with screenshots, incl. an on-device sign-in/download/edit/sync flow against a loopback server and a process-death relaunch check |
| Performance | 30,060-transaction smoke test (CI JVM): month view 784 ms, list 375 ms, balances 52 ms, reports 1.0 s; recurrence inputs memoized by `total_changes()` |

## Latest CI

- Build and unit test: green, https://github.com/sporez/actualist-android/actions/runs/38513472719 — 1,122 JVM tests, 0 failures, 0 skipped (count from the run before, same tests); debug APK artifact `actualist-android-debug-apk`.
- Emulator (API 34): https://github.com/sporez/actualist-android/actions/runs/38513696781 — **9/9 UI tests passed** (second consecutive 9/9; previous https://github.com/sporez/actualist-android/actions/runs/38512580752) (onboarding, every main screen, assign, add transaction, rule preview, reconcile, schedules, activity recreation, sign-in/download/edit/sync against a loopback server); process-death relaunch reopened the selected budget.
- Resolved: an earlier after-reconcile display capture showed the old balance. Compose's render and a later display capture both show the new state; the stale frame was emulator compositing lag (software GPU) right after the dialog closed. Screenshots now use Compose's render whenever no dialog is open.

## Bugs found by the emulator (all fixed)

- Android's ICU regex rejects `Pattern.UNICODE_CHARACTER_CLASS`; the rule engine crashed on device while JVM tests passed. Flag now used only where supported.
- A newly created newest transaction could be inserted above the visible list (LazyColumn key anchoring).
- Emulator infra: runner disk too small for the AVD; system ANR dialogs covering the app.

## Known failures and limitations

- Not implemented: budget templates, bank sync (SimpleFIN), transaction duplicate/merge/batch edit, rule and schedule editors, OpenID/header login, account groups, notes editing, widgets, background sync while the app is closed (WorkManager).
- Transfer payee/account changes on edit are refused rather than re-pairing the transfer.
- CSV import skips exact duplicates only; Actualist's fuzzy import-reconcile matcher and import rules are not ported.
- Plain-http servers are refused except on loopback (Android network security config matches).
- Server sync has only been exercised against in-repo mock servers, never a real Actual server (by design for this experiment).

## Parity findings

- AND-01: a refused hold refuses atomically; Actual commits the income-carryover reset first. Inherited from Actualist (its harness attributes the rows to OP-32). See `KNOWN_DIVERGENCES.md`.
- Merkle re-pull after a partial outbox batch reports a false out-of-sync; fixed here by comparing only after the last batch. Actualist's flush has the same shape (500-message batches then the re-pull loop); not verified against Actualist itself.
- Reconciliation unlock of a child under a locked parent follows Actual (no write), not Actualist's OP-19.

## Next recommended work

1. Rule and schedule editors (the engines and writers exist).
2. Port Actualist's fuzzy import-reconcile matcher for CSV import.
3. Background sync with WorkManager (needs a busy-timeout story for a second SQLite connection).
4. Budget templates (Actualist has a large template engine and fixtures).
