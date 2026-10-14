# Actualist for Android (experimental)

An experimental native Android port of [Actualist](https://github.com/sporez/actualist),
a local-first client for [Actual Budget](https://actualbudget.org). Kotlin, Jetpack
Compose, Material 3 and SQLite.

**This is a personal engineering experiment, not a release.** It is not published,
has no production signing, and must not be pointed at a real budget. All tests and
the bundled demo budget use synthetic data only.

## Layout

| Module | Contents |
|---|---|
| `core/` | Pure Kotlin/JVM engine: Actual's HLC timestamps, merkle trie, protobuf sync wire format, AES-256-GCM encryption, the SQLite budget database with CRDT apply and an atomic local-write outbox, and the envelope/tracking budget math. Runs in JVM unit tests and on Android through the bundled SQLite driver. |
| `app/` | Jetpack Compose + Material 3 Android app on top of `core`. |

The reference implementation and the exact commit this port follows are in
[`REFERENCE.md`](REFERENCE.md). Engineering status is in [`PROGRESS.md`](PROGRESS.md).

## Building

All builds and tests run in GitHub Actions; nothing Android-specific needs to be
installed locally.

- **Build and unit test** (`.github/workflows/build.yml`): every push. Builds the
  debug APK, runs the JVM tests, uploads the APK (`actualist-android-debug-apk`)
  and test reports.
- **Android emulator tests** (`.github/workflows/emulator.yml`): manual dispatch
  and pushes to `main` touching app or core code. Boots an API 34 emulator,
  installs the APK, cold-launches it, runs the instrumentation/Compose UI suite
  and uploads screenshots, logcat and results (`emulator-results`).

With a local JDK 17 and Android SDK, `./gradlew :core:test :app:assembleDebug`
works as usual.

## License

GNU GPLv3, as Actualist. See [`LICENSE`](LICENSE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
