# KaavalanNote — Field Notebook UI v2.7 (Penpot-ready design kit)

This directory is the design source of truth for the v2.7 field-notebook pass, in a form
Penpot can import directly. It exists because **Penpot itself could not be reached from
this session** — see [Penpot status](#penpot-status) below for the exact blocker and the
one action that unblocks it.

Everything here is generated from the shipped Compose theme, not drawn by hand, so a board
cannot quietly disagree with the app.

## What is here

| Path | What it is |
| --- | --- |
| `tokens/kaavalan-tokens.json` | Design tokens in the Tokens Studio JSON dialect Penpot imports: colour roles (light + dark), spacing, radius, sizing and the type scale. |
| `tokens/TOKENS.md` | The same tokens documented in prose, with the rule each one encodes. |
| `components/tokens-sheet.svg` | One board showing every colour role as a light/dark swatch pair, the type scale at real size, and the spacing and radius steps. |
| `components/components-light.svg`, `components/components-dark.svg` | The component library: top bar, entry row, search field, chips, work card (featured and plain), empty state, note bar, navigation bar. |
| `boards/*.svg` | 27 screen boards — every primary surface in light, dark, first-use, populated, no-results, failure, keyboard and 150%-text states. |
| `manifest.json` | The import manifest: page structure, per-board metadata, the Material Symbols icon names each screen uses, and the step-by-step Penpot import procedure. |
| `tools/` | The generator. `tokens.py` holds every value; `svgkit.py` is a small layout kit that knows the app's primitives; `generate_boards.py` composes the boards. |

Boards are 360 × 800 dp. **One SVG user unit is one dp**, so a measurement taken off a
board is a measurement you can type into Compose.

## Board index

| Board | Screen | Theme | State |
| --- | --- | --- | --- |
| 01, 02, 03 | Onboarding | light, dark, light @150% | first use |
| 04 | Today | light | first use |
| 05, 06, 07 | Today | light, dark, light @150% | populated |
| 08 | Today | light | private workspace |
| 09, 10 | Instructions | light, dark | populated |
| 11, 12 | Instructions | light | first use, no results |
| 13, 14 | Contacts | light, dark | populated |
| 15 | Contacts | light | first use |
| 16 | Workspace | light | retryable failure |
| 17, 18, 19 | Quick capture | light, dark, light | empty, empty, keyboard open |
| 20, 21 | Instruction editor, Contact editor | light | populated |
| 22, 23 | Subdivision review | light, dark | populated |
| 24 | Stations & staff | light | populated |
| 25 | Matters | light | first use |
| 26, 27 | Settings | light, dark | populated |

## Regenerating

```bash
python3 design/penpot-v2.7/tools/generate_boards.py
```

No dependencies beyond the standard library. If the Compose theme changes, update
`tools/tokens.py` first — it is the only place a colour, type step, spacing step or radius
is written down — then re-run the generator.

## Penpot status

Penpot could **not** be automated from this session. The blocker, precisely:

- `https://design.penpot.app/` is reachable but serves a Cloudflare interstitial to
  non-browser clients, and the application itself opens on **“Log into my account”**.
  No Penpot session, cookie, API token or `PENPOT_*` environment variable exists on this
  machine.
- There is no self-hosted Penpot: nothing is listening on the usual local ports and no
  Penpot container is running.
- No Penpot MCP server or connector is attached to this session.
- The Chrome extension that would carry an existing logged-in Penpot session reports no
  connected browser.

Creating an account and entering a password are both things this session must not do on
someone's behalf, so the work stopped at the login wall rather than silently switching to
Figma (a Figma MCP *is* attached — it was deliberately not used).

**The one action needed from you:** sign in to Penpot — either at
`https://design.penpot.app/` or on a self-hosted instance — and then either

1. import this directory yourself following `manifest.json` → `importSteps` (about five
   minutes: create the project and file, add three pages, drag the SVGs in, import the
   token JSON), **or**
2. tell this session the instance URL and connect a browser it can drive (the Claude in
   Chrome extension, with Penpot already signed in), and it will create the project, the
   pages and the token set and hand back the editable project URL.

Until then, **there is no Penpot project URL** — none was created, and none should be
quoted.

## What the boards encode

These are the product rules the boards are drawn to, each traceable to `PRODUCT.md`,
`DESIGN.md` or `docs/architecture/officer-workspace.md`:

- Exactly three working destinations — Today, Instructions, Contacts. Settings is a
  top-bar action. The subdivision record is reached by one labelled secondary action per
  tab, never a fourth tab.
- Today opens with the local date. It is the title block's eyebrow, so the one labelled
  subdivision action stays immediately below the top bar.
- One capture bar with disjoint New note / Photo / Voice targets, present on every working
  tab and never floating over the last row.
- Deep service blue, cool paper, slate ink, restrained teal. No gradients, no glass, no
  red lateness badges, no streaks, no ranking, no dashboards.
- Page inset 20dp; cards 16dp; spacing on 4/8/12/16/20/24; radii 8/12/16; every target at
  least 48dp.
- Loading, retryable failure, no-results and first-use are four distinct states, not one.
- Editors scroll their body and pin Save above the keyboard (board 19).
- Staff are records, not users. Nothing in these boards implies an account, a reply, a
  receipt, a score or a shared workspace.
