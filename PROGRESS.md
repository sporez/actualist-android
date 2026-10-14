# Engineering progress

## Current phase

Phase A/B: foundation and core engine. First CI run pending.

## Completed milestones

- Reference pinned at Actualist `actualist-dev` `61987e5` (see `REFERENCE.md`).
- Gradle 9.6.0 wrapper (checksum-verified), AGP 9.4.1 with built-in Kotlin, Kotlin 2.4.21, Compose BOM 2026.09.00.
- `core`: HLC timestamps and clock, merkle trie and murmur3 hash, sync values, protobuf codec, Actual crypto, SQLite budget database (remote apply, local commit + outbox, merkle persistence), envelope budget reads and writes.
- CI workflows for build/unit tests and emulator tests.

## Latest CI

Not yet run.

## Known failures and limitations

- Nothing verified in CI yet.

## Next recommended work

1. Get the first build green.
2. Budget-month oracle replay tests.
3. Demo budget and Compose screens.
