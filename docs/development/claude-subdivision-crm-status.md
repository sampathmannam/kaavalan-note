# KaavalanNote 2.6.0 — subdivision CRM implementation status (Claude)

Live progress log for `docs/development/claude-subdivision-crm-handover.md`.

**Branch:** `feat/subdivision-crm` — **Base:** `origin/main` @ `a7eea25` (v2.5.0)
**Version:** 2.6.0 / versionCode 52 — **Database:** 17 → 18 — **Manual backup schema:** 3 → 4
**Signing / publication:** NOT authorized. Every Gradle run passes
`-Pkaavalan.enableReleaseSigning=false`. The release APK is **unsigned and not
installable**. Signing, push, PR, tag, publication and physical-phone install remain
behind a fresh approval gate.

Full evidence: [`docs/development/v2.6.0-subdivision-crm-verification.md`](v2.6.0-subdivision-crm-verification.md).
Officer-facing guide: [`docs/officer-guide-subdivision.md`](../officer-guide-subdivision.md).

---

## Milestone board

| # | Milestone | State |
|---|-----------|-------|
| 0 | Read handover, inspect diff, verify toolchain | **done** |
| 1 | Repair + harden the data layer | **done** |
| 2 | Review projections + `SubdivisionViewModel` | **done** |
| 3 | Subdivision UI destinations + tab entry points | **done** |
| 4 | Instruction / capture / contact integration | **done** |
| 5 | Atomic JSON / CSV / manual backup round trips | **done** |
| 6 | Version bump 2.6.0 / versionCode 52 | **done** |
| 7 | Unit suite + lint + all Android builds | **done** |
| 8 | Instrumented acceptance + emulator visual QA | **done, with a recorded device-stability caveat** |
| 9 | Artifacts, checksums, screenshots, reports, guide | **done** |

---

## What has been implemented

### Data layer (milestone 1)

Repairs to the inherited, never-compiled work:

- `RoomInstructionRepository.create()` returned the pre-copy entity, so the caller got a
  null `stationId` even though the row on disk had one. Both create paths now read the
  saved row back.
- `update()` and `setSensitive()` used `INSERT OR REPLACE`, which deletes the row first
  and cascades `instruction_tags` away — editing an instruction's status silently dropped
  every label on it. Both now use `UPDATE`, inside a transaction.
- `update()` rewrote the FTS row at `MAX(rowid)` — some other instruction's row — leaving
  the edited note stale in search. It now uses that row's own rowid.
- `InstructionWorkflow.editContact` was replacing the person row; `important_date` and
  `person_link` cascade from `persons`, so a rename deleted a contact's dates and
  relationships. Now `UPDATE`.
- `undoCompletion` did not reactivate an archived station/matter, so undoing a completion
  could leave open work under an archived unit.

New and rewritten: `SubdivisionProjections` (pure, `now`-injected review projections shared
by the UI and the repository's review snapshot), `SubdivisionRepository` (transactional
writes that re-read the row and recheck vault and sensitivity at save time; archive guards
that name the next action; station uniqueness including archived rows),
`resolveCreationContext` (creation and link in one transaction),
`SUBDIVISION_MIGRATION_17_18` (non-destructive; derives stations per vault; seeds no
profile and no staff classification), `SubdivisionDao` (mode-scoped reads, `@Upsert`).

### UI (milestones 2–4)

`ui/subdivision/`: `SubdivisionViewModel`, `SubdivisionModel`, `SubdivisionComponents`,
`SubdivisionEditors`, `SubdivisionReviewScreen`, `StationsAndStaffScreen`, `MattersScreen`.

Integration: three labelled secondary entry rows (Today → Subdivision review, Instructions
→ Matters, Contacts → Stations & staff), hidden in the private workspace; six full-screen
child destinations with Back, cleared on a switch into the private workspace; a
`Station & matter` context row plus `Change work context` on the instruction detail;
instruction search matching recorded station and matter title/reference; capture carrying a
work context through `SavedStateHandle`, in the duplicate-save fingerprint, offering a
labelled choice rather than replacing a non-empty draft; contact detail exposing staff
posting, responsibilities, posting history and a review entry.

### Backup / export / import (milestone 5)

`CsvCodec` (a real quoted-record reader/writer, replacing a parser that tore journal JSON
and multi-paragraph notes apart at blank lines and discarded the halves silently);
`PlainExporter` (one-transaction snapshot, new columns appended after the historic ones,
labelled `subdivision_json` block); `PlainImporter` (parse and validate the whole file,
then apply in one transaction; refuses duplicate ids, dangling and cross-vault references,
malformed journals and unsupported versions); `BackupManager` (schema 3 → 4, atomic
validated restore, no more silently skipped rows, older backups still restore without an
invented profile); `SubdivisionArchiveCodec` (one versioned codec for all three formats).

