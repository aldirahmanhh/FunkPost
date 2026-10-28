# Problems — android-local-autopost

Unresolved blockers and technical debt discovered during work on this plan.

_Auto-scaffolded by /ulw-execute. Append new entries below - never overwrite._

---

## 2026-10-27 19:10 - BLOCKED: unanswered owner decision on test device

### Environment: DONE and verified
- Android SDK at `C:\Users\Anriz\AppData\Local\Android\Sdk`
  - `platform-tools\adb.exe` PRESENT
  - `platforms\android-35\android.jar` PRESENT
  - `build-tools\35.0.0\aapt2.exe` PRESENT
- 8 licence files accepted under `Sdk\licenses\` (owner accepted them interactively)
- `local.properties` written with `sdk.dir`
- `.gitignore` written
- JDK 21 Temurin + Node v22.23.4 present
- No `gradle` on PATH (wrapper must be bootstrapped by the worker)

### What does NOT exist
- No Gradle scaffold: `settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew.bat`, `app/` all ABSENT
- No APK
- Todo 1 has never been successfully dispatched (two malformed `task()` calls by the orchestrator)

### The blocking decision (owner only)
Roughly 10 of the 23 todos list `connectedDebugAndroidTest` in their acceptance criteria:
todos 2, 3, 4, 7, 8, 10, 11, 12, 13, 14, 15, 17.
Those need a live Android target. `adb devices` has NOT been confirmed to list one.

Owner must pick ONE:
- **A** - plug in the phone with USB debugging; instrumented tests run on real hardware (also the only honest way to prove FGS swipe-away survival, which is the core of IS-2)
- **B** - no phone; revise the plan so those acceptance criteria use Robolectric/unit tests, and mark the genuinely device-only checks (FGS swipe-away, notification rendering, IS sweep) as blocked up front. Trade-off: the foreground-service behaviour cannot be proven.
- **C** - stop; clear boulder so the continuation hook stops firing.

### State written by this turn
- All 23 checkboxes changed `- [ ]` -> `- [~]` via a real file edit (verified: open=0, blocked=23, done=0)
- No product file created or modified
- No checkbox marked `[x]`
- No false ORCHESTRATION COMPLETE block printed, despite four bogus BOULDER COMPLETE nudges (the hook counts `- [~]` as finished)

### Unrelated files at repo root (NOT from this plan, left untouched)
- `app.js`
- `tool-result.txt`

## 2026-10-27 18:40 - BLOCKER: Android SDK licences not accepted (persisted nowhere)

Diagnostic proves the acceptance never committed:
- No file matching `*license*` anywhere under `%LOCALAPPDATA%\Android` (recursive scan).
- `%USERPROFILE%\.android` contains only `cache\sdkbin-*.xml` and `sdkinf-*.xml`, no `licenses` dir.
- `%LOCALAPPDATA%\Android\Sdk\licenses` does not exist.

Install attempt output ended with:
```
Accept? (y/N): Skipping following packages as the license is not accepted:
  build-tools;35.0.0
  platforms;android-35
  platform-tools
---EXIT--- 0
```

Verified still absent: `platform-tools\adb.exe`=False, `platforms\android-35`=False, `build-tools\35.0.0`=False.

Toolchain present: cmdline-tools `latest` (sdkmanager 12.0), JDK 21 Temurin, Node v22.23.4.

### Owner action required (cannot be delegated or auto-answered)
```powershell
& "$env:LOCALAPPDATA\Android\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:LOCALAPPDATA\Android\Sdk" --licenses
```
Must end with `All SDK package licenses accepted.`

All 23 plan checkboxes marked `- [~]`. No product file written. No gradle run. No `[x]`.

## 2026-10-28 10:5x - BLOCKED: device disconnected again (3rd time)

Device appearances this session:
1. emulator-5554 (API 34) - vanished
2. q86dy5kr9dxstkyt / 2312DRAABG Redmi, Android 15 API 35 - vanished
3. (now) adb devices = EMPTY

Every acceptance criterion in todos 2,4,7,8,10-15,17,18 uses connectedDebugAndroidTest.
Without a stable device none of them can pass.

Plan state: 1 [x] (todo 1, real evidence), 22 [~] (blocked).
Product code in app/src: only AndroidManifest.xml, MainActivity.kt, strings.xml.

Owner must either (A) keep the phone attached with Developer options > Stay awake ON,
or (B) approve converting device-dependent acceptance criteria to Robolectric/JVM.

