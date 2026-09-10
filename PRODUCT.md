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

## Boundaries

- Native Android, local-first, SQLCipher-encrypted Room. No new service, telemetry, account requirement or AI inference.
- Existing optional encrypted Google Drive backup remains explicit. Android speech recognition may use a network service; do not promise fully offline voice.
- Three working destinations: Today, Instructions, Contacts. Settings is a top-bar action, not a working destination.
- No shame language, streaks, red lateness badges or productivity scoring. Older work is carried over, never silently discarded by the UI.
- Private-contact mode is a UI visibility feature, not forensic deniability. Unlinked notes cannot be saved into this mode because the current schema scopes privacy through contacts.

## Success criteria

- A first note can be saved without onboarding a roster.
- Every open instruction is reachable, including undated and future items.
- Contacts explain who belongs there and how linking helps the officer.
- Responsibility and reminders survive save and recreation; failed writes retain the draft.
- Deadline and dated update history survive edits, recreation and backup restore. Old reminder times remain unchanged when upgrading.
- All records is the default search scope; All/Open/Closed are visible, with responsibility filtered separately. Search finds words in updates as well as the original instruction.
- Copying or opening Share is never reported as confirmed delivery. No colleague account, Slack connection or automatic outbound message is required.
- Theme choices, system insets, navigation semantics, large text and touch targets behave like a native Android app.

The capture-speed target remains under five seconds for a short typed note, but is a target rather than a measured performance claim.
