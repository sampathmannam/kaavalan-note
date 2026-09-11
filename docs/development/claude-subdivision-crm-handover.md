# KaavalanNote 2.6.0 — fixed implementation handover to Claude

Prepared by Codex on 2026-09-10 at the user's explicit request to transfer implementation to **Claude**, using their Claude subscription. This is an execution brief, not an invitation to redesign. The user has approved the existing blue-and-slate native appearance and delegated remaining small visual decisions to Codex. All decisions below are now fixed. Execute, test, repair and produce the build artifacts. Do not ask routine product, design, library, naming or implementation questions. Do not stop after making a plan or producing a mock-up.

## 1. Authority, privacy and delivery boundary

- Work in `/Users/sujithsampath/work/kaavalan-note-dev` only. This is the real repository, not the unrelated Codex workspace directory.
- Never enter, enumerate or read `kaavalan-note-prod`. Never read local signing passwords, signing property files, production keystores, shell credential stores, private app databases, phone contacts or personal exports. Do not print environment variables. Source-code references to signing are not permission to load signing material.
- The earlier signing/publication exception applied to **v2.5.0 only** and has already been used. It does not authorize signing this build. Always pass `-Pkaavalan.enableReleaseSigning=false` to Gradle. No release signing, push, PR, tag, release publication, or physical-phone install without fresh user approval.
- Build an unsigned daily-app release candidate and an isolated debug QA APK. An unsigned APK is NOT installable; a debug APK is NOT a production-update handover. Do not claim otherwise. Final signing/installation remains a clearly labeled approval gate after all implementation and testing are finished.
- Keep application ID `com.kaavalan.note` for the eventual daily app. Do not fork the product into multiple normal-use apps. The QA-only suffix is `.debug.officer` and must never be presented as the user's everyday app.
- No third-party AI inference, cloud CRM, analytics, telemetry, or staff data uploads. Existing Android system speech recognition exception remains documented. This task authorizes Claude to process development source code, not operational police data.
- Use only synthetic fixtures. Only task-owned Android emulator `emulator-5596` / AVD `kaavalan-contact-import-20260909` may be changed. Do not install on any physical phone or another emulator; do not terminate unrelated emulators, CI or processes. Always select the exact serial, never bare `adb install`, and set `ANDROID_SERIAL=emulator-5596` for connected Gradle tests.
- Preserve all pre-existing and new working-tree edits. Do not reset, clean, checkout over, delete or stash them. Do not start a parallel agent that races this tree. Continue on the existing feature branch.
- Security, missing authentication, inaccessible signing material or an unavailable emulator can be real blockers. Exhaust safe alternatives, record the precise remaining gate, and finish everything else. Never fake a test pass or silently broaden permissions just to avoid a question.

## 2. Exact starting point — unfinished, not yet tested

Branch: `feat/subdivision-crm`, based on `origin/main` commit `a7eea250266169d9b0ac69a6c2bf4a444c522b1f` (released v2.5.0). No new commit/push exists. The working tree contains intentional Codex changes; do not switch branches and lose them. This already satisfies the user's separate-branch requirement.

Read these repository instructions first: `AGENTS.md`, the 2026-09-04 status entry in `docs/PRODUCTION_READINESS_PLAN.md`, `docs/v2.5.0_release_notes.md`, `PRODUCT.md`, `DESIGN.md`, `docs/architecture/officer-workspace.md`. Older AGENTS paragraphs have historical paths and cloud architecture; the current source and these newer product documents are authoritative for reality.

Architecture: one native Android app module; Kotlin 2, Compose Material 3, Hilt, Room with SQLCipher, Flow/Coroutines, WorkManager, local-only domain repositories. Room is the source of truth. `OfficerAppRoot` owns navigation and global instruction/edit/contact sheets. `WorkspaceViewModel` projects the three tabs. `InstructionWorkflow` owns transactional lifecycle changes and journaling; do not create competing writers in Composables.

Existing uncommitted changes:

