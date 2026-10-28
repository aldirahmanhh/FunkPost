# android-local-autopost - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**Who this is for and what changes for them:** for you, on your own phone. Right now the auto-poster only runs on a computer that has to stay switched on. After this, it lives in an app on the phone you already carry, keeps posting while the app is closed or the screen is off, and warns you when something needs your attention.

**What you'll get:** an installable Android app that manages your Discord bots and channels, posts on the schedule you set, keeps your bot tokens encrypted on the device, and sends you an alert if a token stops working, a channel keeps failing, or Android pauses the posting.

**Why this approach:** Android only lets an app keep working in the background if it shows a permanent notification, so the app is built around that. Everything stays on the phone - no server, no account, no cloud. Your bot tokens are scrambled with a key that never leaves the device.

**What it will NOT do:** it will not run on a server or sync between devices; it will not import your old PC configuration file (you start fresh); it will not put a hard limit on how many channels you add, but it does slow requests down so Discord does not ban your bot.

**Effort:** Large
**Risk:** Medium - Android background-execution rules and Discord rate limits mean "posting forever" is best-effort, not guaranteed; the app tells you when it pauses instead of failing silently.
**Decisions to sanity-check:** post in the background with a permanent notification; keep the 1-second minimum interval but throttle every bot to 5 requests per second; support one Start/Stop per bot, not one global switch; start empty instead of importing your old PC config; target Android 14 to avoid Android 15's 6-hour background limit; alert you (with a button) when a token is rejected, unreadable, or when Android pauses posting.

Your next move: approve, or ask for a high-accuracy review first. Full execution detail follows below.

---

> TL;DR (machine): Large/Medium - native Kotlin+Compose sideload app; dataSync FGS schedulers, Room, Tink-AES-GCM secrets, OkHttp with token bucket + Retry-After, actionable alerts; 19 todos + 4 verifiers.

## Scope
### Affected user and ideal state
**Affected user:** the owner-operator, on their own Android phone. Today they run `autopost.py` on a PC that must stay powered on and reach it through a browser at `localhost:5000`. After this plan they install one sideloaded APK on a phone that is already always on, manage their Discord bots and channels there, press Start per bot, and the app keeps posting while it is backgrounded, swiped away, or the screen is off.

