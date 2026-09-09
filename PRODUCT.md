# Kaavalan note — officer workspace

## User and job

An officer working between field duty, calls, station reviews and briefings. Interruptions are normal. The app must help them capture an instruction quickly, remember who is responsible, and close the loop without demanding an organisational ritual first.

## Primary workflows

1. Open Today and see one next action, then other personal work and team follow-ups.
2. Write, speak or photograph a note from any working tab. Responsibility defaults to **For me**; **Assigned by me** and **Received** are explicit choices.
3. Optionally link an officer or other work contact and set a local reminder. Save without a contact in the normal workspace.
4. Open an instruction to read it, change its reminder, mark it done or close without action. Closed records remain searchable and can be reopened.
5. Use Contacts for colleagues, staff and other work relationships, with name, designation, station and linked instructions. Contacts are not a mandatory first step.

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
- Theme choices, system insets, navigation semantics, large text and touch targets behave like a native Android app.

The capture-speed target remains under five seconds for a short typed note, but is a target rather than a measured performance claim.