- `data/subdivision/SubdivisionEntities.kt`: Room/serialization entities `SubdivisionProfile`, `Station`, `Matter`, `StaffPosting`, `SubdivisionReview`, `SubdivisionArchive`.
- `SubdivisionDao.kt`: mode-scoped observations, snapshots, lookup and Upsert operations.
- `SubdivisionMigration.kt`: `SUBDIVISION_MIGRATION_17_18` adds tables and fields, derives stable station IDs from existing contact station text separately per vault, snapshots station IDs onto existing instructions. No real subdivision or staff classification is seeded.
- `SubdivisionRepository.kt`: transactional profile/station/staff/matter edits, link/relink journal, reversible archive guards, review record, station name normalization.
- DB version is now 18, entities registered and migration registered in `DatabaseModule`.
- Person entity/domain/mappers now carry `stationId`, `isStaff`, `staffActive`, `responsibilities`; instruction entity/domain/mappers carry `stationId`, `matterId`.
- `PersonDao.updateExisting` added to avoid replace/cascade during edits.
- `RoomPersonRepository` now takes `AppDatabase`, creates/resolves a station during contact creation in a transaction. One real-DB constructor test was adapted.
- `RoomInstructionRepository.create` and `createWithAudience` now save a station snapshot; audience creation returns the saved row. **Known unfinished correction:** plain `create()` still returns the old pre-copy entity, so return the saved row with its station ID there too. Confirm no additional create/write path drops the IDs.
- `InstructionWorkflow.editContact` now resolves the stable station ID, preserves other contact fields and records staff posting changes; `edit` journals old/new responsible contact names; reopen reactivates linked station/matter.

NONE of this new work has been compiled or tested yet. Earlier v2.5.0 success is not evidence for this work. Inspect and correct the existing changes before extending them. Do not discard them and rewrite from scratch.

## 3. Product contract

This is a **single-officer personal subdivision work manager**, not a sales CRM or a shared command system. It helps an officer remember instructions, understand responsibility, follow up, record updates, verify completion and prepare reviews. Staff do not log in. Nothing pretends to be a staff acknowledgement unless the officer records it. A friend's separate installation has its own blank profile and private local data. No hard-coded district, station, subdivision, rank, person, account or phone number.

Retain exactly three primary tabs: **Today, Instructions, Contacts**. Retain the single NoteBar as the primary quick input, the same app icon/name, blue/slate Material theme, existing spacing and typography tokens, light/dark modes, reminders, import from contacts, capture, journal, deadline, verification, undo, search and backup features.

New first-class concepts:

1. Subdivision profile: name required; district and officer display name optional; editable later.
2. Stations/units: stable ID, name, type text (default `Station`, user editable), notes, archive state. Unique trimmed ASCII-case-insensitive name per vault. Keep non-Latin names intact.
3. Staff: an existing contact explicitly marked as staff, current station/unit, responsibilities, active/inactive posting. Existing contacts remain ordinary contacts until classified. Imported numbers/names remain intact. No scores, ranking, attendance or inferred performance.
4. Matters: a named grouping of instructions, optional station, optional reference, context/description, archive state. The label is `Matters`; explain `Group related instructions and their history.` Do not imply legal/case records or add unrelated sales fields.
5. Review records: dated officer notes at subdivision, station or officer scope, with an honest point-in-time count of open and ready-to-verify instructions. Review does not complete work or send messages.

## 4. Frozen navigation and screen design

Use normal native full-screen child destinations with Back, not a fourth tab or a crowded all-in-one dialog. Keep navigation state through recreation; clear inaccessible CRM destinations when vault mode changes. All CRM views are **normal-workspace only** for this version. Hidden/sensitive content must never appear in a normal CRM list, review, search, picker or count. Existing hidden workspace functions must continue to work.

### Entry points on existing tabs