| Row | Statement | Reason |
| --- | --- | --- |
| IS-1 | Every feature of `autopost.py` exists on the phone: multi-token list, switch active token, per-token Start/Stop, add/edit/delete channel (channel id + message + h/m/s interval), optional per-token webhook logging, dark mode | This is the "fitur ini" the owner asked to port |
| IS-2 | Posting keeps running when the app is backgrounded, swiped away, or the screen is off; a persistent notification shows how many tokens and channels are posting | Owner decision D1; without it the port has no value |
| IS-3 | Intervals are configured in hours/minutes/seconds with a 1-second minimum, exactly as today | Owner decision D2 |
| IS-4 | A 429 response is honoured: `Retry-After` / `retry_after` is parsed, the channel waits that long, then retries; a global 429 pauses all channels; proactive `X-RateLimit-*` headers schedule the next tick before a 429 happens | Consequence of D2; the current code blind-loops (`autopost.py:104-114`) and Discord can revoke tokens that ignore 429 |
| IS-5 | Bot tokens and webhook URLs are never plaintext on disk: AES-256-GCM via Tink `AndroidKeystore`, ciphertext only in Room, excluded from Android auto-backup AND device-to-device transfer; an unreadable ciphertext marks the token "re-enter" instead of crashing | Today they sit in plaintext `config.json` (`autopost.py:60-62`); on a phone that is a backup/adb/root leak |
| IS-6 | Webhook logging sends the same embed shape as the reference (title, description, colour 65280/16711680, the four fields with their emoji tokens, `<#channel_id>` formatting, the `"%d %B %Y  %I:%M:%S %p"` timestamp, the footer string) and stays optional per token | Existing feature (`autopost.py:70-89`) |
| IS-7 | Start/Stop is PER TOKEN (D15) and survives swiping the app away; on any cold start (including after a device reboot) no token auto-resumes and no token shows a false "Running" badge (D17) | Faithful in spirit to the reference, whose multi-token config keeps `posting_active=true` after a restart (`autopost.py:47`) while no thread actually runs - a drift this port fixes by declaring the reset |
| IS-8 | The deliverable is a sideloadable APK; no server, no cloud, no account, no telemetry; the only runtime network egress is discord.com (the Credit tab's external links are user-tapped only) | Owner decision D3 + "local only" |
| IS-9 | An interval below 5 seconds shows an inline warning in the channel form but is still accepted and saved | Owner accepted in the approval brief; mitigates the ban risk of D2 without removing the feature |
| IS-10 | Total request rate per bot token never exceeds 5 req/s (client-side token bucket, D13), regardless of how many channels are configured at 1 s | Owner decision D13; prevents hitting Discord's 50 req/s per-bot bucket and the per-IP Cloudflare ban |
| IS-11 | On a 401/403 the affected token pauses all its channels, shows a notification with a "Fix token" action, and never auto-retries; a manual token test is required to resume | Owner decision D18; a revoked token at 1 s across 50 channels reaches the 10k-invalid-request ban in under 10 minutes |
| IS-12 | Each channel row shows last HTTP status, last post time and next scheduled tick; the foreground notification shows how many tokens and channels are posting plus posts sent this session | Owner decision D20; the phone user has no console to debug "why is nothing posting" |
| IS-13 | Editing a channel's interval while that token is running takes effect without a manual Stop→Start | Matches the reference loop, which re-reads `ch["interval"]` every iteration (`autopost.py:99-128`, `:310-322`) |
| IS-14 | Problems that need the owner surface as a heads-up notification with an action button: token rejected (401/403), token unreadable after key loss, posting paused by Android, and a channel failing 5 consecutive attempts. The notification persists until handled and never stacks per problem type | Owner request; today the reference silently logs failures (`autopost.py:104-122`) and a phone has no console to notice |
| IS-15 | Informational events (posting started/stopped, a rate-limit wait over 10 s, posting paused) are low-priority and do not nag; each problem type re-notifies at most once per 10 minutes; when `POST_NOTIFICATIONS` is denied the same problems appear as an in-app banner instead | Owner request + anti-spam; notification permission can be denied on Android 13+ |

| GAP | Difference from today | Reason it matters | Closed by |
| --- | --- | --- | --- |
| GAP-1 | Needs a PC/VPS powered on 24/7 | The whole point of an Android version | 1, 7, 10 |
| GAP-2 | UI exists only as Flask HTML at `localhost:5000` | No web server on the phone | 2, 10, 11, 12, 14 |
| GAP-3 | The posting loop is a daemon thread that dies with the process | Posting must survive backgrounding | 6, 7, 8, 9 |
| GAP-4 | No 429 / `Retry-After` / `X-RateLimit-*` handling; every failure just logs | Token revocation and Cloudflare IP bans | 5, 19 |
| GAP-5 | Tokens + webhook URLs in plaintext `config.json` | Credential leak via backup/adb/root | 4 |
| GAP-6 | No Android project exists at all (no Gradle, no manifest, no source) | Nothing to build | 1, 2, 16 |
| GAP-7 | No running indicator outside the app window | User cannot tell whether posting is alive | 7, 8, 9, 13, 15 |
| GAP-8 | No rate governor: the reference fires as fast as channels allow, and 50 channels at 1 s equals Discord's whole per-bot budget | Token/account ban risk that only grows on a phone that never sleeps | 5, 19 |
| GAP-9 | No auth-failure policy: a 401 just logs and keeps hammering | Reaches the per-IP Cloudflare ban in under 10 minutes | 5, 8, 17, 19 |
| GAP-10 | No per-channel observability: the phone user cannot see why a channel is silent | Blind debugging | 8, 10, 11, 13 |
| GAP-11 | Interval edits in a naive port would require a full Stop→Start | Editing a typo would force restarting every channel of that token | 6, 11 |
| GAP-12 | No alert when something needs the owner: bad token, lost key, OS-paused posting, a channel failing repeatedly | The owner never watches the app while it is "working", so a silent failure runs for hours | 17, 15 |
| GAP-13 | No distinction between "fine", "waiting on a rate limit" and "needs you" | Every failure looks the same or is invisible, so real problems get ignored | 17, 13 |

### Must have
- A buildable Gradle project (`settings.gradle.kts`, root + `app/` builds, version catalog, wrapper) with Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 34`, `compileSdk 35`, applicationId `com.gabut.autopost`.
- Room schema for tokens and channels, per-token `running` state, cold-start reset.
- Tink `AndroidKeystore` AES-256-GCM encryption for `token` and `webhook_url`, keyset in `filesDir/keysets/`, `android:allowBackup="false"` + `dataExtractionRules`.
- OkHttp Discord client: `POST /api/v10/channels/{id}/messages`, webhook embeds, per-token 5 req/s bucket, `X-RateLimit-*` scheduling, 429 wait/retry, 401/403 pause, `IOException` backoff capped at 60 s.
- `dataSync` foreground service: per-channel coroutine schedulers, per-token Start/Stop via intents, live interval re-arm, `onTimeout()` defensive handling, persistent notification with counts, `POST_NOTIFICATIONS` runtime request, battery-optimization guidance intent.
- Compose UI: token list + switch + per-token Start/Stop, channel CRUD form with h/m/s and sub-5 s warning and 1 s validation, per-channel status row, settings tab (token/name/delete), webhook tab + test, credit tab, dark mode persisted.
- A release APK, installed and QA'd per IS row.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- No cloud sync, no account, no multi-device, no backup service, no telemetry.
- No Play Store publishing work (listing, justification forms, `specialUse` type).
- No message rotation, content scheduler, calendar, random text, templates.
- No analytics, dashboard, charts, or remote logging.
- No proxy rotation, anti-detection, multi-account orchestration, or user-token (self-bot) support — bot tokens only.
- No widgets, shortcuts, quick-settings tiles, push notifications beyond the FGS notification.
- No auto-resume after reboot and no `BOOT_COMPLETED` receiver for posting.
- No WebView wrapper and no bundled Python/Flask runtime.
- No `config.json` importer and no migration from the PC (D14).
- No hard per-token channel cap (D13 chose a token bucket instead).
- No i18n framework; English UI strings copied in spirit from the Flask templates.
- No changes to `autopost.py` — it stays untouched as the reference implementation.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: **tests-after** with two layers — JVM unit tests (JUnit 5 + OkHttp `MockWebServer` + Robolectric where Android APIs are needed) and instrumented tests (`androidx.test` + Compose UI test) run on an emulator via `./gradlew :app:connectedDebugAndroidTest`. Device checks use `adb` commands with printed output captured to evidence files.
- Evidence: `.omo/evidence/task-<N>-android-local-autopost.<ext>` (outside ulw-loop; under ulw-loop use `<attemptDir>` from the loop status operation). Every QA step writes the command, its stdout, and its exit code into that file; a step whose evidence file is missing or has a non-zero exit code counts as FAILED, not skipped.
- No step may be verified by "the code looks right" or by reading source. Each acceptance criterion below names a command or an assertion that produces a pass/fail.
- Known test constraint (U-10): Android 14+ refuses `startForegroundService` from a background context. Every instrumented test that starts the service must first launch the activity (`ActivityScenario.launch`) or use `adb shell am start-foreground-service`. Tests that violate this will throw `ForegroundServiceStartNotAllowedException` and must be treated as test bugs, not product bugs.

## Execution strategy
### Parallel execution waves
- **Wave 1 (foundation, sequential-ish):** 1, 2, 3, 4 — project scaffold must exist before anything compiles; 3 and 4 depend only on 1.
- **Wave 2 (data + network core):** 5, 6, 7 — Room repository, Discord client, crypto. All depend on 1 and can run in parallel with each other.
- **Wave 3 (service):** 8, 9, 10 — schedulers, foreground service, live re-arm. All depend on 5+6+7.
- **Wave 4 (UI):** 11, 12, 13, 14, 15 — token screen, channel form, status rows, settings/webhook/credit, dark mode. Depend on 5 (+8 for status).
- **Wave 5 (delivery):** 16, 17 — release APK + per-IS QA sweep.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | — | 2,3,4,5,6,7 | — |
| 2 | 1 | 5,6,7 | 3,4 |
| 3 | 1 | 5,9 | 2,4 |
| 4 | 1 | 5,9,15 | 2,3 |
| 5 | 2,3 | 9,11,12,16 | 6,7 |
| 6 | 1,2,4 | 8,9,14 | 5,7 |
| 7 | 1,6 | 8,9 | 5,6 |
| 8 | 5,7 | 9,10,13 | 6 |
| 9 | 5,6,7,8 | 11,16 | 10 |
| 10 | 5,8 | 13 | 11,12 |
| 11 | 5,9 | 12,16 | 10,12 |
| 12 | 5,11 | 13,16 | 10,13 |
| 13 | 8,10,12 | 14,16 | 11,14 |
| 14 | 6,13 | 16 | 12,15 |
| 15 | 4,14 | 16,17 | 13 |
| 16 | 5,6,9,11,12,13,14,15,17 | 18 | — |
| 17 | 6,7,8 | 15,16,18 | 13,14 |
| 18 | 16,17 | F1-F4 | — |
| 19 | 5,6,8,12 | F1-F4 | 18 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

- [x] 1. Scaffold the Gradle + Compose Android project root
  What to do / Must NOT do: Create `settings.gradle.kts` (rootProject.name = "AutoPost", `include(":app")`, dependencyResolutionManagement with `google()` + `mavenCentral()`), root `build.gradle.kts` with plugin aliases declared `apply false`, `gradle/libs.versions.toml` version catalog, `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.9+), `gradlew`/`gradlew.bat`, `.gitignore` (`.gradle/`, `build/`, `local.properties`, `*.keystore`, `key.properties`). Versions to pin in the catalog: AGP compatible with Gradle 8.9, Kotlin 2.0.x with the `org.jetbrains.kotlin.plugin.compose` plugin, Compose BOM (latest stable), KSP matching Kotlin, Room, OkHttp 4.12.x, Tink Android 1.24.0, kotlinx-coroutines-android, androidx.core-ktx, activity-compose, lifecycle-runtime-compose, junit5, mockwebserver, robolectric, androidx.test + compose-ui-test. `app/build.gradle.kts`: `applicationId "com.gabut.autopost"`, `minSdk 26`, `targetSdk 34`, `compileSdk 35`, Java 17 target, `buildFeatures { compose = true }`, KSP for Room, `testOptions { unitTests.isIncludeAndroidResources = true }`. Must NOT add Flutter, React Native, WebView, or any Python runtime. Must NOT add Hilt/Dagger (plain constructor injection is enough for this size).
  Closes: GAP-6
  Parallelization: Wave 1 | Blocked by: none | Blocks: 2,3,4,5,6,7
  References (executor has NO interview context - be exhaustive): `D:\Project-Gabut\autopost-android\autopost.py:1555-1557` (entry point being replaced); draft D4/D5/D11/D12 in `.omo/drafts/android-local-autopost.md`; Android Gradle plugin + Compose BOM version notes at https://developer.android.com/build/releases/gradle-plugin and https://developer.android.com/develop/ui/compose/bom/bom-mapping ; Tink Android 1.24.0 at https://github.com/tink-crypto/tink-java/releases
  Acceptance criteria (agent-executable): `./gradlew --version` exits 0 and prints Gradle 8.9+; `./gradlew :app:tasks --all` exits 0; `grep -q 'applicationId = "com.gabut.autopost"' app/build.gradle.kts`; `grep -q 'targetSdk = 34' app/build.gradle.kts`; `grep -q 'minSdk = 26' app/build.gradle.kts`.
  QA scenarios (name the exact tool + invocation): happy — run `./gradlew :app:tasks` and capture stdout to `.omo/evidence/task-1-android-local-autopost.log`, assert exit 0. failure — run `./gradlew :app:tasks` with a deliberately bogus dependency coordinate in a scratch copy and assert the build fails with an unresolved-dependency error, proving the harness actually compiles the catalog (then revert).
  Commit: Y | build: scaffold Gradle + Compose Android project
  Recommended task executor category: `unspecified-high` - multi-file build configuration where a single wrong version pair breaks the whole build.

