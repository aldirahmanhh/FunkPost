# Issues — android-local-autopost

Problems and gotchas encountered during work on this plan.

_Auto-scaffolded by /ulw-execute. Append new entries below - never overwrite._

---

## [2026-10-28]
- `connectedDebugAndroidTest --tests '*X*'` is invalid on AGP 8.5.2. Use `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`. Applies to instrumented ACs in todos 2, 4, 7, 8, 10, 11, 12, 13, 14, 15, 17.
- No commits existed at session start despite todo 1 being checked; baseline commits for todos 1 and 2 must be produced after the fact.
- Catalog Tink 1.13.0 is older than the required 1.24.0; re-check `AndroidKeysetManager` availability when todo 4 lands.
- Instrumented tests run on a REAL device, so tests that revoke permissions, clear app data, or force-stop mutate device state; each must restore state in `@After`.

## [2026-10-28] Session: resumed execution, tests deferred by owner
- Owner instruction: "lanjut tanpa test" (continue without running tests) + "pastikan simpan dilokal pake room" (all persistence must stay in Room/DataStore, no raw SQL, no external DB).
- Todos 3-17 implemented/verified by compilation only; instrumented/JVM test runs were NOT executed as an acceptance gate this session. Todos 18 (per-IS sweep) and 19 (resilience suite) remain open, as does the F1-F4 verification wave.
- Deviations from the plan's acceptance-criteria reality (recorded so a later session does not mistake these for proven):
  - `SecretCipherTest` (6 tests) DID pass on device 2312DRAABG (API 35) before the test policy changed.
  - `SecretAtRestTest` DID pass on device (plaintext absent from db+wal+shm; keyset deletion yields null decrypt).
  - Everything from todo 5 onward is compile-verified + reviewed, not test-verified.
- Todo 3 fix: `RoomRepositoryTest` failed with `IllegalStateException: Illegal connection pointer` from Robolectric's LEGACY SQLite shadow (each connection is bound to its opening thread, while Room's Flow queries run on the ArchTaskExecutor IO pool and ignore `setQueryExecutor`). Fixed with `@SQLiteMode(SQLiteMode.Mode.NATIVE)`; all 6 tests then passed.
- `gradle/libs.versions.toml`: `tink` bumped 1.13.0 -> 1.24.0 (1.13.0 lacks the required `AndroidKeysetManager` surface for this usage and the plan pinned 1.24.0).
- Release signing falls back to the debug key with a printed WARN when `key.properties` is absent, so a sideload artifact is always produced.
- `app/build.gradle.kts` gained `testLogging { showStandardStreams = true }` so the todo-3 QA evidence line ("inserted 1 token, 2 channels; cascade delete removed 2") appears in the Gradle log.
- Editing gotcha: `Set-Content` writes a UTF-8 BOM that breaks `libs.versions.toml` parsing; use `[System.IO.File]::WriteAllText` with a `UTF8Encoding($false)` instead.
- Device installation is MIUI-intermittent (`INSTALL_FAILED_USER_RESTRICTED`); `adb shell settings put global adb_install_need_confirm 0` reduces but does not eliminate it. Expect a retry on any `connectedDebugAndroidTest` run against this device.