- Today: one labeled secondary action immediately below the top bar, `Subdivision review`. Once configured, its supporting text shows the profile name. Before setup use `Set up your subdivision` supporting text, not a blocking onboarding modal.
- Instructions: secondary action `Matters`, supporting text `Group related instructions`.
- Contacts: secondary action `Stations & staff`, supporting text `Postings and responsibilities`.
- Each entry uses an existing outlined Material icon, a clearly readable label and a forward chevron. Hide these entries in private mode. Do not crowd three text buttons into the top app bar. Preserve Settings and every current empty/error/loading state.

### Subdivision review home

Top app bar `Subdivision review` and Back. Compact profile header (name; optional district/officer), with labeled `Edit subdivision` or `Set up subdivision`. Explain once: `Your private work record. Staff do not need an account.`

Use a scrollable, vertically ordered review surface, not charts or a wall of colored metric cards:

1. Scope chooser: `Whole subdivision`, station/unit, or staff officer. Searchable chooser, not an unbounded menu or chip wall.
2. Small textual summary of open instructions and ready to verify. Counts are based only on current visible, nonsensitive records.
3. Filters: `Open`, `Ready to verify`, `Deadline passed`, `No update in 7 days`, `Changed since last review`. If there is no earlier review, say so and do not fabricate a since-date. Completed/dropped are excluded from open categories but included in relevant history/changes.
4. Instruction list using existing card presentation, with recorded station and matter context plus current responsible contact. Open the existing instruction detail to update/reassign/verify/remind.
5. `Record review` opens an editor with scope displayed, required notes and Save; store timestamp and snapshot counts transactionally at save time. Show latest review date and a history list of previous notes/counts. Snapshot counts must be explicitly labeled `At this review`, not mistaken for live data.

### Stations & staff

Top bar `Stations & staff`, Back. Two local segmented categories `Stations` and `Staff`; these are not primary navigation tabs. Search. `Add station / unit` action in Stations. `Add from contacts` action in Staff; if no contacts exist offer the existing manual/import contact flow first. Do not create a duplicate contact form.

Station list rows: name, unit type, active staff count, open instruction count; archived toggle/filter separate from active. Empty copy explains what a station/unit represents. Detail shows notes, active staff (tap existing contact or staff editor), matter links and the station's work list. `Edit station` and reversible `Archive`/`Reopen`. An archive blocked by active staff, active matters or open work explains the specific next action inline, without losing entered data.

Staff list rows: name, rank when present, current station or `No station assigned`, active/inactive indicator. Detail/editor: existing contact identity shown; choose station; multiline responsibilities; staff classification and active posting. `Edit contact details` reuses existing editor. Staff history shows dated old/new station names and responsibility notes. Inactive staff remain searchable and linked history remains accessible. Do not auto-reassign their work.

### Matters

Top bar `Matters`, Back. Search and active/archived filter; `Add matter`. Rows: title, optional reference and station, open instruction count. Editor fields: title, station chooser (including subdivision-wide/no station), reference, context. Detail: context and related instructions with lifecycle filters, `Link instruction`, `Edit matter`, archive/reopen.

Link instruction selects an existing visible instruction through a searchable chooser, previews any changed context and saves through the repository. Add-new uses the existing NoteBar/Capture flow, never a parallel New Task form. Preferred complete flow: `Add instruction` opens existing capture with the matter/station context preselected and visibly labeled. Persist context in Capture SavedState, validate on save, include context in duplicate-save fingerprint and clear only after durable success. Never silently replace context on a nonempty draft: retain the draft and offer a clearly labeled choice in-app. If implementing context requires a repository create-in-context method, make the entire creation and link atomic; a failed link must not leave a misleading partially saved instruction.

### Instruction and contact integration

- Existing instruction detail gains a concise `Station & matter` context row and `Change work context` action. Display the **instruction's recorded station**, not just the assigned contact's current station. Context is independently editable and changes append journal entries.
- Instruction search must match recorded station and matter title/reference as well as text/contact/journal. Preserve existing direction, status and reminder filtering.
- Reassigning the contact changes responsibility, not station/matter. Journal records old/new contact. Moving staff changes current posting, not old instructions. Explicit context changes are required to move work.
- Contact detail should expose staff posting/responsibilities and a review-this-officer entry when applicable, while keeping phone import/calling/sharing behavior unchanged.

