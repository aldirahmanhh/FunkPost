---
slug: android-local-autopost
status: drafting
intent: clear
review_required: false
pending-action: write .omo/plans/android-local-autopost.md
approach: Native Kotlin + Jetpack Compose port of autopost.py with a dataSync foreground service driving per-channel coroutine timers, Room persistence, Android Keystore + Tink AES-GCM for bot tokens, OkHttp client with Retry-After handling, delivered as a sideload APK.
---

# Draft: android-local-autopost

## Affected user and ideal state
Sole affected user: the operator, on their own Android phone. Today they run `autopost.py` on a PC (Flask at `localhost:5000`) that must stay powered on. After the port they open one app on a phone that is already always on.

| Row | Statement | Reason |
| --- | --- | --- |
| IS-1 | Every feature of `autopost.py` exists on the phone: multi-token list, switch active token, add/edit/delete channel (channel id + message + h/m/s interval), global Start/Stop, optional per-token webhook logging, dark mode | This is the "fitur ini" the user asked to port |
| IS-2 | Posting keeps running when the app is backgrounded, swiped away, or the screen is off; a persistent notification shows running state and channel count | Owner decision D1; without it the port has no value |
| IS-3 | Intervals are configured in hours/minutes/seconds with a 1-second minimum, exactly as today | Owner decision D2 |
| IS-4 | A 429 response is honoured: `Retry-After` / `retry_after` is parsed, the channel waits that long, then retries; a global 429 pauses all channels | Consequence of D2; the current code blind-loops (`autopost.py:104-114`) and Discord can revoke tokens that ignore 429 |
| IS-5 | Bot tokens and webhook URLs are never plaintext on disk: AES-256-GCM via Tink `AndroidKeystore`, ciphertext only in Room, excluded from Android auto-backup | Today they sit in plaintext `config.json` (`autopost.py:60-62`); on a phone that is a backup/adb/root leak |
| IS-6 | Webhook logging still sends the same embed shape (status, date-time, channel, message, footer) and stays optional per token | Existing feature (`autopost.py:70-89`) |
| IS-7 | Start/Stop is PER TOKEN (D15) and survives swiping the app away; on any cold start (including after a device reboot) no token auto-resumes and no token shows a false "Running" badge (D17) | Faithful in spirit to the reference, whose multi-token config keeps `posting_active=true` after a restart (`autopost.py:47`) while no thread actually runs - a drift this port fixes by declaring the reset |
| IS-8 | The deliverable is a sideloadable APK; no server, no cloud, no account, no telemetry; the only network egress is discord.com | Owner decision D3 + "local only" |
| IS-9 | An interval below 5 seconds shows an inline warning in the channel form but is still accepted and saved | Owner accepted in the approval brief; mitigates the ban risk of D2 without removing the feature |
| IS-10 | Total request rate per bot token never exceeds 5 req/s (client-side token bucket, D13), regardless of how many channels are configured at 1 s | Owner decision D13; prevents hitting Discord's 50 req/s per-bot bucket and the per-IP Cloudflare ban |
| IS-11 | On a 401/403 the affected token pauses all its channels, shows a notification with a "Fix token" action, and never auto-retries; a manual token test is required to resume | Owner decision D18; a revoked token at 1 s across 50 channels reaches the 10k-invalid-request ban in under 10 minutes |
| IS-12 | Each channel row shows last HTTP status, last post time and next scheduled tick; the foreground notification shows how many tokens and channels are posting | Owner decision D20; the phone user has no console to debug "why is nothing posting" |
| IS-13 | Editing a channel's interval while that token is running takes effect without a manual Stop→Start | Matches the reference loop, which re-reads `ch["interval"]` every iteration (`autopost.py:99-128`, `:310-322`) |

| GAP | Difference from today | Reason it matters | Closed by |
| --- | --- | --- | --- |
| GAP-1 | Needs a PC/VPS powered on 24/7 | The whole point of an Android version | T1, T5, T6 |
| GAP-2 | UI exists only as Flask HTML at `localhost:5000` | No web server on the phone | T1, T6, T7, T8 |
| GAP-3 | The posting loop is a daemon thread that dies with the process | Posting must survive backgrounding | T5, T6 |
| GAP-4 | No 429 / `Retry-After` handling; every failure just logs | Token revocation and Cloudflare IP bans | T4 |
| GAP-5 | Tokens + webhook URLs in plaintext `config.json` | Credential leak via backup/adb/root | T3 |
| GAP-6 | No Android project exists at all (no Gradle, no manifest, no source) | Nothing to build | T1, T2 |
| GAP-7 | No running indicator outside the app window | User cannot tell whether posting is alive | T5, T6 |
| GAP-8 | No rate governor: the reference can fire as many requests as channels allow, and 50 channels at 1 s equals Discord's whole per-bot budget | Token/account ban risk that only grows on a phone that never sleeps | T4 |
| GAP-9 | No auth-failure policy: a 401 just logs and keeps hammering | Reaches the per-IP Cloudflare ban in under 10 minutes | T4, T5 |
| GAP-10 | No per-channel observability: the phone user cannot see why a channel is silent | Blind debugging | T6, T9 |
| GAP-11 | Interval edits require a restart of the whole posting run in the naive port (the Python app actually applies them live) | Editing a typo would otherwise force a full Stop→Start of every channel of that token | T5 |

