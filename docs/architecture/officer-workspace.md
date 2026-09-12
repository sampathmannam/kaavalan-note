# Officer workspace architecture

## Why it changed

The former Home/People screen owned contact creation, global search, inbound/outbound instructions, shared text, camera, microphone, capture and dispatch. Today separately projected a dated brief and performed its own mutations. This made features depend on which tab was composed, misclassified every raw note as outgoing, and duplicated detail state.

## Active boundaries

- `MainActivity`: Android lifecycle, share/widget/tile/reminder intent ingress and first-run gating.
- `OfficerAppRoot`: navigation, app-scoped workspace and capture view models, ID-based live instruction detail, settings/backup routes, undo and save feedback.
- `WorkspaceViewModel`: one mode-scoped reactive Room read model, retryable load state, serialized mutations with visible success/failure.
- `InstructionWorkflow`: transactional edit, dated update, progress, completion/undo, reminder and contact-edit operations. Re-reads records and vault ownership at the write boundary, rather than trusting a stale screen snapshot. Uses Room `@Update`, preserving SQLite row IDs and tag links; FTS is updated using the actual instruction row ID.
- `WorkspaceEditors`: shared scrollable editor frame with pinned Save, keyboard insets, rotation-safe field state and a discard confirmation.
- `WorkspaceModel`: pure projections for Today, search and filters. Takes date/time zone explicitly. Includes all open lifecycle states; excludes closed work from attention; does not hide old work by age.
- `WorkspaceScreen`: stateless destinations and shared instruction cards. UI events are callbacks rather than new repository writes.
- `CaptureHost`: one lifecycle owner for media permissions, shared text, quick capture, calendar handoff and post-save feedback. Exists outside navigation destinations.
- `CaptureViewModel`: saved draft, responsibility/contact/reminder selection and save state. `createWithAudience` commits direction, contact, due ISO and due milliseconds together inside the existing Room transaction. Legacy create callers retain their outgoing default.
- `PersonDetailScreen`: contact-specific records, shared detail/reminder UI and the same app-level capture host. Relationships, important dates, sharing and privacy remain available as secondary actions.
- `SettingsCategory`: single selected settings section with back navigation; existing secure backup/recovery implementations remain intact.
- `SubdivisionRepository`: every subdivision write. Re-reads the target row, rechecks vault ownership and sensitivity at the write boundary, and runs in a Room transaction. Owns station-name uniqueness, the archive guards, the link/relink journal and the review snapshot. Composables never call `SubdivisionDao`.
- `SubdivisionProjections`: pure, `now`-injected review projections — scopes, counts, the five filters, the stale window and the normal-workspace visibility gate. Shared by the UI and by the repository's review snapshot, so a saved review can never disagree with the number the officer was looking at when they saved it.
- `SubdivisionViewModel`: lifecycle-aware Flow state for profile, stations, matters, postings and reviews, with loading, a recoverable read error, a busy flag and an actionable mutation error. Resets before revealing anything on a vault change.
- `SubdivisionModel`: pure row projections and search for the station, staff and matter lists.
- `SubdivisionScaffold` / `SubdivisionEditors`: one frame per destination handling Back and the three non-content states once, and one editor frame with a scrolling body, pinned Save, retained draft and a discard warning only when something was typed.

## Privacy and data compatibility

The original workspace redesign required no database migration. The September 10 follow-up workflow adds a non-destructive **16 → 17** migration. Existing IDs, notes, contacts, tags and reminder times are preserved. Legacy screens/helpers remain compiled for compatibility with existing isolated feature tests but are not application navigation destinations.

### Follow-up workflow — September 10

- Today shows assigned work / waiting replies first. Each section initially previews up to three records with an explicit “See all” control; no record is dropped by age.
- An instruction has two independent dates: `deadlineAtMs` (when work must finish) and the existing `dueAt` / `dueAtMs` compatibility pair (when the officer wants a notification). Historical reminders are **not** reinterpreted as deadlines. Editing a deadline never reschedules a reminder.
- `updatesJson` is a version-tolerant, typed private journal owned by the instruction aggregate. A Room transaction appends the journal entry together with progress and the next reminder. Updates carry a stable ID, timestamp, text, progress and the follow-up chosen at that time. Text edits retain the previous text. These are personal working notes, not a forensic audit chain or server-side conversation.
- `REPORTED_DONE` means “Ready to verify”, remains open and appears in Follow up even with a future reminder. Only the officer’s “Verify & mark done” closes it.
- Completion Undo restores the previous progress state, not a generic Open state, and reschedules the retained reminder. A completion timestamp guards against stale Undo overwriting a newer lifecycle action. Undo is a transient snackbar; after it expires, completed work remains available under Closed and can be reopened.
- Instruction search starts with All records, explicitly exposes All/Open/Closed and filters responsibility separately. Search includes update text as well as instruction text and contact context.
- Active Today, Instructions and normal contact timelines open the same app-level detail/editor. The legacy sensitive-record detail remains available only from its owning contact; protected notes are not promoted into workspace search.
- Contact edits update the local KaavalanNote record only, keeping its ID, vault mode and linked instructions. They do not alter the phone’s address book.
- Copy and opening the Android share chooser are recorded as `COPIED` / `SHARE_OPENED`, never confirmed delivery. The migration corrects historical `SENT` rows only where the recorded channel is COPY or SHARE. Real channel-specific historical receipts are unchanged.
- JSON backup format 3 includes deadline, journal and the previously omitted audience/reminder/channel fields. Old backups remain readable with empty journal / null deadline defaults. Whole-database encrypted backup naturally carries the new columns. Plain JSON/CSV exports also include journal and date fields; neither plaintext export nor backup is automatic sharing with contacts.