### Native craft requirements

Use existing MaterialTheme typography/colors and component patterns. 48dp minimum action targets; sp text; support large font without clipped buttons/chips; appropriate IME/inset padding; max readable width on tablets; scrolling forms; pinned primary Save; labels above/within fields, not placeholder-only. Loading, empty, error and saved states must be distinct. Keep drafts on validation failure; focus/show the failing field. Back/dismiss warns only for actual unsaved changes, not empty untouched forms. Do not hide essential context in unlabeled icons. No red overdue badges, shame wording, streaks, ranking, novelty animations, gradients, new fonts or web libraries.

The design work has already been approved. Do not restart a moodboard, Figma exercise, skill approval gate or visual exploration. Small unspecified implementation details must use the nearest existing native component. Review visually on an emulator, not only from Kotlin source.

## 5. Architecture and correctness specification

Keep new code under `data/subdivision` and `ui/subdivision` with focused files. Do not turn `OfficerAppRoot`, `WorkspaceViewModel` or an editor into another monolith.

- Add `SubdivisionViewModel` with lifecycle-aware Flow state: profile/stations/matters/postings/reviews; loading and recoverable read error; busy and actionable mutation error. Cancel/reset on vault change before showing any old state. Call repository mutations from ViewModel; no DAO calls in Composables.
- Add pure review/context projection functions with injected `now` where necessary. Exclude DONE and DROPPED from open; REPORTED_DONE remains open/ready until verification. Seven-day stale uses last real update/created timestamp, and the UI says exactly what it measures. Missing/invalid dates must not crash a whole screen. Compare last review against instruction update timestamps, using consistent Instant parsing.
- All writes must re-read target records, recheck vault and sensitive status and run in Room transactions. Never trust cached picker objects for ownership. A sensitive person cannot be linked through an audience loophole. Do not treat unassigned private-mode captures as visible.
- Station uniqueness includes archived records. Reopen an existing station instead of silently duplicating its name. Renaming updates compatibility contact station text but not immutable posting-history snapshots.
- Historical work retains stable station ID after transfer. All creation paths, capture quick-save, import and restore must carry IDs. Domain/entity conversion must preserve new fields in both directions.
- Existing matters with linked instructions cannot silently change station. Station-specific matter links require the same station; subdivision-wide matters may span stations. Reject dangling/mismatched/vault-crossing references with actionable errors.
- Archive, not destructive delete. Station archive requires no active staff, active matters or open linked work. Matter archive requires no open linked work. Reopen work must reopen linked context consistently, including Undo reopening after an intervening archive. Test concurrency, not only the happy path.
- Keep FTS valid after text edits/restores/imports. Keep sync-status compatibility fields consistent even though cloud sync is dormant. Use UPDATE instead of REPLACE when editing existing persons/instructions to avoid deleting related rows via FK cascades.
- Migration 17→18 must be nondestructive, registered for every real DB construction path, and validated against Room's generated schema. Current SQL lower() uses ASCII folding; the Kotlin normalizer intentionally matches it. Test non-Latin names, whitespace and visible/hidden duplicates. No fallbackToDestructiveMigration.
- Schema export/versioned files must be checked in as appropriate. No sample personal data or subdivision seed in production assets.

## 6. Backup/import/export — required, not optional

New metadata is only useful if it survives recovery. Inspect `PlainExporter`, `PlainImporter`, `BackupManager` and the encrypted full-database backup flow. The latter naturally includes new tables but needs upgrade/restore verification.

