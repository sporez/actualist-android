# Engineering progress

## Current phase

Phase C (functional Android interface) on top of a verified core (Phases A, B and the
core of D). The Compose app is written; its first CI compile and emulator run are in progress.

## Completed milestones

| Milestone | Evidence |
|---|---|
| Reference pinned: Actualist `actualist-dev` `61987e5` | `REFERENCE.md` |
| Private repo, Gradle 9.6.0 wrapper (checksum-verified), AGP 9.4.1 built-in Kotlin, Kotlin 2.4.21, compileSdk 37 | CI |
| Core sync primitives: HLC, merkle trie + murmur3, sync values, protobuf codec, AES-256-GCM/PBKDF2 | Upstream merkle vectors, Node-generated crypto vectors, byte-exact protobuf tests |
| SQLite budget database: CRDT apply (LWW, duplicates, quarantine, reserved datasets), atomic local commit + outbox, merkle persistence, restart | `BudgetDatabaseTest` |
| Envelope + tracking budget math and writes (assign, move, cover, transfer-available, carryover, hold/reset) | **78/80 replayable Actual oracle cases match exactly; 2 classified (AND-01, inherited from Actualist)** |
| Transaction creation cells (manual, transfer, import, starting balance) | **21/21 Actual oracle cases match** (2 with Actualist's intended OP-86) |
| Sync engine (push with server confirmation, pull, merkle re-pull, encryption) | `SyncEngineTest` (11 multi-device scenarios) |
| Actual server HTTP client + encrypted download/import | `ServerIntegrationTest` against a loopback mock server |
| Deterministic demo budget built through the CRDT write path | `DemoBudgetTest` |

## Latest CI

- Build and unit test: last green run https://github.com/sporez/actualist-android/actions/runs/38502933290 (266 JVM tests, core only; app UI not yet compiled in CI at that commit).
- Emulator workflow: not yet run with the full UI.

## Known failures and limitations

- App UI not yet verified on an emulator.
- Editing split transactions, reconciliation, rules, schedules, CSV import/export, templates, bank sync: not implemented.
- Transfer payee/account changes on edit are refused rather than re-pairing the transfer.
- Server sign-in supports password login only (no OpenID/header auth).
- Plain-http servers are refused except on loopback.

## Parity findings

- AND-01: a refused hold refuses atomically; Actual commits the income-carryover reset first. Inherited from Actualist (its harness attributes the rows to OP-32). See `KNOWN_DIVERGENCES.md`.
- Found while porting: running the merkle re-pull loop after a partial outbox batch reports a false out-of-sync. Fixed here by comparing only after the last batch. Actualist's flush has the same shape (500-message batches followed by the re-pull loop); not verified against Actualist itself.

## Next recommended work

1. Get the app compiling in CI and the emulator suite green with screenshots.
2. Split edit, reconciliation, CSV import/export.
3. Rules and schedules (Actualist has oracle fixtures for both).
