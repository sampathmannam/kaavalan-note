# KaavalanNote (formerly Baton)

![KaavalanNote app icon](docs/icon-shield-1024.png)

**An ADHD-friendly, local-first instruction tracker for IPS officers and other coordination-heavy roles.**

KaavalanNote is built for one job: keeping up with what seniors tell you, what you tell subordinates, and what you told yourself you'd do — without dropping the ball, without shame, and without leaking the data.

The name comes from **காவலன்** (Kaavalan — "guardian"): a symbol of authority, and the thing you pass from person to person. The app icon is the KAAVALAN shieldmark, in indigo on cream. The v2.1.1 release renamed identifiers (package + classes) from `com.baton.app` / `Baton*` to `com.kaavalan.note` / `Kaavalan*`; the launcher label is `KaavalanNote` (one token).

## The problem it solves

Current productivity apps fail people with ADHD because they assume:

- you can feel time passing (you can't — time blindness is clinical)
- a red "overdue" badge motivates (it triggers shame and avoidance)
- a 47-item task list is a useful reference (it's cognitive overload)
- you'll remember to open the app (you won't — out of sight, out of mind)
- you'll do the setup ritual and the weekly review (you won't)

A working IPS officer gets instructions from a dozen people, gives instructions to a dozen more, and is in meetings, on calls, and on the move. Baton is built for *that*.

## What it does

- **Single note bar everywhere.** Speak, type, or snap a photo. Capture always lands as an editable raw note; people, tags, and a calendar reminder are optional context, not prerequisites.
- **People-centric.** The home screen is a list of people (SP, DSP, SHOs, IOs) with a quiet badge showing open items per person. Tap a person → their full timeline.
- **Lightweight organisation.** Add people and tags when they help; the app never tries to infer sensitive work context remotely.
- **Layered follow-up.** A focus-first daily brief, quiet-contact cues, and review tools support follow-through without red badges or shame language.
- **Local-first by design (v2.1.1).** The working database is SQLCipher-encrypted Room on the device. There is no shared workspace, telemetry, or service-backed product account. Optional encrypted Google Drive backup and release checks are the only application-controlled network features. See [`docs/threat-model.md`](docs/threat-model.md) for the current privacy surface.
- **Device capture services.** Photo OCR uses ML Kit's on-device recogniser. Voice capture uses Android's system `SpeechRecognizer`, whose on-device versus network routing depends on the device and account settings.
- **Vault mode.** Optional hidden storage for the sensitive subset of your data. The whole app is local-only, but vault-mode rows are also gated behind a 4-6 digit PIN and the hidden list lives in a separate Room table.
- **Backup.** Local export/restore is available, and an opt-in Google Drive backup encrypts a snapshot client-side with the user's recovery phrase before upload.

## What this is NOT (v2.0)

Baton v2.0.0 is deliberately narrow. It is **not**:

- **A multi-device app.** There is no live cloud sync or shared state between devices. A Drive backup can be restored manually on another device.
- **A cloud-backed workspace.** There is no Supabase backend, team account, remote task store, or remote auth for the core app. Google OAuth exists only for the optional Drive backup.
- **A team app.** No shared instructions, no delegation, no @-mentions. Single-officer use only.
- **An analytics product.** No usage telemetry, no funnel events, no A/B test scaffolding. Crash logs stay in `cacheDir/crashes/` and never leave the device unless the user explicitly taps "Report a problem" in Settings.
- **An enterprise-IT app.** No MDM hooks, no remote admin, no policy enforcement, no audit-log shipping. The audit chain is a local append-only table that the officer can review in-app.
- **A free-of-every-third-party app.** ML Kit uses Google Play Services, `SpeechRecognizer` may use a device-configured speech service, and the optional Drive backup uses Google OAuth/Drive. The privacy model calls these surfaces out explicitly.

If you need any of the above, v1.x is in the [GitHub Releases](../../releases) history. v2.0 is a deliberate narrowing, not a step backward.

## Design principles

These are non-negotiable, applied at the component level:

1. **One next action.** No "what do I do now?" screens. Drill-down only.
2. **Show less, not more.** Tabs = 3. Capture is always one tap away.
3. **"Carried over", never "overdue."** No red badges, no streaks, no shame.
4. **Capture in < 5 seconds.** Measured. CI fails if it regresses.
5. **Forgive inconsistency.** Skip the review for a month → still works, still calm.
6. **Local-first.** No data leaves the device unless the user explicitly exports it or enables encrypted Drive backup.
7. **External scaffolding, not rigid.** Suggestions, not diktats.

See [`docs/architecture/focus-first-redesign.md`](docs/architecture/focus-first-redesign.md) for the current interaction model. The earlier Baton specification remains useful historical context.

## Status

