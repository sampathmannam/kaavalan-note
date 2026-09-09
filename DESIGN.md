# Officer workspace design

## Direction

A quiet working notebook, not a command-centre dashboard. Keep the existing blue identity, replace the inconsistent warm/default-purple Material palette with ink, slate and cool paper. Distinct primary action, restrained surfaces, readable content and plain language take precedence over decoration.

## Navigation and hierarchy

- **Today** is the start destination: local date → next action → other work → follow-ups → coming up → completed today.
- **Instructions** contains all records, search and responsibility/lifecycle filters.
- **Contacts** explains its purpose before listing names, ranks, stations and open work.
- The note bar has disjoint New note, Photo and Voice targets on every working tab. It is not a floating overlay covering the last row.
- Use Material navigation bar below 600dp and navigation rail at wider widths. Settings stays in the top bar.
- Settings shows named categories before controls. Sample data and developer actions are isolated from the officer workflow.

## System

- Complete Material 3 light and dark color roles live in `ui/theme/Theme.kt`.
- System typeface. Page titles 28/36sp, sheet headings 24/32sp, instruction content 16–20sp, body 14–16sp, minimum labels 12sp.
- Spacing: 4, 8, 12, 16, 20, 24dp. Page inset 20dp; cards 16dp; next action 20dp.
- Shapes: 8/12/16dp. No ornamental gradients, glass, bright alerts or nested dashboard cards.
- Touch targets at least 48dp; navigation selection uses native Material semantics. Icon-only actions have descriptions. Preserve system back, keyboard and navigation insets.
- Long content scrolls. Capture Save is pinned outside the scrolling form and above the IME.

## Language and states

Use For me / Assigned by me / Received, not Inbox / Outbox. Contacts, not unexplained People. “Close without action” preserves a record and explains reopening. Loading, retryable failure, no results and first-use states are distinct. Successful writes acknowledge completion; failure must not dismiss unfinished input.

Privacy and backup remain factual: local encrypted notes, optional external backup, possible network speech recognition. Private mode must never surface contacts or notes from the other workspace.