The journal is embedded in its owning row rather than introducing a second independently managed sync/history store. This keeps local edits, rollback and portable backup aligned with the existing single-record architecture. A future multi-user or high-volume journal would warrant a normalized paged event table; this change does not introduce either deployment model.

Workspace projections filter sensitive instructions and contacts outside the selected vault before search or rendering. By-person audience pointers are also scoped. Private-mode captures require a private contact because instructions do not independently carry a vault mode. Invalid or stale contact links fail visibly before a write. No UUID placeholder is presented as a person name.

Date projections refresh on resume and while the app remains open across midnight. Selected detail uses an ID, not a stale copy of an entity. Reminder notifications resolve into the same live workspace detail.

Cold startup no longer automatically loads or replaces synthetic fixtures, even in debug builds. Sample data remains an explicit developer action. Existing legacy `OCR` source values are read as photo captures without rewriting or discarding their records; this prevents one older photo row from failing the entire workspace projection.

Contacts uses a single scrolling directory, including its introductory controls. Empty/error content can scroll at larger text sizes. Capture and contact forms keep their save action outside the scrolling body. App-selected light/dark system-bar appearance also reaches the separate Material sheet windows.

In landscape, capture omits the drag-handle chrome. While the keyboard is open it also reduces vertical padding and hides the redundant heading, without replacing the focused editor or shrinking the user's text. Dismissing the keyboard restores the full heading and form. The large-text device check requires an editor viewport of at least 80dp alongside a visible, enabled Save action.

### Subdivision work record — September 11

A non-destructive **17 → 18** migration adds `subdivision_profile`, `stations`, `matters`,
`staff_postings` and `subdivision_reviews`, plus `stationId` / `isStaff` / `staffActive` /
`responsibilities` on `persons` and `stationId` / `matterId` on `instructions`, with
indices for the new columns. `SUBDIVISION_MIGRATION_17_18` is registered in
`DatabaseModule` for every real construction path; there is no
`fallbackToDestructiveMigration`. The migration derives stations from the station text
already typed into contacts, separately per vault, and stamps the resulting IDs onto
existing instructions. It seeds no subdivision profile and classifies nobody as staff.
`exportSchema` remains `false` for this project, so the migration test validates by opening
the migrated database through Room itself and letting Room's identity check run, rather
than diffing an exported JSON schema.

- An instruction carries the station it was **recorded at**, not a pointer to wherever its
  contact is posted today. Moving an officer writes a dated posting entry and leaves their
  past work where it happened. Only an explicit context change moves an instruction, and it
  appends a journal entry naming both sides.
- Station names are unique per vault on the trimmed, ASCII-case-folded form, and the check
  includes archived rows. The SQL `lower()` folding and the Kotlin normalizer deliberately
  match. Non-Latin names are stored and compared intact. A rename updates the compatibility
  station text on contacts but never a posting-history snapshot.
- Archive replaces delete. A station archive requires no active staff, no active matters
  and no open linked work; a matter archive requires no open linked work. Each refusal
  names the specific next action. Reopening work reopens its archived station and matter,
  including an Undo that lands after an intervening archive.
- `resolveCreationContext` makes creation and the work-context link one transaction, so a
  refused context leaves nothing behind rather than a misleading half-saved instruction.
  Capture carries the context through `SavedStateHandle`, includes it in the duplicate-save
  fingerprint, and offers a labelled choice instead of overwriting a non-empty draft.
- Editing uses Room `@Update`, never `INSERT OR REPLACE`. Replace deletes the row first,
  which cascaded `instruction_tags` off an edited instruction and `important_date` /
  `person_link` off a renamed contact. FTS is rewritten against the row's own rowid.
- Review records store the scope, the officer's note and the open / ready-to-verify counts
  computed inside the saving transaction. The UI labels them `At this review`. Recording a
  review completes nothing and sends nothing.
- Everything CRM is normal-workspace only. Sensitive people, hidden links and audience-only
  pointers are excluded from every list, count, review, picker and search result, and a
  vault switch mid-form fails the write closed rather than showing stale state.