**v2.2 — Focus-first redesign** (in progress).
Built on v2.1.1's local-first foundation: single-officer workflow, no live sync, and calm action-first surfaces. Validation is performed in CI before merge.

## Releases

Every release ships a signed `app-arm64-v8a-release.apk` with a SHA-256 fingerprint and the production keystore (unchanged since v1.9.0). See [GitHub Releases](../../releases) for the full list, starting from v1.4.3.

## Stack

- **Android:** Kotlin, Jetpack Compose, Hilt, Room/SQLCipher, WorkManager
- **Capture:** Android system `SpeechRecognizer` for voice and ML Kit Text Recognition for on-device photo OCR; no LLM extraction pipeline
- **Networking:** Ktor + OkHttp for the in-app GitHub release check and the optional Google Drive encrypted-backup flow
- **Local encryption:** SQLCipher (`net.zetetic:sqlcipher-android:4.6.1`), Argon2id + AES-GCM for vault-mode rows, BIP39 recovery phrase
- **Shared with MindAnchor:** `app-anchor-crypto` Kotlin module (Argon2id + AES-GCM + SQLCipher setup)

## Build

```bash
# 1. (Optional) Copy the local.properties template — only needed for
#    the SHA-256 keystore fingerprint and the Google Maps API key.
#    v2.0 has no Supabase config; the local.properties is empty
#    by default.
cp local.properties.example local.properties
# 2. Build a debug APK
./gradlew :app:assembleDebug
# 3. Run unit tests
./gradlew :app:testDebugUnitTest
# 4. Lint
./gradlew :app:lintDebug
```

Full test suite + lint + assemble is what CI runs on every push. See [`.github/workflows/build.yml`](.github/workflows/build.yml).

## Repo layout

This is a single-module Android project (`:app`). The tree below reflects the current local-first implementation.

```
kaavalan-note/
├── app/                          # The whole app (Kotlin + Compose)
│   ├── src/main/java/com/kaavalan/note/
│   │   ├── ui/                   # home, today, settings, privacy, components, theme
│   │   ├── features/             # capture, theme, onboarding, vault, adhd
│   │   ├── data/                 # local (Room/SQLCipher), vault, backup, export
│   │   ├── di/                   # Hilt modules, migrations
│   │   ├── qa/                   # in-app QA hooks
│   │   └── integration/          # cross-feature tests
│   └── src/test/                 # 80+ test files
├── docs/
│   ├── superpowers/specs/        # Design source-of-truth
│   ├── development/sdd-history/  # Pre-1.0 QA reports, dev diary
│   ├── threat-model.md           # Local-only threat model (v2.0)
│   └── PRODUCTION_READINESS_PLAN.md  # Living project plan
├── tools/
│   ├── qa/                       # Reusable QA scripts (qa-drive.py, etc.)
│   └── synthetic-data/           # Test fixture generators
└── .github/workflows/            # CI: unit-test + lint + assemble
```

## Project docs

- [`docs/PRODUCTION_READINESS_PLAN.md`](docs/PRODUCTION_READINESS_PLAN.md) — living project plan, priorities, open questions
- [`docs/architecture/focus-first-redesign.md`](docs/architecture/focus-first-redesign.md) — current UX and reliability plan
- [`docs/superpowers/specs/2026-08-10-baton-design.md`](docs/superpowers/specs/2026-08-10-baton-design.md) — historical product-design background
- [`AGENTS.md`](AGENTS.md) — guide for AI coding agents working in this repo
- [`docs/threat-model.md`](docs/threat-model.md) — local-only threat model
- [`docs/development/sdd-history/`](docs/development/sdd-history/) — pre-1.0 QA reports + dev diary

## Privacy posture (v2.1.1)

- The primary store is the SQLCipher-encrypted Room DB at `filesDir/databases/kaavalan-note.db`.
- Network features are limited to release checks and the optional Google Drive backup. Drive snapshots are encrypted on-device before upload and require Google OAuth plus a recovery phrase.
- ML Kit OCR runs on-device through Google Play Services. Android system speech recognition may use a device-configured online service; it is not represented as a guaranteed fully offline feature.
- No analytics, no telemetry, no crash reporting that sends data off-device. In-app crash log stays in `cacheDir/crashes/`.
- Threat model: [`docs/threat-model.md`](docs/threat-model.md).

## License

TBD — a license will be added before a public release.

## Related projects

- **MindAnchor** (`github.com/sampathmannam/MindAnchor`) — shares the `app-anchor-crypto` Kotlin module. v2.0 integration is suspended (no cloud to ship cross-app state through); the on-device vault crypto is still shared.
- **CCA / Kaavalan** (`github.com/sampathmannam/cca`, `kaavalan-mobile-forensics`) — separate projects for crime analytics and mobile forensics. Not integrated with Baton in v1 or v2.
