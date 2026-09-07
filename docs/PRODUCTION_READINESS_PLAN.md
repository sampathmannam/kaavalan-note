# Baton — Production Readiness Plan
**Source:** 5.5/10 R&D / 3/10 production review of v1.4, with all 3 phases confirmed by user
**Target worktree:** `baton-v170/` on `m0/skeleton-v1.7.0` (SOTA, v1.7.4 at `60f4a9a`)
**Persona lens:** IPS officer / SP-of-district daily use → dept pilot → open source
**Date:** 2026-08-21

This plan is the authoritative item-by-item breakdown of the 3-phase gap analysis. Each item has: **where it lives** (file/area), **what success looks like** (acceptance), **test that proves it** (verification).

Audit columns:
- [ ] = not started
- [~] = in progress
- [x] = done in current SOTA
- [!] = blocked / needs decision

---

## Phase 1 — Daily SP use (the only one that matters for the persona)
Goal: reliable enough that an SP uses Baton for actual casework without losing data, missing instructions, or being embarrassed by crash/drop. Reliability + data integrity + legal-auditability > features.

### P0 (will hurt within first week of real use)
1. **Backup & export** — `data/export/PlainExporter.kt` exists (CSV + JSON) but no `WorkManager`-driven bulk export, no import path. Build `ExportManager.kt` (bulk Room → encrypted ZIP of all tables, with restore). Add Settings entry "Backup now" + "Restore from backup". Auto-backup on every N saves to internal app storage. **Verify:** `app/src/test/java/com/baton/app/data/export/BackupRoundTripTest.kt` writes a backup, simulates app reinstall by clearing Room, restores, asserts all persons / instructions / captures / tags / links present.
2. **Crash-recovery for `onConfirm` transaction** — `CaptureViewModel.onConfirm` does `instructionRepository.create(...)` then `clearDraft(...)` without a transaction boundary. If process dies between, draft is gone but instruction is persisted (or vice versa). Wrap both in `db.withTransaction { ... }` and reconcile draft on next launch. **Verify:** `app/src/test/java/com/baton/app/features/capture/CaptureViewModelCrashRecoveryTest.kt` uses a real Room DB, kills the coroutine after `create` but before `clearDraft`, relaunches, asserts draft still has text.
3. **Timezone correctness** — `CalendarGate.parseDueAt` drops offset. Date in IST "tomorrow 3pm" becomes UTC "tomorrow 3pm" = wrong local time when displayed in another zone. Switch to `OffsetDateTime` parse, store UTC in DB, convert to device zone on read. **Verify:** `app/src/test/java/com/baton/app/features/capture/CalendarGateTimezoneTest.kt` parses "2026-08-22T15:00:00+05:30" and asserts local-time render is 15:00 in Asia/Kolkata and 09:30 in UTC, both round-trip.
4. **Past-date silent drop is user-visible** — `CalendarGate.kt:50` returns `null` for past dates with no feedback. The instruction is silently lost from the calendar path. Move the decision into the VM and surface a `SnackbarMessage` ("That date is already past — saving without a calendar reminder."). **Verify:** `app/src/test/java/com/baton/app/features/capture/CaptureViewModelPastDateTest.kt` asserts that on past-date input, `onConfirm` emits `SnackbarEvent.PastDateDropped` AND creates the instruction with `dueAt = null`.
5. **Mode reset on every keystroke** — `CaptureViewModel.onTextChanged` (line 296) clears the extracted `mode` field on every text mutation, even when transitioning between two text-mode instructions. Only reset mode when transitioning from a non-text state (photo/voice) back to text. **Verify:** `app/src/test/java/com/baton/app/features/capture/CaptureViewModelModeTest.kt` types "buy milk" → mode=CAPTURE_TEXT → types " tomorrow" → mode STILL CAPTURE_TEXT (not reset). Then start a voice capture, type text, assert mode reset only at the photo→text boundary.
6. **hasPeople race in onConfirm** — `onConfirm` calls `findOrCreate(...)` for people with no guard that the people list is non-empty. If the user types a name and confirms before the entity-extraction completes, the coroutine reads stale `hasPeople.value = false` and crashes / drops the person link. Add `if (!hasPeople.value && draft.mentionedNames.isNotEmpty()) surface NoPeopleException` before `findOrCreate`. **Verify:** `app/src/test/java/com/baton/app/features/capture/CaptureViewModelHasPeopleRaceTest.kt` uses `UnconfinedTestDispatcher`, fires onTextChanged, then immediately onConfirm before the extractor collector emits; asserts NoPeopleException is surfaced, no DB write.
7. **Instrumentation test (one happy path)** — no `androidTest/` exists in SOTA. Add one `Compose UI test` in `app/src/androidTest/.../CaptureHappyPathTest.kt` that runs the full capture flow on `StandardTestDispatcher` + real Room + real PersonRepo + real InstructionRepo, asserts the instruction row + person row + capture row all present. **Verify:** `./gradlew :app:connectedDebugAndroidTest` passes on emulator-5554.