## Components (topology ledger)
| id | outcome (one line) | status | evidence path |
| --- | --- | --- | --- |
| C1 | A Gradle + Compose Android app compiles and launches with theme + 4-tab navigation | active | no existing project (explore bg_851f4e5f) |
| C2 | Room persists bots and channels; a one-shot importer can read a legacy `config.json` | active | `autopost.py:9-13`, `:18-68` |
| C3 | Secrets are encrypted at rest and excluded from backup | active | `autopost.py:60-62` |
| C4 | Discord REST client builds the exact legacy payload and handles 429/network/401 correctly | active | `autopost.py:95-130` |
| C5 | A `dataSync` foreground service runs one timer per channel, honours Stop within one second, and terminates cleanly | active | `autopost.py:132-153`, D1 |
| C6 | Compose UI covers token list/switch, channel CRUD, settings, webhook + test, credit; dark mode | active | `autopost.py:1173-1446` |
| C7 | A release APK is produced, installed, and QA'd against every IS row | active | D3 |

## Open assumptions (announced defaults)
| assumption | adopted default | rationale | reversible? |
| --- | --- | --- | --- |
| D4 Language/UI toolkit | Native Kotlin + Jetpack Compose (no Flutter, no WebView-Flask) | A 24/7 FGS is ~150 native lines; Flutter adds a platform channel plus a plugin that lags SDK churn; WebView-Flask needs a Python runtime on-device, dies with the process, and inherits `debug=True` bound to `0.0.0.0` (`autopost.py:1557`) | Yes, but expensive |
| D5 Persistence | Room (SQLite) over encrypted fields, not a JSON file | Channel list needs per-item update/delete; JSON round-tripping is what the Python app did | Yes |
| D6 Reboot behaviour | No auto-resume after reboot; `running` flag is reset to false on cold start | Matches `autopost.py:36-39`; `BOOT_COMPLETED` may not start a `dataSync` FGS on Android 15 anyway | Yes, later feature |
| D7 Test strategy | Tests-after + agent-executed QA (JVM unit tests + instrumented tests), not strict TDD | The port needs the structure first; QA is still mandatory and automated | No need |
| D8 Low-interval warning | Inline warning under 5 s, value still accepted | Reduces ban risk without removing the owner-requested 1 s minimum | Yes |
| D9 Single user, single device | No accounts, no sync, no multi-device | "local only" | Yes |
| D10 Android 15 `dataSync` 6h/24h budget | AVOIDED by targeting SDK 34: the cumulative `dataSync` budget and `Service.onTimeout()` enforcement apply to apps targeting 35+. Also implement `onTimeout()` defensively (no-op-safe) in case the device later enforces it | Owner decision: 24/7 posting matters more than targetSdk currency; sideload means no Play targetSdk floor applies | Yes (raise targetSdk later + add onTimeout UX) |
| D11 Package id / app name | `com.gabut.autopost`, label "Auto Post" | Mirrors the sidebar title (`autopost.py:1106`) | Yes |
| D12 minSdk / targetSdk | minSdk 26, targetSdk 34, compileSdk 35 | `POST_NOTIFICATIONS` (33+) and FGS types (34+) still apply at 34; 26 keeps notification channels unconditional | Yes |
| D13 Rate governor | Client-side per-token token bucket at 5 req/s + proactive `X-RateLimit-*` reading; NO hard channel cap; the 1 s interval stays allowed in the UI but requests queue and effectively slow down | Owner decision: prevents a 50-channel 1 s config from hitting Discord's 50 req/s per-bot bucket and the per-IP Cloudflare ban | Yes (a constant) |
| D14 Config importer | CUT from v1; the app starts empty | Owner decision: no migration from the PC `config.json` | Yes, later feature |
| D15 Start/Stop scope | PER-TOKEN, matching `autopost.py:341-378` (each token has its own `posting_active`); the service runs one scheduler set per active token, and switching tokens in the UI does not stop another token's posting | Faithful port; the reference's daemon threads also keep running after a switch (`autopost.py:225-237`, `:95-99`) | Yes |
| D16 Interval edit while running | Live re-arm: editing a channel's interval cancels and restarts only that channel's scheduler, matching the reference where the loop re-reads `ch["interval"]` each iteration (`autopost.py:99-128`, `:310-322`) | The draft's earlier "Stop→Start required" claim mis-cited the source; live re-read is what the Python app actually does | Yes |
| D17 Cold start | On every process start the `running` flag is forced to false and the service is not started; posting requires an explicit Start. This is an explicit, declared behaviour change (the reference persists `posting_active=true` for multi-token configs at `autopost.py:47` yet has no live thread after a restart - a real drift) | Avoids a zombie "Running" badge with nothing running, which is strictly better than the reference and matches the reference's *effective* behaviour | Yes |
| D18 Auth failure policy | On 401/403: never auto-retry, pause all channels of that token, set `running=false` for it, and post a notification with a "Fix token" action opening the Settings tab | Prevents the reachable per-IP Cloudflare ban (>10k invalid requests / 10 min); reference blind-loops on auth errors (`autopost.py:104-122`) | Yes |
| D19 Stop semantics | "Stop" means no NEW request is initiated after the tap; a request already in flight may still complete (same race as the reference) | Makes the acceptance criterion testable without pretending to cancel the network | N/A |
| D20 Observability | Each channel row shows last HTTP status, last post time, and next scheduled tick; the FGS notification shows token count + channel count + total posts sent this session | Without it the phone user is blinder than the PC user who had console + webhook logs | Yes |

