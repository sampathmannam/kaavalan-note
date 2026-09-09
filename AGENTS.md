# KaavalanNote — Agent Guide

KaavalanNote (formerly Baton) is a private Android project. This file is the entry point for any AI coding agent (Mavis, Codex, Cursor, Aider, etc.) working in this repo.

> **Before doing anything, read [`docs/PRODUCTION_READINESS_PLAN.md`](docs/PRODUCTION_READINESS_PLAN.md) (see its 2026-09-04 status entry first) for current priorities and the latest [`docs/vX.Y.Z_release_notes.md`](docs) for what actually shipped most recently. `docs/PLAN.md` and `docs/superpowers/specs/2026-08-10-kaavalan-design.md` were referenced here previously but do not exist in this repo — do not try to read them.**
>
> **v2.1.1 brand rename** (2026-08-26): the package, theme, widget classes, and color tokens all moved from `com.baton.app` / `Baton*` to `com.kaavalan.note` / `Kaavalan*`. The launcher label, widget label, and tile label are CamelCase `KaavalanNote` (one token). In-prose mentions keep the spaced form `Kaavalan note` for natural English. The app icon (the KAAVALAN shield on cream) is unchanged.

## What this project is

A native Android (Kotlin/Compose) app for an IPS officer with ADHD. It tracks instructions flowing in from superiors and out to subordinates, with on-device AI for capture/extraction, and a cloud MCP server for desktop tools. The v2.0.0 release dropped Supabase (local-only). Currently at **v2.1.1** (hierarchy polish + brand rename) with weekly shipping cadence.

## How to work in this repo

### Module layout (current — single-module reality)

```
baton/
├── app/                          # The whole app
│   ├── src/main/java/com/baton/app/
│   │   ├── ui/
│   │   │   ├── home/             # HomeScreen, PersonDetail, AddPerson, NudgeSheet
│   │   │   ├── today/            # TodayViewModel, Decay, Win, Worry, Brief
│   │   │   ├── settings/         # SettingsSheet, SettingsViewModel
│   │   │   ├── auth/             # AuthViewModel
│   │   │   ├── privacy/          # RecoveryPhrase, ThreatModel, FlagSecure
│   │   │   ├── components/       # OfflineIndicator, etc.
│   │   │   ├── theme/            # Color, Theme
│   │   │   └── util/             # SafeError
│   │   ├── features/
│   │   │   ├── capture/          # Voice/text/photo capture, share intake, widget
│   │   │   ├── onboarding/       # OnboardingViewModel
│   │   │   ├── vault/            # VaultViewModel
│   │   │   ├── theme/            # ThemeViewModel
│   │   │   └── adhd/             # AdhdUxFindingTests (the "rule" test suite)
│   │   ├── data/
│   │   │   └── captures/         # SupabaseCaptureRepository
│   │   ├── di/                   # Hilt modules, DatabaseModule, migrations
│   │   ├── qa/                   # V156QaTest
│   │   └── integration/          # FifteenDaySimulationTest
│   ├── src/test/                 # 72 test files (unit)
│   └── src/androidTest/          # M0AcceptanceTest, VaultEndToEndTest
├── supabase/                     # Migrations + Edge Functions
├── tools/
│   ├── qa/                       # qa-drive.py + utilities (moved from .sdd/)
│   └── synthetic-data/           # Test fixture generators
├── docs/
│   ├── superpowers/specs/        # Design source of truth
│   ├── development/sdd-history/  # Pre-1.0 QA reports + dev diary
│   └── PLAN.md                   # Living project plan
└── .github/workflows/android-ci.yml
```

### Module layout (planned for v2.0 — see PLAN.md §3.1)

```
baton/
├── app/                    # Android app (UI + capture + sync)
├── ai/                     # On-device AI module (llama / whisper / ocr)
├── data/                   # Persistence + sync (db / sync / mcp-client)
├── features/               # capture, people, brief, nudge, mcp
├── shared/                 # crypto, ui, time
└── server/                 # Supabase project (functions + migrations)
```

The split is **deferred to v2.0.0-pre1** so we ship v1.9.7 / v1.9.8 / v1.9.9 as a monolith first.

### Design rules (non-negotiable)