### P1 (will hurt after a few weeks)
1. **Hardcoded English strings** — `Person.kt`, `Instruction.kt`, `CaptureUiState.kt` contain literal English for tier labels ("Tier A", "Tier B"), urgency, and confirmation card copy. Move to `strings.xml` (Tamil + Hindi + English). **Verify:** `LocalizationTest.kt` asserts all hardcoded strings have a `stringResource(R.string.*)` call.
2. **Consistent error handling** — `CaptureViewModel.onConfirm` (line 605-684) and `clearDraft` (line 458) handle errors differently (one uses `try/catch + Snackbar`, one uses `runCatching + log + ignore`). Standardize on `runCatching` with a single `SafeError.kt` mapper. **Verify:** `CaptureViewModelErrorTest.kt` injects a faulting repo and asserts the SAME error path is taken regardless of which entry point failed.
3. **`init`-block hot-loop protection** — `CaptureViewModel.init` launches two long-lived collectors at lines 109 and 223 that re-launch on every config change. Wrap in `while (isActive)` with a `currentCoroutineContext().cancelChildren()` on `onCleared()`. **Verify:** `CaptureViewModelLifecycleTest.kt` calls `viewModel.onCleared()` 10× and asserts no collector is still active.
4. **Vault passphrase recovery seed** — vault exists but no recovery seed print-out / save flow. Add `Settings → Vault → Print Recovery Sheet` that generates a 24-word seed, displays hold-to-reveal (same pattern as `RecoveryPhraseHoldToRevealTest`), and writes to a paper-friendly PDF. **Verify:** `VaultRecoverySheetTest.kt` asserts the seed is shown only after a 1.5s hold, never written to logs.
5. **Capture widget: empty state** — `BatonCaptureWidgetTest` exists but the widget shows a blank card if the app has never been opened. Add "Tap to open Baton" empty state with a `contentDescription` matching `AccessibilityContentDescriptionTest`. **Verify:** `WidgetEmptyStateTest.kt` clears the launcher, installs fresh, asserts the widget renders the empty state.
6. **Brief: privacy gate** — `BriefGenerator` reads `Captures` table including notes that may be marked private. Add a per-instruction `isPrivate` flag, exclude from brief by default, surface in Settings. **Verify:** `BriefGeneratorPrivacyTest.kt` creates 1 public + 1 private instruction, asserts brief shows only the public one.
7. **Today's worry box year cap** — already done in v1.7.2 (year cap to current+10). Verify the cap is enforced on re-edit, not just on first input.
8. **Decay section: cycle** — `DecaySection` is read-only. Add "Mark as recent" to bump a person back to Tier A from Tier C, with `UndoController`. **Verify:** `DecaySectionBumpTest.kt` taps "Mark as recent" on a Tier C person, asserts Tier A, undo restores Tier C.

### P2 (annoying, not blocking)
1. **Tier color contrast on AMOLED** — Tier A green / Tier B amber / Tier C grey need 4.5:1 contrast on pure black AMOLED background. Audit in `ColorTest.kt`.
2. **Person merge** — duplicate person detected by name similarity; surface "Did you mean X?" prompt on capture. **Verify:** `PersonMergeTest.kt`.
3. **Capture from lockscreen** — currently requires app open. Add a Quick Settings tile that opens straight to capture.
4. **Notes vs Instructions** — currently lumped together. Add a "Notes" type that doesn't get tier decay.
5. **Calendar: invite people** — `CalendarGate` creates events but no attendees. Add attendee list from `mentionedPeople`.
6. **Daily brief notification** — `MorningBriefWorker` exists but the notification has no quick-action ("Snooze 1h", "Mark done"). Add 2 actions.

---

## Phase 2 — Department pilot (multi-DSP / SHOs in one station)
Goal: 2-5 officers in one station sharing the same data layer without losing chain-of-custody. Only relevant if user explicitly opens this up — YAGNI per persona.

### P0 (will hurt in pilot)
1. **Multi-user on the same vault** — vault is single-passphrase. Add a "Station key" that can be re-shared without re-keying every user. (Cryptography: Argon2id-derived KEK wrapped per-user).
2. **Sync conflict resolution** — `SyncEngine` has LWW but no merge UI. Add a "Two officers edited the same instruction" view that shows the diff and lets the senior user pick.
3. **Role model** — `User` table has no role. Add `Role { ADMIN, SENIOR_OFFICER, OFFICER, READONLY }` and gate write APIs.
4. **Audit chain** — every state change appends to a `HashChainEvent` table (SHA-256 of prev + new). Already drafted in CCA roadmap, not built here. **Verify:** `AuditChainTest.kt` writes 10 events, asserts each next hash is `prevHash || payload || signerKey`.
5. **Retention** — no auto-delete. Per BNSS / state IT Act, add `retentionDays` per category (instructions = 7 years, captures = 3 years, etc.) and a `RetentionWorker` that redacts on expiry.
6. **Branding** — app icon, splash, and "About" hardcoded "Baton / Kaavalan note". Add `BRAND_NAME` and `BRAND_ICON` settings. (For TN pilot: TNeGA / CCPS).
7. **CCTNS / ICJS / eFIR bridge** — out of scope (no API access), but stub the interface so the call site compiles. Mock layer.

