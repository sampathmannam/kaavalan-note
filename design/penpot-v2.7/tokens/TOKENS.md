# Design tokens — KaavalanNote v2.7

Transcribed from `app/src/main/java/com/kaavalan/note/ui/theme/Theme.kt` and `Type.kt`.
`tokens/kaavalan-tokens.json` is the machine-readable form Penpot imports;
`tools/tokens.py` is the generator's copy. All three must agree.

## Colour roles

| Role | Light | Dark | What it is for |
| --- | --- | --- | --- |
| `primary` | `#174D6C` | `#A8D2EC` | The one distinct primary action: New note, Save, Mark done. Deep service blue. |
| `onPrimary` | `#FFFFFF` | `#0C354E` | Text and icons on a filled primary control. |
| `primaryContainer` | `#D8EBF6` | `#203F53` | The featured next-action card, the selected navigation indicator, icon tiles and the responsibility badge. |
| `onPrimaryContainer` | `#113A53` | `#DDEFFA` | Text on any primary container. |
| `secondary` | `#4B6170` | `#B7CBD8` | Reserved. No component sets it directly in v2.7. |
| `secondaryContainer` | `#DFE9EF` | `#2A4251` | Selected filter chips, the avatar of a contact with no open work, and the subdivision list-row icon tile. |
| `onSecondaryContainer` | `#263F4F` | `#DAE8F1` | Text on a secondary container. |
| `tertiary` | `#176B63` | `#91D3CA` | Restrained teal. Reserved for a non-alarming accent. |
| `tertiaryContainer` | `#D0EEE9` | `#204B47` | The Priority badge and the privacy tile in onboarding. This is the app's only 'attention' colour and it is deliberately not red. |
| `onTertiaryContainer` | `#134A45` | `#D0F0EB` | Text on a tertiary container. |
| `background` | `#F6F8FA` | `#0C161D` | The page ground and the top app bar at rest. |
| `onBackground` | `#15242E` | `#E4EDF2` | Page titles. |
| `surface` | `#FBFCFD` | `#14232C` | Ordinary sheet and dialog ground. |
| `onSurface` | `#15242E` | `#E4EDF2` | Body and title text. Slate ink, never pure black. |
| `surfaceVariant` | `#E6EDF1` | `#2B3F4B` | Reserved for Material internals. |
| `onSurfaceVariant` | `#4B5E6A` | `#B7C7D0` | Supporting text, metadata, unselected navigation. |
| `surfaceContainerLowest` | `#FFFFFF` | `#091218` | Cards, panels, the note bar and the navigation bar — cool paper against the page ground. |
| `surfaceContainerLow` | `#F0F4F6` | `#13232C` | Modal sheet ground, the search field at rest, the Photo/Voice capture tiles. |
| `surfaceContainer` | `#E9EFF3` | `#1A2D37` | Reserved. |
| `surfaceContainerHigh` | `#E1E9EE` | `#233844` | A disabled pinned action. |
| `surfaceContainerHighest` | `#D8E2E8` | `#2C4350` | Reserved. |
| `outline` | `#687D8B` | `#8CA0AB` | The border of an outlined control. |
| `outlineVariant` | `#CAD7DE` | `#3A515E` | Card and panel borders, list dividers, the rule above the note bar. The v2.7 surfaces are separated by a hairline, never by a shadow. |
| `inversePrimary` | `#A8D2EC` | `#174D6C` | Snackbar action. |
| `inverseSurface` | `#233642` | `#E0EAF0` | Snackbar ground. |
| `inverseOnSurface` | `#F2F6F8` | `#1D303B` | Snackbar text. |
| `surfaceTint` | `#174D6C` | `#A8D2EC` | Material elevation tint. Elevation is 0 on every card in v2.7, so this is inert by design. |

### Colours that are deliberately absent

There is no error / red role in the workspace palette. A refused save, a passed deadline
and a blocked archive are all stated in words on a calm surface — `secondaryContainer` for
the inline refusal banner. `PRODUCT.md` forbids red lateness badges and shame language,
and a palette that has no red to reach for is the cheapest way to keep that true.

There is also no gradient, no translucent "glass" fill and no elevation shadow. Cards are
`surfaceContainerLowest` with a 1dp `outlineVariant` border and elevation 0.

## Type scale

System typeface (`FontFamily.Default`). Roboto stands in for it in the boards.

| Style | Size / line | Weight | Where |
| --- | --- | --- | --- |
| `displaySmall` | 28 / 36 sp | 300 | Reserved. |
| `headlineMedium` | 28 / 36 sp | 600 | Page title on a working tab (Today / Instructions / Contacts). |
| `headlineSmall` | 24 / 32 sp | 500 | Sheet title; the heading of an emphasised first-use panel. |
| `titleLarge` | 22 / 30 sp | 600 | Subdivision destination title; the featured next-action card's text; empty-state titles. |
| `titleMedium` | 16 / 22 sp | 600 | Card text, section headings, contact names, settings category names. |
| `titleSmall` | 14 / 20 sp | 500 | Entry-row labels, form sub-headings, the note bar's label. |
| `bodyLarge` | 16 / 24 sp | 400 | Long-form content and empty-state body. |
| `bodyMedium` | 14 / 20 sp | 400 | Supporting lines, metadata, descriptions. |
| `bodySmall` | 12 / 16 sp | 400 | Reserved. |
| `labelLarge` | 14 / 20 sp | 500 | Buttons, counts, filter chips. |
| `labelMedium` | 12 / 16 sp | 500 | Badges, the top-bar eyebrow, navigation labels. |
| `labelSmall` | 12 / 16 sp | 500 | Reserved. |

The app never ships its own typeface and never overrides the system text size. Boards 03,
07 and the QA screenshots cover 150%, where every style scales and layouts reflow rather
than truncate.

## Spacing, radius and targets

- **Spacing:** `xs` 4dp · `sm` 8dp · `md` 12dp · `lg` 16dp · `screen` 20dp · `xl` 24dp. Page inset is 20dp on every surface, including empty states and inline text actions — a bare text button carries its own horizontal padding, so those reset it to zero.
- **Radius:** `small` 8dp · `medium` 12dp · `large` 16dp. Badges 8, fields and icon tiles 12, cards / panels / search 16, pill controls fully rounded.
- **Sizes:** `touchTarget` 48dp · `noteBar` 56dp · `searchField` 56dp · `iconTile` 40dp · `avatar` 44dp. Nothing tappable is under 48dp.

## Keeping these honest

`app/src/test/java/com/kaavalan/note/ui/components/FieldNotebookSurfacesTest.kt` fails the
build if the shipped code drifts from the rules these tokens encode — no gradients, no
streak or ranking language, badges that ellipsise instead of clipping, empty states on the
page inset with a real control, Today's date in the title block, an icon on every settings
category, and exactly three working destinations.