## Findings (cited - path:lines)
- `D:\Project-Gabut\autopost-android\autopost.py` is the only product file: 1.557 lines, single-file Flask app with inline HTML/JS, CRLF, no trailing newline. Directory is not a git repo; no `requirements.txt`, no `config.json`, no Gradle/Flutter/manifest (explore bg_851f4e5f).
- Config schema: `{"tokens":[{name, token, use_webhook, webhook_url, channels:[{id, message, interval}], posting_active}], "current_token_index", "dark_mode"}` — `autopost.py:9-13`, `:33-43`, `:207-214`, `:306`.
- Legacy single-token migration branch exists — `autopost.py:30-44` (the Android importer must accept both shapes, plus the no-file case).
- Posting request: `POST https://discord.com/api/v10/channels/{ch.id}/messages`, headers `Authorization: <token>` + `Content-Type: application/json`, body `{"content": ch["message"]}`; success = 200 or 204 — `autopost.py:101-105`.
- Loop shape: one `while posting_active` thread per channel, `time.sleep(ch["interval"])`, never re-reads edited config — `autopost.py:95-130`, `:145-153`.
- Start/Stop routes mutate `posting_active` and spawn daemon threads — `autopost.py:341-378`.
- Webhook embed: title, description, colour 65280/16711680, four fields (Status/Date-Time/Channel Target/Status Message), footer "Auto Post By Lantas Continental. design By Void_String" — `autopost.py:70-89`; the timestamp format is `"%d %B %Y  %I:%M:%S %p"` (`:73`).
- Token dedupe on register (`:199-201`), duplicate-name suffixing (`:204-205`), channel-id dedupe on add/edit (`:299-302`, `:314-316`), interval `<= 0` rejected (`:293-296`).
- `posting_active` is persisted on Start/Stop (`:358`, `:376`) but reset to False by the legacy-migration branch and by default on missing config — `autopost.py:36-39`.
- External: WorkManager `PeriodicWorkRequest` floor is 15 minutes (developer.android.com define-work / PeriodicWorkRequest ref) → unusable for minute/second intervals.
- External: Android 14+ requires `android:foregroundServiceType` + matching permissions; `dataSync` maps to `FOREGROUND_SERVICE_DATA_SYNC`, a normal (auto-granted) permission — developer.android.com fgs-types-required, service-types.
- External: Android 15 imposes a ~6h/24h cumulative budget on `dataSync` FGS, fires `Service.onTimeout()`, and forbids starting `dataSync` from `BOOT_COMPLETED` — developer.android.com behavior-changes-15, fgs/timeout.
- External: `POST_NOTIFICATIONS` is a runtime permission on 13+; an FGS can start without it but its notification is hidden — developer.android.com notification-permission.
- External: `androidx.security:security-crypto` (`EncryptedSharedPreferences`) is deprecated as of 1.1.0-alpha07 (Apr 2025) and 1.1.0 stable (Jul 2025); migration guidance is `javax.crypto.KeyGenerator` + AndroidKeyStore.
- External: Tink Android 1.24.0, AndroidKeystore AEAD, Java 11, minSdk 23.
- External: Discord rate limits are per-route and global, keyed on the token; read `Retry-After` / `retry_after` and the `global` flag; global cap 50 req/s per bot; >10k invalid requests (401/403/429) per 10 min per IP → temporary Cloudflare ban; repeated 429 ignoring can revoke keys — docs.discord.com/developers/topics/rate-limits.
- External: bot-token automation is the sanctioned path; self-bots (user tokens) are forbidden — Discord Developer Policy / Automated User Accounts.

