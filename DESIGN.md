# Officer workspace design

## Direction

A quiet working notebook, not a command-centre dashboard. Keep the existing blue identity, replace the inconsistent warm/default-purple Material palette with ink, slate and cool paper. Distinct primary action, restrained surfaces, readable content and plain language take precedence over decoration.

## Navigation and hierarchy

- **Today** is the start destination: local date → follow-ups → personal next action → other work → coming up → completed today. Visible counts and See all controls keep previews from concealing the rest of the work.
- **Instructions** contains all records, search and responsibility/lifecycle filters.
- **Contacts** explains its purpose on first use; a populated directory prioritizes names, ranks, stations and open work. Counts follow the current search results. Edit contact is a named action.
- The note bar has disjoint New note, Photo and Voice targets on every working tab. It is not a floating overlay covering the last row.
- The subdivision record is reached from the tab it belongs to, never from a fourth tab. One labelled secondary action sits immediately below the top bar on each working tab: Today → **Subdivision review** (supporting text names the profile, or offers *Set up your subdivision*), Instructions → **Matters** (*Group related instructions*), Contacts → **Stations & staff** (*Postings and responsibilities*). Each uses an existing outlined icon, a readable label and a forward chevron. All three are hidden in the private workspace.
- Subdivision destinations are ordinary full-screen children with a titled top bar and Back — not a fourth tab, not one crowded dialog. Navigation state survives recreation; destinations that the current workspace cannot reach are cleared on a vault switch rather than left showing stale content.
- Within Stations & staff, **Stations** and **Staff** are local segmented categories, not primary navigation. Archived records are a separate filtered list, never mixed into the active one.
- Choosing a station, matter, contact or scope uses a searchable chooser, not an unbounded menu or a wall of chips.
- Use Material navigation bar below 600dp and navigation rail at wider widths. Settings stays in the top bar.
- Settings shows named categories before controls. Sample data and developer actions are isolated from the officer workflow.

## System

- Complete Material 3 light and dark color roles live in `ui/theme/Theme.kt`.
- System typeface. Page titles 28/36sp, sheet headings 24/32sp, instruction content 16–20sp, body 14–16sp, minimum labels 12sp.
- Spacing: 4, 8, 12, 16, 20, 24dp. Page inset 20dp; cards 16dp; next action 20dp.
- Shapes: 8/12/16dp. No ornamental gradients, glass, bright alerts or nested dashboard cards.
- Touch targets at least 48dp; navigation selection uses native Material semantics. Icon-only actions have descriptions. Preserve system back, keyboard and navigation insets.
- Long content scrolls. Capture, instruction/update and contact editors pin Save outside the scrolling form and above the IME. Instruction detail keeps Add update and completion reachable while the journal scrolls.
- Subdivision editors reuse that same frame: labels above or within fields rather than placeholder-only, scrolling body, pinned Save, drafts retained on a refused save with the failing field focused, and a discard warning only when something was actually typed.

## Language and states

Use For me / Assigned by me / Received, not Inbox / Outbox. Contacts, not unexplained People. “Close without action” preserves a record and explains reopening. Loading, retryable failure, no results and first-use states are distinct. Successful writes acknowledge completion; failure must not dismiss unfinished input.

Use **Deadline** for the work commitment and **Next follow-up** for the officer’s reminder; never imply these are the same date. **Ready to verify** means reported complete, not verified. **Verify & mark done** is the officer’s explicit closure. Updates are a chronological private journal, not a chat feed, notification stream or read-receipt system.

**Matters** groups related instructions and their history; it never implies a legal case file. **Stations & units** carry a user-editable type, defaulting to `Station`. A staff member is an existing contact the officer classified, shown with their current posting or **No station assigned** — never a score, rank order or attendance figure. Saved review counts read **At this review** so a snapshot is never mistaken for a live number; with no earlier review the app says **No earlier review for this scope** rather than inventing a date.

A refused archive explains the specific next action inline and keeps the entered data. No red overdue badges, shame wording, streaks, ranking, charts, metric card walls, novelty animation, gradients or new typefaces enter with the subdivision record.

Privacy and backup remain factual: local encrypted notes, optional external backup, possible network speech recognition. Private mode must never surface contacts or notes from the other workspace.