- **Never introduce a "red overdue" badge or streak counter.** Use "carried over" framing.
- **No API calls to third-party AI for inference.** The only AI in v1.9.6 is on-device ML Kit OCR (see `docs/architecture/ai-strategy.md` for the full story). **Voice capture is the exception:** it uses `android.speech.SpeechRecognizer`, which is a *system* service and **may use Google cloud depending on the device and the user's Google account settings.** This is documented in `docs/threat-model.md` §8.2 as a v1.x privacy trade-off. If a future v2.x ships a fully on-device speech path, the rule tightens; until then, the rule is "no third-party AI for inference *except the system speech recogniser*".
- **No analytics, no telemetry, no crash reporting that sends data off-device.** Local logs only.
- **The single note bar is the primary input.** Don't add a separate "New task" form.
- **Tabs = 3.** Today, Instructions, Contacts. Settings is a top-bar action. This officer-workspace redesign supersedes the old Home/Today/Settings navigation. Read PRODUCT.md, DESIGN.md and docs/architecture/officer-workspace.md before changing the UX.
- **Capture must complete in < 5 seconds.** Measure it; the CI fails if it regresses.
- **Conflict resolution is last-write-wins on `updatedAt`, logged in `SyncConflict` table.** No silent data loss.

### Tech decisions (locked)

- **Kotlin 2.0+**, **Jetpack Compose** for UI, **Material 3** design system
- **Hilt** for DI, **Room + SQLCipher** for local DB
- **WorkManager** for background jobs (brief generation, sync, stale detection)
- **Coroutines + Flow** for async, **kotlinx.serialization** for JSON
- **ML Kit Text Recognition v2** (Latin script) for photo OCR. **This is the only AI in v1.9.6.** See `docs/architecture/ai-strategy.md` §1. (The v1.5.x llama.cpp + Whisper.cpp stack was removed in v1.6.1 — see the strategy doc for the why.)
- **Android system `SpeechRecognizer`** for voice capture. On-device variant is requested via `EXTRA_PREFER_OFFLINE`; actual on-device vs cloud is a device-dependent property the app cannot enforce. See `docs/threat-model.md` §8.2.
- **Supabase** (Postgres + Auth + Storage + Realtime + Edge Functions) for cloud
- **Gradle Version Catalog** (libs.versions.toml) for dependency management

### Testing rules

- **Test the user-visible behavior, not the implementation.**
- **"Finding tests"** — tests that assert conclusions from the design (e.g., "the home screen never shows a red overdue badge") are required for the ADHD UX rules. If a rule exists in this file, there must be a test that fails if the rule is broken.
- **Reproduce any quoted number.** If you write "5 seconds", there's a benchmark.
- **CI runs three jobs** on every push: `unit-test`, `lint` (Android Lint, ktlint, detekt if present), `assemble`. **All three must pass** before merge. Currently the unit-test job is red — see [issues](../../issues) and PLAN.md §1.1.

### Privacy posture

- The user's data is police work. The bar is "no third-party AI ever sees it" — *with the documented exception of the system speech recogniser* (see Design rules above).
- AI provider for inference: **ML Kit on-device OCR** is the only production AI. The v1.5.x llama.cpp on-device LLM was removed in v1.6.1; the v1.9.6 reality is documented in `docs/architecture/ai-strategy.md`.
- Cloud storage = Supabase, encrypted in transit + at rest, with the option to mark items "sensitive" → local-only.
- No analytics, no telemetry, no crash reporting that sends data off-device.

### Out of scope (for v1)

- Multi-user / team mode
- CCTNS / eCourts integration
- WhatsApp Business API integration
- iOS app
- Wear OS app

These may come in v1.1+ or v2.0+.

## Build commands

```bash
./gradlew :app:assembleDebug           # Build debug APK
./gradlew :app:testDebugUnitTest       # Run unit tests
./gradlew :app:lintDebug               # Lint (warnings don't fail — see workflow)
./gradlew :app:connectedAndroidTest    # Instrumented tests (requires device)
```

CI runs the first three in parallel on every push; the third depends on the first.

## Repo conventions

- **Trunk-based.** Single default branch (currently `m0/skeleton-v1.7.0`, planned to become `main` after the `chore/sync-main-to-v1.9.6` PR merges).
- **Commit messages:** imperative, present tense ("Add capture flow", not "Added").
- **PR titles** = commit messages. No `feat:` / `fix:` prefixes unless conventional commits is enforced.
- **Squash-merge** to default branch. Each commit on the default branch should be a logical unit.
- **Branch protection on default branch:** planned — see PLAN.md §2.1.

## Related repos

- **MindAnchor** (`github.com/sampathmannam/MindAnchor`) — shares the `app-anchor-crypto` module and provides energy/notification state via MCP. Sharing mechanism: TBD — see PLAN.md §3.2 (open question).
- **Rowdy-Baby / CCA** (`github.com/sampathmannam/Rowdy-Baby`, `/cca`) — separate projects for crime analytics. Not integrated with Baton in v1.
- **Kaavalan Mobile Forensics** (`github.com/sampathmannam/kaavalan-mobile-forensics`) — separate project, shares the Tamil/IPS context.