- Extend `PlainExporter.Snapshot` with subdivision archive data (sensible empty defaults for old test callers). JSON and manual backup must carry profile, stations, matters, staff postings, reviews and all new person/instruction columns. Preserve vault flags, sensitive flags, journal, deadlines, reminders, audience and lifecycle fields.
- Use a versioned `subdivision` JSON object, preferably the existing @Serializable `SubdivisionArchive` plus kotlinx.serialization. Raise manual backup schema version from 3 to 4 with backward-compatible reading. Reject unsupported newer schema versions, malformed journal, duplicate IDs, missing references and cross-vault references before any mutation.
- Read a backup snapshot consistently in one Room transaction. Restore/import parse completely and apply in one transaction, parents before children. Fail atomically rather than skipping malformed rows or leaving partially restored records. Current BackupManager parser catches/skips rows; fix this for reliable recovery rather than preserving the silent-loss behavior.
- Keep old version 3 and earlier supported backups readable. Derive missing legacy station IDs when safe; do not invent staff classification or profile. Preserve identities/history and make repeated restoration idempotent.
- Current CSV parser splits on newlines/blank lines and is unsafe for quoted multiline fields. Implement a real quoted-record parser and test commas, quotes, CRLF, Unicode and blank lines inside notes. Retain old column order, append new columns. Include a clearly labeled subdivision metadata block (for example `subdivision_json` header followed by one quoted serialized archive record) and parse it atomically. Export must not claim a complete round trip while dropping new metadata.
- Review existing snapshot/manual-constructor tests when adding database/DAO dependencies. Use real in-memory Room DB tests for transaction guarantees; mocks alone cannot prove rollback.
- Do not test with actual local backups; create synthetic files only under the dev tree or the QA app sandbox. Handover instructions distinguish APK sharing from private backup sharing: the friend gets only the app, never the user's backup or app data.

## 7. Ordered execution and acceptance gates

Implement in this order without requesting checkpoints:

1. Inspect current diff and compile data layer; repair incomplete write/return paths and migration registration. Add migration/transaction/transfer/visibility unit tests.
2. Implement pure review projections, state/ViewModel and all fixed screen destinations/forms, then integrate existing root, capture, contact and instruction screens. Do not regress quick capture or create extra main tabs.
3. Finish atomic JSON/CSV/manual/encrypted backup round trips and test older backups.
4. Add instrumentation acceptance coverage and visual QA. Fix all failures before generating candidate artifacts.
5. Bump candidate version to **2.6.0 / versionCode 52** in the usual source and bundled changelog. If code 52 is already taken in this same dev branch, inspect and document before advancing; do not query or access prod. Keep public application ID.
6. Run the entire unit suite, lint and Android builds; inspect actual reports; run the targeted emulator acceptance suite; then create handover evidence. Do not push or sign.

Required behavioral tests (use names that communicate the scenario):

- Upgrade a populated v17 DB: old contacts, notes, journals, dates, tags, links and hidden rows survive; station backfill deduplicates per vault; no profile seeded; no staff auto-classification. Validate schema18 exactly. Update older `Migration14To16Test` migration chains to include 17→18.
- Two fresh independent DBs: setting subdivision/staff/matters on one leaves the other empty. Friend onboarding contains no first officer's identity or data.
- Create/rename station, reject duplicate trimmed name, archive guards, reopen. Unicode station round trip.
- Import a synthetic phone contact, mark staff, add responsibilities, move station/deactivate. Old instruction station and matter remain; new work snapshots current station; contact name/phone and posting history survive restart.
- Create matter, add new instruction through existing capture, link an existing instruction, update text, reassign officer, explicit context change, inspect journal, deadline/reminder independence, record progress, ready-to-verify, complete/undo/reopen; history never silently replaced.
- Cross-vault/sensitive people, audience-only links, archived and missing objects: normal CRM rejects and never leaks them in pickers, counts, reviews or search. Switch vault while a form/save is active; fail closed without stale data.
- Review at subdivision/station/officer scopes: exact counts; ready state remains open; no-update boundary; changed-since-last-review and no-prior-review state; saving a review doesn't complete instructions or send messages; timestamps/counts reflect transaction-time snapshot.
- Backup/export/import/restore every new field and table, including multiline Tamil/English notes and commas/quotes; repeat restore; reject invalid references/unsupported versions with DB unchanged; restore old backup without a fake subdivision or staff data.
- UI empty/loading/error; editor validation preserves draft; rotation/process-recreation; light/dark; large font (~1.5–2x); small phone and tablet layout; keyboard doesn't obscure Save; TalkBack labels/targets; capture remains <5 seconds on existing benchmark.
- Existing tests for reminders, reboot rescheduling, notification tap, follow-up share preview, contact import, journal, Undo and private workspace still pass. Do not mark a phone-specific check as passed without that hardware; emulator testing is accurately labeled.