- [x] 2. Application shell: manifest, MainActivity, theme, navigation
  What to do / Must NOT do: Create `AndroidManifest.xml` with `<uses-permission android:name="android.permission.INTERNET"/>`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; `<application android:allowBackup="false" android:fullBackupContent="false" android:dataExtractionRules="@xml/data_extraction_rules" android:label="Auto Post" android:theme="@style/Theme.AutoPost">`; `MainActivity` with `singleTop` launcher and `android:exported="true"`; `<service android:name=".service.PostingService" android:foregroundServiceType="dataSync" android:exported="false"/>` (the class may be a stub in this todo). Create `res/xml/data_extraction_rules.xml` with `<cloud-backup>` and `<device-transfer>` both `<exclude domain="root" path="."/>` (D-secrets: device transfer is NOT covered by `allowBackup`). Create Compose theme with the reference's palette from `autopost.py:541-572` (`--primary #4361ee`, `--secondary #7209b7`, `--success #4cc9f0`, `--danger #e63946`, dark set `#1a1a2e`/`#16213e`/`#0f3460`) and a `MainActivity` hosting a `Scaffold` with 4 tabs (Channels / Settings / Webhook / Credit) using Material3 `NavigationBar` + `NavHost`, each tab a placeholder composable with a `testTag`. Must NOT add a splash screen library, a DI framework, or any analytics SDK.
  Closes: GAP-2, GAP-6
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 3,4,5,6,7
  References: `autopost.py:530-583` (theme CSS variables), `:1102-1200` (sidebar + tab structure), `:1404-1446` (credit tab); draft D12 (minSdk/targetSdk), draft D17; Android FGS type requirement https://developer.android.com/about/versions/14/changes/fgs-types-required ; `POST_NOTIFICATIONS` https://developer.android.com/develop/ui/compose/notifications/notification-permission ; backup rules https://developer.android.com/identity/data/autobackup
  Acceptance criteria (agent-executable): `./gradlew :app:assembleDebug` exits 0; `./gradlew :app:connectedDebugAndroidTest --tests '*MainNavigationTest*'` passes, asserting all four tabs exist by `testTag` and navigating to each shows its placeholder. Manifest assertions: `grep -c 'FOREGROUND_SERVICE_DATA_SYNC' app/src/main/AndroidManifest.xml` = 1; `grep -q 'allowBackup="false"'`; `grep -q 'foregroundServiceType="dataSync"'`; `grep -q '<device-transfer>' app/src/main/res/xml/data_extraction_rules.xml`.
  QA scenarios (name the exact tool + invocation): happy — `adb shell am start -n com.gabut.autopost/.MainActivity` then `adb shell dumpsys activity activities | grep -m1 mResumedActivity` shows `com.gabut.autopost`, captured to `.omo/evidence/task-2-android-local-autopost.log`. failure — temporarily remove `android:foregroundServiceType` and assert `./gradlew :app:assembleDebug` fails the manifest merge with a `dataSync`-missing error on a targetSdk-34 build, proving the manifest is actually validated (then revert).
  Commit: Y | feat(app): manifest, Compose theme, 4-tab shell
  Recommended task executor category: `unspecified-high` - manifest + Compose navigation wiring across several files.

- [x] 3. Room data layer: entities, DAO, database, repository
  What to do / Must NOT do: Create `data/db/` with `BotTokenEntity(id: Long autoGenerate, name, encryptedToken: String, useWebhook: Boolean, encryptedWebhookUrl: String, running: Boolean, createdAt)`, `ChannelEntity(id: Long autoGenerate, tokenId: Long FK CASCADE, channelId: String, message: String, intervalSeconds: Int, lastStatus: Int?, lastPostedAt: Long?, nextTickAt: Long?)`, `AutoPostDatabase` (version 1, `exportSchema = true`), DAOs with `Flow<List<...>>` queries (`observeTokens`, `observeChannels(tokenId)`, `updateRunning(tokenId, running)`, `updateChannelStatus(...)`, `deleteToken` cascade, `upsertChannel`). Enforce `IntervalSeconds >= 1` with a `@Check`-style validation at repository level (throw `IllegalArgumentException`); enforce channel-id uniqueness per token via a unique index on `(tokenId, channelId)`. Create `data/SettingsStore` backed by `DataStore` for `darkMode: Boolean` and `activeTokenId: Long?`. Create a `ColdStartReset` invoked from `Application.onCreate` that sets `running = false` for every token (D17) and never starts the service. Must NOT add a `config.json` importer (D14). Must NOT store `token`/`webhookUrl` as plaintext columns - the column names must be `encryptedToken` / `encryptedWebhookUrl` so the schema makes the intent obvious.
  Closes: GAP-1, GAP-6
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 5,6,7
  References: `autopost.py:9-13` (config shape), `:30-44` (legacy migration - explicitly NOT ported), `:47` (multi-token persistence and the drift D17 fixes), `:306` (channel dict shape), `:299-302`, `:314-316` (channel-id dedupe), `:293-296` (interval > 0)
  Acceptance criteria (agent-executable): `./gradlew :app:testDebugUnitTest --tests '*RoomRepositoryTest*'` passes with tests: insert token → observeChannels emits empty; upsert channel with interval 0 throws `IllegalArgumentException`; upsert duplicate `(tokenId, channelId)` rejects/replaces per the chosen strategy (assert the chosen one); `deleteToken` cascades and leaves zero channels; `ColdStartReset` flips `running=true` → `false` on a pre-seeded DB.
  QA scenarios (name the exact tool + invocation): happy — unit test logs "inserted 1 token, 2 channels; cascade delete removed 2" to `.omo/evidence/task-3-android-local-autopost.log`. failure — seed a channel with `intervalSeconds = 0` via the DAO directly and assert the repository rejects it with `IllegalArgumentException` whose message contains "interval"; assert the DB row count is unchanged after the rejection.
  Commit: Y | feat(data): Room entities, DAOs, repository, cold-start reset
  Recommended task executor category: `unspecified-high` - schema and DAO design that every later layer depends on.

- [x] 4. Crypto layer: Tink AndroidKeystore AES-256-GCM for secrets
  What to do / Must NOT do: Create `crypto/SecretCipher` using Tink `AndroidKeysetManager` with `AesGcmKeyManager.aes256GcmTemplate()`, keyset stored at `filesDir/keysets/autopost_keyset` (app-private, D-secrets), `aead.encrypt(plaintext, associatedData=null)`/`decrypt`. Expose `encryptToken(String): String` (Base64 of ciphertext) and `decryptToken(String): String?` where a decryption failure returns `null` rather than throwing (U-5 recovery path). Add a `MaskedPreview` helper that renders `token.take(4) + "…" + token.takeLast(4)`. Must NOT use `EncryptedSharedPreferences` (deprecated in 1.1.0-alpha07 / 1.1.0, per draft findings). Must NOT log plaintext anywhere; every log statement that touches a secret must go through `MaskedPreview`. Must NOT write the keyset outside `filesDir/keysets/`.
  Closes: GAP-5
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 5,9,15
  References: draft findings "EncryptedSharedPreferences deprecated"; Tink AndroidKeystore AEAD https://developers.google.com/tink/aead ; Tink Android 1.24.0 https://github.com/tink-crypto/tink-java/releases ; `autopost.py:60-62` (plaintext `save_config`), `:1342-1347` (token field is a password input in the reference UI)
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*SecretCipherTest*'` passes with tests: encrypt→decrypt round-trips a 72-char token; ciphertext != plaintext; ciphertext does not contain the plaintext substring; decrypting a corrupted Base64 blob returns `null` (no throw); encrypting the same plaintext twice yields different ciphertexts (random IV).
  QA scenarios (name the exact tool + invocation): happy — instrumented test writes a token through the repository, then `adb shell run-as com.gabut.autopost cat databases/autopost.db | grep -c '<the plaintext token>'` outputs `0`, captured to `.omo/evidence/task-4-android-local-autopost.log`. failure — after writing the token, delete `filesDir/keysets/autopost_keyset` via `adb shell run-as com.gabut.autopost rm`, relaunch, and assert the UI shows the "re-enter token" state instead of crashing (assert via `./gradlew :app:connectedDebugAndroidTest --tests '*KeyLossRecoveryTest*'`, which simulates the missing keyset).
  Commit: Y | feat(crypto): Tink AndroidKeystore AES-GCM for tokens and webhook URLs
  Recommended task executor category: `deep-low` - keystore integration with a failure-recovery path; correctness matters and the API surface is non-obvious.

