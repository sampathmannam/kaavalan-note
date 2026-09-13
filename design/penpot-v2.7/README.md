# KaavalanNote — Field Notebook UI v2.7 (Penpot-ready design kit)

This directory is the reproducible design source of truth for the v2.7 field-notebook
pass, in a form Penpot can import directly. The matching editable Penpot cloud file was
created and verified on 14 September 2026; see [Penpot status](#penpot-status).

Everything here is generated from the shipped Compose theme, not drawn by hand, so a board
cannot quietly disagree with the app.

## What is here

| Path | What it is |
| --- | --- |
| `tokens/kaavalan-tokens.json` | W3C DTCG design tokens Penpot imports: colour roles (light + dark), spacing, radius, sizing and the type scale. |
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

The editable cloud project and file are both named **KaavalanNote field notebook UI
v2.7**. The verified file contains:

- **Foundations** — the colour, typography, spacing, radius and target-size reference;
- **Components** — separate light and dark component boards, arranged side by side;
- **Screens & states** — all 27 360 × 800 boards arranged in a five-column grid;
- the six imported token sets (`light`, `dark`, `spacing`, `radius`, `size`,
  `typography`) and working **Light** / **Dark** themes, with Light active by default.

The personal-workspace cloud URL is intentionally not committed into this repository;
the implementation handoff provides it. The generator and SVG/JSON files here remain the
portable source of truth, so the design is reproducible without access to that account.

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