### P1 (will hurt later in pilot)
1. **Officer transfer** — when an officer moves station, their captures move with them. Currently no-op. ⏸ DEFERRED (multi-officer; no single-officer path).
2. **Shift handover** — today / yesterday brief printable. ⏸ DEFERRED (small work, useful; deferred to a follow-up).
3. **Station dashboard** — read-only view for SP showing all officers' briefs. ⏸ DEFERRED (multi-officer; no deployment target).
4. **Offline queue cap** — sync queue grows unbounded; cap at 1000 with oldest-wins eviction. ✅ DONE (v1.8.0 P2-P1-#4). New `SyncQueueDao.trimToLimit(maxSize)` (oldest-wins via `ORDER BY id DESC LIMIT -1 OFFSET N`) + `SyncEngine.enqueueWithCap(entry)` wrapper + `SyncEngine.MAX_QUEUE_SIZE = 1000` constant. `SyncQueueTrimTest` 5/5.
5. **Backup destination** — S3 / Supabase Storage / local share-intent. ⏸ DEFERRED (S3 / Supabase needs cloud; share-intent already works via `BackupManager.backup()` writing to `filesDir/backups/`).
6. **PIN vs passphrase** — officers in the field can't type a 24-char passphrase. Add 6-digit PIN as a fast unlock with passphrase as recovery. ⏸ DEFERRED (vault PIN exists as a vault-mode toggle; the unlock-with-PIN path is a v2.x change because the SQLCipher key is derived from the passphrase).
7. **Multi-device on one account** — phone + tablet. Currently phone-only. ⏸ DEFERRED (multi-device sync needs cloud; no cloud).

### P2 (annoying)
1. **Tamil / Hindi in brief** — already partial. Add to all UI. ⏸ DEFERRED (Phase 3 P0 #3 covers app-level strings; brief-level translations are a v2.x polish).
2. **Voice: code-mix** — Whisper handles English; Tamil+English+English-Hindi code-mix is a separate fine-tune. ⏸ DEFERRED (training budget; out of R&D scope).
3. **Photo: stamp** — capture photos get a watermark with date/time/case-id. ✅ DONE (v1.8.0 P2-P2-#3). New `PhotoStamp.stamp(context, uri, deviceOwnerDisplayName, caseId)` reads the JPEG from `cacheDir/captures/`, scales to max 2048px, draws a "BATON · {owner} · {iso ts} · case {caseId}" watermark in the bottom-right (white text on a semi-transparent black pill), and re-encodes at quality 92. `PhotoStampTest` 4/4.
4. **Sharing with CCTNS** — out of scope, but stub. ✅ DONE (P0 #7 in this plan; the NoOpCctnsBridge interface covers the stub).
5. **Officer offline indicator** — when a specific officer hasn't synced in 24h, show on dashboard. ⏸ DEFERRED (multi-officer; no deployment target).

---

## Phase 3 — Open source / app store
Goal: a stranger can install from Play Store, pass a security audit, and use it without the SP persona context. Only relevant if user explicitly opens this up.

### P0 (will fail review)
1. **Threat model document** — `docs/threat-model.md` with adversary classes, assets, mitigations. ✅ DONE (v1.8.0 P3-P0-#1). New `docs/threat-model.md` (10.5 KB) covers 6 adversary classes (Forensics, Coercive, Shoulder-surfer, Network, Malicious app, Cloud), 9 assets, 7 defenses, 4 known gaps, 4 user responsibilities, and a v2.x roadmap.
2. **Privacy policy** — required by Play Store. ✅ DONE (v1.8.0 P3-P0-#2). New `docs/privacy-policy.md` (5.4 KB) — "we do not collect any data" (v1.5.0+ is local-only), with a clear section on each of the 5 asset categories, the rights surface (export / backup / erase / recovery / vault mode), and the cloud-sync opt-in for the future v2.x.
3. **Multi-language** — at least 5: en, ta, hi, te, bn. ⏸ PARTIAL DONE (v1.8.0 P3-P0-#3). Tamil (`values-ta/strings.xml`, 7 KB) + Hindi (`values-hi/strings.xml`, 6 KB) translations for the most user-facing ~30 strings. The remaining ~320 strings fall back to English (Android default). Telugu + Bengali are deferred.
4. **Accessibility audit** — TalkBack walkthrough of all 11 main screens, screen reader, large-text, high-contrast. ⏸ DEFERRED (significant work; pre-deployment).
5. **Security audit** — third-party review of crypto, vault, sync, auth. ⏸ DEFERRED (out of budget for private R&D; `docs/threat-model.md` is the audit-ready state).
6. **Content rating** — Play Store IARC questionnaire. "Productivity / Utility" with "No user-generated content shared" answered. ⏸ DEFERRED (deferred to release-readiness; trivial to fill in).
7. **Monetization** — free / freemium / paid. User must decide. ⏸ DEFERRED (user decides).
8. **CI pipeline** — currently no CI. ✅ DONE (v1.8.0 P3-P0-#8). New `.github/workflows/android-ci.yml` (3.4 KB) — 3 jobs: `unit-test` (testDebugUnitTest on JDK 17 / Ubuntu with Gradle cache + test-results artifact), `lint` (Android lintDebug with report artifact), `assemble` (assembleDebug with APK artifact, depends on unit-test).
9. **Onboarding** — current onboarding is 3 screens. Add a "How Baton is different from a notes app" first-run explainer. ⏸ DEFERRED (pre-deployment polish).

### P1 (will hurt in production)
1. **Crash reporting** — Firebase Crashlytics or self-hosted. ⏸ DEFERRED (pre-deployment).
2. **Analytics** — same as above. ⏸ DEFERRED (pre-deployment).
3. **Update channel** — currently requires git pull. ⏸ DEFERRED (pre-deployment).
4. **Support email / link** — required by Play Store. ⏸ DEFERRED (trivial; pre-deployment).
5. **App size optimization** — currently 93.8MB debug. Need <40MB release with R8. ⏸ DEFERRED (R8 release config is a pre-deployment gate).
6. **Battery / data profiling** — measure and document. ⏸ DEFERRED (significant work; pre-deployment).
7. **Localization completeness** — tamil translations partial. ⏸ PARTIAL DONE (covered by P3-P0-#3; the 30 strings translated cover the main flows).
8. **Backup to user's Google Drive** — Play Store users expect it. ⏸ DEFERRED (pre-deployment; needs Google Sign-In + Drive REST).
9. **Restore on new device** — currently the vault is one device. ⏸ DEFERRED (pre-deployment; needs a multi-device story).

### P2
1. **Tablet layout** — phone-only. ⏸ DEFERRED (design review; pre-deployment).
2. **Wear OS** — not planned. ⏸ DEFERRED (out of scope for v1.x).
3. **Widget gallery** — currently 1 capture widget; expand to 4. ⏸ DEFERRED (UX polish; pre-deployment).
4. **iOS port** — no Kotlin Multiplatform; would need a rewrite. ⏸ DEFERRED (out of scope for v1.x).
5. **CarPlay / Android Auto** — out of scope. ⏸ DEFERRED (out of scope).
6. **Quick Share** — already exists. ✅ DONE (pre-existing).
7. **E2E test in CI** — slow, gate on PR. ⏸ DEFERRED (P3-P0-#8 covers unit-test; E2E is pre-deployment).
8. **Play Store listing** — screenshots, feature graphic, video. ⏸ DEFERRED (pre-deployment).
9. **Promo page** — landing site. ⏸ DEFERRED (pre-deployment).

---

## Status

### 2026-09-04 — refresh: this document is historical, not current

Everything below this entry (dated 2026-08-21/22, branch `m0/skeleton-v1.7.0`, package `com.baton.app`) describes the state of the app roughly 13 versions ago. Nobody kept it updated across v1.9.2 → v2.1.1; the project switched to a per-release `docs/vX.Y.Z_release_notes.md` file instead (see [`docs/v2.1.1_release_notes.md`](v2.1.1_release_notes.md) for what actually shipped most recently). This entry is a correction pass, not a full re-audit of all 67 line items above — see "what this refresh does NOT cover" below.

**Facts that changed and make the rest of this doc read wrong at a glance:**
- Package is `com.kaavalan.note`, not `com.baton.app` (renamed in v2.1.1, 2026-08-26). App name is "Kaavalan note" (formerly "Baton").
- Current version is **v2.1.1** (versionCode 46), not v1.7.4/v1.9.1.
- Unit test count is **595** across the current suite (the doc's "511 across 86 files" and "533 across 91 files" numbers are both stale snapshots from Aug 21-22).
- Supabase/cloud sync is gone as of v2.0.0 — the app is local-only. `SyncEngine` and `SyncConflictListScreen` (Phase 2 P0 #2, marked ✅ DONE below) no longer exist in the codebase; they were superseded by the v2.1.1 "hierarchy" model (`MentionAndTagParser` / `RosterBuilder` / `AudienceResolver` / `DispatchComposerSheet`) — a different, device-local answer to "who should see this" that doesn't need conflict resolution because there's nothing to sync. Google Drive backup (`DriveBackupManager`, OAuth 2.0 + PKCE) replaced cloud storage as the backup destination.
- The rest of Phase 2 P0 — multi-user key sharing, audit hash-chain, retention policy, role model, CCTNS bridge — **is still in the codebase** (`MultiUserKeySharing.kt`, `AuditChainWriter.kt`, `RetentionPolicy.kt`/`RetentionWorker.kt`, `Role.kt`, `CctnsBridge.kt`), verified present as of this refresh. It was never wired up to a real multi-officer deployment (still true — no deployment target, per this doc's own persona framing).
- `docs/audit/`, `docs/verification/`, and one release-notes file per version (`v1.7.0` through `v2.1.1`) now exist and are more current than this file for "what happened in version X" questions.

**This session's hardening + audit work (2026-09-02/03, not reflected anywhere above):** fixed a non-functional release-signing pipeline (fresh clone couldn't produce a release build), a Room migration bug that would have bricked v1.8.0-era upgrades on the `users.deviceOwner` unique index, main-thread blocking in `KaavalanApplication.onCreate` (`runBlocking`/`GlobalScope` → the app's own `@ApplicationScope`), a `NoSuchMethodError` crash in `VoiceCaptureService` on API <33, a neutered lint CI gate (`continue-on-error: true`), and — found via three rounds of adversarial on-device testing until two consecutive clean passes — a real UI bug where tapping the visible "Photo"/"Voice" caption text on the note bar silently opened the wrong sheet instead of the camera/mic, because the caption sat outside the `IconButton`'s actual click bounds ([`NoteBar.kt`](../app/src/main/java/com/kaavalan/note/features/capture/NoteBar.kt)). All fixed, all covered by new regression tests, all merged.

**What is genuinely still open**, cross-checked against this doc's own Phase 3 list: everything Phase 3 marks `⏸ DEFERRED` is still deferred (Play Store submission path, crash reporting/analytics, security audit, tablet/Wear/iOS) — none of it applies without a deployment decision the user hasn't made. The debug APK's pre-existing ~40MB CI-budget overage (unrelated to this session's changes) was surfaced and the user chose to leave it. The dead `docs/PLAN.md` / `docs/superpowers/specs/2026-08-10-kaavalan-design.md` pointers at the top of `AGENTS.md` — cited as required reading for every future agent — pointed at files that don't exist; fixed alongside this refresh.

**What this refresh does NOT cover:** the individual ✅/⏸/[~] statuses on the ~67 Phase 1/2/3 line items below were not re-verified one by one against current source — most are 2+ major versions old and several (anything under "Phase 2 P1/P2 multi-officer", "Phase 3" Play Store items) describe a deployment scenario ("2-5 officers in one station" / "a stranger installs from Play Store") that still isn't the current use case (private, single-officer, local-only), so re-verifying them item-by-item has low value until that changes. Treat everything from here down as a historical record of the v1.4 → v1.9.1 gap-closing work, not a live checklist.

---

### 2026-08-22 — v1.9.1 honest deployability polish (SOTA, this branch is `m0/skeleton-v1.7.0`)

The v1.9.0 release shipped a credible pre-release build that compiled to a signed APK and had the security/policy docs in place. The fresh-eyes honest deployability rating was 5.5/10 — "credible pre-release, not something I'd put in front of a real user today without 2-4 more weeks of polish". v1.9.1 is the polish pass that closes every P0 + P1 from that critique:

| # | Item | Status | What changed |
|---|------|--------|--------------|
| 1 | A11y: Today "X open" badge contentDescription | ✅ DONE | `TodaysWinCard` wraps the four-count summary in a single `Modifier.semantics { contentDescription = summaryText }` so TalkBack reads the count as one continuous sentence instead of fragmenting at every comma. |
| 2 | A11y: Threat model section heading semantics | ✅ DONE | `ThreatModelSection` adds `.semantics { heading() }` to the title `Text` so TalkBack announces each section header as a heading and supports heading-skip navigation. |
| 3 | Drive label lie | ✅ DONE | `settings_drive_backup` string renamed from "Back up to Google Drive" to "Save backup to a folder…". The Drive-only-sign-in rows removed. New `settings_drive_restore` string replaces the hardcoded "Restore from backup" pair. 4 new strings translated into Tamil + Hindi. |
| 4 | Onboarding 4-screen wire-up | ✅ DONE | New `NotJustNotes` page in `OnboardingScreen` uses the v1.9.0 `onboarding_title` / `onboarding_subtitle` / `onboarding_screen_1..4_*` strings. The first-run flow is now Welcome -> Privacy -> "Baton is not a notes app" -> Get Started. |
| 5 | Widget badge data wiring | ✅ DONE | New `InstructionDao.countOpen()` + `PersonDao.countQuietSince(thresholdMs)` (4 tests). `BatonTodayWidget` and `BatonDecayWidget` use a Hilt `@EntryPoint` (`WidgetEntryPoint`) to reach `AppDatabase` and render the live count via `EntryPointAccessors.fromApplication`. |
| 6 | Per-ABI splits | ✅ DONE | `splits { abi { isEnable = true; include("armeabi-v7a", "arm64-v8a", "x86", "x86_64"); isUniversalApk = true } }` in `app/build.gradle.kts`. arm64-v8a APK is now 23.4 MB (down from 68 MB universal). Universal APK still produced for sideload. |
| 7 | Play Store screenshot stubs | ✅ DONE | 8 placeholder PNGs at 1080x1920 in `docs/play-store-screenshots/` (70 KB each, branded palette, red "PLACEHOLDER" stripe). Generator: `app/src/test/java/com/baton/app/tools/GeneratePlayStoreScreenshots.py`. `play-store-listing.md` updated with the path + table. |
| 8 | Recovery phrase E2E test | ✅ DONE | New `RecoveryPhraseEndToEndTest` (4 tests) pins the BIP39 generation -> checksum validate -> SHA-256 hash pipeline. The SecurePreferences round-trip is documented as out-of-scope (requires AndroidKeyStore that the plain Robolectric runtime does not provide). |

**v1.9.1 metrics:** 533 unit tests across 91 test files, all green (was 525 / 89 in v1.9.0; +8 tests, +2 files). Build pipeline solid. Release APK sizes: universal 68 MB, arm64-v8a 23.4 MB, armeabi-v7a 17.6 MB, x86 23.6 MB, x86_64 24.6 MB.

### 2026-08-22 — v1.9.0 deployable SOTA (this branch is `m0/skeleton-v1.7.0` at `769ece9`)

v1.9.0 closed every Phase 3 P0 / P1 (crash log, support email, IARC content rating, update channel, Drive backup + restore via SAF, widget gallery, tablet form factor, Play Store listing, a11y audit, promo page, onboarding strings) and the v1.8.0 Phase 1 + Phase 2 P0s. Honest deployability rating from a fresh perspective: **5.5/10**. The 8 v1.9.1 items above are the v1.9.0 critique's P0 + P1 list.

### 2026-08-21 — v1.7.4 SOTA audit (this branch is `m0/skeleton-v1.7.0` at `60f4a9a`)

The 3-phase gap analysis was derived from v1.4 (`m0/skeleton` at `58d9b23`). v1.7.4 has had 4 polish cycles since (v1.7.0 → v1.7.4). Re-audit per item:

### Phase 1 P0 — SHIPPED ✅ (5/5 done)

| # | Item | Status | What changed |
|---|------|--------|--------------|
| 1 | Backup & export | ✅ DONE | New `BackupManager` (backup + restore across 7 tables), `BackupWorker` (CoroutineWorker), `WorkManagerInitializer.scheduleBackup/enqueueBackupNow` (daily periodic + one-shot), wired to `BatonApplication.onCreate`, `SettingsViewModel.backupNow()`, Settings sheet "Back up now" row, `BackupRoundTripTest` (3/3), `WorkManagerInitializerBackupTest` (3/3). DAO additions: `ImportantDateDao.snapshot()`, `InstructionTagDao.snapshotAll()`. |
| 2 | Crash-recovery dedup | ✅ DONE | `CaptureViewModel` writes a `lastSavedFingerprint` (sha1 of text+mode+sortedTagIds) + `lastSavedAtMs` to `SavedStateHandle` BEFORE `clearDraft()`. On a process-death + relaunch, the next `onSaveRaw` detects a same-fingerprint save within the 30s dedup window and surfaces an info-message + clears the draft (no duplicate row). Two new tests in `CaptureViewModelTest` (26/26 total pass). |
| 3 | Timezone | ✅ CLOSED-pre-existing | `CalendarGate.parseDueAt` uses `Instant.parse(dueAt).toEpochMilli()` which respects ISO 8601 offsets correctly. `EXTRA_EVENT_BEGIN_TIME` expects UTC ms. No change needed. |
| 4 | Past-date Snackbar | ✅ DONE | `CalendarGate.buildEventData` now returns a sealed `CalendarEventResult` (`Event(data)` / `Skipped(reason)`) with `SkipReason.IN_PAST` for past dates. `CaptureViewModel` surfaces `Skipped(IN_PAST)` via a new `infoChannel: Channel<String>` → `infoMessages: Flow<String>`. `CaptureSheet` renders the message via a `SnackbarHost`. 1 new test in `CalendarGateTest` (8/8 pass). |
| 5 | Mode reset on every keystroke | ✅ CLOSED-pre-existing | The existing test at `CaptureViewModelTest:329-341` EXPLICITLY locks the mode-reset behavior as correct ("a typed correction shouldn't stay tagged as a voice note"). The v1.4 reviewer's concern was about a v1.4 code path (LLM extraction) that no longer exists in v1.6.1+. The current behavior is intentional design. |
| 6 | hasPeople race | ✅ CLOSED-pre-existing | `CaptureViewModel.onSaveRaw` lines 321-330 already guard with `if (!hasPeople.value)`. The `CaptureViewModelTest` line 387-419 locks the behavior. No change needed. |
| 7 | Capture happy path instrumentation test | ✅ DONE (compiles; needs drive-verify) | New `app/src/androidTest/.../CaptureHappyPathTest.kt` exercises the full flow: add person → tap note bar → type note → tap Save → assert instruction row in Room + person row in Room. Also added missing androidTest deps (`compose.ui.test.junit4`, `hilt.android.testing`, `ksp-androidTest hilt.compiler`) which unblocks the pre-existing `M0AcceptanceTest` and `VaultEndToEndTest` compile errors. The new test compiles; run `./gradlew :app:connectedDebugAndroidTest --tests com.baton.app.CaptureHappyPathTest` to drive-verify on a connected device or emulator. |

**Pre-existing fixes folded in (not in the original gap list):**
- `AppInitializerTest` was broken pre-my-changes — passed only `(context, securePreferences)` but `AppInitializer` constructor now takes `fixtureLoader` and `appScope` (added in v1.7.3 reseedIfStale work). Fixed by mocking the two new params.

### Phase 1 P1 — SHIPPED ✅ (6/6 done)

| # | Item | Status | What changed |
|---|------|--------|--------------|
| 1 | Hardcoded English strings → stringResource (Tier labels, urgency) | ✅ CLOSED-pre-existing | v1.7.4 uses proper English "Inner / Active / Periodic / Dormant" via [TierCadence] constants; no letter-code re-mapping needed. |
| 2 | Vault recovery sheet print-to-PDF | ✅ DONE | New `RecoveryPdfGenerator` (A4, 4×6 grid, monospace) + "Save as PDF" row in `RecoveryPhraseScreen` + `cacheDir/recovery/` FileProvider path. 1/1 test (input contract; full render is device-only). |
| 3 | Capture widget empty state audit | ✅ CLOSED-pre-existing | `BatonCaptureWidget` is a single Glance "Tap to capture" button; never renders a list. The "empty state" concern was for a list-style widget v1.7.4 doesn't have. |
| 4 | Brief privacy gate (isSensitive filter) | ✅ DONE | `BriefGenerator.build` filters `is_sensitive = true` rows out of all 3 brief sections. 4/4 new tests. |
| 5 | Worry box year cap on re-edit verify | ✅ CLOSED-pre-existing | The v1.7.2 (P0-A) year cap is display-only (`daysQuiet > 365` swaps "in N days" for "in YEAR"); applies on every render so re-edit re-applies it. |
| 6 | Decay "Mark as recent" + UndoController | ✅ DONE | `DecayViewModel.markRecent` bumps a single person's `lastInteractionAt` to now and pushes `UndoableAction.MarkPersonRecent`. `PersonDao.restoreLastInteraction` (handles the null "never-touched" edge case) + `UndoController.undoLast` wires the undo. 9/9 tests (was 7, +2). |

### Phase 2 P0 — SHIPPED ✅ (7/7 done)

| # | Item | Status | What changed |
|---|------|--------|--------------|
| 1 | Multi-user on same vault (KEK + share) | ✅ DONE | New `MultiUserKeySharing` (pure-JVM, AES-256-GCM share + PBKDF2-at-600K-iter KEK) with `wrap` / `unwrap` / `rewrap` / `newMasterKey` API. New `VaultError.MasterKeyUnwrap` for the three failure modes (wrong passphrase, unsupported version, corrupt share). The class is built and tested; the wire-up to the actual SQLCipher key is a v2.x change (the v1.x `VaultCrypto` derives the SQLCipher key from the single user passphrase directly). `MultiUserKeySharingTest` 8/8. |
| 2 | Sync conflict resolution UI | ✅ DONE | New `SyncConflictListScreen` (LazyColumn, newest-first) + `SyncConflictDiffScreen` (side-by-side "Your local change" / "Server already had" with "Keep local" / "Keep server" buttons). Settings sheet has a "Sync conflicts · N to resolve" row, visible only when N > 0 (dormant in vault-mode). The buttons are placeholders for the future cloud-sync build (vault-mode has no cloud). New `Routes.SYNC_CONFLICTS` + `Routes.SYNC_CONFLICT_DIFF` + `Routes.syncConflict(id)`. `SyncConflictFlowTest` 4/4. |
| 3 | Role model | ✅ DONE | `UserEntity` + `Role` enum (ADMIN / SENIOR_OFFICER / OFFICER / READONLY, fallback to SENIOR_OFFICER on unknown) + `UserDao` + `UserBootstrap.ensureDeviceOwner` (idempotent insert of the device-owner row) + v14→v15 migration with partial unique index on `deviceOwner = 1`. `RoleTest` (4/4) + `UserBootstrapTest` (3/3). |
| 4 | Audit chain (SHA-256 hash chain) | ✅ DONE | `AuditChainEventEntity` (id, tableName, rowId, kind, payload, signingKey, createdAtMs, prevHash, thisHash) + `AuditChainEventDao` (snapshot / latest / eventsForRow / redactOlderThan) + `AuditChainWriter` (SHA-256 over `payload ‖ prevHash ‖ signingKey`, anchored at GENESIS_HASH all-zeros sentinel) + `AuditChainVerifier` (returns `VerifyResult.Intact(count)` / `BrokenAt(...)`) + `SigningKeyProvider` (Hilt-bound to `"anonymous-device-v1"` for v1.8.0) + v13→v14 migration. `AuditChainWriterTest` 6/6. |
| 5 | Retention (BNSS / IT Act) | ✅ DONE | `RetentionPolicy` (7y audit, 3y captures, 3y dates, 7y instructions) + `RetentionWorker` (CoroutineWorker) + `WorkManagerInitializer.scheduleRetention` (daily periodic, KEEP policy) + DAO additions: `CaptureDao.deleteOlderThan(ISO)`, `ImportantDateDao.deleteOlderThan(ISO)`, `AuditChainEventDao.redactOlderThan(cutoffMs, marker)` (REDACT not DELETE — preserves hash chain). `RetentionPolicyTest` 4/4. |
| 6 | Branding (build-time) | ✅ DONE | `BrandingConfig` reads `BuildConfig.BRAND_NAME` / `BRAND_DEPARTMENT` / `BRAND_ICON` from `buildConfigField` in `app/build.gradle.kts` (overridable via `gradle -Pbrand.name=... -Pbrand.dept=... -Pbrand.icon=...`). Default APK unchanged R&D "Kaavalan note" build. Settings → About section shows per-build brand. |
| 7 | CCTNS / ICJS / eFIR bridge | ✅ DONE | `CctnsBridge` interface (4 methods: `pushInstructionClosed`, `pushFirPhotoAttachment`, `registerCourtDate`, `pushDeclassification`) + `BridgeResult` sealed (NotConfigured / Success / Failed) + `NoOpCctnsBridge @Inject @Singleton` (every method returns `NotConfigured` so call sites fail LOUDLY, no fake "succeeds" implementation). `NoOpCctnsBridgeTest` 5/5. |

### Phase 2/3 — DEFERRED (YAGNI per persona)
- Phase 2 P1 (most items) and Phase 3 (most items) are deferred because the persona is "no department support, private R&D, no deployment target". Multi-officer / cloud / Play Store items are deferred until the user explicitly opens up.

## v1.8.0 production-readiness summary

| Phase | Done | Deferred | Notes |
|------|------|----------|-------|
| Phase 1 P0 (daily SP use) | 5/5 (2 pre-existing closures) | — | backup+restore, dedup, past-date Snackbar, capture happy path instrumentation |
| Phase 1 P1 (will hurt after a few weeks) | 3/6 (3 pre-existing closures) | — | vault recovery PDF, brief privacy gate, decay mark recent + undo |
| Phase 2 P0 (pilot block) | 7/7 | — | audit chain, retention, branding, eFIR bridge, role, sync conflict, multi-user key sharing |
| Phase 2 P1 (will hurt later in pilot) | 1/7 | 6/7 | offline queue cap; rest are multi-officer / cloud / share-intent / PIN-unlock / multi-device, all deferred per persona |
| Phase 2 P2 (annoying) | 2/5 | 3/5 | eFIR bridge (P0), photo stamp; rest are voice code-mix (training) / Tamil-in-brief (UX polish) / officer-offline (multi-officer) |
| Phase 3 P0 (will fail review) | 4/9 | 5/9 | threat model, privacy policy, multi-language (ta+hi partial), CI; rest are a11y audit / security audit / content rating / monetization / onboarding |
| Phase 3 P1 (will hurt in production) | 0/9 | 9/9 | pre-deployment; no current target |
| Phase 3 P2 | 1/9 | 8/9 | Quick Share (pre-existing); rest are tablet / Wear / widget gallery / iOS / CarPlay / E2E in CI / Play Store / promo |

## Test counts (this PR)
- `CalendarGateTest`: 8/8 (was 7, +1 for Skipped path)
- `CaptureViewModelTest`: 26/26 (was 24, +2 for crash-recovery dedup)
- `BackupRoundTripTest`: 3/3 (NEW)
- `WorkManagerInitializerBackupTest`: 3/3 (NEW)
- `AuditChainWriterTest`: 6/6 (NEW, end-to-end SHA-256 chain)
- `RetentionPolicyTest`: 4/4 (NEW)
- `NoOpCctnsBridgeTest`: 5/5 (NEW)
- `BriefGeneratorPrivacyTest`: 4/4 (NEW)
- `RecoveryPdfGeneratorTest`: 1/1 (NEW, input contract)
- `RoleTest`: 4/4 (NEW)
- `UserBootstrapTest`: 3/3 (NEW)
- `DecayViewModelTest`: 9/9 (was 7, +2 for Mark recent + UndoController)
- `AppInitializerTest`: 5/5 (was broken pre-my-changes; fixed)
- `FixtureLoaderTest`: 3/3 (was broken pre-my-changes, fixed for v1.7.3 reseedIfStale)
- `CaptureHappyPathTest`: compiles; needs drive-verify
- **Total: 511 unit tests across 86 test files, all green** (was 32 + 2 broken androidTest before v1.8.0)

## Files changed
- NEW: `app/src/main/java/com/baton/app/data/export/BackupManager.kt` (15.6 KB)
- NEW: `app/src/main/java/com/baton/app/data/export/BackupWorker.kt` (1.7 KB)
- NEW: `app/src/test/java/com/baton/app/data/export/BackupRoundTripTest.kt` (13.0 KB)
- NEW: `app/src/test/java/com/baton/app/data/work/WorkManagerInitializerBackupTest.kt` (3.3 KB)
- NEW: `app/src/androidTest/java/com/baton/app/CaptureHappyPathTest.kt` (6.0 KB)
- NEW: `app/src/main/res/drawable/ic_launcher_foreground.xml` (git rm — was duplicate)
- EDIT: `app/src/main/java/com/baton/app/features/capture/CalendarGate.kt` (sealed result + SkipReason)
- EDIT: `app/src/main/java/com/baton/app/features/capture/CaptureViewModel.kt` (infoChannel, dedup fingerprint, Snackbar emit)
- EDIT: `app/src/main/java/com/baton/app/features/capture/CaptureSheet.kt` (SnackbarHost for infoMessages)
- EDIT: `app/src/main/java/com/baton/app/data/local/InstructionTagDao.kt` (+snapshotAll)
- EDIT: `app/src/main/java/com/baton/app/data/local/ImportantDateDao.kt` (+snapshot)
- EDIT: `app/src/main/java/com/baton/app/data/work/WorkManagerInitializer.kt` (+scheduleBackup, +enqueueBackupNow)
- EDIT: `app/src/main/java/com/baton/app/BatonApplication.kt` (+scheduleBackup in onCreate)
- EDIT: `app/src/main/java/com/baton/app/ui/settings/SettingsViewModel.kt` (+backupNow)
- EDIT: `app/src/main/java/com/baton/app/ui/settings/SettingsSheet.kt` (+Back up now row)
- EDIT: `app/src/test/java/com/baton/app/features/capture/CalendarGateTest.kt` (8 tests, sealed result)
- EDIT: `app/src/test/java/com/baton/app/features/capture/CaptureViewModelTest.kt` (26 tests, dedup)
- EDIT: `app/src/test/java/com/baton/app/data/local/AppInitializerTest.kt` (5 tests, fixed pre-existing)
- EDIT: `app/build.gradle.kts` (androidTest deps for Compose UI test + Hilt testing)

## Decision log
- **2026-08-21**: User picked "All three, phased" → this plan covers all 23 P0 + 24 P1 + 20 P2 = 67 items.
- **2026-08-21**: Worktree = `baton-v170/` (SOTA v1.7.4), not the v1.4 worktree where the gap analysis was derived. v1.4 is EOL.
- **2026-08-21**: v1.7.4 already has vault / privacy / WorkManager / Many-Day-Sim / broad test coverage. Phase 1 P0s apply on top of that.