- [x] 5. Discord REST client: message send, webhook embed, rate limiting, error taxonomy
  What to do / Must NOT do: Create `network/DiscordClient` (OkHttp + kotlinx-serialization or Moshi) with:
  (a) `sendMessage(token: String, channelId: String, content: String): SendResult` → `POST https://discord.com/api/v10/channels/{channelId}/messages`, headers `Authorization: <token>` (no `Bot ` prefix - the reference sends the raw token, `autopost.py:102`), `Content-Type: application/json`, body exactly `{"content": "<msg>"}`. Success = 200 or 204 (`:105`).
  (b) `sendWebhookLog(webhookUrl: String, channelId: String?, message: String, success: Boolean): Boolean` → embed with title `"<a:ms_discord:1129069176917610619> Auto Post Discord <a:ms_discord:1129069176917610619>"`, description `"> **Details Info**"`, colour `65280` when success else `16711680`, the four fields exactly as in the reference (`<a:live:1247888274161143878> Status Log` → `"> Success"`/`"> Failed"`; `<:BS_Time:1182386606661972099> Date Time` → `"> " + now` where now = `SimpleDateFormat("dd MMMM yyyy  hh:mm:ss a", Locale.ENGLISH)` to match Python's `%d %B %Y  %I:%M:%S %p`; `<:discord:1263889532688797727> Channel Target` → `"> <#{id}>"` or `"> Unknown"`; `<a:ki_verify:1193420850511224913> Status Message` → `"> " + message`), footer `"Auto Post By Lantas Continental. design By Void_String"`, body `{"embeds":[embed]}`.
  (c) `PerTokenRateLimiter` (token bucket, capacity 5, refill 5/sec, D13) - every request for a token acquires a permit first.
  (d) Result taxonomy: `Success`, `RateLimited(retryAfterMs, isGlobal)`, `AuthFailed(401|403)`, `NetworkError`, `Other(code)`. On 200/204 buffer, parse `X-RateLimit-Remaining` / `X-RateLimit-Reset-After` and return them as `nextAllowedAtMs` so the scheduler can avoid a 429 entirely; on 429 parse `Retry-After` header, else `retry_after` from the JSON body, else default 1000 ms; `global=true` in body means the whole token pauses.
  Must NOT retry 401/403 (D18). Must NOT retry 429 in a loop inside the client - the caller schedules the retry. Must NOT add a `Bot ` prefix to the Authorization header.
  Closes: GAP-4, GAP-8, GAP-9
  Parallelization: Wave 2 | Blocked by: 2,3 | Blocks: 9,11,12,16
  References: `autopost.py:95-130` (loop + request + success check), `:70-89` (full webhook embed), `:73` (timestamp format), `:105`; Discord rate limits https://docs.discord.com/developers/topics/rate-limits ; Discord API https://docs.discord.com/developers/resources/message
  Acceptance criteria (agent-executable): `./gradlew :app:testDebugUnitTest --tests '*DiscordClientTest*'` passes a `MockWebServer` matrix: 200 → Success with correct bytes sent; 204 → Success; 429 with `Retry-After: 5` → `RateLimited(5000, false)`; 429 with body `{"retry_after": 2.5, "global": true}` and no header → `RateLimited(2500, true)`; 401 → `AuthFailed`, and the server records exactly ONE request (no retry); 403 → `AuthFailed`; `IOException` → `NetworkError`; 500 → `Other(500)`. Golden body test asserts the recorded request body string equals `{"content":"hello"}` and header `Authorization` equals the raw token. Golden webhook test asserts the serialized embed JSON equals the expected fixture including `"color":65280` and the footer string.
  QA scenarios (name the exact tool + invocation): happy — MockWebServer test prints the dispatched request line + JSON to `.omo/evidence/task-5-android-local-autopost.log`. failure — configure MockWebServer to return 429 with `Retry-After: 5`, assert the client does NOT issue a second request within 5 s (assert `server.requestCount == 1`), proving no blind retry loop.
  Commit: Y | feat(network): Discord client with token bucket, rate-limit parsing, auth pause
  Recommended task executor category: `deep-high` - the rate-limit contract is a correctness boundary argued from headers, not something observed in a test run.

- [x] 6. Channel scheduler: per-channel coroutine timer with live re-arm
  What to do / Must NOT do: Create `service/ChannelScheduler` holding a `Map<ChannelKey, Job>` where `ChannelKey = (tokenId, channelId)`. `start(token, channel)` launches a coroutine that loops: acquire the token bucket permit, call `DiscordClient.sendMessage`, persist `lastStatus`/`lastPostedAt`, compute the next tick, then `delay`. On `RateLimited(ms)` delay `ms` (plus the configured interval). On `AuthFailed` emit an auth event and exit the loop. On `NetworkError` use exponential backoff starting at 2 s, doubling to a 60 s cap, resetting on success. `stop(tokenId)` cancels that token's jobs and waits for cancellation. `rearm(channel)` cancels just that channel's job and starts a new one with the new interval (D16). Stop semantics: cancellation prevents NEW requests; an in-flight request may complete (D19). Must NOT use `WorkManager` for the loop. Must NOT read the interval once and cache it - re-read it from the repository each iteration so a live edit is picked up even without `rearm`.
  Closes: GAP-3, GAP-11
  Parallelization: Wave 2 | Blocked by: 1,2,4 | Blocks: 8,9,14
  References: `autopost.py:99-128` (loop reads `ch["interval"]` fresh each iteration), `:310-322` (in-place channel mutation), `:104-122` (current blind failure handling being replaced), draft D16, D19
  Acceptance criteria (agent-executable): `./gradlew :app:testDebugUnitTest --tests '*ChannelSchedulerTest*'` (coroutine-test with a virtual time source + a fake client) passes: two channels with 60 s and 120 s produce 3 and 2 calls in 180 virtual seconds; `stop(tokenId)` leaves `0` calls after one more virtual interval; `rearm` with a 10 s interval produces calls at the new cadence within 20 s virtual; a `NetworkError` sequence delays 2,4,8,16,32,60,60 s (asserted from recorded delay list); a `RateLimited(5000)` delays 5000 ms.
  QA scenarios (name the exact tool + invocation): happy — the virtual-time test writes the recorded call timestamps to `.omo/evidence/task-6-android-local-autopost.log`. failure — start a channel, `stop(tokenId)` mid-flight, and assert no new call is recorded after the stop timestamp while allowing the one already in flight to complete (assert the in-flight call is the last recorded).
  Commit: Y | feat(service): per-channel scheduler with live re-arm and backoff
  Recommended task executor category: `deep-low` - coroutine timing semantics with cancellation; settleable from the tests it writes.

- [x] 7. Foreground service: dataSync type, notification, lifecycle
  What to do / Must NOT do: Create `service/PostingService : Service` with `onStartCommand` handling `ACTION_START` (extra `tokenId`), `ACTION_STOP` (extra `tokenId`), `ACTION_STOP_ALL`. On `ACTION_START`: mark that token `running=true`, call `startForeground(NOTIF_ID, notification)` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, and start that token's schedulers. A second `ACTION_START` for another token must ADD that token's schedulers without disturbing the first (D15). `ACTION_STOP` cancels that token's schedulers, sets `running=false`, and calls `stopSelf()` only when no token remains running. Build a low-importance notification channel `posting` with a configurable, dynamic text: "Posting · 2 bots · 7 channels · 134 sent" (D20), plus a Stop action that sends `ACTION_STOP_ALL`. Request `POST_NOTIFICATIONS` at runtime from the UI (todo 11) and, if denied, still start the service but show an in-app banner (draft default). Handle `onTimeout(startId, fgsType)` defensively: stop all schedulers, set every `running=false`, update the notification to "paused by Android", and `stopSelf()` - so raising targetSdk later does not crash. Provide a `buildBatteryOptimizationIntent()` returning `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for a UI button (must never call it itself). Must NOT start the service from `onCreate`/`BOOT_COMPLETED`. Must NOT use `specialUse`. Must NOT hold a wakelock.
  Closes: GAP-3, GAP-7, GAP-9
  Parallelization: Wave 2 | Blocked by: 1,6 | Blocks: 8,9
  References: `autopost.py:341-378` (per-token start/stop routes), `:95-99` (threads keep the token_data reference, so switching does not stop them), `:1166-1169` (status badge), draft D15, D17, D18, D19, D20; FGS launch rules https://developer.android.com/develop/background-work/services/fgs/launch ; timeout https://developer.android.com/develop/background-work/services/fgs/timeout
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*PostingServiceTest*'` passes: launch activity (required, U-10) → `startForegroundService(ACTION_START token A)` → `dumpsys activity services com.gabut.autopost` contains `PostingService` and `isForeground=true`; add token B → both A and B are running (repository `running=true` for both); `ACTION_STOP` for A leaves B running and the service alive; `ACTION_STOP` for B stops the service; invoking `onTimeout` directly (via a test-visible seam) sets all `running=false` and stops the service. Notification assertion: `adb shell dumpsys notification --noredact | grep 'Posting · '` matches the counts format.
  QA scenarios (name the exact tool + invocation): happy — start posting, then `adb shell input keyevent KEYCODE_APP_SWITCH` + swipe away via `adb shell am force-stop`-free route (use `adb shell input swipe` on the recents card), then `adb shell dumpsys activity services com.gabut.autopost | grep -c PostingService` outputs `1`, captured to `.omo/evidence/task-7-android-local-autopost.log`. failure — revoke notifications (`adb shell pm revoke com.gabut.autopost android.permission.POST_NOTIFICATIONS`), start posting, and assert the service is still foreground (dumpsys) and the UI banner text is present (Compose assertion), proving posting survives a denied notification permission.
  Commit: Y | feat(service): dataSync foreground service with per-token start/stop and timeout safety
  Recommended task executor category: `deep-high` - FGS lifecycle, per-token isolation and the timeout seam are a cross-boundary contract with no in-repo pattern to copy.

- [x] 8. Wire Start/Stop end-to-end: repository ⇄ service ⇄ UI state
  What to do / Must NOT do: Create `service/PostingController` (a single app-scoped object) exposing `start(tokenId)`, `stop(tokenId)`, `stopAll()`, `observeRunning(): Flow<Set<Long>>`. `start` validates the token is decryptable (else surface "re-enter token"), writes `running=true`, and calls `ContextCompat.startForegroundService(ACTION_START)`. `stop` writes `running=false` and sends `ACTION_STOP`. On any `AuthFailed` event from the scheduler: set `running=false` for that token, stop that token's schedulers, and post a high-priority notification channel `auth` with a "Fix token" `PendingIntent` opening the Settings tab for that token (D18). Ensure no code path starts the service from a background context without a user action. Must NOT add a separate "stop everything" service call from `Application`.
  Closes: GAP-3, GAP-7, GAP-9, GAP-10
  Parallelization: Wave 3 | Blocked by: 5,7 | Blocks: 9,10,13
  References: `autopost.py:341-360` (start), `:362-378` (stop), `:350-351` (`if not posting_active` guard preventing double start), draft D15, D18, D19
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*PostingControllerTest*'` passes: `start` twice for the same token starts the service once (assert `dumpsys` shows one `PostingService` and the scheduler call count for that token is not doubled); `stop` leaves `running=false` and zero further calls; a simulated `AuthFailed` sets `running=false` and posts a notification whose `contentIntent` targets the Settings tab for that token id.
  QA scenarios (name the exact tool + invocation): happy — run the controller test and dump both `running` rows and the notification list to `.omo/evidence/task-8-android-local-autopost.log`. failure — simulate `AuthFailed` and assert the scheduler issues exactly one request for that token in the following 60 virtual seconds (no auto-retry), and that the token's `running` is false.
  Commit: Y | feat(service): start/stop controller with auth-failure pause
  Recommended task executor category: `unspecified-high` - wiring three layers with a notification contract.

- [x] 9. Cold-start and process-death state reconciliation
  What to do / Must NOT do: Ensure `Application.onCreate` performs the D17 reset exactly once and never starts the service; ensure a swiped-away app (process killed while the FGS is running) leaves the service alive (Android keeps the FGS process) but a device reboot leaves `running=false` and no service. Add `PostingService` `onTaskRemoved` that does NOT stop the service (default behaviour is what we want) and document it. Add a `BootGuard`: no `BOOT_COMPLETED` receiver of any kind. Must NOT add `START_STICKY` in a way that restarts posting without the `running` flag - use `START_NOT_STICKY` so a killed service stays dead until the user presses Start.
  Closes: GAP-3, GAP-7
  Parallelization: Wave 3 | Blocked by: 5,6,7,8 | Blocks: 11,16
  References: `autopost.py:36-39` (legacy-only reset) and `:47` (multi-token persists) - the drift being fixed; draft D17; Android 15 `BOOT_COMPLETED` restriction https://developer.android.com/about/versions/15/behavior-changes-15
  Acceptance criteria (agent-executable): `./gradlew :app:testDebugUnitTest --tests '*ColdStartTest*'` asserts `Application.onCreate` sets every `running` to false on a DB seeded with `running=true` for 2 tokens; `grep -c 'BOOT_COMPLETED' app/src/main/AndroidManifest.xml` outputs `0`; `grep -q 'START_NOT_STICKY' app/src/main/java/com/gabut/autopost/service/PostingService.kt`.
  QA scenarios (name the exact tool + invocation): happy — start posting, `adb shell am kill com.gabut.autopost` (kills the app process but not the FGS in the same way as `force-stop`), then relaunch and assert `running=false` on the token row and the service is NOT foreground, captured to `.omo/evidence/task-9-android-local-autopost.log`. failure — `adb shell am force-stop com.gabut.autopost` then relaunch and assert no service instance exists and no notification is present (`dumpsys notification` grep for "Posting · " returns nothing).
  Commit: Y | fix(service): no auto-resume, explicit cold-start reset, no boot receiver
  Recommended task executor category: `quick` - small, contained changes with precise assertions.

- [x] 10. Token screen: list, switch, add, delete, per-token Start/Stop
  What to do / Must NOT do: Create `ui/tokens/TokenListScreen` listing all bots from the repository (name, channel count, status badge Running/Stopped) with an "Add New Token" action and a per-row selected state that persists `activeTokenId` in DataStore (mirroring `current_token_index`). Tapping a row switches the active token (it must NOT stop any other token's posting - D15). Each row has Start/Stop bound to `PostingController.start/stop`, disabled while a transition is in flight. Add/edit token sheet: name + token (password field with a reveal toggle matching `autopost.py:1492-1503`) + Save; reject empty name/token (mirroring `:194-196`) and reject a token already used by another bot (mirroring `:199-201`), appending a suffix for a duplicate name (mirroring `:204-205`). Delete token with a confirmation dialog that also deletes its channels (via cascade), and is the only way to wipe a token. Show the masked token preview, never the plaintext. Must NOT stop other running tokens on switch. Must NOT show the full token in the list.
  Closes: GAP-1, GAP-2, GAP-7, GAP-10
  Parallelization: Wave 3 | Blocked by: 5,8 | Blocks: 13
  References: `autopost.py:1111-1152` (active bot card + token switching sidebar), `:179-223` (register token page + validation + dedupe + name suffix), `:225-237` (switch route), `:1338-1356` (settings token field)
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*TokenListScreenTest*'` passes: add a token → row appears with "0 channels" and Stopped badge; duplicate name → row shows the suffixed name; duplicate token value → inline error and row count unchanged; empty name → inline error; Start on token A shows the Running badge and `running=true` in DB; switching to token B leaves A's `running` true; delete A removes A and its channels and does not affect B.
  QA scenarios (name the exact tool + invocation): happy — drive the add/start/switch/delete flow via Compose test and write the resulting DB dump to `.omo/evidence/task-10-android-local-autopost.log`. failure — attempt to add a channel-less token then press Start: assert the badge stays Running (a token with zero channels is legal, matching the reference) but the notification reads "0 channels" rather than crashing.
  Commit: Y | feat(ui): token list with switch, per-token start/stop, delete
  Recommended task executor category: `visual-engineering` - Compose screen with form validation and state badges.

- [x] 11. Channel screen: CRUD form with h/m/s, 1 s minimum, sub-5 s warning, per-channel status
  What to do / Must NOT do: Create `ui/channels/ChannelListScreen` + `ChannelFormSheet`. The form has channel id, message (multiline), and three numeric fields Hours/Minutes/Seconds (mirroring `autopost.py:1223-1240`); compute `interval = h*3600 + m*60 + s` with the exact arithmetic of `:285-291`; reject `interval <= 0` with the message "Interval must be at least 1 second" (mirroring `:293-296`); show an inline warning (not a block) when `interval < 5` with the text from IS-9. Reject an empty channel id or message (mirroring `:279-281`). Reject a duplicate channel id within the same token (mirroring `:299-302`, `:314-316`). Channel id is editable on edit (the reference pins it readonly at `:1217` only because the edit page is a separate route; the port keeps it editable and re-checks duplication - this is a small intentional UX improvement to state in the commit body). Editing a channel of a running token calls `ChannelScheduler.rearm` so the new interval applies live (D16). Each row shows `#<id>`, the human interval (`Every Xh Ym Zs`, mirroring `:1309`), the message, and the status line from `lastStatus` / `lastPostedAt` / `nextTickAt` (D20). Delete with confirmation (mirroring `:1286`). Show a `POST_NOTIFICATIONS` rationale + request the first time Start is pressed (draft default), and a "Battery optimization" button that opens the system dialog via `buildBatteryOptimizationIntent()`.
  Closes: GAP-2, GAP-10, GAP-11
  Parallelization: Wave 4 | Blocked by: 5,9 | Blocks: 12,16
  References: `autopost.py:1200-1328` (channels tab + form + list), `:285-296` (interval math + validation), `:299-302` (dup add), `:314-316` (dup edit), `:1307-1310` (interval display), `:1217` (readonly channel id in edit)
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*ChannelFormTest*'` passes: h=1,m=2,s=3 → stored `intervalSeconds == 3723`; all-zero → inline error "Interval must be at least 1 second" and nothing saved; s=4 → warning visible AND save succeeds with `intervalSeconds == 4`; duplicate channel id in the same token → error and count unchanged; same channel id in a different token → allowed; edit interval 60→10 on a running token → scheduler records the new cadence within the next 20 s (virtual time test).
  QA scenarios (name the exact tool + invocation): happy — create a channel with 1h2m3s, dump the row to `.omo/evidence/task-11-android-local-autopost.log`, assert `interval_seconds=3723`. failure — enter seconds=0/hours=0/minutes=0, assert the submit is rejected and the DB row count stays the same.
  Commit: Y | feat(ui): channel CRUD with h/m/s interval, 1s floor, sub-5s warning, live re-arm
  Recommended task executor category: `visual-engineering` - form UX with validation branches and a warning state.

- [x] 12. Settings, Webhook, Credit tabs
  What to do / Must NOT do: **Settings tab**: edit the active token's name and token (password field + reveal + Save), showing the same "already used by another bot" rejection as registration (`autopost.py:258-270`). **Webhook tab**: enable switch, webhook URL field, Save, and a "Test Webhook" button that calls `DiscordClient.sendWebhookLog` and reports success/failure inline; when the switch is off or the URL is empty the test button reports "Webhook tidak diaktifkan atau URL tidak ada" semantics as an inline error (`:385-387`). **Credit tab**: the About text and the four links (Discord invite, YouTube, GitHub, Saweria) opened via an `Intent` - must be user-tapped only, never loaded automatically (IS-8). Dark mode toggle lives in the top bar and persists to `SettingsStore` (`:1484-1488`). Must NOT add a "sync" or "export config" button. Must NOT auto-open any Credit link.
  Closes: GAP-2
  Parallelization: Wave 4 | Blocked by: 5,11 | Blocks: 13,16
  References: `autopost.py:1330-1359` (settings tab), `:1361-1401` (webhook tab), `:1403-1446` (credit tab), `:380-395` (test webhook route), `:258-270` (token change dedupe), `:1427-1442` (links)
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*SettingsWebhookTest*'` passes: save a new token value and assert decrypt(ciphertext) equals it; save a token value used by another bot → inline error, ciphertext unchanged; Test Webhook with MockWebServer returning 204 → success message and exactly one request to the configured URL; Test Webhook with the switch off → error text and zero requests; Credit tab renders 4 links, and no network request is made on tab entry (assert MockWebServer request count is 0).
  QA scenarios (name the exact tool + invocation): happy — run the webhook test against MockWebServer and save the recorded request body to `.omo/evidence/task-12-android-local-autopost.log`, asserting the embed title string matches the reference exactly. failure — set the webhook URL to an unreachable host, press Test, assert an inline failure message appears and the app does not crash (test asserts no uncaught exception and the error text is displayed).
  Commit: Y | feat(ui): settings, webhook test, credit tabs, dark mode
  Recommended task executor category: `visual-engineering` - three screens plus a network-triggered action.

- [x] 13. Global posting status bar + notification sync + observability polish
  What to do / Must NOT do: Add a top-bar status area showing "N bots running · M channels" derived from the repository, and make the FGS notification text derive from the same source so the two never disagree (D20). Show "posts sent this session" from an in-memory counter reset on process start. Add a per-channel status line renderer covering the four states: `Running (next in 00:42)`, `Rate limited (retry in 5s)`, `Auth failed - fix token`, `Never posted yet`. Ensure the notification updates at most once per 5 s to avoid notification spam (coalesce updates). Must NOT add a separate log screen or history table (out of scope).
  Closes: GAP-7, GAP-10
  Parallelization: Wave 4 | Blocked by: 8,10,12 | Blocks: 14,16
  References: `autopost.py:1166-1169` (top-nav status badge), `:1114-1128` (running/stopped badge), `:1300-1304` (per-channel active badge), draft D20
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*StatusSyncTest*'` passes: with 2 tokens running and 3 channels total, the top bar text equals the notification text (`N bots running · M channels`); a `RateLimited` event renders "Rate limited (retry in 5s)"; an `AuthFailed` event renders "Auth failed - fix token"; notification updates are coalesced (assert `dumpsys notification` update count over 10 s is <= 3).
  QA scenarios (name the exact tool + invocation): happy — start 2 tokens, dump both UI text and `dumpsys notification` to `.omo/evidence/task-13-android-local-autopost.log`, assert equality of the counts. failure — trigger a rate limit and assert the status line shows the retry countdown while the notification does not spam (update count unchanged or coalesced).
  Commit: Y | feat(ui): synchronized posting status and per-channel observability
  Recommended task executor category: `visual-engineering` - UI state rendering tied to service state.

- [x] 14. Dark mode persistence + theme parity check
  What to do / Must NOT do: Persist `darkMode` in `SettingsStore`, apply it at the Compose root, and default to light (`autopost.py:12` default `False`). Match the reference palette tokens from `:541-572` for both themes. Ensure the token/channel/webhook/credit screens use theme colours (no hardcoded white/black). Must NOT add a system-follow option (reference has only a manual toggle at `:1163-1165`).
  Closes: GAP-2
  Parallelization: Wave 4 | Blocked by: 6,13 | Blocks: 15,16
  References: `autopost.py:541-572` (CSS variables), `:1085` (`data-theme` attribute), `:1477-1489` (toggle + persistence), `:397-402` (save-dark-mode route)
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*DarkModeTest*'` passes: toggling to dark then killing and relaunching the activity keeps dark (assert `SettingsStore.darkMode == true` and a background colour assertion on the root); default on a fresh install is light.
  QA scenarios (name the exact tool + invocation): happy — toggle dark, capture both theme colours via Compose assertion, write to `.omo/evidence/task-14-android-local-autopost.log`. failure — clear app data (`adb shell pm clear com.gabut.autopost`) and relaunch, assert the theme is light again (no crash from a missing preference).
  Commit: Y | feat(ui): persisted dark mode with reference palette
  Recommended task executor category: `quick` - contained preference + palette wiring.

- [x] 15. Battery-optimization guidance + notification permission UX
  What to do / Must NOT do: Add a first-run/inline card on the Channels tab: "Android may pause background posting. Allow Auto Post to ignore battery optimization." with a button that fires `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` through `buildBatteryOptimizationIntent()`; hide the card once `isIgnoringBatteryOptimizations` is true. Add the `POST_NOTIFICATIONS` rationale dialog before the first Start and request the permission; if denied, show a persistent in-app banner ("Notifications are off - posting continues but you can't see status"). Must NOT show the battery card on every launch - dismissible and remembered. Must NOT request the battery exemption automatically.
  Closes: GAP-7
  Parallelization: Wave 4 | Blocked by: 4,14 | Blocks: 16
  References: `autopost.py` has no equivalent (new platform requirement); draft default "battery-optimization guidance intent only, never programmatic"; `POST_NOTIFICATIONS` https://developer.android.com/develop/ui/compose/notifications/notification-permission
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*PermissionUxTest*'` passes: with `POST_NOTIFICATIONS` revoked, pressing Start shows the banner text and the service still becomes foreground; with the permission granted, no banner; dismissing the battery card and relaunching keeps it hidden.
  QA scenarios (name the exact tool + invocation): happy — `adb shell pm revoke com.gabut.autopost android.permission.POST_NOTIFICATIONS` then drive Start and assert banner + foreground service; evidence to `.omo/evidence/task-15-android-local-autopost.log`. failure — deny the permission via the system dialog path (UiAutomator deny), assert the app does not crash and posting still starts.
  Commit: Y | feat(ui): battery-optimization guidance and notification permission UX
  Recommended task executor category: `unspecified-low` - small, well-scoped platform UX.

- [x] 16. Build the release APK with a documented signing path
  What to do / Must NOT do: Add a `release` signing config reading `key.properties` when present (gitignored) and falling back to debug signing with an explicit `WARN` printed, so the build always produces an installable artifact. Set `isMinifyEnabled = true` with a ProGuard/R8 file keeping Tink, OkHttp, Room, and the serialization models (missing keeps cause silent runtime failures - verify with a release run, not just a build). Configure `androidResources`/`packaging` as needed. Document the exact signing steps in the todo's commit body (do NOT create a separate README). Must NOT commit any keystore or password. Must NOT add `android:debuggable` to release.
  Closes: GAP-1, GAP-6
  Parallelization: Wave 5 | Blocked by: 5,6,9,11,12,13,14,15 | Blocks: 17
  References: draft D3 (sideload); `autopost.py` has no build step; Android signing docs https://developer.android.com/studio/publish/app-signing
  Acceptance criteria (agent-executable): `./gradlew :app:assembleRelease` exits 0; `ls app/build/outputs/apk/release/app-release*.apk` exists; `adb install -r <apk>` exits 0 and `adb shell dumpsys package com.gabut.autopost | grep -c 'pkgFlags'` shows the package installed; `unzip -p <apk> classes.dex | grep -c 'Tink'` > 0 proving obfuscation did not strip the crypto layer (use `dexdump`/`apkanalyzer` if `grep` on dex is unreliable); `adb shell run-as com.gabut.autopost ls files/keysets/` shows the keyset is created at runtime (release build), and an encrypt→decrypt round-trip passes on the RELEASE build.
  QA scenarios (name the exact tool + invocation): happy — install the release APK on a clean emulator (`adb uninstall` first), add a token + channel, start, wait for one simulated send against a local MockWebServer endpoint switched in via a debug-only base-URL override (release build uses discord.com; assert the request builder URL string instead), write the logcat to `.omo/evidence/task-16-android-local-autopost.log`. failure — build release without `key.properties` and assert the WARN is printed and the APK is still produced and installs (proving the fallback path works).
  Commit: Y | build: release APK with R8 keeps and documented signing
  Recommended task executor category: `deep-low` - R8 keep-rule failures are only visible at runtime; the worker must reason across build config and runtime behaviour.

- [x] 17. Alert notifications for problems that need the owner
  What to do / Must NOT do: Create `notify/AlertNotifier` with two notification channels created at `Application.onCreate`: `alerts` (IMPORTANCE_HIGH, `setOnlyAlertOnce(true)`, `setAutoCancel(false)`, not ongoing so the user can dismiss after acting) and `events` (IMPORTANCE_LOW, auto-cancel). Alert types, each with a stable notification id derived from `(tokenId, type)` so an update replaces rather than stacks:
  (a) `AUTH_FAILED` - title "<Bot name>: token rejected (401)", body "Posting paused. Fix the token to resume.", action `PendingIntent` to the Settings tab for that token (D18). This is the alert that makes IS-11 visible to the user.
  (b) `TOKEN_UNREADABLE` - title "<Bot name>: token can't be read", body "Re-enter the token.", action to Settings. Triggered when `SecretCipher.decryptToken` returns null (U-5).
  (c) `OS_PAUSED` - title "Posting paused by Android", body "Open the app and press Start to resume.", triggered from the service's `onTimeout` and defensive stop path.
  (d) `CHANNEL_FAILING` - title "#<channelId> keeps failing", body "5 attempts failed in a row (<last reason>).", action to that channel's row. Counter resets on any success.
  (e) `EVENTS_RATE_LIMITED` (low priority) - only when the wait exceeds 10 s: "Rate limited, retrying in Ns" per token, auto-cancel.
  (f) `EVENTS_RUNNING` (low priority) - "Posting started · N channels" / "Posting stopped" per token.
  Enforce a per-(token, type) 10-minute re-notify floor in `AlertNotifier` (in-memory `lastNotifiedAt` map, reset on process start) so a flapping channel cannot spam. Render every alert type as an in-app banner as well when `POST_NOTIFICATIONS` is denied or the `alerts` channel is disabled. Must NOT use server push, a WorkManager notification, or a repeating alarm. Must NOT notify per posted message. Must NOT notify on ordinary 429 waits under 10 s.
  Closes: GAP-12, GAP-13
  Parallelization: Wave 4 | Blocked by: 6,7,8 | Blocks: 15,16
  References: `autopost.py:104-122` (failures currently only logged), `:70-89` (webhook log exists but needs a webhook and is not a phone alert), `:1114-1128` (status badge semantics); draft D18 (auth pause), D20 (observability), IS-14, IS-15; notification channels https://developer.android.com/develop/ui/views/notifications/channels ; POST_NOTIFICATIONS denial https://developer.android.com/develop/ui/compose/notifications/notification-permission
  Acceptance criteria (agent-executable): `./gradlew :app:connectedDebugAndroidTest --tests '*AlertNotifierTest*'` passes: an `AUTH_FAILED` event posts a notification whose `contentIntent` targets Settings for that token; posting the same event twice within 10 minutes yields exactly ONE notification (assert `dumpsys notification` entry count unchanged); a `CHANNEL_FAILING` event fires after 5 consecutive `NetworkError`s and resets after a success; a 429 with `Retry-After: 30` produces an `events`-channel notification while `Retry-After: 5` produces none; with `POST_NOTIFICATIONS` revoked, `AUTH_FAILED` renders the in-app banner text and does not throw.
  QA scenarios (name the exact tool + invocation): happy — simulate 5 network failures on one channel, then `adb shell dumpsys notification --noredact | grep -c '<Bot name>'`, capture to `.omo/evidence/task-17-android-local-autopost.log`, assert exactly 1 alert entry plus the FGS summary. failure — fire `AUTH_FAILED` 3 times within a minute and assert only 1 notification exists, then advance the injected clock past 10 minutes and assert a 2nd notification appears.
  Commit: Y | feat(notify): actionable alerts plus low-priority event notifications
  Recommended task executor category: `deep-low` - notification identity, dedupe timing and the denied-permission fallback are subtle but settleable from the tests it writes.

- [ ] 18. Per-IS acceptance sweep on a real install
  What to do / Must NOT do: Execute one recorded command sequence per IS row (1-15) against the installed release APK, writing a single evidence file with a PASS/FAIL line per row and the exact command + observed output. Any FAIL becomes a new `- [ ] N.` todo, never a note. Use an emulator or a device chosen by the executor; if no device is available, use the managed device and record its API level. Must NOT mark a row PASS from source inspection. Must NOT skip a row because it looks manual - every row has a command.
  Closes: GAP-1, GAP-2, GAP-3, GAP-4, GAP-5, GAP-6, GAP-7, GAP-8, GAP-9, GAP-10, GAP-11, GAP-12, GAP-13
  Parallelization: Wave 5 | Blocked by: 16,17 | Blocks: F1-F4
  References: the `## Success criteria` table below (one row per IS, with its proving scenario); draft IS/GAP ledgers
  Acceptance criteria (agent-executable): `.omo/evidence/task-18-android-local-autopost.log` contains exactly 15 lines matching `^IS-(1|2|3|4|5|6|7|8|9|10|11|12|13|14|15): (PASS|FAIL) - ` and every line is PASS; the file also contains the device/emulator API level and the APK sha256.
  QA scenarios (name the exact tool + invocation): happy — full sweep, all 15 PASS. failure — deliberately revoke `POST_NOTIFICATIONS` mid-sweep and assert the IS-15 row prints FAIL with the observed reason, then restore and re-run to PASS, proving the sweep detects a regression instead of always passing.
  Commit: Y | test(qa): per-IS acceptance sweep on installed release APK
  Recommended task executor category: `unspecified-high` - a broad evidence-driven verification pass across the whole app.

- [ ] 19. Negative-path and resilience test suite
  What to do / Must NOT do: Add tests for the paths that would otherwise be discovered in production: token rotation mid-run (401 → pause → fix token → resume), device offline for 5 minutes (assert the backoff order and that no request is attempted while offline), 429 global pause (assert every channel of that token waits while another token keeps posting), interval edit while `RateLimited` (assert the new interval applies after the wait), and a cold start with a `running=true` row for a deleted token (cascade keeps the DB consistent, no crash). Must NOT add retry-on-401 anywhere. Must NOT swallow an exception silently - every catch records a status.
  Closes: GAP-4, GAP-8, GAP-9
  Parallelization: Wave 5 | Blocked by: 5,6,8,12 | Blocks: F1-F4
  References: `autopost.py:104-122` (the blind handling being replaced); https://docs.discord.com/developers/topics/rate-limits ; draft D13, D18, D19
  Acceptance criteria (agent-executable): `./gradlew :app:testDebugUnitTest --tests '*ResilienceTest*'` passes every listed scenario and prints one PASS line per scenario.
  QA scenarios (name the exact tool + invocation): happy — the suite, evidence to `.omo/evidence/task-19-android-local-autopost.log`. failure — reintroduce a retry-on-401 in a scratch copy and assert the suite FAILS, then revert.
  Commit: Y | test: resilience suite for auth, offline, global 429, corrupted state
  Recommended task executor category: `deep-low` - cross-cutting failure semantics the worker can settle from the code it reads.

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit
- [ ] F2. Code quality review
- [ ] F3. Real manual QA
- [ ] F4. Ideal-state fidelity

## Commit strategy
One commit per todo (19 total), each atomic and buildable on its own. Conventional Commits prefixes as written in each todo's `Commit:` line. Never commit a keystore, `key.properties`, a real bot token, or an `app/build/` artifact. Run `./gradlew :app:assembleDebug` before each commit; a red build blocks the commit. Do not squash waves - the wave boundaries double as review points. Do not rewrite history or force-push; this directory is not a git repository yet, so execute `git init` in todo 1 and commit.

## Success criteria
> One row per IS row. The plan is complete only when every IS row has a delivering todo and a proving QA scenario; F4 checks the delivered behavior against these rows 1:1, and a shortfall becomes new `- [ ] N.` rows, never a note.
| IS | Delivering todo(s) | Proving QA scenario | Evidence |
| --- | --- | --- | --- |
| IS-1 | 3, 10, 11, 12 | `TokenListScreenTest` add/switch/delete + `ChannelFormTest` + `SettingsWebhookTest` | `.omo/evidence/task-10-android-local-autopost.log` |
| IS-2 | 7, 9 | `PostingServiceTest` swipe-away, then `dumpsys activity services` still lists `PostingService` | `.omo/evidence/task-7-android-local-autopost.log` |
| IS-3 | 11 | `ChannelFormTest`: 1h2m3s stores 3723; all-zero is rejected | `.omo/evidence/task-11-android-local-autopost.log` |
| IS-4 | 5, 19 | `DiscordClientTest` 429 matrix (header, body, global) + `ResilienceTest` global pause | `.omo/evidence/task-5-android-local-autopost.log` |
| IS-5 | 4, 16 | `SecretCipherTest` round-trip; `run-as` grep for the plaintext token returns 0; release-build round-trip | `.omo/evidence/task-4-android-local-autopost.log` |
| IS-6 | 5, 12 | `DiscordClientTest` webhook golden JSON + `SettingsWebhookTest` recorded request body | `.omo/evidence/task-12-android-local-autopost.log` |
| IS-7 | 7, 9, 10 | `PostingServiceTest` per-token isolation + `ColdStartTest` reset + switch leaves token A running | `.omo/evidence/task-9-android-local-autopost.log` |
| IS-8 | 16, 12 | `assembleRelease` + install + opening the Credit tab makes 0 network requests | `.omo/evidence/task-16-android-local-autopost.log` |
| IS-9 | 11 | `ChannelFormTest`: seconds=4 shows the warning AND saves | `.omo/evidence/task-11-android-local-autopost.log` |
| IS-10 | 5, 19 | `DiscordClientTest` token bucket: 20 requests take at least 4 s | `.omo/evidence/task-19-android-local-autopost.log` |
| IS-11 | 5, 8, 17 | `AuthFailed` produces exactly 1 request + an `AUTH_FAILED` alert whose action opens Settings | `.omo/evidence/task-17-android-local-autopost.log` |
| IS-12 | 13, 11 | `StatusSyncTest`: top-bar text equals the notification counts; per-channel status line renders 4 states | `.omo/evidence/task-13-android-local-autopost.log` |
| IS-13 | 6, 11 | `ChannelSchedulerTest`: `rearm` to a 10 s cadence within 20 s virtual time | `.omo/evidence/task-6-android-local-autopost.log` |
| IS-14 | 17 | `AlertNotifierTest`: AUTH_FAILED / TOKEN_UNREADABLE / OS_PAUSED / CHANNEL_FAILING each notify once with the right action | `.omo/evidence/task-17-android-local-autopost.log` |
| IS-15 | 17, 15 | `AlertNotifierTest` 10-minute dedupe + `PermissionUxTest` denied-permission banner | `.omo/evidence/task-17-android-local-autopost.log` |
