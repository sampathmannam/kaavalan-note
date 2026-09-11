# Kaavalan note — officer workspace

## User and job

An officer working between field duty, calls, station reviews and briefings. Interruptions are normal. The app must help them capture an instruction quickly, remember who is responsible, and close the loop without demanding an organisational ritual first.

## Primary workflows

1. Open Today and see follow-ups first: instructions assigned to others, replies being awaited, and reported completions to verify. Personal next actions follow. Sections preview three records with an explicit See all control.
2. Write, speak or photograph a note from any working tab. Responsibility defaults to **For me**; **Assigned by me** and **Received** are explicit choices.
3. Optionally link an officer or other work contact and set a local reminder. Save without a contact in the normal workspace.
4. After a call or review, open the instruction → Add update → record what happened, progress and the next follow-up. These dated notes are private, not messages to staff. Reported completion remains open until the officer verifies it.
5. Edit saved instruction text, responsibility, contact and deadline. The deadline describes when work must finish; the follow-up controls when the officer is reminded. Neither date overwrites the other. Completion offers immediate Undo; closed records remain searchable and can be reopened.
6. Use Contacts for colleagues, staff and other work relationships. Edit name, rank, station and phone locally, including after import. Contacts are not a mandatory first step and editing one does not edit the phone address book.
7. Record the subdivision itself: its stations and units, which contacts are posted to them as staff and with what responsibilities, and the matters that group related instructions. The subdivision profile needs a name and nothing else; it is never a precondition for capturing a note.
8. Review the work at subdivision, station or officer scope: open instructions, work ready to verify, passed deadlines, work with no update in seven days, and what changed since the previous review. Recording a review saves a dated note and the counts as they stood at that moment. It completes nothing and sends nothing.

## Boundaries

- Native Android, local-first, SQLCipher-encrypted Room. No new service, telemetry, account requirement or AI inference.
- Existing optional encrypted Google Drive backup remains explicit. Android speech recognition may use a network service; do not promise fully offline voice.
- Three working destinations: Today, Instructions, Contacts. Settings is a top-bar action, not a working destination.
- No shame language, streaks, red lateness badges or productivity scoring. Older work is carried over, never silently discarded by the UI.
- Private-contact mode is a UI visibility feature, not forensic deniability. Unlinked notes cannot be saved into this mode because the current schema scopes privacy through contacts.
- Single-officer scope. Staff are records, not users: no account, no login, no acknowledgement, no notification and no scoring, ranking or attendance. Nothing is presented as a staff reply unless the officer typed it.
- Every installation is independent. A colleague's copy starts blank, with its own subdivision and contacts. There is no shared account, common database or district/station/rank seeded anywhere in the product.
- Subdivision records are normal-workspace only. Hidden and sensitive records never appear in a subdivision list, count, review, picker or search result.
- Archive, never destructive delete. A station or matter that still carries active staff, active matters or open work refuses to archive and names the blocker.
- Matters group instructions and their history. They are not legal case records, and nothing in them implies a court, an FIR or a sales pipeline.

## Success criteria

- A first note can be saved without onboarding a roster.
- Every open instruction is reachable, including undated and future items.
- Contacts explain who belongs there and how linking helps the officer.
- Responsibility and reminders survive save and recreation; failed writes retain the draft.
- Deadline and dated update history survive edits, recreation and backup restore. Old reminder times remain unchanged when upgrading.
- All records is the default search scope; All/Open/Closed are visible, with responsibility filtered separately. Search finds words in updates as well as the original instruction.
- Copying or opening Share is never reported as confirmed delivery. No colleague account, Slack connection or automatic outbound message is required.
- An instruction remembers the station it was recorded at. Transferring an officer changes their current posting and leaves past work where it happened; only an explicit context change moves it.
- Review counts saved with a review are labelled as a point-in-time snapshot and are never presented as live numbers. With no earlier review the app says so instead of inventing a since-date.
- Upgrading an existing database preserves every contact, note, journal, date, tag and hidden row, derives stations from existing contact text without duplicating them, and seeds neither a subdivision nor a staff classification.
- Backup, restore, export and import carry the subdivision record whole, or fail and change nothing. Older backups still restore, without a fabricated subdivision.
- Theme choices, system insets, navigation semantics, large text and touch targets behave like a native Android app.

The capture-speed target remains under five seconds for a short typed note, but is a target rather than a measured performance claim.
