#!/usr/bin/env python3
"""Generate the Penpot-ready SVG boards for KaavalanNote — Field Notebook UI v2.7.

    python3 design/penpot-v2.7/tools/generate_boards.py

Writes design/penpot-v2.7/boards/*.svg, components/*.svg, tokens/*.json and manifest.json.
Every colour, type step, spacing step and radius comes from tools/tokens.py, which is
transcribed from the shipped Compose theme — so a board can never drift from the app
without the transcription being updated first.
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ROOT = os.path.dirname(HERE)

from svgkit import BOARD_H, BOARD_W, INSET, Board  # noqa: E402
from tokens import (  # noqa: E402
    DARK, FONT_STACK, LARGE_TEXT_SCALE, LIGHT, RADIUS, SIZE, SPACING, TYPE,
)

BOARDS = []


def board(filename, title, screen, theme, state, scale=1.0, notes=""):
    def register(fn):
        palette = DARK if theme == "dark" else LIGHT
        b = Board(title, palette, scale=scale)
        b.ground()
        b.status_bar()
        fn(b)
        path = os.path.join(ROOT, "boards", filename)
        with open(path, "w") as f:
            f.write(b.render())
        BOARDS.append({
            "file": "boards/" + filename,
            "board": title,
            "screen": screen,
            "theme": theme,
            "state": state,
            "textScale": scale,
            "notes": notes,
        })
        return fn
    return register


# ---------------------------------------------------------------- onboarding

def _onboarding(b):
    scale = b.scale
    y = b.y + SPACING["xs"]
    _, e_line, _ = b.sp("labelMedium")
    _, t_line, _ = b.sp("titleMedium")
    _, h_line, _ = b.sp("headlineMedium")
    _, body_line, _ = b.sp("bodyLarge")
    hero_lines_n = 4 if scale > 1 else 2
    hero_h = (SPACING["xl"] * 2 + SIZE["iconTile"] + SPACING["screen"] + h_line * hero_lines_n
              + SPACING["screen"] + body_line * (3 if scale > 1 else 2))
    b.panel(y, hero_h, emphasized=True)
    iy = y + SPACING["xl"]
    b.icon_tile(INSET + SPACING["xl"], iy, b.c["surface"], b.c["onPrimaryContainer"])
    tx = INSET + SPACING["xl"] + SIZE["iconTile"] + SPACING["md"]
    b.text(tx, iy + e_line * 0.9, "Private field notebook", "labelMedium", b.c["onPrimaryContainer"])
    b.text(tx, iy + e_line + t_line * 0.8, "Kaavalan note", "titleMedium", b.c["onPrimaryContainer"])
    hy = iy + SIZE["iconTile"] + SPACING["screen"]
    hero_inner = BOARD_W - (INSET + SPACING["xl"]) * 2
    hero_lines = b.wrap("A clear head.", "headlineMedium", hero_inner) + \
        b.wrap("A clear record.", "headlineMedium", hero_inner)
    for i, ln in enumerate(hero_lines):
        b.text(INSET + SPACING["xl"], hy + h_line * (i + 0.8), ln, "headlineMedium", b.c["onPrimaryContainer"])
    py = hy + h_line * len(hero_lines) + SPACING["screen"]
    b.paragraph(INSET + SPACING["xl"], py,
                "Your working notebook for instructions, decisions and follow-ups on duty.",
                "bodyLarge", b.c["onPrimaryContainer"], max_w=BOARD_W - (INSET + SPACING["xl"]) * 2)

    y += hero_h + SPACING["xl"]
    y = b.section_heading(y, "Built around your duty")
    y += SPACING["lg"]
    rows = [
        ("Today", "See your next action and follow up with your team."),
        ("Instructions", "Record work for yourself, assigned by you, or received."),
        ("Contacts", "Link officers and staff when useful. You can start without adding anyone."),
    ]
    for name, desc in rows:
        # Top-aligned tile: at 150% text a centred tile floats beside a three-line body.
        b.icon_tile(INSET, y)
        tx = INSET + SIZE["iconTile"] + 14
        _, l1, _ = b.sp("titleMedium")
        _, l2, _ = b.sp("bodyMedium")
        b.text(tx, y + l1 * 0.8, name, "titleMedium", b.c["onSurface"])
        desc_lines = b.wrap(desc, "bodyMedium", BOARD_W - INSET - tx)
        for i, ln in enumerate(desc_lines):
            b.text(tx, y + l1 + SPACING["xs"] + l2 * (i + 0.8), ln, "bodyMedium", b.c["onSurfaceVariant"])
        y += max(SIZE["iconTile"], l1 + SPACING["xs"] + l2 * len(desc_lines)) + SPACING["xl"]
        if y > BOARD_H - 200:
            break

    ay = BOARD_H - SPACING["xl"] - SIZE["touchTarget"] * 2 - SPACING["sm"]

    # The scrolling column ends above the two pinned actions. At 150% system text the
    # privacy panel falls below that fold and the officer scrolls to it — board 03 shows
    # exactly that, so it is omitted here rather than drawn over the buttons.
    tx = INSET + SPACING["lg"] + SIZE["iconTile"] + SPACING["md"]
    _, l1, _ = b.sp("titleMedium")
    _, l2, _ = b.sp("bodyMedium")
    privacy_body = b.wrap(
        "Notes are encrypted on this phone. No account is needed. Drive backup is "
        "optional; system voice recognition may use an online service.",
        "bodyMedium", BOARD_W - INSET - SPACING["lg"] - tx)
    privacy_h = SPACING["lg"] * 2 + l1 + l2 * len(privacy_body)
    if y + privacy_h <= ay - SPACING["lg"]:
        b.panel(y, privacy_h)
        b.icon_tile(INSET + SPACING["lg"], y + SPACING["lg"], b.c["tertiaryContainer"], b.c["onTertiaryContainer"])
        b.text(tx, y + SPACING["lg"] + l1 * 0.8, "Private by default", "titleMedium", b.c["onSurface"])
        b.paragraph(tx, y + SPACING["lg"] + l1, privacy_body, "bodyMedium", b.c["onSurfaceVariant"])

    b.rect(INSET, ay, BOARD_W - INSET * 2, SIZE["touchTarget"], b.c["primary"], SIZE["touchTarget"] / 2)
    size, _, _ = b.sp("labelLarge")
    b.text(BOARD_W / 2, ay + SIZE["touchTarget"] / 2 + size * 0.36, "Open my workspace",
           "labelLarge", b.c["onPrimary"], "middle")
    b.text(BOARD_W / 2, ay + SIZE["touchTarget"] + SPACING["sm"] + SIZE["touchTarget"] / 2 + size * 0.36,
           "Skip", "labelLarge", b.c["primary"], "middle")


board("01-onboarding-light.svg", "Onboarding · light", "Onboarding", "light", "first use",
      notes="One screen, no roster and no permission prompt before the first note.")(_onboarding)
board("02-onboarding-dark.svg", "Onboarding · dark", "Onboarding", "dark", "first use")(_onboarding)
board("03-onboarding-large-text.svg", "Onboarding · 150% text", "Onboarding", "light", "first use",
      scale=LARGE_TEXT_SCALE,
      notes="At 150% the hero wraps to three lines and the privacy panel moves below the fold; "
            "the column scrolls and the two actions stay pinned.")(_onboarding)


# ---------------------------------------------------------------- Today

def _today_first_use(b):
    b.top_bar("Today", "Saturday, 12 September")
    y = b.y + SPACING["sm"]
    y = b.entry_row(y, "Subdivision review", "Set up your subdivision")
    y += SPACING["lg"]
    panel_h = SPACING["xl"] * 2 + SIZE["iconTile"] + SPACING["lg"] * 3 + 30 + 40 + SIZE["touchTarget"]
    b.panel(y, panel_h, emphasized=True)
    iy = y + SPACING["xl"]
    b.icon_tile(INSET + SPACING["xl"], iy, b.c["surface"], b.c["onPrimaryContainer"])
    _, h_line, _ = b.sp("headlineSmall")
    hy = iy + SIZE["iconTile"] + SPACING["lg"]
    inner = BOARD_W - (INSET + SPACING["xl"]) * 2
    for i, ln in enumerate(b.wrap("A clear start to your duty.", "headlineSmall", inner)):
        b.text(INSET + SPACING["xl"], hy + h_line * (i + 0.8), ln, "headlineSmall", b.c["onPrimaryContainer"])
    by = hy + h_line + SPACING["lg"]
    b.paragraph(INSET + SPACING["xl"], by,
                "Capture an instruction while it is fresh. Add a reminder if it needs your attention later.",
                "bodyLarge", b.c["onPrimaryContainer"], max_w=inner)
    ay = by + 44 + SPACING["lg"]
    b.rect(INSET + SPACING["xl"], ay, 150, SIZE["touchTarget"], b.c["primary"], SIZE["touchTarget"] / 2)
    size, _, _ = b.sp("labelLarge")
    b.text(INSET + SPACING["xl"] + 75, ay + SIZE["touchTarget"] / 2 + size * 0.36,
           "Write your first note", "labelLarge", b.c["onPrimary"], "middle")

    y += panel_h + SPACING["lg"]
    y = b.section_heading(y, "Built around your day")
    y += SPACING["md"]
    for title, body in (("For me", "Tasks and decisions you will handle."),
                        ("Assigned by me", "Instructions to your team; a place to follow up."),
                        ("Received", "Instructions from a senior or another office.")):
        _, l1, _ = b.sp("titleSmall")
        _, l2, _ = b.sp("bodyMedium")
        b.text(INSET, y + l1 * 0.8, title, "titleSmall", b.c["onSurface"])
        y = b.paragraph(INSET, y + l1 + SPACING["xs"] - l2 * 0.75, body, "bodyMedium", b.c["onSurfaceVariant"])
        y += SPACING["md"]

    nav_y = BOARD_H - 72
    bar_y = nav_y - (SIZE["noteBar"] + SPACING["sm"] * 2 + 1)
    b.note_bar(bar_y)
    b.nav_bar(nav_y, 0)


def _today_populated(b, scale_note=False):
    b.top_bar("Today", "Saturday, 12 September")
    y = b.y + SPACING["sm"]
    y = b.entry_row(y, "Subdivision review", "Warangal Subdivision")
    y += SPACING["lg"]
    y = b.section_heading(y, "Follow up", "3 to check · assigned work and replies you're waiting for")
    y += SPACING["md"]
    y = b.work_card(
        y, "Assigned by me",
        "Daily diary report — need it submitted by 6 PM every day.",
        context="K. Mahesh · Jangaon Circle",
        meta=["Follow-up · Today, 18:00"],
        featured=True, priority=True,
    )
    y += SPACING["lg"]
    y = b.work_card(
        y, "Received",
        "164 statement from the new informant in the 2024 appeal.",
        context="B. Ramesh Naidu · Subedari PS",
        meta=["Carried over · 10 Sep, 09:00"],
    )
    y += SPACING["lg"]
    y = b.section_heading(y, "Your next action", "Start with one thing")

    nav_y = BOARD_H - 72
    bar_y = nav_y - (SIZE["noteBar"] + SPACING["sm"] * 2 + 1)
    b.note_bar(bar_y)
    b.nav_bar(nav_y, 0)


board("04-today-first-use-light.svg", "Today · first use · light", "Today", "light", "first use",
      notes="The one labelled subdivision action sits immediately below the top bar; the local "
            "date is the title block's eyebrow rather than a card of its own.")(_today_first_use)
board("05-today-populated-light.svg", "Today · populated · light", "Today", "light", "populated",
      notes="Follow-ups first, then the officer's own next action.")(_today_populated)
board("06-today-populated-dark.svg", "Today · populated · dark", "Today", "dark", "populated")(_today_populated)
board("07-today-populated-large-text.svg", "Today · populated · 150% text", "Today", "light",
      "populated", scale=LARGE_TEXT_SCALE,
      notes="At 150% each card takes roughly half the viewport; the list scrolls and the note "
            "bar and navigation stay fixed.")(_today_populated)
def _today_private(b):
    """The private workspace names itself on every tab, and offers no subdivision entry."""
    b.top_bar("Today", "Saturday, 12 September", badge="Private")
    y = b.y + SPACING["sm"]
    y = b.section_heading(y, "Follow up", "1 to check")
    y += SPACING["md"]
    y = b.work_card(y, "For me", "Verify the private contact's pending court date.",
                    context="Protected contact", featured=True)
    nav_y = BOARD_H - 72
    b.note_bar(nav_y - (SIZE["noteBar"] + SPACING["sm"] * 2 + 1))
    b.nav_bar(nav_y, 0)


board("08-today-private-workspace.svg", "Today · private workspace", "Today", "light",
      "private workspace",
      notes="Subdivision records are normal-workspace only, so no entry row appears here; the "
            "Private badge sits in the top bar on every tab.")(_today_private)


# ---------------------------------------------------------------- Instructions

def _instructions(b, state):
    b.top_bar("Instructions", "Field notebook")
    y = b.y + SPACING["sm"]
    y = b.entry_row(y, "Matters", "Group related instructions")
    y += SPACING["sm"]
    hint = "zzzznoresults" if state == "no results" else "Search instructions, updates, stations and matters"
    y = b.search_field(y, hint)
    y += SPACING["sm"]
    y = b.chips(y, ["All records", "All open", "Closed"], 0)
    y += SPACING["sm"]
    size, line, _ = b.sp("labelLarge")
    b.text(INSET, y + SIZE["touchTarget"] / 2 + size * 0.36, "All responsibilities", "labelLarge", b.c["primary"])
    b.glyph(INSET + len("All responsibilities") * size * 0.62 + 10, y + SIZE["touchTarget"] / 2,
            "caret", b.c["primary"])
    y += SIZE["touchTarget"]

    nav_y = BOARD_H - 72
    bar_y = nav_y - (SIZE["noteBar"] + SPACING["sm"] * 2 + 1)

    if state == "populated":
        _, l, _ = b.sp("labelLarge")
        b.text(INSET, y + l, "152 instructions", "labelLarge", b.c["onSurfaceVariant"])
        y += l + SPACING["md"]
        y = b.work_card(y, "Assigned by me", "IO's confidential report on the chain snatching case.",
                        context="A. Venkateshwarlu · District HQ", meta=["Closed without action"], priority=True)
        y += SPACING["md"]
        y = b.work_card(y, "Received", "Chain snatching at Subedari PS. 1 accused arrested.",
                        context="B. Ramesh Naidu · Subedari PS", meta=["Deadline · 20 Sep, 17:00"])
    elif state == "first use":
        b.empty_state(y, "A clear place for your work",
                      "Record a task for yourself, an instruction you gave, or one you received. "
                      "A contact is optional.",
                      "New note")
    elif state == "no results":
        b.empty_state(y, "No matching instructions",
                      "No results in the selected scope. Try All records and All responsibilities, "
                      "or a few different words.",
                      "Reset search & filters")
    b.note_bar(bar_y)
    b.nav_bar(nav_y, 1)


board("09-instructions-populated-light.svg", "Instructions · populated · light", "Instructions",
      "light", "populated")(lambda b: _instructions(b, "populated"))
board("10-instructions-populated-dark.svg", "Instructions · populated · dark", "Instructions",
      "dark", "populated")(lambda b: _instructions(b, "populated"))
board("11-instructions-first-use-light.svg", "Instructions · first use · light", "Instructions",
      "light", "first use",
      notes="Every left edge sits on the 20dp page inset, including the empty-state action.")(
    lambda b: _instructions(b, "first use"))
board("12-instructions-no-results-light.svg", "Instructions · no results · light", "Instructions",
      "light", "no results",
      notes="A no-results state is distinct from first use and from a load failure.")(
    lambda b: _instructions(b, "no results"))


# ---------------------------------------------------------------- Contacts

def _contacts(b, state):
    b.top_bar("Contacts", "Field notebook")
    y = b.y + SPACING["sm"]
    y = b.entry_row(y, "Stations & staff", "Postings and responsibilities")
    y += SPACING["sm"]
    if state != "populated":
        y = b.paragraph(INSET, y, "Your officers, staff and other work contacts. Link instructions to see "
                        "each person's follow-ups in one place.",
                        "bodyMedium", b.c["onSurfaceVariant"]) + SPACING["sm"]
    y = b.search_field(y, "Search name, rank or station")
    y += SPACING["sm"]
    size, _, _ = b.sp("labelLarge")
    b.text(INSET, y + SIZE["touchTarget"] / 2 + size * 0.36, "Import from phone contacts",
           "labelLarge", b.c["primary"])
    y += SIZE["touchTarget"]

    nav_y = BOARD_H - 72
    bar_y = nav_y - (SIZE["noteBar"] + SPACING["sm"] * 2 + 1)

    if state == "populated":
        b.text(INSET, y + SIZE["touchTarget"] / 2 + size * 0.36, "46 contacts", "labelLarge", b.c["onSurface"])
        b.text(BOARD_W - INSET, y + SIZE["touchTarget"] / 2 + size * 0.36, "Add contact",
               "labelLarge", b.c["primary"], "end")
        y += SIZE["touchTarget"]
        y = b.list_row(y, "A", "A. Test SP", "Superintendent of Police · District HQ", "2 open instructions")
        y = b.list_row(y, "B", "B. Ramesh Naidu", "Sub-Inspector (SI) · Subedari PS", "1 open instruction")
        y = b.list_row(y, "K", "K. Mahesh", "Inspector · Jangaon Circle", "No open instructions", badge_on=False)
    else:
        # With nothing in the directory the count row is hidden: the first-use block
        # below already carries the one Add action.
        b.empty_state(y, "Know who is handling what",
                      "Add a colleague with their rank and station. You can still save notes "
                      "without adding anyone.",
                      "Add contact")
    b.note_bar(bar_y)
    b.nav_bar(nav_y, 2)


board("13-contacts-populated-light.svg", "Contacts · populated · light", "Contacts", "light",
      "populated")(lambda b: _contacts(b, "populated"))
board("14-contacts-populated-dark.svg", "Contacts · populated · dark", "Contacts", "dark",
      "populated")(lambda b: _contacts(b, "populated"))
board("15-contacts-first-use-light.svg", "Contacts · first use · light", "Contacts", "light",
      "first use",
      notes="One Add action, not two: the count row is hidden while the first-use block is "
            "the only thing on screen.")(lambda b: _contacts(b, "first use"))


# ---------------------------------------------------------------- workspace failure

def _workspace_error(b):
    b.top_bar("Today", "Saturday, 12 September")
    y = b.y + SPACING["sm"]
    b.empty_state(y, "Your workspace could not load",
                  "The encrypted database could not be opened. Nothing has been changed or lost.",
                  "Try again")
    nav_y = BOARD_H - 72
    b.note_bar(nav_y - (SIZE["noteBar"] + SPACING["sm"] * 2 + 1))
    b.nav_bar(nav_y, 0)


board("16-workspace-error-light.svg", "Workspace · retryable failure", "Today", "light", "error",
      notes="A retryable failure is stated plainly and offers the retry — never a red alert, "
            "never a dead end.")(_workspace_error)


# ---------------------------------------------------------------- quick capture

def _capture(b, keyboard=False):
    b.rect(0, 0, BOARD_W, BOARD_H, b.c["background"])
    b.sheet("New note", "Quick capture")
    y = b.y
    y = b.field(y, "Note",
                "Verify night patrol roster" if keyboard else None,
                lines=1 if keyboard else 2, focused=keyboard)
    y += SPACING["screen"]
    _, l, _ = b.sp("titleMedium")
    b.text(INSET, y + l * 0.8, "Who will act on this?", "titleMedium", b.c["onSurface"])
    y += l + SPACING["md"]
    y = b.chips(y, ["For me", "Assigned by me", "Received"], 0)
    y += SPACING["md"]
    size, _, _ = b.sp("bodyMedium")
    b.text(INSET, y + size, "A task or decision for you.", "bodyMedium", b.c["onSurfaceVariant"])
    y += size + SPACING["screen"]

    if not keyboard:
        lsize, _, _ = b.sp("labelLarge")
        b.text(INSET, y + SIZE["touchTarget"] / 2 + lsize * 0.36, "Link a contact (optional)",
               "labelLarge", b.c["primary"])
        y += SIZE["touchTarget"] + SPACING["sm"]
        _, tl, _ = b.sp("titleMedium")
        _, bl, _ = b.sp("bodyMedium")
        b.glyph(INSET + 8, y + tl * 0.6, "glyph", b.c["onSurfaceVariant"])
        b.text(INSET + 28, y + tl * 0.8, "Reminder", "titleMedium", b.c["onSurface"])
        rl = b.wrap("Get a private notification when this note needs you.", "bodyMedium",
                    BOARD_W - INSET - 28)
        for i, ln in enumerate(rl):
            b.text(INSET + 28, y + tl + bl * (i + 0.8), ln, "bodyMedium", b.c["onSurfaceVariant"])
        y += tl + bl * len(rl) + SPACING["md"]
        y = b.chips(y, ["In 1 hour", "Tomorrow at 9:00", "Next week"], -1)
        y += SPACING["sm"]
        y = b.chips(y, ["Pick date & time"], -1)
        y += SPACING["screen"]
        b.text(INSET, y + SIZE["touchTarget"] / 2 + lsize * 0.36, "Add labels (optional)",
               "labelLarge", b.c["primary"])
        b.pinned_action(BOARD_H - SPACING["xl"] - SIZE["touchTarget"] - SPACING["md"], "Save", enabled=False)
    else:
        # Compact typing: the heading and secondary rows give way, Save stays above the IME.
        lsize, _, _ = b.sp("labelLarge")
        b.text(INSET, y + SIZE["touchTarget"] / 2 + lsize * 0.36, "Link a contact (optional)",
               "labelLarge", b.c["primary"])
        y += SIZE["touchTarget"] + SPACING["sm"]
        y = b.chips(y, ["In 1 hour", "Tomorrow at 9:00", "Next week"], -1)
        ime_top = 470
        b.pinned_action(ime_top - SIZE["touchTarget"] - SPACING["md"] - SPACING["md"], "Save", enabled=True)
        b.keyboard(ime_top)


board("17-capture-light.svg", "Quick capture · light", "Quick capture", "light", "empty")(
    lambda b: _capture(b, False))
board("18-capture-dark.svg", "Quick capture · dark", "Quick capture", "dark", "empty")(
    lambda b: _capture(b, False))
board("19-capture-keyboard-light.svg", "Quick capture · keyboard", "Quick capture", "light", "keyboard",
      notes="Save is pinned outside the scrolling form and above the IME; the heading is dropped "
            "while typing rather than shrinking the officer's text.")(lambda b: _capture(b, True))


# ---------------------------------------------------------------- editors

def _instruction_editor(b):
    b.rect(0, 0, BOARD_W, BOARD_H, b.c["background"])
    b.sheet("Edit instruction", "Field notebook")
    y = b.y
    y = b.field(y, "Instruction", "Daily diary report — need it submitted", lines=4)
    y += SPACING["screen"]
    _, l, _ = b.sp("titleSmall")
    b.text(INSET, y + l * 0.8, "Responsibility", "titleSmall", b.c["onSurface"])
    y += l + SPACING["md"]
    y = b.chips(y, ["For me", "Assigned by me", "Received"], 1)
    y += SPACING["screen"]
    y = b.field(y, "Linked contact", "K. Mahesh · Jangaon Circle")
    y += SPACING["lg"]
    y = b.field(y, "Deadline", "20 Sep 2026, 17:00")
    y += SPACING["sm"]
    size, _, _ = b.sp("bodyMedium")
    b.paragraph(INSET, y, "The deadline is when the work must finish. Your next follow-up "
                          "reminder is separate and is not changed by editing this.",
                "bodyMedium", b.c["onSurfaceVariant"])
    b.pinned_action(BOARD_H - SPACING["xl"] - SIZE["touchTarget"] - SPACING["md"], "Save")


def _contact_editor(b):
    b.rect(0, 0, BOARD_W, BOARD_H, b.c["background"])
    b.sheet("Edit contact", "Field notebook")
    y = b.y
    y = b.paragraph(INSET, y, "Changes apply only in KaavalanNote, not to your phone's "
                              "address book.", "bodyMedium", b.c["onSurfaceVariant"])
    y += SPACING["screen"]
    for label, value in (("Name", "B. Ramesh Naidu"), ("Rank / designation", "Sub-Inspector (SI)"),
                         ("Station / office", "Subedari Police Station"), ("Phone", "+91 90000 00000")):
        y = b.field(y, label, value)
        y += SPACING["lg"]
    b.pinned_action(BOARD_H - SPACING["xl"] - SIZE["touchTarget"] - SPACING["md"], "Save")


board("20-instruction-editor-light.svg", "Instruction editor · light", "Instruction editor",
      "light", "populated",
      notes="Deadline and next follow-up are named separately and never overwrite each other.")(
    _instruction_editor)
board("21-contact-editor-light.svg", "Contact editor · light", "Contact editor", "light", "populated")(
    _contact_editor)


# ---------------------------------------------------------------- subdivision record

def _subdivision_review(b):
    b.top_bar("Subdivision review", "Subdivision record", back=True)
    y = b.y + SPACING["md"]
    _, tl, _ = b.sp("titleLarge")
    b.text(INSET, y + tl * 0.8, "Warangal Subdivision", "titleLarge", b.c["onSurface"])
    y += tl + SPACING["sm"]
    _, l, _ = b.sp("titleMedium")
    b.text(INSET, y + l * 0.8, "Scope", "titleMedium", b.c["onSurface"])
    y += l + SPACING["xs"]
    _, bl, _ = b.sp("bodyMedium")
    b.text(INSET, y + bl * 0.8, "Choose what this review covers.", "bodyMedium", b.c["onSurfaceVariant"])
    y += bl + SPACING["md"]
    b.rect(INSET, y, BOARD_W - INSET * 2, SIZE["touchTarget"], "none", SIZE["touchTarget"] / 2, b.c["outline"])
    size, _, _ = b.sp("labelLarge")
    b.text(BOARD_W / 2, y + SIZE["touchTarget"] / 2 + size * 0.36, "Whole subdivision",
           "labelLarge", b.c["primary"], "middle")
    y += SIZE["touchTarget"] + SPACING["lg"]
    b.text(INSET, y + l * 0.8, "105 open · 0 ready to verify", "titleMedium", b.c["onSurface"])
    y += l + SPACING["xs"]
    b.paragraph(INSET, y, "Counts cover only the records available in this workspace right now.", "bodyMedium", b.c["onSurfaceVariant"])
    y += bl * 2 + SPACING["md"]
    y = b.chips(y, ["Open", "Ready to verify", "Deadline passed", "No update in 7 days"], 0)
    y += SPACING["md"]
    b.text(INSET, y + bl * 0.8, "105 instructions", "labelLarge", b.c["onSurfaceVariant"])
    y += bl + SPACING["md"]
    b.work_card(y, "Received", "164 statement from the new informant in the 2024 appeal.",
                context="B. Ramesh Naidu · Subedari PS",
                footer="Recorded at Subedari PS · Responsible: B. Ramesh Naidu",
                priority=True)


def _stations_staff(b):
    b.top_bar("Stations & staff", "Subdivision record", back=True)
    y = b.y + SPACING["sm"]
    y = b.chips(y, ["Stations", "Staff"], 0)
    y += SPACING["sm"]
    y = b.search_field(y, "Search stations and units")
    y += SPACING["sm"]
    size, _, _ = b.sp("labelLarge")
    b.text(INSET, y + SIZE["touchTarget"] / 2 + size * 0.36, "4 active records", "labelLarge", b.c["onSurface"])
    b.text(BOARD_W - INSET, y + SIZE["touchTarget"] / 2 + size * 0.36, "Add station / unit",
           "labelLarge", b.c["primary"], "end")
    y += SIZE["touchTarget"] + SPACING["sm"]
    for name, kind, staff in (("Subedari Police Station", "Station", "6 active staff · 12 open instructions"),
                              ("Jangaon Circle", "Circle", "3 active staff · 7 open instructions"),
                              ("District Headquarters", "Wing", "2 active staff · 1 open instruction")):
        h = 78
        b.panel(y, h)
        b.icon_tile(INSET + SPACING["lg"], y + (h - SIZE["iconTile"]) / 2,
                    b.c["secondaryContainer"], b.c["onSecondaryContainer"])
        tx = INSET + SPACING["lg"] + SIZE["iconTile"] + SPACING["md"]
        _, l1, _ = b.sp("titleMedium")
        _, l2, _ = b.sp("bodyMedium")
        top = y + (h - (l1 + l2 * 2)) / 2
        b.text(tx, top + l1 * 0.8, name, "titleMedium", b.c["onSurface"])
        avail = BOARD_W - INSET - SPACING["lg"] - 20 - tx
        b.text(tx, top + l1 + l2 * 0.8, b.ellipsize(kind, "bodyMedium", avail),
               "bodyMedium", b.c["onSurfaceVariant"])
        b.text(tx, top + l1 + l2 * 1.8, b.ellipsize(staff, "bodyMedium", avail),
               "bodyMedium", b.c["onSurfaceVariant"])
        b.glyph(BOARD_W - INSET - SPACING["lg"] - 4, y + h / 2, "chevron", b.c["onSurfaceVariant"])
        y += h + SPACING["md"]


def _matters(b):
    b.top_bar("Matters", "Subdivision record", back=True)
    y = b.y + SPACING["sm"]
    y = b.paragraph(INSET, y, "Group related instructions and their history.",
                    "bodyMedium", b.c["onSurfaceVariant"]) + SPACING["sm"]
    y = b.search_field(y, "Search matters and references")
    y += SPACING["screen"]
    b.empty_state(y, "Group work that belongs together",
                  "A matter is a named group of instructions — an inquiry, a drive, a recurring "
                  "duty. Add one, then link instructions to it or capture new ones in its context.",
                  "Add matter")


board("22-subdivision-review-light.svg", "Subdivision review · light", "Subdivision review",
      "light", "populated",
      notes="Saved counts read 'At this review'; recording a review completes nothing and sends "
            "nothing. Record context belongs inside its card.")(_subdivision_review)
board("23-subdivision-review-dark.svg", "Subdivision review · dark", "Subdivision review",
      "dark", "populated")(_subdivision_review)
board("24-stations-staff-light.svg", "Stations & staff · light", "Stations & staff", "light",
      "populated",
      notes="Stations and Staff are local segments, not a fourth tab.")(_stations_staff)
board("25-matters-first-use-light.svg", "Matters · first use · light", "Matters", "light", "first use")(
    _matters)


# ---------------------------------------------------------------- settings

def _settings(b):
    b.rect(0, 0, BOARD_W, BOARD_H, b.c["background"])
    b.rect(0, 0, BOARD_W, BOARD_H, b.c["surfaceContainerLow"], 28)
    b.rect(BOARD_W / 2 - 16, 10, 32, 4, b.c["outlineVariant"], 2)
    b.icon_tile(INSET, 28)
    _, hl, _ = b.sp("headlineSmall")
    b.text(INSET + SIZE["iconTile"] + SPACING["md"], 28 + SIZE["iconTile"] / 2 + hl * 0.32,
           "Settings", "headlineSmall", b.c["onSurface"])
    b.glyph(BOARD_W - INSET - 6, 48, "close", b.c["onSurfaceVariant"])
    y = 28 + SIZE["iconTile"] + SPACING["lg"]
    for title, supporting in (
        ("Labels", "Organise notes with optional labels"),
        ("Privacy & security", "Private contacts, PIN and recovery"),
        ("Google Drive backup", "Optional encrypted off-device backup"),
        ("Display theme", "Follow your phone, light or dark"),
        ("Export & restore", "Save a copy or move to another phone"),
        ("About & support", "Updates, app information and help"),
        ("Erase local data", "Remove all records from this phone"),
    ):
        y = b.settings_row(y, title, supporting)
        y += SPACING["sm"]


board("26-settings-light.svg", "Settings · light", "Settings", "light", "populated",
      notes="Named categories before controls; each category leads with the same icon tile the "
            "workspace entry rows use. Settings is a top-bar action, never a fourth tab.")(_settings)
board("27-settings-dark.svg", "Settings · dark", "Settings", "dark", "populated")(_settings)


# ---------------------------------------------------------------- component + token sheets

def write_component_sheet():
    for theme, palette, name in (("light", LIGHT, "components-light.svg"), ("dark", DARK, "components-dark.svg")):
        b = Board(f"Components · {theme}", palette, height=1180, width=760)
        b.ground()
        b.text(INSET, 40, f"KaavalanNote v2.7 · components · {theme}", "headlineSmall", palette["onSurface"])
        y = 70
        rows = []

        def label(txt, yy):
            b.text(INSET, yy, txt, "labelMedium", palette["onSurfaceVariant"])

        label("KaavalanTopBarTitle", y)
        b.y = y + 10
        b.top_bar("Today", "Saturday, 12 September")
        y = b.y + 24

        label("SubdivisionEntryRow", y)
        y = b.entry_row(y + 10, "Subdivision review", "Warangal Subdivision") + 24

        label("KaavalanSearchField", y)
        y = b.search_field(y + 10, "Search instructions, updates, stations and matters") + 24

        label("Filter chips (selected / unselected)", y)
        y = b.chips(y + 10, ["All records", "All open", "Closed"], 0) + 24

        label("WorkCard · featured / plain", y)
        y = b.work_card(y + 10, "Assigned by me", "Daily diary report — need it submitted by 6 PM.",
                        context="K. Mahesh · Jangaon Circle", featured=True, priority=True, actions=True) + 16
        y = b.work_card(y, "Received", "164 statement from the new informant.",
                        context="B. Ramesh Naidu · Subedari PS", meta=["Carried over · 10 Sep, 09:00"]) + 24

        label("KaavalanEmptyState", y)
        y = b.empty_state(y, "Know who is handling what",
                          "Add a colleague with their rank and station.", "Add contact") + 8

        label("Note bar + navigation bar", y)
        y = b.note_bar(y + 10)
        y = b.nav_bar(y, 0)

        with open(os.path.join(ROOT, "components", name), "w") as f:
            f.write(b.render())
        BOARDS.append({
            "file": "components/" + name, "board": f"Components · {theme}",
            "screen": "Component library", "theme": theme, "state": "reference", "textScale": 1.0,
            "notes": "Every primitive in ui/components/KaavalanSurfaces.kt plus the shared shells.",
        })


def write_token_sheet():
    b = Board("Tokens", LIGHT, height=980, width=760)
    b.ground()
    b.text(INSET, 40, "KaavalanNote v2.7 · colour roles", "headlineSmall", LIGHT["onSurface"])
    order = ["primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
             "secondary", "secondaryContainer", "tertiary", "tertiaryContainer",
             "background", "surface", "surfaceContainerLowest", "surfaceContainerLow",
             "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
             "onSurface", "onSurfaceVariant", "outline", "outlineVariant", "inversePrimary"]
    y = 64
    for i, role in enumerate(order):
        col = i % 2
        row = i // 2
        x = INSET + col * 360
        yy = y + row * 46
        b.rect(x, yy, 40, 34, LIGHT[role], 6, LIGHT["outlineVariant"])
        b.rect(x + 46, yy, 40, 34, DARK[role], 6, DARK["outlineVariant"])
        b.text(x + 96, yy + 14, role, "titleSmall", LIGHT["onSurface"])
        b.text(x + 96, yy + 29, f"{LIGHT[role]}  /  {DARK[role]}", "labelMedium", LIGHT["onSurfaceVariant"])
    y = y + ((len(order) + 1) // 2) * 46 + 36
    b.text(INSET, y, "Type scale (system typeface — Roboto stands in for it here)",
           "titleMedium", LIGHT["onSurface"])
    y += 24
    for name, (size, line, weight) in TYPE.items():
        b.parts.append(
            f'<text x="{INSET}" y="{y + size * 0.8}" font-family="{FONT_STACK}" font-size="{size}" '
            f'font-weight="{weight}" fill="{LIGHT["onSurface"]}">{name}</text>'
        )
        b.text(520, y + size * 0.8, f"{size}/{line} · {weight}", "labelMedium", LIGHT["onSurfaceVariant"])
        y += line + 6
    y += 20
    b.text(INSET, y, "Spacing 4 · 8 · 12 · 16 · 20 · 24 dp     Radius 8 · 12 · 16 dp     "
                     "Minimum target 48 dp", "titleSmall", LIGHT["onSurface"])
    y += 20
    x = INSET
    for step in (4, 8, 12, 16, 20, 24):
        b.rect(x, y, step, 24, LIGHT["primaryContainer"], 2, LIGHT["primary"])
        b.text(x, y + 40, str(step), "labelMedium", LIGHT["onSurfaceVariant"])
        x += step + 28
    with open(os.path.join(ROOT, "components", "tokens-sheet.svg"), "w") as f:
        f.write(b.render())
    BOARDS.append({
        "file": "components/tokens-sheet.svg", "board": "Tokens", "screen": "Token reference",
        "theme": "both", "state": "reference", "textScale": 1.0,
        "notes": "Light / dark swatch pairs for every Material 3 role the app sets, plus the "
                 "type, spacing and radius scales.",
    })


def write_tokens_json():
    """W3C design-token JSON (the Tokens Studio dialect Penpot imports)."""

    def colour_set(palette):
        return {k: {"$value": v, "$type": "color"} for k, v in palette.items()}

    doc = {
        "$description": "KaavalanNote — Field Notebook UI v2.7. Transcribed from "
                        "app/src/main/java/com/kaavalan/note/ui/theme/.",
        "light": {"color": colour_set(LIGHT)},
        "dark": {"color": colour_set(DARK)},
        "spacing": {k: {"$value": f"{v}px", "$type": "spacing"} for k, v in SPACING.items()},
        "radius": {k: {"$value": f"{v}px", "$type": "borderRadius"} for k, v in RADIUS.items()},
        "size": {k: {"$value": f"{v}px", "$type": "sizing"} for k, v in SIZE.items()},
        "typography": {
            name: {
                "$type": "typography",
                "$value": {
                    "fontFamily": "Roboto",
                    "fontSize": f"{size}px",
                    "lineHeight": f"{line}px",
                    "fontWeight": str(weight),
                },
            }
            for name, (size, line, weight) in TYPE.items()
        },
        "$themes": [
            {"name": "Light", "selectedTokenSets": {"light": "enabled", "spacing": "source",
                                                    "radius": "source", "size": "source",
                                                    "typography": "source"}},
            {"name": "Dark", "selectedTokenSets": {"dark": "enabled", "spacing": "source",
                                                   "radius": "source", "size": "source",
                                                   "typography": "source"}},
        ],
    }
    with open(os.path.join(ROOT, "tokens", "kaavalan-tokens.json"), "w") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")


def write_manifest():
    manifest = {
        "project": "KaavalanNote — Field Notebook UI v2.7",
        "file": "KaavalanNote — Field Notebook UI v2.7",
        "generatedBy": "design/penpot-v2.7/tools/generate_boards.py",
        "sourceOfTruth": [
            "app/src/main/java/com/kaavalan/note/ui/theme/Theme.kt",
            "app/src/main/java/com/kaavalan/note/ui/theme/Type.kt",
            "app/src/main/java/com/kaavalan/note/ui/components/KaavalanSurfaces.kt",
            "DESIGN.md",
            "PRODUCT.md",
            "docs/architecture/officer-workspace.md",
        ],
        "units": "1 SVG user unit = 1 dp. Boards are 360 x 800 dp (Pixel 6 class).",
        "iconography": {
            "family": "Material Symbols Outlined",
            "note": "Boards draw schematic marks, not the real glyphs. The shipped icons are "
                    "listed per screen below so they can be placed from Penpot's icon plugin.",
            "perScreen": {
                "Today": ["today", "account_balance", "edit_note", "schedule", "check"],
                "Instructions": ["checklist", "folder", "search", "arrow_drop_down"],
                "Contacts": ["people", "groups", "person_add", "chevron_right"],
                "Quick capture": ["edit_note", "photo_camera", "mic", "schedule", "close"],
                "Settings": ["settings", "label", "shield", "cloud_upload", "contrast",
                             "folder_zip", "info", "construction", "delete"],
                "Stations & staff": ["account_balance", "badge", "folder"],
            },
        },
        "pages": [
            {"name": "01 Tokens", "boards": ["components/tokens-sheet.svg"]},
            {"name": "02 Components", "boards": ["components/components-light.svg",
                                                 "components/components-dark.svg"]},
            {"name": "03 Screens", "boards": [b["file"] for b in BOARDS if b["file"].startswith("boards/")]},
        ],
        "boards": BOARDS,
        "importSteps": [
            "In Penpot, create a project named 'KaavalanNote — Field Notebook UI v2.7'.",
            "Create a file with the same name and add the three pages listed under 'pages'.",
            "On each page use File > Import > SVG and select the listed .svg files. Penpot "
            "imports each SVG as an editable board with live text and vector shapes.",
            "Open the Design tokens panel and import tokens/kaavalan-tokens.json (Tokens "
            "Studio JSON). The 'Light' and 'Dark' themes map the same role names the Compose "
            "theme uses, so a role renamed in one place is visibly wrong in the other.",
            "Optional: replace the schematic marks with real Material Symbols Outlined glyphs "
            "using the names under 'iconography.perScreen'.",
        ],
    }
    with open(os.path.join(ROOT, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    write_component_sheet()
    write_token_sheet()
    write_tokens_json()
    write_manifest()
    print(f"{len([b for b in BOARDS if b['file'].startswith('boards/')])} screen boards, "
          f"{len([b for b in BOARDS if b['file'].startswith('components/')])} reference sheets")