## 8. Known local build environment

Read-only installed toolchain access is allowed for building; no unrelated user files:

```
JAVA_HOME=/Users/sujithsampath/actions-runner/_work/_tool/Java_Temurin-Hotspot_jdk/17.0.20-101/arm64/Contents/Home
ANDROID_HOME=/Users/sujithsampath/Library/Android/sdk
GRADLE_USER_HOME=/Users/sujithsampath/work/kaavalan-note-dev/.gradle-user-home
ANDROID_USER_HOME=/Users/sujithsampath/work/kaavalan-note-dev/.android-dev
ANDROID_AVD_HOME=/Users/sujithsampath/work/kaavalan-note-dev/.android-dev/avd
```

Use JDK17; SDK tools available at `platform-tools/adb`, `emulator/emulator`, `build-tools/37.0.0`. Host has limited memory; use serial builds, `--max-workers=1`, no daemon, no aggressive parallel Gradle processes. Set all state/cache paths under the dev folder. Do not read signing configuration to troubleshoot a disabled-signing build.

Canonical gate command, executed with the environment above:

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease \
  --no-daemon --max-workers=1 -Pkotlin.incremental=false -Pksp.incremental=false \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  -Pkaavalan.enableReleaseSigning=false -Pkaavalan.debugApplicationIdSuffix=.debug.officer
```

The task-owned API34 ARM emulator may be stopped. Start only the named AVD at port5596 with these local Android paths; do not wipe data. Prefer isolated debug package for synthetic data. The public package on that emulator is a prior signed release with synthetic data; never uninstall/overwrite it to hide signature or migration issues. No production signing has been authorized to test an actual signed2.5→2.6 upgrade, so explicitly keep that final signed-upgrade test pending rather than pretend debug QA proves it.

For compile/test failures: read the first actionable failure, fix it, rerun targeted test, then rerun complete gates. Record warnings separately and address new warnings. Never disable tests, add blanket lint suppressions, remove assertions or claim old reports prove this branch. Verify report timestamps belong to the current code. If a dependency cannot download, try safe existing cache/offline alternatives before reporting a genuine blocker.

## 9. Deliverables and final response

Write `docs/development/v2.6.0-subdivision-crm-verification.md` containing exact branch/HEAD/diff state, feature checklist, test command results and counts, lint errors/warnings, build variants, emulator/API/serial, screenshots, exercised scenarios and honest pending gates. Update PRODUCT/DESIGN/architecture docs to reflect the implemented data and screens (do not change the fixed design brief).

Put candidate APKs, SHA-256 checksums and screenshot evidence in `app/build/ui-qa/v2.6.0/`. Clearly label:

- `kaavalan-note-2.6.0-unsigned.apk`: release candidate requiring approved production signing; not installable yet.
- `kaavalan-note-2.6.0-qa.apk`: isolated test app, not a daily-app update.

Write a short officer-facing guide covering profile setup, stations, imported contacts→staff, responsibilities/transfers, matter→instruction→follow-up→verify, subdivision review and safe backup. Friend rollout: share only the eventual signed APK/release link, each friend sets their own subdivision and contacts; no common account/database required.

Write `docs/development/claude-subdivision-crm-status.md` as you work, with milestones and current actual verification state, so the user can inspect progress without spending more Codex usage. End with concise completed features and direct artifact/report paths. Say signing/publication needs approval if still pending; do not ask design questions. If any implementation/test remains incomplete, list it explicitly and do not call the app finished.

Proceed now. This brief resolves the product choices; use the existing code and fix integration details autonomously. Implementation and tests, not more planning, are the next action.