---

## Defect found and fixed by the device tests

`SubdivisionNotice` — the empty and unavailable state — carried its own
`Modifier.verticalScroll`, and is used as the empty state inside `LazyColumn` items. A
lazy list measures its items with unbounded height, so every subdivision destination threw
`IllegalStateException: Vertically scrollable component was measured with an infinity
maximum height constraints` the moment it rendered an empty list. That is the **default
state of a fresh install**, so Subdivision review, Stations, Staff and Matters all crashed
on first open.

Unit tests could not have caught this: it is a measurement error that only exists once the
composable is laid out on a device. Fixed by removing the scroll container from the
in-list notice and adding `SubdivisionNoticeScreen` for notices that occupy a whole
destination. Four of the five acceptance tests failed before the fix and pass after it.

---

## Environment deviations (recorded, not hidden)

1. `-Djava.io.tmpdir` / `-Dorg.sqlite.tmpdir` → a project-local `.gradle-tmp`. The sandbox
   denies the macOS per-user temp dir, where the Kotlin compiler writes its `.alive` marker
   and Room's `DatabaseVerifier` extracts its SQLite native library.
2. `-Pkotlin.compiler.execution.strategy=in-process` so those properties reach Kotlin/KSP,
   with the Gradle heap raised 1536m to 2048m and metaspace 512m to 768m.
3. `-Pkaavalan.testUserHome=<repo>/.test-home` — a new, opt-in Gradle property. Unset (CI,
   ordinary local runs) it does nothing. Set, it redirects `user.home` so Robolectric can
   write its download lock and read its `android-all` cache, and pre-loads
   `byte-buddy-agent` so mockk does not need the HotSpot attach listener.
4. `android-all-instrumented` runtimes for SDK 21/26/28/33/34 were pre-fetched into
   `.test-home/.m2/repository`. `.test-home/` and `.gradle-tmp/` are gitignored.
5. **JAVA_HOME changed.** The handover's
   `actions-runner/_work/_tool/Java_Temurin-Hotspot_jdk/17.0.20-101` no longer exists on
   this machine — the whole `_work/_tool` tree was removed during a disk cleanup on
   2026-09-11. Builds now use Homebrew `openjdk@17` (**17.0.20.1**), the same major and
   minor version. Nothing else about the toolchain changed.
6. **The acceptance suite raises Espresso's idling timeouts** to five minutes via
   `IdlingPolicies`, and its own polling helpers to sixty seconds. This emulator renders in
   software (see below); the defaults assume a hardware-accelerated device. No assertion
   was changed, removed or weakened.

Everything else matches the handover line, including
`-Pkaavalan.enableReleaseSigning=false`,
`-Pkaavalan.debugApplicationIdSuffix=.debug.officer`, `--no-daemon`, `--max-workers=1`,
and disabled Kotlin/KSP incremental compilation.

### Process deviations

- The background-session harness wanted this work isolated in a git worktree. The
  task-specific hook `tools/qa/claude-subdivision-guard.py:26` rejects every tool call
  whose working directory is not exactly the repository root, so a worktree makes the
  session inoperable — verified empirically. Work therefore continues in place on
  `feat/subdivision-crm` with all pre-existing edits preserved.
- The Gradle device-test task could not be used, even with `ANDROID_SERIAL=emulator-5596`
  set as the handover requires: ddmlib enumerates every attached device and hung
  indefinitely fetching properties from an unrelated `emulator-5680` on this host. The
  acceptance suite was therefore driven directly through `adb -s emulator-5596` and **the
  same AndroidX Test Orchestrator with `clearPackageData true`** that the Gradle task
  would have used. Only the task-owned emulator was touched; the unrelated one was left
  running and untouched.

---

## Host limits that shaped this session

- The startup volume reached **zero bytes free** mid-session, which stopped every tool that
  needs to write. The user freed space; work resumed. Before that, the emulator refused to
  boot at all (`FATAL | Your device does not have enough disk space`) against an AVD
  declaring a 6 GB data partition.
- Host memory stays near exhaustion (tens of MB free, ~1.2 GB reclaimable), so the emulator
  falls back to software rendering: `Software GL rendering will be used due to system
  memory pressure (Available Memory: 1073 MB, Required: 5120 MB)`. Every device timing in
  this report reflects a software-rendered device.

---

## Pending gates (honest list)

- Production signing and publication — **not authorized**, not attempted.
- Signed 2.5.0 to 2.6.0 in-place upgrade of the public package — cannot be tested without
  release signing, stays **pending**.
- Physical-phone install — not authorized, not attempted.
- Physical-device checks (real TalkBack pass, field capture-speed benchmark, camera, voice
  recognition, Google Drive account integration) — emulator results are labelled as such
  and are not a substitute.
