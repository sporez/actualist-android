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

- Parity sweep against Actualist's feature folders (two read-only audits, then five waves of parallel work, each verified in CI):
  - Budget: month chooser, Today, swipe between months, total assigned, carryover badges, months shown; category and group rename, reorder, move, unhide, delete with transfer; overspending cover review and uncategorized review; hold preview, move money with direction swap, amount arithmetic, savings banner, Budget Actions menu, Markdown notes, encrypted-budget unlock; the full template editor (every kind and option Actualist authors, Fill Empty, a browser).
  - Transactions: filter sheet (match all/any, several values, is not, dates) applied without saving, saved-filter rename/update/delete; reconcile in steps; reconciled-row guards; batch and merge reviews; Fill Remaining; CSV import and export reviews; schedule conversion review; paged feed; payee picker with recents.
  - Accounts: reorder, move between groups, budget/off-budget picker, working/cleared/uncleared header. Payees: search, add, delete unused, favorites, rules per payee. Rules: search, duplicate, match preview.
  - Reports: This Month, 3-Month Average and Transaction Calendar cards, card order, and an Explorer with five report types, daily/monthly, presets, custom range, charts and filters.
  - Schedules: search, status sections, show completed, details, post with scheduled date or today, date picker.
  - Settings: named themes, display size, green income, sample values, app-switcher privacy, budget display options, rollover all overspending, reimport, default account, custom headers after sign-in, diagnostics, change budget; History with named rows and an undo review; Bank Sync screen with Sync All.
  - App: only the sign-in methods the server offers (header auth explained), re-authentication banner, new-transaction alerts with "new" markers, launcher shortcuts, `actualist://open/<route>` deep links, share text to create a transaction, a navigation rail on wide windows, and seven home-screen widgets.
  - Second audit (re-read Actualist's views in full), two more waves: Sample Values as Actualist's data projection (totals add up, notes hidden, edits refused); display size scales spacing; demo-mode confirmation; the category month screen; move money to several categories at once; Include Rollover in Alerts; remembered group expansion; collapsible account sections and group menus; swipe to delete, discard-changes prompt, rule-delete review, feed states, pull to refresh, grouped category picker; schedule advancement and auto-post after every sync (it was never wired), Not Posted Automatically, background bank sync, schedule reviews and detail page, upcoming window; widget configuration, themes, sizes and row links; report cards open their Explorer preset with paged, tappable transactions and Actualist's range limits; fallback server, delete budgets, Disconnect & Erase, security and connection rows, Sign in again; deep-link prefill, an Add expense tile, Tasker/Routines intents behind an Allow automation setting, and background work only when alerts or bank sync need it; budget-open watchdog; reconcile and add-account messages.
  - Bugs the emulator found in this sweep: a filter option row whose name did not toggle it, Delete pushed out of view in the saved-filters list, and history naming every budget assignment "Deleted category" (budget row ids were sliced wrongly).
- Reference pinned (Actualist `61987e5`, Actual `v26.10.0` `2bebdbaa`), private repo, Gradle 9.6.0 / AGP 9.4.1 / Kotlin 2.4.21, CI for JVM tests and an API 34 emulator.
- Rule editor: pre/default/post stages, "add before/after notes" actions, "is not" on text, "is between" on amounts, plain date conditions (a day, month or year), on/off-budget accounts, tags, and inflow/outflow amounts (stored as Actual stores them; JVM round trips; device test for stages, notes actions and "is between").
- Report Explorer over the ported query engine (date range, accounts, categories, transfers; totals, per-category, matching transactions). Its JVM test checks totals against ledger sums; its device steps (part of the reports test) passed on the emulator in https://github.com/sporez/actualist-android/actions/runs/38591078296.
- SimpleFIN direct bridge (device-claimed access key) when the server does not host SimpleFIN; templates with several entries in the template editor; a 50,000-transaction performance smoke test.
- Wide screens (840dp and up) show the next two months beside the edited one, and a later month's assigned amount edits in place (as Actualist's grid); schedule and "up to" templates in the template editor.
- Category learning (Actual's `updateCategoryRules`) after editor and batch categorization, with budget-wide and per-payee switches; schedule every-N intervals, monthly last-day, nth-weekday, and several days and weekdays ("1, 15, last, 2nd fri") patterns, weekend handling and end rules; percentage-of-income templates.
- Budget overview home-screen widget (Jetpack Glance) through the session hub; template editing for by-date, average, copy and goal templates; category notes; reports month switcher, Budget vs Spent and drill-down; rule editor "one of" and "approximately".
- Multi-select batch actions, status filters, account notes, make-a-schedule from a transaction, budget ZIP export and import-as-new (with Actual's resetSync, checked against the real server), custom request headers scoped to the server origin.
- Device tests moved to Compose's v2 test API (StandardTestDispatcher): the deprecated v1 rule's unconfined dispatcher once let a background state change run the Recomposer off the main thread (CalledFromWrongThreadException at teardown). Rules learned under v2: never block the test thread on work that needs the session's write lock (run it on a thread and `waitUntil`), invoke long-press through the `OnLongClick` semantics action, and wait for the browser to fully take the foreground before delivering an OpenID redirect.
- Emulator guards: a five-minute JUnit timeout per test (outermost rule, reports the stuck thread) and a 45-minute cap on the instrumentation run, after one round hung in its first test on a slow emulator (12.9 s cold launch) and had to be cancelled without logcat (https://github.com/sporez/actualist-android/actions/runs/38592419688).
- Emulator workflow accepts an optional `test_filter` input (`am instrument -e class`) for fast single-test reruns.
- History screen (this session's edits with undo and redo); schedule editing (name, amount, auto-posting, first date and frequency of simple rules); account groups (create, rename, delete, move accounts) with Actualist's compatibility replay on open.
- SimpleFIN bank sync through the Actual server's `/simplefin/*` routes (as Actualist), field mapping, link/sync/unlink in the account dialog; background sync with WorkManager sharing the UI's session through a reference-counted SessionHub; OpenID sign-in via browser redirect with a single-use nonce; monthly template editing from the category sheet.
- Undo/redo: Actual's cell-level undo (`server/undo.ts`) captured in every local commit, per-session history, refused after a later edit to a touched row (OP-76); Undo/Redo in the top bar.
- Core engine: sync primitives, SQLite CRDT database with atomic outbox, sync engine with server confirmation and merkle repair, encrypted download/import, new-budget creation and registration, every domain above.
- App: onboarding + demo; Budget (assign, move, cover, hold, rollover, add/hide categories, bulk month actions, templates preview/apply, month notes); Spending (search, saved-filter chips, CSV export/import); transaction editor (rule preview, splits, transfers, account moves, duplicate, merge); Accounts (add, rename, close with transfer, reopen, reconcile); Reports; Settings (sync status, Rules editor, Payees rename/merge, Schedules add/post/skip/complete/delete, theme, hide amounts); server sign-in, download, new budget, sync on open/resume/after edits.
- Performance smoke test (JVM, CI runner) on a 50,000-transaction synthetic budget: budget month 281ms, feed of 500 118ms, search 127ms, structured query 116ms, reports dashboard 384ms; limits are 10-25x these so only real regressions fail.
- Bugs found by tests and fixed: Android ICU regex rejecting `UNICODE_CHARACTER_CLASS`; new newest row inserted off-screen; transfer edits propagating the date (Actual does not); false out-of-sync after a partial outbox batch; a write started from a screen that was then left (back right after an edit) committed but skipped the change notification, so other screens showed stale data (writes now finish NonCancellable).

## Latest CI

- Build and unit test: green, https://github.com/sporez/actualist-android/actions/runs/38677321312 — 3,142 JVM tests, 0 failures; 4 skipped by design (the real-server tests, which run in the next workflow).
- Real Actual server (`actualbudget/actual-server:26.10.0` service container, synthetic password): green, https://github.com/sporez/actualist-android/actions/runs/38667273955 — 4/4 (last core change).
- Emulator (API 34, Compose v2 test API): https://github.com/sporez/actualist-android/actions/runs/38677586005 — 71/71, none skipped; process-death relaunch reopened the selected budget. The run starts with a throwaway warm-up instrumentation, after the first test on a freshly booted emulator hung twice; every test class saves `failure-<test>.png` when it fails.

## Known limitations

- Not ported: GoCardless and header auth (neither is in Actualist), Apple Wallet import and the app icon picker (iOS-only), schedule patterns counted from the month's end other than "last" (such as second to last) in the editor, template kinds and options beyond those listed in the README, in the rule editor: recurring date values, split and schedule-link actions, and Handlebars templates (such rules stay read-only), Actual's dashboard widgets.
- Real-server coverage is a disposable `actualbudget/actual-server:26.10.0` container in CI with synthetic data (login, new plain and encrypted budgets, download, two-device sync, conflicts); the on-device tests use an in-process loopback server.
- Background sync runs hourly on any network; its first run waits 30 minutes, since opening the app syncs anyway.
- Plain-http servers are refused except on loopback.

## Next recommended work

1. Reports and widgets are not projected under Sample Values (Actualist projects reports; its widgets have none).
2. Split actions in the rule editor (`set-split-amount`), beyond Actualist (it cannot author them either).

