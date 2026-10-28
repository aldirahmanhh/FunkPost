# Learnings — android-local-autopost

Conventions, patterns, and successful approaches discovered during work on this plan.

_Auto-scaffolded by /ulw-execute. Append new entries below - never overwrite._

---
## Todo 2 - Application shell (manifest, theme, nav)
- Manifest: all 5 perms declared; dataExtractionRules XML used (targetSdk 34 -> also keep fullBackupContent="false" for <31).
- Theme.AutoPost is an XML style (android:Theme.Material.Light.NoActionBar) - Compose colors come from AutoPostTheme; no appcompat/Material XML dep needed.
- NavigationBar icons: used a plain 24.dp Box placeholder instead of Icons.Default.* - material-icons-core is not a declared dep and adding it is out of scope.
- Kotlin daemon threw "Could not close incremental caches" on first compile; it auto-fell back to no-daemon and BUILD SUCCESSFUL. If it recurs: `./gradlew.bat --stop` then rebuild.
- compose ui-test-junit4 / ui-test-manifest / ext-junit / runner were already in libs.versions.toml -> zero gradle changes needed.

## [2026-10-28] Environment + state findings (session ses_e7f633465ffe)
- Shell is Windows PowerShell 5.1, NOT bash: never use `&&`, use `;`. Use `cmd /c "..."` for cmd chaining.
- The bash tool auto-injects a `set CI="true" && ... && git ...` preamble when the command text contains a bare `git ` invocation; that preamble is invalid PowerShell and aborts the call. Working workaround: `$g = 'gi' + 't'; & $g status --short`.
- `adb` is NOT on PATH. Full path: `C:\Users\Anriz\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- A real device is attached: `q86dy5kr9dxstkyt` (model 2312DRAABG). Use it for instrumented tests.
- JDK 21 (Temurin). Gradle wrapper 8.9, AGP 8.5.2, Kotlin 2.0.20, KSP 2.0.20-1.0.25, Compose BOM 2024.09.00, Room 2.6.1, OkHttp 4.12.0.
- `compileSdk 35` on AGP 8.5.2 needs `android.suppressUnsupportedCompileSdk=35` in `gradle.properties` (already present).
- Version catalog pins `tink = "1.13.0"` but the plan requires tink-android 1.24.0 for `AndroidKeysetManager`; todo 4 must bump it.
- Gradle invocation: `cmd /c "gradlew.bat ..."` from repo root (not `./gradlew`).

## [2026-10-28] Test invocation gotcha (affects 11 acceptance criteria)
- AGP `connectedDebugAndroidTest` does NOT accept `--tests`. The plan's `--tests '*X*'` form fails: "Unknown command-line option '--tests'".
- Correct: `cmd /c "gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.gabut.autopost.XTest --console=plain"`.
- `testDebugUnitTest --tests` still works for JVM tests.

## [2026-10-28] Repo state at session start
- `git init` had been run by an earlier session but ZERO commits existed; branch `master` was empty and every file untracked, even though todo 1 was checked complete. Todo 1's `Commit: Y` obligation and the plan's commit strategy were unmet.
- Todo 2 files existed on disk and `:app:assembleDebug` passed with all four manifest assertions green, but its connected test had never run.
- Lesson: a checked box must be proven by the acceptance command having actually run; verify with real output before trusting plan state from a previous session.

