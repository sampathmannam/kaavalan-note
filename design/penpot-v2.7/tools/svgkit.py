"""A very small SVG layout kit for the KaavalanNote design boards.

It is deliberately not a general-purpose framework. It knows about the handful of
primitives the app actually has — panel, card, badge, entry row, note bar, navigation
bar, top bar, field, button — so that a board file and the Compose screen it documents
stay describable in the same words.

Coordinates are dp. One SVG user unit == 1dp, so a board measures 360 x 800.
"""

from html import escape

from tokens import FONT_STACK, RADIUS, SIZE, SPACING, TYPE

BOARD_W = 360
BOARD_H = 800
INSET = SPACING["screen"]


class Board:
    def __init__(self, name, palette, scale=1.0, height=BOARD_H, width=BOARD_W):
        self.name = name
        self.c = palette
        self.scale = scale          # system text size multiplier (1.0 or 1.5)
        self.w = width
        self.h = height
        self.parts = []
        self.y = 0                  # running cursor for flow layout

    # ---------------------------------------------------------------- text

    def sp(self, style):
        size, line, weight = TYPE[style]
        return size * self.scale, line * self.scale, weight

    def measure(self, s, style="bodyMedium"):
        """Approximate Roboto advance width in dp. Good to a few percent, which is all a
        layout board needs — it exists so generated text never runs off its frame."""
        size, _, weight = self.sp(style)
        return len(s) * size * (0.52 if weight <= 400 else 0.56)

    def wrap(self, s, style="bodyMedium", max_w=None):
        """Greedy word wrap at `max_w` dp (default: the content column)."""
        max_w = max_w or (self.w - INSET * 2)
        words, lines, cur = s.split(), [], ""
        for word in words:
            trial = f"{cur} {word}".strip()
            if cur and self.measure(trial, style) > max_w:
                lines.append(cur)
                cur = word
            else:
                cur = trial
        if cur:
            lines.append(cur)
        return lines

    def ellipsize(self, s, style="bodyMedium", max_w=None):
        """One line, cut with an ellipsis — what a singleLine field or a maxLines=1 row does."""
        max_w = max_w or (self.w - INSET * 2)
        if self.measure(s, style) <= max_w:
            return s
        out = s
        while out and self.measure(out + "\u2026", style) > max_w:
            out = out[:-1]
        return out.rstrip() + "\u2026"

    def text(self, x, y, s, style="bodyMedium", fill=None, anchor="start", opacity=None):
        size, _, weight = self.sp(style)
        fill = fill or self.c["onSurface"]
        op = f' opacity="{opacity}"' if opacity else ""
        self.parts.append(
            f'<text x="{r(x)}" y="{r(y)}" font-family="{FONT_STACK}" font-size="{r(size)}" '
            f'font-weight="{weight}" fill="{fill}" text-anchor="{anchor}"{op}>{escape(s)}</text>'
        )

    def paragraph(self, x, y, lines, style="bodyMedium", fill=None, max_w=None):
        """A wrapped block. Returns the y below the last line."""
        if isinstance(lines, str):
            lines = self.wrap(lines, style, max_w or (self.w - x - INSET))
        _, line, _ = self.sp(style)
        for i, s in enumerate(lines):
            self.text(x, y + line * (i + 0.75), s, style, fill)
        return y + line * len(lines)

    # ---------------------------------------------------------------- shapes

    def rect(self, x, y, w, h, fill, radius=0, stroke=None, stroke_w=1, opacity=None):
        st = f' stroke="{stroke}" stroke-width="{stroke_w}"' if stroke else ""
        op = f' opacity="{opacity}"' if opacity else ""
        self.parts.append(
            f'<rect x="{r(x)}" y="{r(y)}" width="{r(w)}" height="{r(h)}" rx="{r(radius)}" '
            f'fill="{fill}"{st}{op}/>'
        )

    def line(self, x1, y1, x2, y2, stroke=None):
        stroke = stroke or self.c["outlineVariant"]
        self.parts.append(
            f'<line x1="{r(x1)}" y1="{r(y1)}" x2="{r(x2)}" y2="{r(y2)}" stroke="{stroke}" stroke-width="1"/>'
        )

    def glyph(self, cx, cy, kind, fill):
        """A schematic icon. Boards document layout and colour, not iconography —
        the shipped icons are Material Symbols Outlined, named in the manifest."""
        s = 9
        if kind == "chevron":
            self.parts.append(
                f'<path d="M {r(cx-3)} {r(cy-6)} L {r(cx+3)} {r(cy)} L {r(cx-3)} {r(cy+6)}" '
                f'fill="none" stroke="{fill}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>'
            )
        elif kind == "search":
            self.parts.append(
                f'<circle cx="{r(cx-1.5)}" cy="{r(cy-1.5)}" r="4.5" fill="none" stroke="{fill}" stroke-width="1.6"/>'
                f'<line x1="{r(cx+2)}" y1="{r(cy+2)}" x2="{r(cx+5.5)}" y2="{r(cy+5.5)}" '
                f'stroke="{fill}" stroke-width="1.6" stroke-linecap="round"/>'
            )
        elif kind == "arrow":
            self.parts.append(
                f'<line x1="{r(cx-5)}" y1="{r(cy)}" x2="{r(cx+5)}" y2="{r(cy)}" stroke="{fill}" stroke-width="1.6" stroke-linecap="round"/>'
                f'<path d="M {r(cx+1)} {r(cy-4)} L {r(cx+5)} {r(cy)} L {r(cx+1)} {r(cy+4)}" fill="none" '
                f'stroke="{fill}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>'
            )
        elif kind == "caret":
            self.parts.append(
                f'<path d="M {r(cx-4)} {r(cy-2)} L {r(cx)} {r(cy+3)} L {r(cx+4)} {r(cy-2)} Z" fill="{fill}"/>'
            )
        else:  # a neutral rounded mark standing in for a Material Symbols glyph
            self.parts.append(
                f'<rect x="{r(cx-s/2)}" y="{r(cy-s/2)}" width="{r(s)}" height="{r(s)}" rx="2" '
                f'fill="none" stroke="{fill}" stroke-width="1.6"/>'
            )

    # ---------------------------------------------------------------- app primitives

    def ground(self):
        self.rect(0, 0, self.w, self.h, self.c["background"])

    def status_bar(self):
        self.text(INSET, 15 * self.scale, "12:00", "labelMedium", self.c["onSurfaceVariant"])
        self.y = 28
        return self.y

    def top_bar(self, title, eyebrow, badge=None, back=False):
        """KaavalanTopBarTitle: an eyebrow label over the page title, settings on the right."""
        x = INSET + (28 if back else 0)
        if back:
            self.glyph(INSET + 8, self.y + 24 * self.scale, "arrow", self.c["onSurface"])
        e_size, e_line, _ = self.sp("labelMedium")
        t_style = "titleLarge" if back else "headlineMedium"
        t_size, t_line, _ = self.sp(t_style)
        self.text(x, self.y + e_line * 0.8, eyebrow, "labelMedium", self.c["primary"])
        self.text(x, self.y + e_line + t_line * 0.8, title, t_style, self.c["onSurface"])
        right = self.w - INSET
        if badge:
            bw = self.badge(0, 0, badge, measure_only=True)
            right -= 24
            self.badge(right - bw, self.y + e_line, badge)
            right -= bw + SPACING["sm"]
        self.glyph(self.w - INSET - 6, self.y + e_line + t_line * 0.5, "gear", self.c["onSurface"])
        self.y += e_line + t_line + SPACING["sm"]
        return self.y

    def badge(self, x, y, label, container=None, content=None, measure_only=False):
        size, line, _ = self.sp("labelMedium")
        w = len(label) * size * 0.56 + SPACING["lg"]
        if measure_only:
            return w
        h = line + SPACING["sm"]
        self.rect(x, y, w, h, container or self.c["primaryContainer"], RADIUS["small"])
        self.text(x + SPACING["sm"], y + h / 2 + size * 0.36, label, "labelMedium",
                  content or self.c["onPrimaryContainer"])
        return w

    def panel(self, y, h, emphasized=False):
        """KaavalanPanel: an outlined surface, never a shadow and never a gradient."""
        fill = self.c["primaryContainer"] if emphasized else self.c["surfaceContainerLowest"]
        stroke = self.c["primary"] if emphasized else self.c["outlineVariant"]
        self.rect(INSET, y, self.w - INSET * 2, h, fill, RADIUS["large"], stroke)
        return y + h

    def icon_tile(self, x, y, container=None, content=None, kind="glyph"):
        s = SIZE["iconTile"]
        self.rect(x, y, s, s, container or self.c["primaryContainer"], RADIUS["medium"])
        self.glyph(x + s / 2, y + s / 2, kind, content or self.c["onPrimaryContainer"])
        return x + s

    def entry_row(self, y, label, supporting):
        """SubdivisionEntryRow: the one labelled secondary action under the top bar."""
        h = 72
        self.panel(y, h)
        self.icon_tile(INSET + SPACING["lg"], y + (h - SIZE["iconTile"]) / 2)
        tx = INSET + SPACING["lg"] + SIZE["iconTile"] + SPACING["lg"]
        _, l1, _ = self.sp("titleSmall")
        _, l2, _ = self.sp("bodyMedium")
        top = y + (h - (l1 + l2)) / 2
        avail = self.w - INSET - SPACING["lg"] - 20 - tx
        self.text(tx, top + l1 * 0.8, self.ellipsize(label, "titleSmall", avail), "titleSmall", self.c["onSurface"])
        self.text(tx, top + l1 + l2 * 0.8, self.ellipsize(supporting, "bodyMedium", avail),
                  "bodyMedium", self.c["onSurfaceVariant"])
        self.glyph(self.w - INSET - SPACING["lg"] - 4, y + h / 2, "chevron", self.c["onSurfaceVariant"])
        return y + h

    def search_field(self, y, hint):
        h = SIZE["searchField"] * (1 if self.scale == 1 else 1.2)
        self.rect(INSET, y, self.w - INSET * 2, h, self.c["surfaceContainerLow"], RADIUS["large"])
        self.glyph(INSET + SPACING["lg"] + 4, y + h / 2, "search", self.c["onSurfaceVariant"])
        size, _, _ = self.sp("bodyMedium")
        self.text(INSET + 44, y + h / 2 + size * 0.36,
                  self.ellipsize(hint, "bodyMedium", self.w - INSET - 44 - SPACING["lg"]),
                  "bodyMedium", self.c["onSurfaceVariant"])
        return y + h

    def chips(self, y, labels, selected=0):
        size, line, _ = self.sp("labelLarge")
        h = max(SIZE["touchTarget"] * 0.72, line + SPACING["lg"])
        x = INSET
        for i, label in enumerate(labels):
            w = len(label) * size * 0.58 + SPACING["xl"]
            if x + w > self.w - INSET:
                x = INSET
                y += h + SPACING["sm"]
            if i == selected:
                self.rect(x, y, w, h, self.c["secondaryContainer"], RADIUS["small"])
            else:
                self.rect(x, y, w, h, "none", RADIUS["small"], self.c["outline"])
            self.text(x + w / 2, y + h / 2 + size * 0.36, label, "labelLarge", self.c["onSurface"], "middle")
            x += w + SPACING["sm"]
        return y + h

    def work_card(self, y, direction, body, context=None, meta=None,
                  featured=False, priority=False, actions=False, footer=None):
        """WorkCard: an outlined record. Featured is the officer's next action."""
        pad = SPACING["screen"] if featured else SPACING["lg"]
        inner = self.w - INSET * 2 - pad * 2
        body_lines = self.wrap(body, "titleLarge" if featured else "titleMedium", inner) \
            if isinstance(body, str) else body
        if isinstance(footer, str):
            footer = self.wrap(footer, "bodyMedium", inner)
        _, badge_line, _ = self.sp("labelMedium")
        body_style = "titleLarge" if featured else "titleMedium"
        _, body_line, _ = self.sp(body_style)
        _, small_line, _ = self.sp("bodyMedium")
        h = pad * 2 + badge_line + SPACING["sm"] + SPACING["sm"] + body_line * len(body_lines)
        if context:
            h += small_line + SPACING["sm"]
        if meta:
            h += small_line * len(meta)
        if footer:
            h += small_line * len(footer) + SPACING["sm"]
        if actions:
            h += SIZE["touchTarget"] + SPACING["sm"]

        fill = self.c["primaryContainer"] if featured else self.c["surfaceContainerLowest"]
        stroke = self.c["primary"] if featured else self.c["outlineVariant"]
        on = self.c["onPrimaryContainer"] if featured else self.c["onSurface"]
        muted = self.c["onPrimaryContainer"] if featured else self.c["onSurfaceVariant"]
        self.rect(INSET, y, self.w - INSET * 2, h, fill, RADIUS["large"], stroke)

        cy = y + pad
        self.badge(INSET + pad, cy, direction,
                   self.c["surface"] if featured else self.c["primaryContainer"],
                   self.c["onPrimaryContainer"])
        if priority:
            pw = self.badge(0, 0, "Priority", measure_only=True)
            self.badge(self.w - INSET - pad - pw, cy, "Priority",
                       self.c["tertiaryContainer"], self.c["onTertiaryContainer"])
        cy += badge_line + SPACING["sm"] + SPACING["sm"]
        for ln in body_lines:
            self.text(INSET + pad, cy + body_line * 0.78, ln, body_style, on)
            cy += body_line
        if context:
            cy += SPACING["sm"]
            self.text(INSET + pad, cy + small_line * 0.78, self.ellipsize(context, "bodyMedium", inner),
                      "bodyMedium", muted)
            cy += small_line
        for m in meta or []:
            self.text(INSET + pad, cy + small_line * 0.78, self.ellipsize(m, "labelLarge", inner),
                      "labelLarge", muted)
            cy += small_line
        for f in footer or []:
            self.text(INSET + pad, cy + small_line * 0.9, f, "bodyMedium", muted)
            cy += small_line
        if actions:
            cy += SPACING["sm"]
            bw = (self.w - INSET * 2 - pad * 2 - SPACING["sm"]) / 2
            self.rect(INSET + pad, cy, bw, SIZE["touchTarget"], self.c["primary"], SIZE["touchTarget"] / 2)
            size, _, _ = self.sp("labelLarge")
            self.text(INSET + pad + bw / 2, cy + SIZE["touchTarget"] / 2 + size * 0.36,
                      "Mark done", "labelLarge", self.c["onPrimary"], "middle")
            self.text(INSET + pad + bw + SPACING["sm"] + bw / 2,
                      cy + SIZE["touchTarget"] / 2 + size * 0.36, "Open", "labelLarge", self.c["primary"], "middle")
        return y + h

    def section_heading(self, y, title, subtitle=None):
        _, l1, _ = self.sp("titleMedium")
        self.text(INSET, y + l1 * 0.8, title, "titleMedium", self.c["onSurface"])
        y += l1
        if subtitle:
            _, l2, _ = self.sp("bodyMedium")
            lines = self.wrap(subtitle, "bodyMedium", self.w - INSET * 2)
            for i, ln in enumerate(lines):
                self.text(INSET, y + SPACING["xs"] + l2 * (i + 0.8), ln, "bodyMedium", self.c["onSurfaceVariant"])
            y += SPACING["xs"] + l2 * len(lines)
        return y

    def empty_state(self, y, title, body_lines, action=None):
        """KaavalanEmptyState — the page inset is 20dp and the action is a real control."""
        y += SPACING["xl"]
        _, l1, _ = self.sp("titleLarge")
        for i, ln in enumerate(self.wrap(title, "titleLarge", self.w - INSET * 2)):
            self.text(INSET, y + l1 * (i + 0.8), ln, "titleLarge", self.c["onSurface"])
            y += l1 if i else 0
        y += l1 + SPACING["md"]
        y = self.paragraph(INSET, y, body_lines, "bodyLarge", self.c["onSurfaceVariant"])
        if action:
            y += SPACING["md"]
            size, _, _ = self.sp("labelLarge")
            w = len(action) * size * 0.62 + SPACING["xl"] * 2
            self.rect(INSET, y, w, SIZE["touchTarget"], "none", SIZE["touchTarget"] / 2, self.c["outline"])
            self.text(INSET + w / 2, y + SIZE["touchTarget"] / 2 + size * 0.36, action,
                      "labelLarge", self.c["primary"], "middle")
            y += SIZE["touchTarget"]
        return y + SPACING["xl"]

    def note_bar(self, y, label="New note"):
        """The single capture bar: one text target plus disjoint Photo and Voice targets."""
        h = SIZE["noteBar"] + SPACING["sm"] * 2 + 1
        self.rect(0, y, self.w, h, self.c["surfaceContainerLowest"])
        self.line(0, y, self.w, y)
        by = y + SPACING["sm"] + 1
        shortcut = SIZE["noteBar"]
        main_w = self.w - INSET * 2 - (shortcut + SPACING["sm"]) * 2
        self.rect(INSET, by, main_w, SIZE["noteBar"], self.c["primary"], RADIUS["large"])
        size, _, _ = self.sp("titleSmall")
        self.glyph(INSET + SPACING["lg"] + 5, by + SIZE["noteBar"] / 2, "glyph", self.c["onPrimary"])
        self.text(INSET + 44, by + SIZE["noteBar"] / 2 + size * 0.36, label, "titleSmall", self.c["onPrimary"])
        x = INSET + main_w + SPACING["sm"]
        for name in ("Photo", "Voice"):
            self.rect(x, by, shortcut, SIZE["noteBar"], self.c["surfaceContainerLow"], RADIUS["medium"])
            self.glyph(x + shortcut / 2, by + 18, "glyph", self.c["primary"])
            lsize, _, _ = self.sp("labelMedium")
            self.text(x + shortcut / 2, by + SIZE["noteBar"] - 8, name, "labelMedium",
                      self.c["onSurfaceVariant"], "middle")
            x += shortcut + SPACING["sm"]
        return y + h

    def nav_bar(self, y, selected=0):
        h = 72
        self.rect(0, y, self.w, h, self.c["surfaceContainerLowest"])
        self.line(0, y, self.w, y)
        labels = ("Today", "Instructions", "Contacts")
        cell = self.w / 3
        for i, label in enumerate(labels):
            cx = cell * i + cell / 2
            if i == selected:
                self.rect(cx - 32, y + 10, 64, 32, self.c["primaryContainer"], 16)
            self.glyph(cx, y + 26, "glyph",
                       self.c["onPrimaryContainer"] if i == selected else self.c["onSurfaceVariant"])
            size, _, _ = self.sp("labelMedium")
            self.text(cx, y + 58, label, "labelMedium",
                      self.c["primary"] if i == selected else self.c["onSurfaceVariant"], "middle")
        return y + h

    def sheet(self, title, eyebrow):
        """A modal sheet frame: drag handle, icon tile, eyebrow, title, close."""
        self.rect(0, 0, self.w, self.h, self.c["surfaceContainerLow"], RADIUS["xl"] if False else 28)
        self.rect(self.w / 2 - 16, 10, 32, 4, self.c["outlineVariant"], 2)
        self.icon_tile(INSET, 28)
        _, e_line, _ = self.sp("labelMedium")
        _, t_line, _ = self.sp("headlineSmall")
        tx = INSET + SIZE["iconTile"] + SPACING["md"]
        self.text(tx, 28 + e_line * 0.9, eyebrow, "labelMedium", self.c["primary"])
        self.text(tx, 28 + e_line + t_line * 0.8, title, "headlineSmall", self.c["onSurface"])
        self.glyph(self.w - INSET - 6, 48, "close", self.c["onSurfaceVariant"])
        self.y = 28 + SIZE["iconTile"] + SPACING["screen"]
        return self.y

    def field(self, y, label, value=None, lines=1, focused=False):
        _, line, _ = self.sp("bodyLarge")
        h = max(SIZE["touchTarget"], line * lines + SPACING["xl"])
        stroke = self.c["primary"] if focused else self.c["outlineVariant"]
        self.rect(INSET, y, self.w - INSET * 2, h, self.c["surfaceContainerLowest"], RADIUS["medium"],
                  stroke, 2 if focused else 1)
        lsize, _, _ = self.sp("labelMedium")
        self.rect(INSET + SPACING["md"] - 2, y - lsize / 2 - 1, len(label) * lsize * 0.6 + 4, lsize + 2,
                  self.c["surfaceContainerLow"])
        self.text(INSET + SPACING["md"], y + lsize * 0.36, label, "labelMedium",
                  stroke if focused else self.c["onSurfaceVariant"])
        if value:
            size, _, _ = self.sp("bodyLarge")
            self.text(INSET + SPACING["lg"], y + SPACING["lg"] + size * 0.8,
                      self.ellipsize(value, "bodyLarge", self.w - INSET * 2 - SPACING["lg"] * 2),
                      "bodyLarge", self.c["onSurface"])
        return y + h

    def pinned_action(self, y, label="Save", enabled=True):
        self.line(INSET, y, self.w - INSET, y)
        y += SPACING["md"]
        fill = self.c["primary"] if enabled else self.c["surfaceContainerHigh"]
        on = self.c["onPrimary"] if enabled else self.c["onSurfaceVariant"]
        self.rect(INSET, y, self.w - INSET * 2, SIZE["touchTarget"], fill, SIZE["touchTarget"] / 2)
        size, _, _ = self.sp("labelLarge")
        self.text(self.w / 2, y + SIZE["touchTarget"] / 2 + size * 0.36, label, "labelLarge", on, "middle")
        return y + SIZE["touchTarget"]

    def keyboard(self, y):
        """A schematic IME, to show what a pinned Save has to clear."""
        self.rect(0, y, self.w, self.h - y, self.c["surfaceContainerHighest"])
        rows = ("qwertyuiop", "asdfghjkl", "zxcvbnm")
        ky = y + 14
        for row in rows:
            kw = (self.w - 12) / 10
            x = (self.w - kw * len(row)) / 2
            for ch in row:
                self.rect(x + 1, ky, kw - 2, 34, self.c["surfaceContainerLowest"], 5)
                self.text(x + kw / 2, ky + 22, ch, "bodyLarge", self.c["onSurface"], "middle")
                x += kw
            ky += 40
        return self.h

    def list_row(self, y, initial, name, supporting, badge_label, badge_on=True):
        _, l1, _ = self.sp("titleMedium")
        _, l2, _ = self.sp("bodyMedium")
        _, l3, _ = self.sp("labelMedium")
        h = SPACING["lg"] * 2 + l1 + SPACING["xs"] + l2 + SPACING["xs"] + l3 + SPACING["sm"]
        av = SIZE["avatar"]
        self.rect(INSET, y + SPACING["lg"], av, av,
                  self.c["primaryContainer"] if badge_on else self.c["secondaryContainer"], RADIUS["medium"])
        size, _, _ = self.sp("titleMedium")
        self.text(INSET + av / 2, y + SPACING["lg"] + av / 2 + size * 0.36, initial, "titleMedium",
                  self.c["onPrimaryContainer"] if badge_on else self.c["onSecondaryContainer"], "middle")
        tx = INSET + av + SPACING["md"]
        ty = y + SPACING["lg"]
        avail = self.w - INSET - 20 - tx
        self.text(tx, ty + l1 * 0.8, self.ellipsize(name, "titleMedium", avail), "titleMedium", self.c["onSurface"])
        ty += l1 + SPACING["xs"]
        self.text(tx, ty + l2 * 0.8, self.ellipsize(supporting, "bodyMedium", avail),
                  "bodyMedium", self.c["onSurfaceVariant"])
        ty += l2 + SPACING["xs"]
        self.badge(tx, ty, badge_label,
                   self.c["primaryContainer"] if badge_on else self.c["surfaceContainerLow"],
                   self.c["onPrimaryContainer"] if badge_on else self.c["onSurfaceVariant"])
        self.glyph(self.w - INSET - 4, y + h / 2, "chevron", self.c["onSurfaceVariant"])
        self.line(INSET, y + h, self.w - INSET, y + h)
        return y + h

    def settings_row(self, y, title, supporting):
        h = 72
        self.icon_tile(INSET, y + (h - SIZE["iconTile"]) / 2)
        tx = INSET + SIZE["iconTile"] + SPACING["md"]
        _, l1, _ = self.sp("titleMedium")
        _, l2, _ = self.sp("bodyMedium")
        top = y + (h - (l1 + SPACING["xs"] + l2)) / 2
        avail = self.w - INSET - 20 - tx
        self.text(tx, top + l1 * 0.8, self.ellipsize(title, "titleMedium", avail), "titleMedium", self.c["onSurface"])
        self.text(tx, top + l1 + SPACING["xs"] + l2 * 0.8, self.ellipsize(supporting, "bodyMedium", avail),
                  "bodyMedium", self.c["onSurfaceVariant"])
        self.glyph(self.w - INSET - 4, y + h / 2, "chevron", self.c["onSurfaceVariant"])
        self.line(INSET, y + h, self.w - INSET, y + h)
        return y + h

    # ---------------------------------------------------------------- output

    def render(self):
        body = "\n  ".join(self.parts)
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
            f'viewBox="0 0 {self.w} {self.h}">\n'
            f'  <title>{escape(self.name)}</title>\n'
            f'  <desc>KaavalanNote — Field Notebook UI v2.7. Generated from the shipped Compose '
            f'theme by design/penpot-v2.7/tools/generate_boards.py. 1 unit = 1dp.</desc>\n'
            f'  {body}\n</svg>\n'
        )


def r(v):
    return round(float(v), 2)
