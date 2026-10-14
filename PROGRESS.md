# Engineering progress

## Current phase

All of Actualist's pinned Actual-oracle domains are ported to Kotlin and replayed in CI.
The Compose app exposes nearly all of them. Remaining work is listed at the bottom.

## Oracle parity (Actual v26.10.0 fixtures, synthetic data)

| Domain | Replayed cases | Excused (as Actualist) | Not replayed |
|---|---|---|---|
| BudgetMonth (incl. 5 bulk actions Actualist lacks) | 85 | OP-32 note cells; AND-01 (2 cases, inherited) | 1 oracle-only |
| Holds | 14 | — | 2 (as Actualist) |
| Tracking recurrence | 36 | — | — |
| Transactions (create) | 21 | OP-86 | — |
| TransactionCommand (duplicate, merge, and their undo) | 39 + 39 undo | OP-70..76 | — |
| TransactionQuery (filters, grouping, split modes, saved filters) | 38 | OP-78, OP-79 | 13 oracle-only |
| Splits | 23 | — | 2 oracle-only |
| Rules | 245 | — | 24 oracle-only |
| Schedules | 136 recurrence + 49 status + 66 stateful | OP-51..53 | 2 oracle-only |
| TemplateApply | 135 (×2 modes) | OP-24 (as-stated mode only), OP-25 | — |
| Template editor (normalize, transitions, validation) | 44 + aggregate-demand family | — | — |
| AccountLifecycle (incl. 5 undo observations) | 16 | OP-22, AND-02 | 6 (forced delete, GoCardless) |
| BankSync / import reconcile | 465 (+5 provider-mapping cases via the SimpleFIN mapping) | OP-58..62, 66..68 | 14 oracle-only |
| Reconciliation | cases ported as unit tests | — | — |

Details: `KNOWN_DIVERGENCES.md`.

## Completed milestones

- Reference pinned (Actualist `61987e5`, Actual `v26.10.0` `2bebdbaa`), private repo, Gradle 9.6.0 / AGP 9.4.1 / Kotlin 2.4.21, CI for JVM tests and an API 34 emulator.
- Multi-select batch actions, status filters, account notes, make-a-schedule from a transaction, budget ZIP export and import-as-new (with Actual's resetSync, checked against the real server), custom request headers scoped to the server origin.
- Device tests moved to Compose's v2 test API (StandardTestDispatcher): the deprecated v1 rule's unconfined dispatcher once let a background state change run the Recomposer off the main thread (CalledFromWrongThreadException at teardown). Rules learned under v2: never block the test thread on work that needs the session's write lock (run it on a thread and `waitUntil`), invoke long-press through the `OnLongClick` semantics action, and wait for the browser to fully take the foreground before delivering an OpenID redirect.
- Emulator workflow accepts an optional `test_filter` input (`am instrument -e class`) for fast single-test reruns.
- History screen (this session's edits with undo and redo); schedule editing (name, amount, auto-posting, first date and frequency of simple rules); account groups (create, rename, delete, move accounts) with Actualist's compatibility replay on open.
- SimpleFIN bank sync through the Actual server's `/simplefin/*` routes (as Actualist), field mapping, link/sync/unlink in the account dialog; background sync with WorkManager sharing the UI's session through a reference-counted SessionHub; OpenID sign-in via browser redirect with a single-use nonce; monthly template editing from the category sheet.
- Undo/redo: Actual's cell-level undo (`server/undo.ts`) captured in every local commit, per-session history, refused after a later edit to a touched row (OP-76); Undo/Redo in the top bar.
- Core engine: sync primitives, SQLite CRDT database with atomic outbox, sync engine with server confirmation and merkle repair, encrypted download/import, new-budget creation and registration, every domain above.
- App: onboarding + demo; Budget (assign, move, cover, hold, rollover, add/hide categories, bulk month actions, templates preview/apply, month notes); Spending (search, saved-filter chips, CSV export/import); transaction editor (rule preview, splits, transfers, account moves, duplicate, merge); Accounts (add, rename, close with transfer, reopen, reconcile); Reports; Settings (sync status, Rules editor, Payees rename/merge, Schedules add/post/skip/complete/delete, theme, hide amounts); server sign-in, download, new budget, sync on open/resume/after edits.
- Bugs found by tests and fixed: Android ICU regex rejecting `UNICODE_CHARACTER_CLASS`; new newest row inserted off-screen; transfer edits propagating the date (Actual does not); false out-of-sync after a partial outbox batch.

## Latest CI

- Build and unit test: green, https://github.com/sporez/actualist-android/actions/runs/38531745514 — 2,990 JVM tests, 0 failures; 3 skipped by design (the real-server tests, which need the server from the next workflow).
- Real Actual server (`actualbudget/actual-server:26.10.0` service container, synthetic password): green, https://github.com/sporez/actualist-android/actions/runs/38531745535 — 3/3 (login, plain and encrypted new budgets, download, two-device sync, conflict, merkle agreement, SimpleFIN routes unconfigured).
- Emulator (API 34): https://github.com/sporez/actualist-android/actions/runs/38531061124 — 19/19, none skipped: every main screen, assign with undo/redo, add transaction, rule preview, reconcile, schedules (post, edit, add), rule editor, duplicate, templates (apply, edit), activity recreation, loopback-server sign-in/download/edit/sync, new budget, background sync worker, SimpleFIN link and sync, OpenID redirect, and the app against the real actual-server through `adb reverse`; process-death relaunch reopened the selected budget. One earlier round had a single `reconcilesAnAccount` timeout that did not reproduce; that wait now captures the screen on failure.

## Known limitations

- Not ported: GoCardless, the direct SimpleFIN bridge fallback, header auth, schedule patterns and end rules in the editor, editing non-monthly template kinds in the UI, rule editor operators beyond is/is not/one of/contains/matches/comparisons/approximately (other rules stay read-only), widgets, multi-month budget view.
- Real-server coverage is a disposable `actualbudget/actual-server:26.10.0` container in CI with synthetic data (login, new plain and encrypted budgets, download, two-device sync, conflicts); the on-device tests use an in-process loopback server.
- Background sync runs hourly on any network; its first run waits 30 minutes, since opening the app syncs anyway.
- Plain-http servers are refused except on loopback.

## Next recommended work

1. Schedule patterns and end rules in the editor.
2. UI for the remaining template kinds (by date, percentage, average, schedule, goal).

