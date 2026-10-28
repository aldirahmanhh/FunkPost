# FunkPost

**Discord auto-poster and auto-verifier for Android.**

FunkPost keeps a Discord bot posting on a schedule and can press verification buttons for you,
from one phone. Tokens are encrypted on the device, nothing is uploaded anywhere except Discord.

[Bahasa Indonesia →](README.id.md)

---

## Features

| | |
|---|---|
| **Scheduled posting** | Each channel repeats on its own interval, per bot, until you stop it. |
| **Multiple bots** | Several bot tokens side by side, each with its own channel list. Switch the active one with a tap. |
| **Auto-verify** | Watches a channel with a user token and presses *Authenticate* / *Yes, Log Me In* only on panels that belong to that account. |
| **Message composer** | Discord markdown toolbar (bold, italic, underline, strike, spoiler, code, quote, list, link) with a live preview. |
| **Activity log** | Every send, rate limit and failure recorded newest-first, capped so it never grows without bound. |
| **Notifications** | A low-importance status notification plus high-importance alerts when a token is rejected or a channel keeps failing. |
| **Bilingual UI** | English and Bahasa Indonesia, switchable in Settings without restarting the app. |
| **Dark mode** | Manual toggle in the top bar, remembered across launches. |
| **Survives reinstalls** | Installing a new build over the old one keeps every bot, channel and log. |

## Screenshots

The app has five tabs:

- **Bots** — add a bot, expand it to manage its channels, start/stop posting.
- **Verify** — accounts that watch a channel and press verify buttons.
- **Activity** — the chronological log, filterable by posted / issues.
- **Settings** — rename the active bot, rotate its token, pick the language, dismiss the
  battery-optimisation hint, watch the token tutorials.
- **About** — what the app does, the privacy promise, credits and links.

## Security

- **Tokens are encrypted at rest** with AES-256-GCM (Google Tink). The keyset is protected by an
  Android Keystore master key and stored in `filesDir/keysets/`, never in the database.
- **No telemetry.** No analytics, no crash reporting, no ad SDKs. The only network traffic is to
  `discord.com`, authenticated with the tokens you entered.
- **Backup is disabled** (`allowBackup=false`, `dataExtractionRules` excludes everything), so the
  keyset cannot leave the device through ADB backup or cloud backup.
- **No auto-start.** Posting never resumes by itself after a reboot or a cold start; the running
  flags are reset and you press Start.
- Tokens are masked in the UI (`MTQ2…hV-E`); revealing one is an explicit tap.

## Requirements

| | |
|---|---|
| Android | 8.0 (API 26) or newer |
| Build | JDK 17, Android SDK with platform 35 |
| Gradle | bundled wrapper (8.9) |

## Build

```bash
git clone https://github.com/masfank/FunkPost.git
cd FunkPost
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Install it on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release build

```bash
./gradlew :app:assembleRelease
```

A release build is minified with R8 and resource-shrunk. If `key.properties` is absent the build
falls back to the debug signing key with a warning, so a sideload build always installs. To sign
properly, create `key.properties` in the project root:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`key.properties` and `*.jks` are git-ignored.

## Tests

```bash
# JVM tests: Room migrations, repository contracts, markdown parser (Robolectric + native SQLite)
./gradlew :app:testDebugUnitTest

# On-device tests: encryption, at-rest secrets, navigation
./gradlew :app:connectedDebugAndroidTest
```

The instrumented suite covers the crypto layer, so it touches the same keystore the app uses.
`gradle.properties` sets `android.injected.androidTest.leaveApksInstalledAfterRun=true` so a test
run never uninstalls the app and wipes the data on your phone.

Notable JVM tests:

- `PersistenceContractTest` — builds a real v1 database and proves the migration to v3 keeps every
  bot and channel; also proves editing a channel preserves its last status and row id.
- `RoomRepositoryTest` — repository validation and channel-count behaviour.

## Architecture

Plain constructor injection, no DI framework. `AutoPostApplication` is the composition root and
hands out every collaborator.

```
ui/          Compose screens, one ViewModel behind all five tabs
 ├ channels/  bot + channel management
 ├ verifiers/ auto-verify accounts
 ├ logs/      activity log
 ├ settings/  settings and about
 ├ components/ shared widgets, markdown renderer, brand mark
 ├ i18n/      language state and the t(en, id) helper
 └ theme/     colours, typography, custom icons
data/        Room database, DAOs, repository, DataStore preferences
crypto/      Tink AES-256-GCM cipher behind a SecretCipher interface
network/     Discord REST client, gateway WebSocket, rate limiter
service/     foreground service, scheduler, verifier engine
notify/      notification channels and actionable alerts
```

**Persistence.** Room 2.6.1, schema version 3, with exported schemas in `app/schemas/`. Migrations
`1→2` (activity log) and `2→3` (verifiers) are explicit; there is no destructive fallback, so an
upgrade can never drop your data.

**Scheduling.** `ChannelScheduler` keeps one job per running token, re-arms a channel when its
config changes, and lives in an application-scoped coroutine scope so UI churn never stops it. A
foreground service keeps the process alive while anything is running.

**Language.** `LanguageState` holds the active language in a Compose `mutableStateOf`, and every
string is produced by `t("English", "Bahasa Indonesia")`. The stored choice is mirrored from the
ViewModel, so switching languages recomposes the whole tree immediately — including the status
lines the ViewModel builds.

## Configuration notes

- **Battery optimisation.** Android may pause background work when the screen is off. The Settings
  tab offers a dismissible card that opens the system exemption screen; the app never exempts
  itself silently.
- **Notification permission.** On Android 13+ the app asks for `POST_NOTIFICATIONS` the first time
  you start a bot. Denying it never blocks posting; a banner explains what is missing.

## Credits

Built and designed by **MrFunk** — <https://tako.id/anrizz>

## Disclaimer

Use this on servers you own or are allowed to automate. A user token acts as your account; you are
responsible for how it is used.
