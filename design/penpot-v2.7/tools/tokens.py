"""Single source of truth for the v2.7 design kit.

Every value here is transcribed from the shipped Compose theme:

  app/src/main/java/com/kaavalan/note/ui/theme/Theme.kt   (colour roles)
  app/src/main/java/com/kaavalan/note/ui/theme/Type.kt    (type scale)
  DESIGN.md                                               (spacing, shapes, targets)

If the theme changes, change it here and re-run generate_boards.py. Nothing in the
design kit should carry a hard-coded hex value that is not in this file.
"""

LIGHT = {
    "primary": "#174D6C", "onPrimary": "#FFFFFF",
    "primaryContainer": "#D8EBF6", "onPrimaryContainer": "#113A53",
    "secondary": "#4B6170", "onSecondary": "#FFFFFF",
    "secondaryContainer": "#DFE9EF", "onSecondaryContainer": "#263F4F",
    "tertiary": "#176B63", "onTertiary": "#FFFFFF",
    "tertiaryContainer": "#D0EEE9", "onTertiaryContainer": "#134A45",
    "background": "#F6F8FA", "onBackground": "#15242E",
    "surface": "#FBFCFD", "onSurface": "#15242E",
    "surfaceVariant": "#E6EDF1", "onSurfaceVariant": "#4B5E6A",
    "surfaceContainerLowest": "#FFFFFF", "surfaceContainerLow": "#F0F4F6",
    "surfaceContainer": "#E9EFF3", "surfaceContainerHigh": "#E1E9EE",
    "surfaceContainerHighest": "#D8E2E8",
    "outline": "#687D8B", "outlineVariant": "#CAD7DE",
    "inverseSurface": "#233642", "inverseOnSurface": "#F2F6F8",
    "inversePrimary": "#A8D2EC", "surfaceTint": "#174D6C",
}

DARK = {
    "primary": "#A8D2EC", "onPrimary": "#0C354E",
    "primaryContainer": "#203F53", "onPrimaryContainer": "#DDEFFA",
    "secondary": "#B7CBD8", "onSecondary": "#223A49",
    "secondaryContainer": "#2A4251", "onSecondaryContainer": "#DAE8F1",
    "tertiary": "#91D3CA", "onTertiary": "#123D39",
    "tertiaryContainer": "#204B47", "onTertiaryContainer": "#D0F0EB",
    "background": "#0C161D", "onBackground": "#E4EDF2",
    "surface": "#14232C", "onSurface": "#E4EDF2",
    "surfaceVariant": "#2B3F4B", "onSurfaceVariant": "#B7C7D0",
    "surfaceContainerLowest": "#091218", "surfaceContainerLow": "#13232C",
    "surfaceContainer": "#1A2D37", "surfaceContainerHigh": "#233844",
    "surfaceContainerHighest": "#2C4350",
    "outline": "#8CA0AB", "outlineVariant": "#3A515E",
    "inverseSurface": "#E0EAF0", "inverseOnSurface": "#1D303B",
    "inversePrimary": "#174D6C", "surfaceTint": "#A8D2EC",
}

# name -> (size sp, line-height sp, weight)
TYPE = {
    "displaySmall":   (28, 36, 300),
    "headlineMedium": (28, 36, 600),
    "headlineSmall":  (24, 32, 500),
    "titleLarge":     (22, 30, 600),
    "titleMedium":    (16, 22, 600),
    "titleSmall":     (14, 20, 500),
    "bodyLarge":      (16, 24, 400),
    "bodyMedium":     (14, 20, 400),
    "bodySmall":      (12, 16, 400),
    "labelLarge":     (14, 20, 500),
    "labelMedium":    (12, 16, 500),
    "labelSmall":     (12, 16, 500),
}

# DESIGN.md "System": spacing 4, 8, 12, 16, 20, 24dp. Page inset 20, card 16, next action 20.
SPACING = {"xs": 4, "sm": 8, "md": 12, "lg": 16, "screen": 20, "xl": 24}

# DESIGN.md "Shapes: 8/12/16dp."
RADIUS = {"small": 8, "medium": 12, "large": 16}

# DESIGN.md "Touch targets at least 48dp"; the note bar and search sit at 56dp.
SIZE = {"touchTarget": 48, "noteBar": 56, "searchField": 56, "iconTile": 40, "avatar": 44}

FONT_STACK = "Roboto, Noto Sans, Helvetica Neue, Arial, sans-serif"

# The 150% system text size the QA matrix covers.
LARGE_TEXT_SCALE = 1.5