Backup carries the record or fails whole. `SubdivisionArchiveCodec` is one versioned
encode / decode / validate shared by all three formats. `PlainExporter` reads its snapshot
in a single transaction and appends the new columns after the historic ones; `PlainImporter`
parses and validates the entire file before applying it in one transaction, refusing
duplicate IDs, dangling and cross-vault references, malformed journals and unsupported
versions. `BackupManager` moves manual backup schema 3 → **4** and no longer skips
malformed rows — a restore now either completes or changes nothing. Schema 3 and older
backups still restore: recoverable legacy station names are folded into real stations, and
no profile or staff classification is ever invented. `CsvCodec` replaces the old
split-on-newlines parser, which tore journal JSON and multi-paragraph notes apart at blank
lines and discarded the halves silently; it handles quoted commas, doubled quotes, CRLF,
Unicode and blank lines inside a field, and carries the archive in a labelled
`subdivision_json` block.

A lazy list measures its items with unbounded height, so `SubdivisionNotice` — the empty
and unavailable state — carries no scroll container of its own. `SubdivisionNoticeScreen`
is the variant for a notice that occupies a whole destination and therefore needs one.

## Development safety

Debug configuration does not resolve local release signing properties. Resolving release signing requires the release operator to explicitly pass `-Pkaavalan.enableReleaseSigning=true`; do not enable this for ordinary development.

Use a repo-local Gradle/Android user home and `-Pkaavalan.debugApplicationIdSuffix=.debug.officer` for isolated redesign verification. This avoids replacing either a public release or an existing debug installation. No pushes, tags, release publication or production signing are part of this redesign without a new approval.

## Verification

For the September 10 follow-up workflow, see [the current verification report](../officer-follow-up-verification-2026-09-10.md). The September 8–9 results below are historical baseline evidence, not results for the newer changes.

Unit coverage includes responsibility/lifecycle projections, local midnight, malformed legacy dates, visibility filtering, draft/dedup behavior and contact-import persistence.

### Unit, lint and debug gates — 2026-09-08–09

- `testDebugUnitTest`: **723 total; 711 passed, 12 pre-existing skips, 0 failures/errors**. The old automatic-fixture-reseed expectations were replaced with startup-no-reseed guards; legacy OCR mapping has explicit compatibility tests.
- `lintDebug`: **passed, 0 errors/fatals, 334 warnings**. This is not a warning-free project; no lint failure was suppressed to pass this redesign.
- `assembleDebug` and `assembleDebugAndroidTest`: **passed**. The isolated arm64 debug APK is `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`; version remains `2.4.0` / code `49`.
- `compileReleaseKotlin` and `lintVitalRelease`: **passed**, with release signing disabled.
- Final fresh-daemon `assembleRelease`: **passed in 4m 36s** on 2026-09-09. R8 minification, resource shrinking, four ABI APKs and the universal unsigned APK completed. The arm64 artifact is approximately 24 MiB, within the CI 40 MiB budget. APKs are in `app/build/outputs/apk/release/`; the R8 mapping is in `app/build/outputs/mapping/release/mapping.txt`.
- `git diff --check`: passed. No database schema/version change, commit, push, tag or release publication.

Artifact SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| `app-arm64-v8a-debug.apk` (isolated development install) | `2e0f671da4d3183a9b452e079012e96b0fb96fa39e276e0ed1abd56aca2feece` |
| `app-arm64-v8a-release-unsigned.apk` (build verification only; not installable until signed) | `c65f35f0ff1a14f369994c01526b2d9378fe29fffb75d746eb6ca46276f49635` |

### Device and visual acceptance — 2026-09-08

- Final `connectedDebugAndroidTest`: **13 passed, 0 failed, 0 skipped**, Android 14 Google APIs arm64 Pixel 6 emulator, isolated package `com.kaavalan.note.debug.officer`.
- Real Compose → ViewModel → SQLCipher Room → WorkManager coverage verifies contact-linked responsibility and reminder persistence, activity recreation, completion cancelling reminder work, completion/reopen, cross-tab drafts, shared-text ingress, settings, onboarding and photo/voice caption hit targets. Notification coverage checks private visibility and Done/Snooze actions.
- Two visual tests also passed independently. Eleven native screenshots cover light/dark themes, first use, Today, Instructions, Contacts, settings, keyboard capture, 150% system text size and landscape. Screenshots wait for both Compose and native window animations. Actual IME visibility is checked before keyboard screenshots.
- Cold force-stop/relaunch preserved three synthetic notes and two contacts. Evidence: `app/build/ui-qa/officer-cold-start.xml` and `officer-cold-contacts.xml`. No startup fixture replacement or workspace load failure appeared.
- Reviewed screenshots: `app/build/ui-qa/ui-review/officer-*.png`. These are generated local artifacts, not officer data or committed fixtures.

The configured device suite retains its pre-existing source exclusions. This is not a physical-phone test, a full OS/device compatibility matrix, a TalkBack audit or a field capture-speed benchmark. Voice recognition, camera quality and Google Drive account integration remain device/account-dependent. Localized strings outside English retain the existing partial translation coverage.