## Decisions (with rationale)
- **D1 (owner):** posting must continue in background → foreground service, mandatory `dataSync` type, persistent low-importance notification.
- **D2 (owner):** minimum interval stays 1 s → 429 handling is a first-class requirement, plus the D8 warning.
- **D3 (owner):** sideload APK → no Play policy work, no `specialUse` justification, debug signing for QA and one release-keystore path documented.
- **D4 (planner):** native Kotlin + Compose. Rejected: Flutter (plugin churn, platform channel for the FGS, no benefit when the whole app is new), WebView-Flask (Python runtime on device, process-lifetime threads, open `debug=True` server).
- **D5 (planner):** Room. Channel CRUD and per-item interval updates need row-level writes; a JSON blob would re-create the Python app's global-save race.
- **D6 (planner):** no boot auto-resume — faithful to `:36-39`, and Android 15 blocks `dataSync` FGS from `BOOT_COMPLETED`.
- **D7 (planner):** tests-after with JVM unit tests (interval math, payload builder, rate-limit parsing, repository) + instrumented tests (crypto round-trip, service start/stop).
- **Loop semantics (planner):** each channel keeps its own `ChannelScheduler` coroutine with an in-memory `Job`; Start marks `running=true` in Room and sends `ACTION_START` to the service, Stop sends `ACTION_STOP` which cancels every job immediately (better than the old up-to-one-interval latency). Interval changes while running require a Stop→Start, exactly like today (threads captured the interval at spawn — `:147-148`).
- **Secrets (planner):** only `token` and `webhook_url` are encrypted individually (AES-256-GCM via Tink `AndroidKeystore`, one keyset). Everything else stays plaintext in Room; `android:allowBackup="false"` plus `dataExtractionRules`.

## Scope IN
- New Gradle project in `D:\Project-Gabut\autopost-android\` (`settings.gradle.kts`, root + `app/` build files, version catalog, wrapper, manifest, Kotlin sources, resources).
- Room schema + repository + legacy `config.json` importer (multi-token, legacy single-token, and none).
- Keystore + Tink crypto layer.
- OkHttp Discord client: send message, send webhook embed, Retry-After/global-429 handling, error taxonomy.
- `dataSync` foreground service with per-channel schedulers, notification, `onTimeout()` handling, runtime `POST_NOTIFICATIONS` request, battery-optimization guidance intent.
- Compose UI: token list + switch, channel list + add/edit/delete form (h/m/s, 1 s minimum, sub-5 s warning), settings (token, name, delete bot), webhook tab + test, credit tab, dark mode, start/stop FAB.
- Release APK build + QA.

## Scope OUT (Must NOT have)
- No cloud sync, no account, no multi-device, no backup service.
- No Play Store publishing work (listing, justification forms, `specialUse` type).
- No message rotation, content scheduler, calendar, random text, templates.
- No analytics, dashboard, charts, or remote logging.
- No proxy rotation, anti-detection, multi-account orchestration, or user-token (self-bot) support — bot tokens only.
- No widgets, shortcuts, quick-settings tiles, push notifications beyond the FGS notification.
- No auto-resume after reboot (`BOOT_COMPLETED` receiver for posting is forbidden by scope).
- No WebView wrapper and no bundled Python/Flask runtime.
- No i18n framework; English UI strings copied in spirit from the Flask templates.
- No `config.json` importer and no migration from the PC (D14).
- No hard per-token channel cap (D13 chose a token bucket instead).
- No `BOOT_COMPLETED` posting receiver.
- No changes to `autopost.py` — it stays untouched as the reference implementation.

## Open questions
None. All three owner forks are answered (D1/D2/D3); every remaining choice is recorded above as an announced default that the owner can veto at the gate.

## Approval gate
- status: approved — owner replied "oke tulis" to the approval brief (plan writing only).
- Approach approved: native Kotlin/Compose + `dataSync` FGS + Room + Keystore/Tink + OkHttp with Retry-After, sideload APK.
- Next action: write `.omo/plans/android-local-autopost.md`, run mandatory Metis gap analysis, append todo batches, fill TL;DR last, run self-check, then hand off (no high-accuracy review — `review_required: false`).
